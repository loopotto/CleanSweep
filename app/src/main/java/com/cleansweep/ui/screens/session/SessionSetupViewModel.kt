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

package com.cleansweep.ui.screens.session

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.R
import com.cleansweep.data.repository.FolderSelectionMode
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.data.repository.UnselectScanScope
import com.cleansweep.domain.bus.FolderUpdateEvent
import com.cleansweep.domain.bus.FolderUpdateEventBus
import com.cleansweep.domain.model.FolderDetails
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.ui.components.FolderSearchManager
import com.cleansweep.ui.navigation.RESET_SEARCH_RESULT_KEY
import com.cleansweep.util.FileOperationsHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Comparator
import javax.inject.Inject

// Define folder sorting options
enum class FolderSortOption {
    ALPHABETICAL_ASC,
    ALPHABETICAL_DESC,
    SIZE_ASC,
    SIZE_DESC,
    ITEM_COUNT_ASC,
    ITEM_COUNT_DESC
}

// Define folder category
data class FolderCategory(
    val name: String,
    val folders: List<FolderDetails>
)

data class SessionSetupUiState(
    val isInitialLoad: Boolean = true,
    val showScanningMessage: Boolean = false,
    val allFolderDetails: List<FolderDetails> = emptyList(),
    val folderCategories: List<FolderCategory> = emptyList(),
    val selectedBuckets: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    val isSearching: Boolean = false, // New state for debounce race condition
    val error: String? = null,
    val currentSortOption: FolderSortOption = FolderSortOption.SIZE_DESC,
    val searchQuery: String = "",
    val favoriteFolders: Set<String> = emptySet(),
    val showFavoritesInSetup: Boolean = true,
    val recursivelySelectedRoots: Set<String> = emptySet(),
    val showRenameDialogForPath: String? = null,
    val toastMessage: String? = null,
    val showMoveFolderDialogForPath: String? = null,

    // Mark as Sorted Dialog State
    val showMarkAsSortedConfirmation: Boolean = false,
    val foldersToMarkAsSorted: List<FolderDetails> = emptyList(),
    val dontAskAgainMarkAsSorted: Boolean = false,

    // Contextual Selection Mode
    val isContextualSelectionMode: Boolean = false,
    val contextSelectedFolderPaths: Set<String> = emptySet(),
    val canFavoriteContextualSelection: Boolean = false
)

