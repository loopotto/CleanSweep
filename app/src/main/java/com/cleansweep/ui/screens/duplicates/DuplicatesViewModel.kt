/*
 * CleanSweep
 * Copyright (c) 2025 LoopOtto
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.cleansweep.ui.screens.duplicates

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.BuildConfig
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.DuplicateScanScope
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.domain.bus.FileModificationEvent
import com.cleansweep.domain.bus.FileModificationEventBus
import com.cleansweep.domain.bus.FolderDelta
import com.cleansweep.domain.bus.FolderUpdateEvent
import com.cleansweep.domain.bus.FolderUpdateEventBus
import com.cleansweep.domain.model.DuplicateGroup
import com.cleansweep.domain.model.ScanResultGroup
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.domain.repository.DuplicatesRepository
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.domain.repository.PersistedScanResult
import com.cleansweep.domain.repository.ScanScopeType
import com.cleansweep.util.HiddenFileFilter
import com.cleansweep.service.BackgroundScanState
import com.cleansweep.service.DuplicateScanService
import com.cleansweep.service.DuplicateScanStateHolder
import com.cleansweep.util.CoilPreloader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class StaleResultsInfo(
    val timestamp: Long,
    val scopeType: ScanScopeType,
    val isDismissed: Boolean = false
)

data class DuplicatesUiState(
    val scanState: ScanState = ScanState.Idle,
    val scanForExactDuplicates: Boolean = true,
    val scanForSimilarMedia: Boolean = true,
    val hasRunDuplicateScanOnce: Boolean = false,
    val canLoadFromCache: Boolean = false,
    val scanProgress: Float = 0f,
    val scanProgressPhase: String? = null,
    val resultGroups: List<ScanResultGroup> = emptyList(),
    val unscannableFiles: List<String> = emptyList(),
    val nonHiddenUnscannableFilesCount: Int = 0,
    val showUnscannableFilesDialog: Boolean = false,
    val showHiddenUnscannableFiles: Boolean = false,
    val showUnscannableSummaryCard: Boolean = true,
    val staleResultsInfo: StaleResultsInfo? = null,
    val selectedForDeletion: Set<String> = emptySet(),
    val spaceToReclaim: Long = 0L,
    val isDeleting: Boolean = false,
    val toastMessage: String? = null,
    val resultViewMode: ResultViewMode = ResultViewMode.LIST,
    val detailedGroup: ScanResultGroup? = null,
    val detailViewColumnCount: Int = 2,
    val gridViewColumnCount: Int = 2,
    val showConfirmDeleteAllExact: Boolean = true,
    // Scan Scope properties
    val scanScope: DuplicateScanScope = DuplicateScanScope.ALL_FILES,
    val includeList: Set<String> = emptySet(),
    val excludeList: Set<String> = emptySet()
)

enum class ScanState {
    Idle,
    Scanning,
    Cancelling,
    Complete
}

enum class ResultViewMode {
    LIST,
    GRID
}

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val duplicatesRepository: DuplicatesRepository,
    private val preferencesRepository: PreferencesRepository,
    private val stateHolder: DuplicateScanStateHolder,
    private val eventBus: FileModificationEventBus,
    private val folderUpdateEventBus: FolderUpdateEventBus,
    private val coilPreloader: CoilPreloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    private val logTag ="DuplicatesViewModel"
    private var validatedCache: PersistedScanResult? = null

    val displayedUnscannableFiles: StateFlow<List<String>> = uiState.map { state ->
        if (state.showHiddenUnscannableFiles) {
            state.unscannableFiles
        } else {
            state.unscannableFiles.filterNot { HiddenFileFilter.isUiHidden(it) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    init {
        // Synchronously initialize from the state holder to prevent flicker and restore state on re-entry
        val initialBackgroundState = stateHolder.state.value
        Log.d(logTag, "ViewModel init with background state: ${initialBackgroundState.scanState}")
        _uiState.update {
            when (initialBackgroundState.scanState) {
                // DO NOT handle Complete here, let the collector do it to prevent flicker on notification click
                BackgroundScanState.Complete -> it
                BackgroundScanState.Scanning -> {
                    it.copy(
                        scanState = ScanState.Scanning,
                        scanProgress = initialBackgroundState.progress,
                        scanProgressPhase = initialBackgroundState.progressPhase
                    )
                }
                else -> it
            }
        }

        viewModelScope.launch {
            preferencesRepository.hasRunDuplicateScanOnceFlow.collectLatest { hasRun ->
                _uiState.update { it.copy(hasRunDuplicateScanOnce = hasRun) }
            }
        }

        // Collect Scan Scope settings
        viewModelScope.launch {
            preferencesRepository.duplicateScanScopeFlow.collect { scope ->
                _uiState.update { it.copy(scanScope = scope) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.duplicateScanIncludeListFlow.collect { list ->
                _uiState.update { it.copy(includeList = list) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.duplicateScanExcludeListFlow.collect { list ->
                _uiState.update { it.copy(excludeList = list) }
            }
        }

        // Collect preference for confirm dialog
        viewModelScope.launch {
            preferencesRepository.showConfirmDeleteAllExactFlow.collectLatest { show ->
                _uiState.update { it.copy(showConfirmDeleteAllExact = show) }
            }
        }


        // One-time check for valid cache to show/hide the "Load" button
        viewModelScope.launch {
            if (duplicatesRepository.hasValidCachedResults()) {
                _uiState.update { it.copy(canLoadFromCache = true) }
            }
        }

        // If we re-entered during a background scan, we also need to load the results to show behind the progress bar.
        if (initialBackgroundState.scanState == BackgroundScanState.Scanning && initialBackgroundState.shouldShowResultsDuringScan) {
            viewModelScope.launch {
                restoreStaleResultsForBackgroundScan()
            }
        }


        // This collector is the source of truth for any SUBSEQUENT scan state changes.
        viewModelScope.launch {
            stateHolder.state.collectLatest { backgroundState ->
                Log.d(logTag, "Observed background state: ${backgroundState.scanState}")
                when (backgroundState.scanState) {
                    BackgroundScanState.Idle -> {
                        if (_uiState.value.scanState != ScanState.Complete) {
                            _uiState.update { it.copy(scanState = ScanState.Idle, scanProgress = 0f, scanProgressPhase = null) }
                        }
                    }
                    BackgroundScanState.Scanning -> {
                        _uiState.update {
                            it.copy(
                                scanState = ScanState.Scanning,
                                scanProgress = backgroundState.progress,
                                scanProgressPhase = backgroundState.progressPhase
                            )
                        }
                    }
                    BackgroundScanState.Complete -> {
                        val hadSelections = _uiState.value.selectedForDeletion.isNotEmpty()
                        val nonHiddenUnscannableCount = backgroundState.unscannableFiles.count { path -> !HiddenFileFilter.isPathExcludedFromScan(path) }

                        // Both timestamp and scopeType must be present for the state to be valid.
                        if (backgroundState.timestamp != null && backgroundState.scanScopeType != null) {
                            _uiState.update {
                                it.copy(
                                    scanState = ScanState.Complete,
                                    resultGroups = backgroundState.results,
                                    unscannableFiles = backgroundState.unscannableFiles,
                                    nonHiddenUnscannableFilesCount = nonHiddenUnscannableCount,
                                    showUnscannableSummaryCard = nonHiddenUnscannableCount > 0,
                                    scanProgress = 1f,
                                    scanProgressPhase = backgroundState.progressPhase,
                                    staleResultsInfo = StaleResultsInfo(
                                        timestamp = backgroundState.timestamp,
                                        scopeType = backgroundState.scanScopeType
                                    ),
                                    selectedForDeletion = emptySet(),
                                    spaceToReclaim = 0L,
                                    toastMessage = if (hadSelections) context.getString(R.string.scan_complete_selection_cleared) else null
                                )
                            }
                            // After a fresh *full* scan, clear any lingering scoped cache to avoid confusion.
                            if (backgroundState.scanScopeType == ScanScopeType.FULL) {
                                duplicatesRepository.clearScopedScanResults()
                            }
                            coilPreloader.preload(backgroundState.results.flatMap { it.items })
                        } else {
                            Log.e(logTag, "Scan complete but metadata is missing. Cannot update UI.")
                        }
                    }
                    BackgroundScanState.Cancelled -> {
                        _uiState.update { it.copy(toastMessage = context.getString(R.string.scan_cancelled_toast)) }
                        loadPersistedResults(isFallback = true)
                    }
                    BackgroundScanState.Error -> {
                        _uiState.update {
                            it.copy(
                                scanState = ScanState.Idle,
                                scanProgress = 0f,
                                scanProgressPhase = null,
                                toastMessage = backgroundState.errorMessage ?: context.getString(R.string.unknown_error)
                            )
                        }
                        loadPersistedResults(isFallback = true)
                    }
                }
            }
        }
    }

    fun loadPersistedResults(isFallback: Boolean) {
        viewModelScope.launch {
            val results = validatedCache ?: duplicatesRepository.loadLatestScanResults()
            if (results != null) {
                validatedCache = results
                val nonHiddenUnscannableCount = results.unscannableFiles.count { path -> !HiddenFileFilter.isPathExcludedFromScan(path) }
                _uiState.update {
                    it.copy(
                        scanState = ScanState.Complete,
                        resultGroups = results.groups,
                        unscannableFiles = results.unscannableFiles,
                        nonHiddenUnscannableFilesCount = nonHiddenUnscannableCount,
                        showUnscannableSummaryCard = nonHiddenUnscannableCount > 0,
                        scanProgress = if (isFallback) 0f else 1f,
                        scanProgressPhase = if (isFallback) null else "Complete",
                        staleResultsInfo = StaleResultsInfo(
                            timestamp = results.timestamp,
                            scopeType = results.scopeType
                        )
                    )
                }
                coilPreloader.preload(results.groups.flatMap { it.items })
            } else {
                _uiState.update {
                    it.copy(
                        scanState = ScanState.Idle,
                        scanProgress = 0f,
                        scanProgressPhase = null,
                        toastMessage = context.getString(R.string.no_valid_cache_toast)
                    )
                }
            }
        }
    }

    private fun restoreStaleResultsForBackgroundScan() {
        viewModelScope.launch {
            val results = validatedCache ?: duplicatesRepository.loadLatestScanResults()
            if (results != null) {
                validatedCache = results
                val nonHiddenUnscannableCount = results.unscannableFiles.count { path -> !HiddenFileFilter.isPathExcludedFromScan(path) }
                _uiState.update {
                    it.copy(
                        // Crucially, DO NOT change the scanState
                        resultGroups = results.groups,
                        unscannableFiles = results.unscannableFiles,
                        nonHiddenUnscannableFilesCount = nonHiddenUnscannableCount,
                        showUnscannableSummaryCard = nonHiddenUnscannableCount > 0,
                        staleResultsInfo = StaleResultsInfo(
                            timestamp = results.timestamp,
                            scopeType = results.scopeType
                        )
                    )
                }
            }
        }
    }

    fun resetToIdle() {
        _uiState.update {
            it.copy(
                scanState = ScanState.Idle,
                resultGroups = emptyList(),
                unscannableFiles = emptyList(),
                selectedForDeletion = emptySet(),
                spaceToReclaim = 0L,
                staleResultsInfo = null
            )
        }
    }

    fun toggleScanForExactDuplicates() {
        _uiState.update { it.copy(scanForExactDuplicates = !it.scanForExactDuplicates) }
    }

    fun toggleScanForSimilarMedia() {
        _uiState.update { it.copy(scanForSimilarMedia = !it.scanForSimilarMedia) }
    }

    fun toggleResultViewMode() {
        _uiState.update {
            val newMode = if (it.resultViewMode == ResultViewMode.LIST) ResultViewMode.GRID else ResultViewMode.LIST
            it.copy(resultViewMode = newMode)
        }
    }

    fun startScan() {
        if (_uiState.value.scanState == ScanState.Scanning || _uiState.value.scanState == ScanState.Cancelling) {
            _uiState.update { it.copy(toastMessage = context.getString(R.string.scan_already_in_progress)) }
            return
        }

        val currentState = _uiState.value
        if (!currentState.scanForExactDuplicates && !currentState.scanForSimilarMedia) {
            _uiState.update { it.copy(toastMessage = context.getString(R.string.select_one_scan_type)) }
            return
        }

        if (!currentState.hasRunDuplicateScanOnce) {
            viewModelScope.launch {
                preferencesRepository.setHasRunDuplicateScanOnce()
            }
        }

        val preparingStr = context.getString(R.string.scanning_preparing_phase)
        val shouldShowResultsDuringScan = currentState.resultGroups.isNotEmpty()
        stateHolder.setScanning(preparingStr, shouldShowResultsDuringScan)

        _uiState.update {
            it.copy(
                scanState = ScanState.Scanning,
                scanProgress = 0f,
                scanProgressPhase = preparingStr,
                // Only clear results if it's a fresh scan from idle
                resultGroups = if (shouldShowResultsDuringScan) it.resultGroups else emptyList(),
                unscannableFiles = if (shouldShowResultsDuringScan) it.unscannableFiles else emptyList(),
                // Selections should always be cleared for a new scan to avoid inconsistency
                selectedForDeletion = emptySet(),
                spaceToReclaim = 0L,
                detailedGroup = null,
                showUnscannableSummaryCard = if (shouldShowResultsDuringScan) it.nonHiddenUnscannableFilesCount > 0 else false
                // Do not hide stale info, let the UI logic handle it
            )
        }

        val intent = Intent(context, DuplicateScanService::class.java).apply {
            action = DuplicateScanService.ACTION_START_SCAN
            putExtra(DuplicateScanService.EXTRA_SCAN_EXACT, currentState.scanForExactDuplicates)
            putExtra(DuplicateScanService.EXTRA_SCAN_SIMILAR, currentState.scanForSimilarMedia)
        }
        context.startService(intent)
        Log.d(
            logTag,
            "Start scan service command issued with exact=${currentState.scanForExactDuplicates}, similar=${currentState.scanForSimilarMedia}"
        )
    }

    fun cancelScan() {
        if (_uiState.value.scanState == ScanState.Scanning) {
            val cancellingStr = context.getString(R.string.scanning_cancelling_phase)
            _uiState.update {
                it.copy(
                    scanState = ScanState.Cancelling,
                    scanProgressPhase = cancellingStr
                )
            }
            val intent = Intent(context, DuplicateScanService::class.java).apply {
                action = DuplicateScanService.ACTION_CANCEL_SCAN
            }
            context.startService(intent)
            Log.d(logTag, "Cancel scan service command issued.")
        } else {
            Log.w(logTag, "Cancel requested but not in a cancellable state. UI State: ${_uiState.value.scanState}")
        }
    }

    fun toggleSelection(item: MediaItem) {
        val currentSelection = _uiState.value.selectedForDeletion
        val newSelection = if (item.id in currentSelection) {
            currentSelection - item.id
        } else {
            currentSelection + item.id
        }
        updateSelection(newSelection)
    }

    fun selectAllButOldest(group: ScanResultGroup) {
        val idsToSelect = group.items.sortedBy { it.dateAdded }.drop(1).map { it.id }.toSet()
        val idsToDeselect = group.items.map { it.id }.toSet()
        val currentSelection = _uiState.value.selectedForDeletion
        val newSelection = (currentSelection - idsToDeselect) + idsToSelect
        updateSelection(newSelection)
    }

    fun selectAllButNewest(group: ScanResultGroup) {
        val idsToSelect = group.items.sortedBy { it.dateAdded }.dropLast(1).map { it.id }.toSet()
        val idsToDeselect = group.items.map { it.id }.toSet()
        val currentSelection = _uiState.value.selectedForDeletion
        val newSelection = (currentSelection - idsToDeselect) + idsToSelect
        updateSelection(newSelection)
    }

    fun toggleSelectAllInGroup(group: ScanResultGroup) {
        val groupIds = group.items.map { it.id }.toSet()
        val currentSelection = _uiState.value.selectedForDeletion
        val selectedInGroup = currentSelection.intersect(groupIds)

        val newSelection = if (selectedInGroup.size == groupIds.size) { // All are selected, so unselect them
            currentSelection - groupIds
        } else { // Not all are selected, so select them all
            currentSelection + groupIds
        }
        updateSelection(newSelection)
    }

    private fun updateSelection(newSelection: Set<String>) {
        var reclaimableSpace = 0L
        val allItemsMap = _uiState.value.resultGroups.flatMap { it.items }.associateBy { it.id }

        newSelection.forEach { id ->
            allItemsMap[id]?.let {
                reclaimableSpace += it.size
            }
        }
        _uiState.update { it.copy(selectedForDeletion = newSelection, spaceToReclaim = reclaimableSpace) }
    }

    fun setShowConfirmDeleteAllExact(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowConfirmDeleteAllExact(enabled)
        }
    }

    fun deleteAllExactDuplicates() {
        val allIds = mutableSetOf<String>()
        val currentState = _uiState.value

        // Only process EXACT duplicates (DuplicateGroup), ignore SimilarGroup
        val exactGroups = currentState.resultGroups.filterIsInstance<DuplicateGroup>()

        if (exactGroups.isEmpty()) {
            _uiState.update { it.copy(toastMessage = context.getString(R.string.no_exact_duplicates_toast)) }
            return
        }

        exactGroups.forEach { group ->
            // Sort by date added (oldest first), then by ID for deterministic behavior
            val sorted = group.items.sortedWith(compareBy<MediaItem> { it.dateAdded }.thenBy { it.id })
            if (sorted.size > 1) {
                // Keep the first one (oldest), mark the rest for deletion
                val toDelete = sorted.drop(1).map { it.id }
                allIds.addAll(toDelete)
            }
        }

        if (allIds.isEmpty()) {
            _uiState.update { it.copy(toastMessage = context.getString(R.string.no_duplicates_to_delete_toast)) }
            return
        }

        updateSelection(allIds)
        // Trigger the existing deletion logic which consumes the selection we just set
        deleteSelectedFiles()
    }

    fun deleteSelectedFiles() {
        val currentState = _uiState.value
        val itemsToProcess = currentState.selectedForDeletion

        if (itemsToProcess.isEmpty()) {
            _uiState.update { it.copy(toastMessage = context.getString(R.string.no_files_selected_delete)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            val allItemsMap = currentState.resultGroups.flatMap { it.items }.associateBy { it.id }

            // Validate existence
            val existingItemsToDelete = itemsToProcess.mapNotNull { id ->
                val item = allItemsMap[id]
                if (item != null && File(item.id).exists()) {
                    item
                } else {
                    Log.w(logTag, "File not found during deletion check: ${item?.id ?: id}")
                    null
                }
            }

            val nonExistentCount = itemsToProcess.size - existingItemsToDelete.size
            if (nonExistentCount > 0) {
                val message = context.resources.getQuantityString(
                    R.plurals.files_not_found_deleting_remaining,
                    nonExistentCount,
                    nonExistentCount
                )
                _uiState.update { it.copy(toastMessage = message) }
            }

            if (existingItemsToDelete.isEmpty()) {
                _uiState.update { it.copy(isDeleting = false, toastMessage = context.getString(R.string.no_files_found_on_disk)) }
                // Clear selection if nothing was found
                updateSelection(emptySet())
                return@launch
            }

            val success = mediaRepository.deleteMedia(existingItemsToDelete)
            onDeleteRequestCompleted(success, existingItemsToDelete)
        }
    }

    private fun onDeleteRequestCompleted(success: Boolean, deletedItems: List<MediaItem>) {
        val idsDeletedSuccessfully = deletedItems.map { it.id }.toSet()
        viewModelScope.launch {
            if (success) {
                eventBus.postEvent(FileModificationEvent.FilesDeleted(idsDeletedSuccessfully.toList()))

                val folderDeltas = mutableMapOf<String, FolderDelta>()
                deletedItems.forEach { item ->
                    File(item.id).parent?.let { folderPath ->
                        val currentDelta = folderDeltas.getOrDefault(folderPath, FolderDelta(0, 0L))
                        folderDeltas[folderPath] = currentDelta.copy(
                            itemCountChange = currentDelta.itemCountChange - 1,
                            sizeChange = currentDelta.sizeChange - item.size
                        )
                    }
                }

                if (folderDeltas.isNotEmpty()) {
                    folderUpdateEventBus.post(FolderUpdateEvent.FolderBatchUpdate(folderDeltas))
                }

                val currentStaleInfo = _uiState.value.staleResultsInfo
                if (currentStaleInfo == null) {
                    Log.e(logTag, "Cannot update cache: stale info is missing.")
                    // Fallback or error handling
                    _uiState.update { it.copy(isDeleting = false, toastMessage = context.getString(R.string.unknown_error)) }
                    return@launch
                }

                val remainingGroups = _uiState.value.resultGroups.mapNotNull { group ->
                    val remainingItems = group.items.filterNot { it.id in idsDeletedSuccessfully }
                    if (remainingItems.size > 1) {
                        when (group) {
                            is DuplicateGroup -> group.copy(items = remainingItems)
                            is SimilarGroup -> group.copy(items = remainingItems)
                        }
                    } else {
                        null // Group no longer contains duplicates
                    }
                }

                // Preserve original timestamp and scope when saving modified cached results
                duplicatesRepository.saveScanResults(
                    groups = remainingGroups,
                    unscannableFiles = _uiState.value.unscannableFiles,
                    scopeType = currentStaleInfo.scopeType,
                    timestamp = currentStaleInfo.timestamp
                )

                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        toastMessage = context.resources.getQuantityString(
                            R.plurals.files_deleted_success,
                            idsDeletedSuccessfully.size,
                            idsDeletedSuccessfully.size
                        ),
                        selectedForDeletion = emptySet(), // Clear selection after deletion
                        spaceToReclaim = 0L,
                        resultGroups = remainingGroups,
                        detailedGroup = null,
                        canLoadFromCache = remainingGroups.isNotEmpty() // Update button state
                    )
                }
            } else {
                _uiState.update { it.copy(isDeleting = false, toastMessage = context.getString(R.string.could_not_delete_all)) }
            }
        }
    }

    fun getOpenFileIntent(item: MediaItem): Intent {
        val uri = if ("content" == item.uri.scheme) {
            item.uri
        } else {
            try {
                val file = File(item.id)
                FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
            } catch (e: Exception) {
                Log.e(logTag, "Error creating content URI", e)
                item.uri // Fallback to original URI
            }
        }

        val mimeType = if (item.isVideo) "video/*" else "image/*"
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun prepareForGroupDetailView(groupId: String) {
        val group = _uiState.value.resultGroups.find { it.uniqueId == groupId }
        _uiState.update { it.copy(detailedGroup = group) }
    }

    fun clearGroupDetailView() {
        _uiState.update { it.copy(detailedGroup = null) }
    }

    fun cycleDetailViewZoom() {
        _uiState.update {
            val newCount = when (it.detailViewColumnCount) {
                2 -> 3
                3 -> 1
                else -> 2
            }
            it.copy(detailViewColumnCount = newCount)
        }
    }

    fun setDetailViewColumnCount(newCount: Int) {
        _uiState.update {
            it.copy(detailViewColumnCount = newCount.coerceIn(1, 3))
        }
    }

    fun cycleGridViewZoom() {
        _uiState.update {
            val newCount = when (it.gridViewColumnCount) {
                2 -> 3
                3 -> 4
                4 -> 2
                else -> 2
            }
            it.copy(gridViewColumnCount = newCount)
        }
    }

    fun setGridViewColumnCount(newCount: Int) {
        _uiState.update {
            it.copy(gridViewColumnCount = newCount.coerceIn(2, 4))
        }
    }

    /**
     * Flags a group as "incorrect," recording a logical denial for all item pairs
     * within the group. This specifically corrects the similarity algorithm.
     */
    fun flagAsIncorrect(group: ScanResultGroup) {
        viewModelScope.launch {
            duplicatesRepository.addSimilarityDenials(group.items)
            _uiState.update {
                it.copy(
                    resultGroups = it.resultGroups.filterNot { g -> g.uniqueId == group.uniqueId },
                    toastMessage = context.getString(R.string.algorithm_corrected_toast)
                )
            }
        }
    }

    /**
     * Hides a specific group composition. If the group membership changes
     * (e.g., another duplicate is added), the group will reappear.
     */
    fun hideGroup(group: ScanResultGroup) {
        viewModelScope.launch {
            duplicatesRepository.hideGroupId(group.compositionId)
            _uiState.update {
                it.copy(
                    resultGroups = it.resultGroups.filterNot { g -> g.compositionId == group.compositionId },
                    toastMessage = context.getString(R.string.group_hidden_toast)
                )
            }
        }
    }

    fun showUnscannableFiles() {
        _uiState.update { it.copy(showUnscannableFilesDialog = true) }
    }

    fun hideUnscannableFiles() {
        _uiState.update { it.copy(showUnscannableFilesDialog = false) }
    }

    fun toggleShowHiddenUnscannableFiles() {
        _uiState.update { it.copy(showHiddenUnscannableFiles = !it.showHiddenUnscannableFiles) }
    }

    fun dismissUnscannableSummaryCard() {
        _uiState.update { it.copy(showUnscannableSummaryCard = false) }
    }

    fun dismissStaleResultsBanner() {
        _uiState.update {
            it.copy(staleResultsInfo = it.staleResultsInfo?.copy(isDismissed = true))
        }
    }
}