@HiltViewModel
class SessionSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val fileOperationsHelper: FileOperationsHelper,
    private val folderUpdateEventBus: FolderUpdateEventBus,
    val folderSearchManager: FolderSearchManager,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionSetupUiState())
    val uiState: StateFlow<SessionSetupUiState> = _uiState.asStateFlow()

    val searchAutofocusEnabled: StateFlow<Boolean> =
        preferencesRepository.searchAutofocusEnabledFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )

    private var hasInitializedSelection = false
    private val _isManualRefreshing = MutableStateFlow(false)


    companion object {
        private const val logTag ="SessionSetupViewModel"
        private const val MIN_REFRESH_DISPLAY_TIME_MS = 500L
    }

    init {
        initialize()
    }

    private fun initialize() {
        viewModelScope.launch {
            // Step 1: Check for cache existence to decide if we need to show the scanning message.
            val hasCache = mediaRepository.hasCache()
            if (!hasCache) {
                // If no cache, show the scanning message before the blocking scan call.
                _uiState.update { it.copy(showScanningMessage = true) }
            }

            // Step 2: Get initial folders. Fast if a cache exists, or it performs the initial scan.
            val initialFolders = mediaRepository.getInitialFolderDetails()

            // Step 3: Process the result and update the UI to its final loaded state.
            val processedState = processFolderDetails(
                foldersToProcess = initialFolders,
                favorites = preferencesRepository.sourceFavoriteFoldersFlow.first(),
                showFavorites = preferencesRepository.showFavoritesFirstInSetupFlow.first(),
                query = _uiState.value.searchQuery,
                sortOption = _uiState.value.currentSortOption
            )
            _uiState.update {
                it.copy(
                    isInitialLoad = false, // The initial load is now complete.
                    showScanningMessage = false, // Hide the message regardless of the outcome.
                    folderCategories = processedState.first,
                    favoriteFolders = processedState.second,
                    allFolderDetails = processedState.third
                )
            }

            if (initialFolders.isNotEmpty()) {
                Log.d(logTag, "Initialized ViewModel with ${initialFolders.size} folders.")
                if (hasCache) {
                    // If we started from a cache, trigger a non-blocking background check for external changes.
                    mediaRepository.checkForChangesAndInvalidate()
                }
            } else {
                Log.d(logTag, "Initialization complete. No media folders were found.")
            }

            // Step 4: Start observing all other flows for ongoing updates.
            observeAndProcessFolderDetails()
            observeRefreshStates()
            observeFolderUpdates()
        }
    }

    fun handleResetResult() {
        if (_uiState.value.searchQuery.isNotEmpty()) {
            Log.d(logTag, "Reset result received. Clearing search query.")
            updateSearchQuery("")
        }
        // Consume the result
        savedStateHandle[RESET_SEARCH_RESULT_KEY] = false
    }

    private fun observeRefreshStates() {
        viewModelScope.launch {
            combine(
                _isManualRefreshing,
                mediaRepository.isPerformingBackgroundRefresh
            ) { isManual, isBackground ->
                isManual || isBackground
            }.distinctUntilChanged().collect { isRefreshing ->
                _uiState.update { it.copy(isRefreshing = isRefreshing) }
            }
        }
    }

    private fun observeFolderUpdates() {
        viewModelScope.launch {
            folderUpdateEventBus.events.collect { event ->
                if (event is FolderUpdateEvent.FullRefreshRequired) {
                    Log.d(logTag, "FullRefreshRequired event received. Triggering a manual refresh.")
                    hasInitializedSelection = false // Reset selection logic
                    refreshFolders()
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeAndProcessFolderDetails() {
        viewModelScope.launch {
            val dbFolderDetailsFlow = mediaRepository.observeMediaFoldersWithDetails()

            val favoritesFlow = preferencesRepository.sourceFavoriteFoldersFlow
            val showFavoritesFlow = preferencesRepository.showFavoritesFirstInSetupFlow
            val searchQueryFlow = _uiState.map { it.searchQuery }.distinctUntilChanged().debounce(200L)
            val sortOptionFlow = _uiState.map { it.currentSortOption }.distinctUntilChanged()

            combine(
                dbFolderDetailsFlow,
                favoritesFlow,
                showFavoritesFlow,
                searchQueryFlow,
                sortOptionFlow
            ) { foldersToProcess, favorites, showFavorites, query, sortOption ->

                if (_isManualRefreshing.value && foldersToProcess.isEmpty()) {
                    return@combine null // Skip emission during refresh wipe
                }
                processFolderDetails(foldersToProcess, favorites, showFavorites, query, sortOption)

            }.filterNotNull().catch { e ->
                Log.e(logTag, "Error in folder processing flow", e)
                val errorMessage = context.getString(R.string.failed_load_folders, e.message)
                _uiState.update { it.copy(error = errorMessage, isSearching = false) }
            }.collect { (newCategories, newFavorites, allFolders) ->
                _uiState.update { currentState ->
                    val allAvailableFolderPaths = allFolders.map { it.path }.toSet()

                    val sanitizedSelection = currentState.selectedBuckets.filter { it in allAvailableFolderPaths }

                    // We keep isInitialLoad as false because the main flow should not revert this.
                    currentState.copy(
                        folderCategories = newCategories,
                        favoriteFolders = newFavorites,
                        allFolderDetails = allFolders,
                        selectedBuckets = sanitizedSelection,
                        isSearching = false // Search is complete
                    )
                }

                if (!hasInitializedSelection && allFolders.isNotEmpty()) {
                    initializeSelection(allFolders)
                    hasInitializedSelection = true
                }
            }
        }
    }

    private fun processFolderDetails(
        foldersToProcess: List<FolderDetails>,
        favorites: Set<String>,
        showFavorites: Boolean,
        query: String,
        sortOption: FolderSortOption
    ): Triple<List<FolderCategory>, Set<String>, List<FolderDetails>> {
        val searchedFolders = if (query.isBlank()) {
            foldersToProcess
        } else {
            foldersToProcess.filter { it.name.contains(query, ignoreCase = true) }
        }

        val favoriteFolders = searchedFolders.filter { it.path in favorites }
        val nonFavoriteFolders = searchedFolders.filter { it.path !in favorites }
        val systemFolders = nonFavoriteFolders.filter { it.isSystemFolder }
        val userFolders = nonFavoriteFolders.filter { !it.isSystemFolder }

        val categories = listOfNotNull(
            if (showFavorites && favoriteFolders.isNotEmpty()) FolderCategory(context.getString(R.string.favorite_folders_category), favoriteFolders) else null,
            if (systemFolders.isNotEmpty()) FolderCategory(context.getString(R.string.system_folders_category), systemFolders) else null,
            if (userFolders.isNotEmpty()) FolderCategory(context.getString(R.string.user_folders_category), userFolders) else null
        )

        val sortedCategories = categories.map { category ->
            val primarySort: Comparator<FolderDetails> = if (category.name == context.getString(R.string.system_folders_category)) {
                compareByDescending { it.isPrimarySystemFolder }
            } else {
                compareBy { 0 }
            }

            val secondarySort: Comparator<FolderDetails> = when (sortOption) {
                FolderSortOption.ALPHABETICAL_ASC -> compareBy { it.name.lowercase() }
                FolderSortOption.ALPHABETICAL_DESC -> compareByDescending { it.name.lowercase() }
                FolderSortOption.SIZE_ASC -> compareBy { it.totalSize }
                FolderSortOption.SIZE_DESC -> compareByDescending { it.totalSize }
                FolderSortOption.ITEM_COUNT_ASC -> compareBy { it.itemCount }
                FolderSortOption.ITEM_COUNT_DESC -> compareByDescending { it.itemCount }
            }

            category.copy(folders = category.folders.sortedWith(primarySort.then(secondarySort)))
        }
        return Triple(sortedCategories, favorites, foldersToProcess)
    }

    private fun initializeSelection(allFolders: List<FolderDetails>) {
        viewModelScope.launch {
            val folderSelectionMode = preferencesRepository.folderSelectionModeFlow.first()
            val previouslySelectedBuckets = preferencesRepository.previouslySelectedBucketsFlow.first()
            val favoriteFolders = preferencesRepository.sourceFavoriteFoldersFlow.first()

            val allPaths = allFolders.map { it.path }.toSet()
            val baseSelection = when (folderSelectionMode) {
                FolderSelectionMode.ALL -> allPaths.toList()
                FolderSelectionMode.REMEMBER -> previouslySelectedBuckets.filter { it in allPaths }
                FolderSelectionMode.NONE -> emptyList()
            }
            val initialSelection = (baseSelection + favoriteFolders).distinct().filter { it in allPaths }

            _uiState.update { state ->
                state.copy(selectedBuckets = initialSelection)
            }
            Log.d(logTag, "Initial selection has been set with mode: $folderSelectionMode")
        }
    }


    fun refreshFolders() {
        viewModelScope.launch {
            if (_isManualRefreshing.value) {
                Log.d(logTag, "Refresh: Manual refresh already in progress. Ignoring request.")
                return@launch
            }
            Log.d(logTag, "Refreshing folders...")
            val startTime = System.currentTimeMillis()
            _isManualRefreshing.value = true
            try {
                mediaRepository.getMediaFoldersWithDetails(forceRefresh = true)
            } catch (e: Exception) {
                Log.e(logTag, "Error refreshing folders", e)
                val errorMessage = context.getString(R.string.failed_refresh_folders, e.message)
                _uiState.update {
                    it.copy(error = errorMessage)
                }
            } finally {
                withContext(NonCancellable) {
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val remainingTime = MIN_REFRESH_DISPLAY_TIME_MS - elapsedTime
                    if (remainingTime > 0) {
                        Log.d(logTag, "Refresh finished in ${elapsedTime}ms. Delaying for ${remainingTime}ms.")
                        delay(remainingTime)
                    }
                    Log.d(logTag, "Refresh flow finished. Resetting isManualRefreshing flag.")
                    _isManualRefreshing.value = false
                }
            }
        }
    }

    fun changeSortOption(sortOption: FolderSortOption) {
        _uiState.update { it.copy(currentSortOption = sortOption) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, isSearching = true) }
    }

    fun selectBucket(bucketId: String) {
        _uiState.update { state ->
            state.copy(
                selectedBuckets = (state.selectedBuckets + bucketId).distinct()
            )
        }
    }

    fun selectFolderRecursively(parentFolderPath: String) {
        _uiState.update { state ->
            val allChildPaths = state.allFolderDetails
                .filter { it.path.startsWith(parentFolderPath) }
                .map { it.path }
            val newSelection = (state.selectedBuckets + allChildPaths).distinct()
            val newRoots = state.recursivelySelectedRoots + parentFolderPath
            state.copy(selectedBuckets = newSelection, recursivelySelectedRoots = newRoots)
        }
    }

    fun deselectChildren(parentFolderPath: String) {
        _uiState.update { state ->
            val childPathsToDeselect = state.allFolderDetails
                .filter { it.path.startsWith(parentFolderPath) && it.path != parentFolderPath }
                .map { it.path }
                .toSet()

            val newSelection = state.selectedBuckets.toSet() - childPathsToDeselect
            val newRoots = state.recursivelySelectedRoots - parentFolderPath

            state.copy(
                selectedBuckets = newSelection.toList(),
                recursivelySelectedRoots = newRoots
            )
        }
    }

    fun unselectBucket(bucketId: String) {
        _uiState.update { state ->
            val currentSelection = state.selectedBuckets.toMutableSet()
            val currentRoots = state.recursivelySelectedRoots.toMutableSet()

            if (bucketId in currentRoots) {
                val allChildPaths = state.allFolderDetails
                    .filter { it.path.startsWith(bucketId) }
                    .map { it.path }
                currentSelection.removeAll(allChildPaths.toSet())
                currentRoots.remove(bucketId)
            } else {
                currentSelection.remove(bucketId)
            }

            state.copy(
                selectedBuckets = currentSelection.toList(),
                recursivelySelectedRoots = currentRoots
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val visiblePaths = state.folderCategories.flatMap { it.folders }.map { it.path }
            val newSelection = (state.selectedBuckets + visiblePaths).distinct()
            state.copy(selectedBuckets = newSelection)
        }
    }

    fun unselectAll() {
        viewModelScope.launch {
            val scope = preferencesRepository.unselectAllInSearchScopeFlow.first()
            _uiState.update { state ->
                val isSearching = state.searchQuery.isNotEmpty()

                if (isSearching && scope == UnselectScanScope.VISIBLE_ONLY) {
                    val visiblePaths = state.folderCategories.flatMap { it.folders }.map { it.path }.toSet()
                    val newSelection = state.selectedBuckets.filter { it !in visiblePaths }
                    state.copy(selectedBuckets = newSelection)
                } else {
                    state.copy(
                        selectedBuckets = emptyList(),
                        recursivelySelectedRoots = emptySet()
                    )
                }
            }
        }
    }

    fun saveSelectedBucketsPreference() {
        viewModelScope.launch {
            val currentlySelected = _uiState.value.selectedBuckets
            preferencesRepository.savePreviouslySelectedBuckets(currentlySelected)
        }
    }

    fun toggleFavorite(folderId: String) {
        val folderInfo = _uiState.value.allFolderDetails.find { it.path == folderId }
        if (folderInfo?.isSystemFolder == true) {
            return
        }

        viewModelScope.launch {
            val currentFavorites = _uiState.value.favoriteFolders
            if (folderId in currentFavorites) {
                preferencesRepository.removeSourceFavoriteFolder(folderId)
            } else {
                preferencesRepository.addSourceFavoriteFolder(folderId)
            }
        }
    }

    fun showRenameDialog(path: String) {
        _uiState.update { it.copy(showRenameDialogForPath = path) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(showRenameDialogForPath = null) }
    }

    fun renameFolder(oldPath: String, newName: String) {
        viewModelScope.launch {
            fileOperationsHelper.renameFolder(oldPath, newName).onSuccess { newPath ->
                mediaRepository.handleFolderRename(oldPath, newPath)
                preferencesRepository.updateFolderPath(oldPath, newPath)
                _uiState.update { it.copy(
                    toastMessage = context.getString(R.string.folder_renamed),
                    showRenameDialogForPath = null
                )}
            }.onFailure { error ->
                _uiState.update { it.copy(
                    toastMessage = context.getString(R.string.error_prefix, error.message),
                    showRenameDialogForPath = null
                )}
            }
        }
    }

    fun markFolderAsSorted(folder: FolderDetails) {
        viewModelScope.launch {
            val shouldShowDialog = preferencesRepository.showConfirmMarkAsSortedFlow.first()
            if (shouldShowDialog) {
                _uiState.update { it.copy(
                    showMarkAsSortedConfirmation = true,
                    foldersToMarkAsSorted = listOf(folder)
                ) }
            } else {
                performMarkFoldersAsSorted(setOf(folder.path))
            }
        }
    }

    fun confirmMarkFolderAsSorted() {
        viewModelScope.launch {
            val folderPaths = _uiState.value.foldersToMarkAsSorted.map { it.path }.toSet()
            if (folderPaths.isEmpty()) return@launch

            if (_uiState.value.dontAskAgainMarkAsSorted) {
                preferencesRepository.setShowConfirmMarkAsSorted(false)
            }
            performMarkFoldersAsSorted(folderPaths)
        }
    }

    private fun performMarkFoldersAsSorted(folderPathsToMark: Set<String>) {
        viewModelScope.launch {
            val currentState = _uiState.value
            val allFolderDetails = currentState.allFolderDetails
            val recursiveRootsInSelection = currentState.recursivelySelectedRoots.intersect(folderPathsToMark)

            val finalPathsToHide = folderPathsToMark.toMutableSet()

            if (recursiveRootsInSelection.isNotEmpty()) {
                recursiveRootsInSelection.forEach { rootPath ->
                    val childPaths = allFolderDetails
                        .filter { it.path.startsWith(rootPath) }
                        .map { it.path }
                    finalPathsToHide.addAll(childPaths)
                }
            }

            finalPathsToHide.forEach { preferencesRepository.addPermanentlySortedFolder(it) }
            mediaRepository.removeFoldersFromCache(finalPathsToHide)

            val message = context.resources.getQuantityString(
                R.plurals.folders_hidden_toast,
                folderPathsToMark.size,
                folderPathsToMark.size
            )

            _uiState.update {
                val updatedSelection = it.selectedBuckets.filterNot { path -> path in finalPathsToHide }
                it.copy(
                    selectedBuckets = updatedSelection,
                    toastMessage = message,
                    showMarkAsSortedConfirmation = false,
                    foldersToMarkAsSorted = emptyList(),
                    dontAskAgainMarkAsSorted = false
                )
            }
        }
    }


    fun onDontAskAgainMarkAsSortedChanged(isChecked: Boolean) {
        _uiState.update { it.copy(dontAskAgainMarkAsSorted = isChecked) }
    }

    fun dismissMarkAsSortedDialog() {
        _uiState.update { it.copy(
            showMarkAsSortedConfirmation = false,
            foldersToMarkAsSorted = emptyList(),
            dontAskAgainMarkAsSorted = false
        ) }
    }

    fun showMoveFolderDialog(sourcePath: String) {
        _uiState.update { it.copy(showMoveFolderDialogForPath = sourcePath) }
        viewModelScope.launch {
            folderSearchManager.prepareForSearch(
                initialPath = null,
                coroutineScope = viewModelScope,
                excludedFolders = setOf(sourcePath)
            )
        }
    }

    fun dismissMoveFolderDialog() {
        _uiState.update { it.copy(showMoveFolderDialogForPath = null) }
        folderSearchManager.reset()
    }

    fun confirmMoveFolderSelection() {
        viewModelScope.launch {
            val sourcePath = _uiState.value.showMoveFolderDialogForPath ?: return@launch
            val destinationPath = folderSearchManager.state.value.browsePath ?: return@launch

            dismissMoveFolderDialog() // Hide dialog immediately

            val result = fileOperationsHelper.moveFolderContents(sourcePath, destinationPath)
            result.onSuccess { (movedCount, failedCount) ->
                mediaRepository.handleFolderMove(sourcePath, destinationPath)
                val baseMessage = context.resources.getQuantityString(R.plurals.moved_files_success_msg, movedCount, movedCount)
                val message = if (failedCount > 0) {
                    baseMessage + context.resources.getQuantityString(R.plurals.moved_files_failed_suffix, failedCount, failedCount)
                } else {
                    baseMessage
                }
                _uiState.update { it.copy(toastMessage = message) }
            }.onFailure { error ->
                _uiState.update { it.copy(toastMessage = context.getString(R.string.error_prefix, error.message)) }
            }
        }
    }

    // --- CONTEXTUAL SELECTION MODE ---

    private fun canFavoriteSelection(selectedPaths: Set<String>, allFolders: List<FolderDetails>): Boolean {
        return allFolders.any { it.path in selectedPaths && !it.isSystemFolder }
    }

    fun enterContextualSelectionMode(folderPath: String) {
        _uiState.update { state ->
            val initialSelection = if (folderPath in state.selectedBuckets) {
                state.selectedBuckets.toSet()
            } else {
                setOf(folderPath)
            }
            state.copy(
                isContextualSelectionMode = true,
                contextSelectedFolderPaths = initialSelection,
                canFavoriteContextualSelection = canFavoriteSelection(initialSelection, state.allFolderDetails)
            )
        }
    }

    fun exitContextualSelectionMode() {
        _uiState.update {
            it.copy(
                isContextualSelectionMode = false,
                contextSelectedFolderPaths = emptySet(),
                canFavoriteContextualSelection = false
            )
        }
    }

    fun toggleContextualSelection(folderPath: String) {
        _uiState.update { state ->
            val currentSelection = state.contextSelectedFolderPaths
            val newSelection = if (folderPath in currentSelection) {
                currentSelection - folderPath
            } else {
                currentSelection + folderPath
            }

            if (newSelection.isEmpty()) {
                state.copy(
                    isContextualSelectionMode = false,
                    contextSelectedFolderPaths = emptySet(),
                    canFavoriteContextualSelection = false
                )
            } else {
                state.copy(
                    contextSelectedFolderPaths = newSelection,
                    canFavoriteContextualSelection = canFavoriteSelection(newSelection, state.allFolderDetails)
                )
            }
        }
    }

    fun contextualSelectAll() {
        _uiState.update { state ->
            val allVisiblePaths = state.folderCategories.flatMap { it.folders }.map { it.path }.toSet()
            state.copy(
                contextSelectedFolderPaths = allVisiblePaths,
                canFavoriteContextualSelection = canFavoriteSelection(allVisiblePaths, state.allFolderDetails)
            )
        }
    }

    fun markSelectedFoldersAsSorted() {
        viewModelScope.launch {
            val selectedPaths = _uiState.value.contextSelectedFolderPaths
            val foldersToMark = _uiState.value.allFolderDetails
                .filter { it.path in selectedPaths }

            if (foldersToMark.isEmpty()) {
                exitContextualSelectionMode()
                return@launch
            }

            val shouldShowDialog = preferencesRepository.showConfirmMarkAsSortedFlow.first()
            if (shouldShowDialog) {
                _uiState.update { it.copy(
                    showMarkAsSortedConfirmation = true,
                    foldersToMarkAsSorted = foldersToMark
                ) }
            } else {
                performMarkFoldersAsSorted(selectedPaths)
            }
            exitContextualSelectionMode()
        }
    }

    fun toggleFavoriteForSelectedFolders() {
        viewModelScope.launch {
            val selectedPaths = _uiState.value.contextSelectedFolderPaths
            val currentFavorites = _uiState.value.favoriteFolders
            val allDetails = _uiState.value.allFolderDetails

            var favoritesAdded = 0
            var favoritesRemoved = 0

            selectedPaths.forEach { path ->
                val folder = allDetails.find { it.path == path }
                if (folder != null && !folder.isSystemFolder) {
                    if (path in currentFavorites) {
                        preferencesRepository.removeSourceFavoriteFolder(path)
                        favoritesRemoved++
                    } else {
                        preferencesRepository.addSourceFavoriteFolder(path)
                        favoritesAdded++
                    }
                }
            }

            val message = when {
                favoritesAdded > 0 && favoritesRemoved > 0 -> context.getString(R.string.favorites_added_removed_msg, favoritesAdded, favoritesRemoved)
                favoritesAdded > 0 -> context.resources.getQuantityString(R.plurals.favorites_added_msg, favoritesAdded, favoritesAdded)
                favoritesRemoved > 0 -> context.resources.getQuantityString(R.plurals.favorites_removed_msg, favoritesRemoved, favoritesRemoved)
                else -> context.getString(R.string.no_changes_system_folders)
            }
            _uiState.update { it.copy(toastMessage = message) }
            exitContextualSelectionMode()
        }
    }

    // --- END CONTEXTUAL SELECTION MODE ---


    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
