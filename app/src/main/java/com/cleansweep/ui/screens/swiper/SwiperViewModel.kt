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

package com.cleansweep.ui.screens.swiper

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.unit.DpOffset
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.AddFolderFocusTarget
import com.cleansweep.data.repository.FolderBarLayout
import com.cleansweep.data.repository.FolderNameLayout
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.data.repository.SummaryViewMode
import com.cleansweep.data.repository.SwipeDownAction
import com.cleansweep.data.repository.SwipeSensitivity
import com.cleansweep.di.AppModule
import com.cleansweep.domain.bus.AppLifecycleEventBus
import com.cleansweep.domain.bus.FileModificationEvent
import com.cleansweep.domain.bus.FileModificationEventBus
import com.cleansweep.domain.bus.FolderDelta
import com.cleansweep.domain.bus.FolderUpdateEvent
import com.cleansweep.domain.bus.FolderUpdateEventBus
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.ui.components.FolderSearchManager
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.util.CoilPreloader
import com.cleansweep.util.FileOperationsHelper
import com.cleansweep.util.ThumbnailPrewarmer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

sealed class NavigationEvent {
    data object NavigateUp : NavigationEvent()
}

sealed class FolderMenuState {
    data object Hidden : FolderMenuState()
    data class Visible(val folderPath: String, val pressOffset: DpOffset) : FolderMenuState()
}

@Parcelize
data class PendingChange(
    val item: MediaItem,
    val action: SwiperAction,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable

data class SwiperUiState(
    val isLoading: Boolean = true,
    val allMediaItems: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val currentItem: MediaItem? = null,
    val error: String? = null,
    val showAddTargetFolderDialog: Boolean = false,
    val showForgetMediaSearchDialog: Boolean = false,
    val showSuccessAnimation: Boolean = false,
    val targetFolders: List<Pair<String, String>> = emptyList(),
    val targetFavorites: Set<String> = emptySet(),
    val pendingChanges: List<PendingChange> = emptyList(),
    val showSummarySheet: Boolean = false,
    val compactFoldersView: Boolean = false,
    val hideFilename: Boolean = false,
    val summaryViewMode: SummaryViewMode = SummaryViewMode.LIST,
    val isApplyingChanges: Boolean = false,
    val toastMessage: String? = null,
    val defaultCreationPath: String = "",
    val folderIdToNameMap: Map<String, String> = emptyMap(),
    val isSortingComplete: Boolean = false,
    val isFolderBarExpanded: Boolean = false,
    val useLegacyFolderIcons: Boolean = false,
    val folderMenuState: FolderMenuState = FolderMenuState.Hidden,
    val showMediaItemMenu: Boolean = false,
    val mediaItemMenuOffset: DpOffset = DpOffset.Zero,
    val videoPlaybackPosition: Long = 0L,
    val videoPlaybackSpeed: Float = 1.0f,
    val isVideoMuted: Boolean = true,
    val showRenameDialogForPath: String? = null,
    val showConfirmExitDialog: Boolean = false,
    val currentTheme: AppTheme = AppTheme.SYSTEM,
    val isCurrentItemPendingConversion: Boolean = false,
    val isSkipButtonHidden: Boolean = true,
    val sessionSkippedMediaIds: Set<String> = emptySet(),
    val useFullScreenSummarySheet: Boolean = false,
    val fullScreenSwipe: Boolean = false,


    // Pre-processed lists for Summary Sheet performance
    val toDelete: List<PendingChange> = emptyList(),
    val toKeep: List<PendingChange> = emptyList(),
    val toConvert: List<PendingChange> = emptyList(),
    val groupedMoves: List<Pair<String, List<PendingChange>>> = emptyList()
)

// Helper for pre-processing summary lists to avoid code duplication
private data class SummaryLists(
    val toDelete: List<PendingChange>,
    val toKeep: List<PendingChange>,
    val toConvert: List<PendingChange>,
    val groupedMoves: List<Pair<String, List<PendingChange>>>
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SwiperViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val fileOperationsHelper: FileOperationsHelper,
    private val thumbnailPrewarmer: ThumbnailPrewarmer,
    private val coilPreloader: CoilPreloader,
    private val savedStateHandle: SavedStateHandle,
    val imageLoader: ImageLoader, // Standard image loader
    @AppModule.GifImageLoader val gifImageLoader: ImageLoader, // GIF-specific image loader
    private val eventBus: FileModificationEventBus,
    private val appLifecycleEventBus: AppLifecycleEventBus,
    private val folderUpdateEventBus: FolderUpdateEventBus,
    val folderSearchManager: FolderSearchManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SwiperUiState())
    val uiState: StateFlow<SwiperUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent: SharedFlow<NavigationEvent> = _navigationEvent.asSharedFlow()

    private val newlyAddedTargetFolders = MutableStateFlow<Map<String, String>>(emptyMap())
    private val sessionHiddenTargetFolders = MutableStateFlow<Set<String>>(emptySet())

    val invertSwipe: StateFlow<Boolean> = preferencesRepository.invertSwipeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val swipeSensitivity: StateFlow<SwipeSensitivity> = preferencesRepository.swipeSensitivityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SwipeSensitivity.MEDIUM)

    val swipeDownAction: StateFlow<SwipeDownAction> = preferencesRepository.swipeDownActionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SwipeDownAction.NONE)

    val rememberProcessedMedia: StateFlow<Boolean> = preferencesRepository.rememberProcessedMediaFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val folderBarLayout: StateFlow<FolderBarLayout> =
        preferencesRepository.folderBarLayoutFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderBarLayout.HORIZONTAL)

    val folderNameLayout: StateFlow<FolderNameLayout> =
        preferencesRepository.folderNameLayoutFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderNameLayout.ABOVE)

    val skipPartialExpansion: StateFlow<Boolean> =
        preferencesRepository.skipPartialExpansionFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val addFolderFocusTarget: StateFlow<AddFolderFocusTarget> =
        preferencesRepository.addFolderFocusTargetFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AddFolderFocusTarget.SEARCH_PATH)

    val addFavoriteToTargetByDefault: StateFlow<Boolean> =
        preferencesRepository.addFavoriteToTargetByDefaultFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val hintOnExistingFolderName: StateFlow<Boolean> =
        preferencesRepository.hintOnExistingFolderNameFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val screenshotDeletesVideo: StateFlow<Boolean> =
        preferencesRepository.screenshotDeletesVideoFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val screenshotJpegQuality: StateFlow<String> =
        preferencesRepository.screenshotJpegQualityFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "90")


    private var bucketIds: List<String> = emptyList()
    private var _invertSwipe = false
    private var processedMediaIds = emptySet<String>()
    private var sessionProcessedMediaIds = mutableSetOf<String>()
    private var _rememberProcessedMediaEnabled = true
    private var _defaultVideoSpeed = 1.0f
    private var lastUsedTargetPath: String? = null
    private val unindexedFileCounter = AtomicInteger(0)
    private val _dialogSearchQuery = MutableStateFlow("")

    companion object {
        private const val logTag ="SwiperViewModel_DEBUG"
        private const val jitlogTag ="SwiperViewModel_JIT"
    }

    init {
        loadSettings()
        observeTargetFolders()
        observeFileDeletions()
        observeAppLifecycle()
        observeDialogSearchQuery()
        val savedChanges: List<PendingChange>? = savedStateHandle["pendingChanges"]
        if (savedChanges != null) {
            _uiState.update { currentState ->
                val summary = processSummaryLists(savedChanges, currentState.folderIdToNameMap)
                currentState.copy(
                    pendingChanges = savedChanges,
                    toDelete = summary.toDelete,
                    toKeep = summary.toKeep,
                    toConvert = summary.toConvert,
                    groupedMoves = summary.groupedMoves
                )
            }
        }
    }

    override fun onCleared() {
        logJitSummary()
        super.onCleared()
    }

    private fun logJitSummary() {
        val count = unindexedFileCounter.get()
        if (count > 0) {
            Log.d(jitlogTag, "Session Summary: Queued $count un-indexed files for MediaStore pre-warming.")
        }
    }

    private fun observeAppLifecycle() {
        viewModelScope.launch {
            appLifecycleEventBus.appResumeEvent.collect {
                validateStateAndRefreshData()
            }
        }
    }

    private fun observeDialogSearchQuery() {
        viewModelScope.launch {
            _dialogSearchQuery
                .debounce(200L)
                .distinctUntilChanged()
                .collect { query ->
                    folderSearchManager.updateSearchQuery(query)
                }
        }
    }

    private suspend fun validateStateAndRefreshData() {
        // No need to call invalidate here, as the BaseActivity -> MainViewModel flow handles it.
        var toastToShow: String? = null

        val currentFavorites = preferencesRepository.targetFavoriteFoldersFlow.first()
        if (currentFavorites.isNotEmpty()) {
            val existenceMap = mediaRepository.getFolderExistence(currentFavorites)
            val missingFavorites = currentFavorites.filter { existenceMap[it] == false }
            if (missingFavorites.isNotEmpty()) {
                missingFavorites.forEach { preferencesRepository.removeTargetFavoriteFolder(it) }
                toastToShow = context.resources.getQuantityString(
                    R.plurals.target_favorite_removed_toast,
                    missingFavorites.size,
                    missingFavorites.size
                )
            }
        }

        val sessionFolders = newlyAddedTargetFolders.value.keys
        if (sessionFolders.isNotEmpty()) {
            val existenceMap = mediaRepository.getFolderExistence(sessionFolders)
            val missingSessionFolders = sessionFolders.filter { existenceMap[it] == false }
            if (missingSessionFolders.isNotEmpty()) {
                newlyAddedTargetFolders.update { currentMap ->
                    currentMap.filterKeys { it !in missingSessionFolders }
                }
            }
        }

        val currentState = _uiState.value
        if (currentState.pendingChanges.isNotEmpty()) {
            val validChanges = fileOperationsHelper.filterExistingFiles(currentState.pendingChanges)
            if (validChanges.size != currentState.pendingChanges.size) {
                _uiState.update { state ->
                    val summary = processSummaryLists(validChanges, state.folderIdToNameMap)
                    state.copy(
                        pendingChanges = validChanges,
                        toDelete = summary.toDelete,
                        toKeep = summary.toKeep,
                        toConvert = summary.toConvert,
                        groupedMoves = summary.groupedMoves
                    )
                }
                savedStateHandle["pendingChanges"] = ArrayList(validChanges)
                if (toastToShow == null) {
                    toastToShow = context.getString(R.string.refreshed_external_changes)
                }
            }
        }

        toastToShow?.let { showToast(it) }

        initializeMedia(bucketIds)

        if (currentState.showForgetMediaSearchDialog) {
            showForgetMediaInFolderDialog()
        }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            preferencesRepository.invertSwipeFlow.collect { isInverted -> _invertSwipe = isInverted }
        }
        viewModelScope.launch {
            preferencesRepository.rememberProcessedMediaFlow.collect { remember -> _rememberProcessedMediaEnabled = remember }
        }
        viewModelScope.launch {
            preferencesRepository.processedMediaPathsFlow.collect { paths ->
                processedMediaIds = if (_rememberProcessedMediaEnabled) paths else emptySet()
            }
        }
        viewModelScope.launch {
            preferencesRepository.defaultVideoSpeedFlow.collect { speed ->
                _defaultVideoSpeed = speed
                if (_uiState.value.currentItem?.isVideo == true) {
                    _uiState.update { it.copy(videoPlaybackSpeed = speed) }
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.useLegacyFolderIconsFlow.collect {
                _uiState.update { s -> s.copy(useLegacyFolderIcons = it) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.summaryViewModeFlow.collect { _uiState.update { s -> s.copy(summaryViewMode = it) } }
        }
        viewModelScope.launch {
            preferencesRepository.compactFolderViewFlow.collect { _uiState.update { s -> s.copy(compactFoldersView = it) } }
        }
        viewModelScope.launch {
            preferencesRepository.hideFilenameFlow.collect { _uiState.update { s -> s.copy(hideFilename = it) } }
        }
        viewModelScope.launch {
            preferencesRepository.defaultAlbumCreationPathFlow.collect { _uiState.update { s -> s.copy(defaultCreationPath = it) } }
        }
        viewModelScope.launch {
            preferencesRepository.themeFlow.collectLatest { theme ->
                _uiState.update { it.copy(currentTheme = theme) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.hideSkipButtonFlow.collectLatest { isHidden ->
                _uiState.update { it.copy(isSkipButtonHidden = isHidden) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.useFullScreenSummarySheetFlow.collect { useFullScreen ->
                _uiState.update { it.copy(useFullScreenSummarySheet = useFullScreen) }
            }
        }
        viewModelScope.launch {
            val initialExpandedState = preferencesRepository.bottomBarExpandedFlow.first()
            _uiState.update { it.copy(isFolderBarExpanded = initialExpandedState) }
        }
        viewModelScope.launch {
            preferencesRepository.fullScreenSwipeFlow.collect { enabled ->
                _uiState.update { it.copy(fullScreenSwipe = enabled) }
            }
        }
    }

    private fun observeTargetFolders() {
        viewModelScope.launch {
            combine(
                preferencesRepository.targetFavoriteFoldersFlow,
                newlyAddedTargetFolders,
                sessionHiddenTargetFolders
            ) { favorites, newlyAdded, sessionHidden ->
                _uiState.update { it.copy(targetFavorites = favorites) }

                val allVisiblePaths = (favorites + newlyAdded.keys).toSet() - sessionHidden
                val folderDetails = if (allVisiblePaths.isNotEmpty()) {
                    mediaRepository.getFoldersFromPaths(allVisiblePaths)
                } else {
                    emptyList()
                }

                val allDetailsMap = folderDetails.toMap() + newlyAdded
                val finalTargetFolders = allVisiblePaths.mapNotNull { path ->
                    allDetailsMap[path]?.let { name -> path to name }
                }
                _uiState.update {
                    it.copy(
                        targetFolders = finalTargetFolders,
                        folderIdToNameMap = it.folderIdToNameMap + allDetailsMap
                    )
                }
            }.collect()
        }
    }

    private fun observeFileDeletions() {
        viewModelScope.launch {
            eventBus.events.collect { event ->
                if (event is FileModificationEvent.FilesDeleted) {
                    val deletedIds = event.paths.toSet()
                    _uiState.update { currentState ->
                        val newMediaList = currentState.allMediaItems.filterNot { it.id in deletedIds }
                        val newPendingChanges = currentState.pendingChanges.filterNot { it.item.id in deletedIds }
                        val summary = processSummaryLists(newPendingChanges, currentState.folderIdToNameMap)
                        savedStateHandle["pendingChanges"] = ArrayList(newPendingChanges)

                        var newCurrentIndex = currentState.currentIndex
                        var newCurrentItem = currentState.currentItem
                        var newIsSortingComplete = currentState.isSortingComplete

                        if (currentState.currentItem?.id in deletedIds) {
                            prewarmNextImages()
                            // Current item was deleted, need to find the next one
                            val allProcessedIds = sessionProcessedMediaIds +
                                    (if (_rememberProcessedMediaEnabled) processedMediaIds else emptySet()) +
                                    newPendingChanges.map { it.item.id }.toSet() +
                                    currentState.sessionSkippedMediaIds

                            // search from the *new* list at the *old* index
                            val searchStartIndex = currentState.currentIndex.coerceAtMost(if (newMediaList.isEmpty()) 0 else newMediaList.lastIndex)

                            val nextIndexInList = newMediaList
                                .drop(searchStartIndex)
                                .indexOfFirst { it.id !in allProcessedIds }

                            if (nextIndexInList != -1) {
                                newCurrentIndex = searchStartIndex + nextIndexInList
                                newCurrentItem = newMediaList.getOrNull(newCurrentIndex)
                                newIsSortingComplete = newCurrentItem == null
                            } else {
                                newCurrentItem = null
                                newIsSortingComplete = true
                            }
                        } else if (currentState.currentItem != null) {
                            // Current item was not deleted. Adjust index if needed.
                            // Count how many items *before* the current one were deleted.
                            val deletedBeforeCount = currentState.allMediaItems
                                .take(currentState.currentIndex)
                                .count { it.id in deletedIds }
                            newCurrentIndex = currentState.currentIndex - deletedBeforeCount
                        }

                        currentState.copy(
                            allMediaItems = newMediaList,
                            pendingChanges = newPendingChanges,
                            currentItem = newCurrentItem,
                            currentIndex = newCurrentIndex,
                            isSortingComplete = newIsSortingComplete,
                            toDelete = summary.toDelete,
                            toKeep = summary.toKeep,
                            toConvert = summary.toConvert,
                            groupedMoves = summary.groupedMoves
                        )
                    }
                }
            }
        }
    }

    fun initializeMedia(sourceBucketIds: List<String>) {
        if (this.bucketIds == sourceBucketIds && !_uiState.value.isLoading && _uiState.value.allMediaItems.isNotEmpty()) return
        this.bucketIds = sourceBucketIds
        unindexedFileCounter.set(0)

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, allMediaItems = emptyList(), currentIndex = 0, currentItem = null, isSortingComplete = false) }

            try {
                val initialLayout = folderBarLayout.first()
                val lastExpandedState = preferencesRepository.bottomBarExpandedFlow.first()
                _uiState.update { it.copy(isFolderBarExpanded = initialLayout == FolderBarLayout.VERTICAL || lastExpandedState) }

                val latestProcessedPaths = if (_rememberProcessedMediaEnabled) {
                    preferencesRepository.processedMediaPathsFlow.first()
                } else {
                    emptySet()
                }
                processedMediaIds = latestProcessedPaths

                var initialItemFound = false
                val allItems = mutableListOf<MediaItem>()

                mediaRepository.getMediaFromBuckets(bucketIds)
                    .onCompletion {
                        if (!initialItemFound) {
                            _uiState.update {
                                it.copy(isLoading = false, isSortingComplete = true, currentItem = null, allMediaItems = allItems.toList())
                            }
                        }
                    }
                    .collect { newBatch ->
                        val unindexedInBatch = newBatch.filter { it.uri.scheme == "file" }.map { it.id }
                        if (unindexedInBatch.isNotEmpty()) {
                            unindexedFileCounter.addAndGet(unindexedInBatch.size)
                            viewModelScope.launch(Dispatchers.IO) {
                                Log.d(jitlogTag, "Queueing ${unindexedInBatch.size} un-indexed files for background pre-warming.")
                                thumbnailPrewarmer.prewarm(unindexedInBatch)
                            }
                        }

                        allItems.addAll(newBatch)

                        if (!initialItemFound) {
                            val allProcessedIds = sessionProcessedMediaIds + processedMediaIds + _uiState.value.sessionSkippedMediaIds
                            val firstUnprocessedIndex = allItems.indexOfFirst { it.id !in allProcessedIds }
                            if (firstUnprocessedIndex != -1) {
                                initialItemFound = true
                                _uiState.update {
                                    it.copy(
                                        allMediaItems = allItems.toList(),
                                        isLoading = false,
                                        currentIndex = firstUnprocessedIndex,
                                        currentItem = allItems[firstUnprocessedIndex],
                                        videoPlaybackPosition = 0L,
                                        videoPlaybackSpeed = _defaultVideoSpeed,
                                        isCurrentItemPendingConversion = false
                                    )
                                }
                                prewarmNextImages()
                            }
                        } else {
                            _uiState.update { it.copy(allMediaItems = allItems.toList()) }
                        }
                    }

            } catch (e: Exception) {
                val errorMessage = context.getString(R.string.failed_load_media_prefix, e.message)
                _uiState.update { it.copy(isLoading = false, error = errorMessage) }
            }
        }
    }

    private fun processSummaryLists(changes: List<PendingChange>, folderIdToNameMap: Map<String, String>): SummaryLists {
        val toDelete = changes.filter { it.action is SwiperAction.Delete || it.action is SwiperAction.ScreenshotAndDelete }
        val toKeep = changes.filter { it.action is SwiperAction.Keep }
        val toConvert = changes.filter {
            it.action is SwiperAction.Screenshot || it.action is SwiperAction.ScreenshotAndDelete
        }

        val movedChanges = changes.filter { it.action is SwiperAction.Move }
        val groupedMoves = movedChanges
            .groupBy { (it.action as SwiperAction.Move).targetFolderPath }
            .toList()
            .sortedBy { (folderId, _) -> folderIdToNameMap[folderId]?.lowercase() ?: folderId }

        return SummaryLists(toDelete, toKeep, toConvert, groupedMoves)
    }

    private fun processAndAdvance(change: PendingChange? = null, skipCurrent: Boolean = false) {
        change?.let { coilPreloader.preload(listOf(it.item)) }
        prewarmNextImages()

        viewModelScope.launch {
            _uiState.update { currentState ->
                val itemToProcess = currentState.currentItem ?: return@update currentState

                // Step 1: Determine new lists of changes and skips
                val newPendingChanges = if (change != null) {
                    currentState.pendingChanges + change
                } else {
                    currentState.pendingChanges
                }
                val newSkippedIds = if (skipCurrent) {
                    currentState.sessionSkippedMediaIds + itemToProcess.id
                } else {
                    currentState.sessionSkippedMediaIds
                }

                savedStateHandle["pendingChanges"] = if (newPendingChanges.isNotEmpty()) ArrayList(newPendingChanges) else null

                // Step 2: Find the next item to display
                val allProcessedIds = sessionProcessedMediaIds +
                        (if (_rememberProcessedMediaEnabled) processedMediaIds else emptySet()) +
                        newPendingChanges.map { it.item.id }.toSet() +
                        newSkippedIds

                val nextIndexInList = currentState.allMediaItems
                    .drop(currentState.currentIndex + 1)
                    .indexOfFirst { it.id !in allProcessedIds }

                val nextItem: MediaItem?
                val nextIndex: Int
                val isSortingComplete: Boolean

                if (nextIndexInList != -1) {
                    val actualIndex = currentState.currentIndex + 1 + nextIndexInList
                    nextIndex = actualIndex
                    nextItem = currentState.allMediaItems.getOrNull(actualIndex)
                    isSortingComplete = nextItem == null
                } else {
                    nextIndex = currentState.currentIndex // Keep index, but item becomes null
                    nextItem = null
                    isSortingComplete = true
                }

                // Step 3: Recalculate summary lists
                val summary = processSummaryLists(newPendingChanges, currentState.folderIdToNameMap)

                // Step 4: Return the new, fully-formed state
                currentState.copy(
                    pendingChanges = newPendingChanges,
                    sessionSkippedMediaIds = newSkippedIds,
                    currentIndex = nextIndex,
                    currentItem = nextItem,
                    isSortingComplete = isSortingComplete,
                    showSummarySheet = isSortingComplete && newPendingChanges.isNotEmpty(),
                    videoPlaybackPosition = 0L,
                    videoPlaybackSpeed = _defaultVideoSpeed,
                    isVideoMuted = true,
                    isCurrentItemPendingConversion = false,
                    toDelete = summary.toDelete,
                    toKeep = summary.toKeep,
                    toConvert = summary.toConvert,
                    groupedMoves = summary.groupedMoves
                )
            }
        }
    }

    fun handleSwipeLeft() {
        val currentItem = _uiState.value.currentItem ?: return
        val change = PendingChange(currentItem, if (_invertSwipe) SwiperAction.Keep(currentItem) else SwiperAction.Delete(currentItem))
        processAndAdvance(change)
    }

    fun handleSwipeRight() {
        val currentItem = _uiState.value.currentItem ?: return
        val change = PendingChange(currentItem, if (_invertSwipe) SwiperAction.Delete(currentItem) else SwiperAction.Keep(currentItem))
        processAndAdvance(change)
    }

    fun handleSwipeDown() {
        val currentItem = _uiState.value.currentItem ?: return
        viewModelScope.launch {
            when(swipeDownAction.first()) {
                SwipeDownAction.NONE -> { /* Do nothing */ }
                SwipeDownAction.MOVE_TO_EDIT -> moveToEditFolder()
                SwipeDownAction.SKIP_ITEM -> handleSkip()
                SwipeDownAction.ADD_TARGET_FOLDER -> showAddTargetFolderDialog()
                SwipeDownAction.SHARE -> shareCurrentItem()
                SwipeDownAction.OPEN_WITH -> openCurrentItem()
            }
        }
    }

    fun handleSkip() {
        if (_uiState.value.currentItem == null) return
        processAndAdvance(skipCurrent = true)
    }

    fun moveToFolder(folderPath: String) {
        val currentItem = _uiState.value.currentItem ?: return
        val change = PendingChange(currentItem, SwiperAction.Move(currentItem, folderPath))
        processAndAdvance(change)
    }

    fun addScreenshotChange(timestampMicros: Long) {
        viewModelScope.launch {
            val currentItem = _uiState.value.currentItem ?: return@launch
            if (!currentItem.isVideo || _uiState.value.isCurrentItemPendingConversion) return@launch

            val deleteAfter = screenshotDeletesVideo.first()
            val change = if (deleteAfter) {
                PendingChange(currentItem, SwiperAction.ScreenshotAndDelete(currentItem, timestampMicros))
            } else {
                PendingChange(currentItem, SwiperAction.Screenshot(currentItem, timestampMicros))
            }

            if (deleteAfter) {
                processAndAdvance(change)
            } else {
                // Just add the change without advancing
                _uiState.update { currentState ->
                    val newChanges = currentState.pendingChanges + change
                    val summary = processSummaryLists(newChanges, currentState.folderIdToNameMap)
                    currentState.copy(
                        pendingChanges = newChanges,
                        isCurrentItemPendingConversion = true,
                        toastMessage = context.getString(R.string.added_screenshot_toast),
                        toDelete = summary.toDelete,
                        toKeep = summary.toKeep,
                        toConvert = summary.toConvert,
                        groupedMoves = summary.groupedMoves
                    ).also {
                        savedStateHandle["pendingChanges"] = ArrayList(newChanges)
                        coilPreloader.preload(listOf(change.item))
                    }
                }
            }
            dismissMediaItemMenu()
        }
    }

    fun moveToEditFolder() {
        val currentItem = _uiState.value.currentItem ?: return
        viewModelScope.launch {
            val parentPath = withContext(Dispatchers.IO) {
                try {
                    File(currentItem.id).parent
                } catch (e: Exception) {
                    Log.e(logTag, "Could not determine parent path for ${currentItem.id}", e)
                    null
                }
            }

            if (parentPath != null) {
                val localizedFolderName = context.getString(R.string.folder_name_to_edit)
                val toEditPath = File(parentPath, localizedFolderName).absolutePath
                val toEditName = localizedFolderName

                // Ensure the folder is known to the UI, even if it doesn't exist yet.
                // This prevents a flicker or missing folder name in the summary sheet.
                if (!_uiState.value.folderIdToNameMap.containsKey(toEditPath)) {
                    _uiState.update {
                        it.copy(folderIdToNameMap = it.folderIdToNameMap + (toEditPath to toEditName))
                    }
                }

                moveToFolder(toEditPath)
            } else {
                _uiState.update { it.copy(toastMessage = context.getString(R.string.could_not_find_parent)) }
            }
            dismissMediaItemMenu()
        }
    }

    fun applyChanges() {
        viewModelScope.launch {
            _uiState.update { it.copy(isApplyingChanges = true) }

            val initialChanges = _uiState.value.pendingChanges
            if (initialChanges.isEmpty()) {
                Log.d(logTag, "applyChanges: No pending changes. Completing.")
                _uiState.update { it.copy(isApplyingChanges = false) }
                return@launch
            }
            Log.d(logTag, "applyChanges: Found ${initialChanges.size} initial pending changes.")

            val validatedChanges = fileOperationsHelper.filterExistingFiles(initialChanges)
            val missingCount = initialChanges.size - validatedChanges.size

            if (missingCount > 0) {
                showToast(context.resources.getQuantityString(R.plurals.files_skipped_missing_toast, missingCount, missingCount))
                _uiState.update { currentState ->
                    val summary = processSummaryLists(validatedChanges, currentState.folderIdToNameMap)
                    currentState.copy(
                        pendingChanges = validatedChanges,
                        toDelete = summary.toDelete,
                        toKeep = summary.toKeep,
                        toConvert = summary.toConvert,
                        groupedMoves = summary.groupedMoves
                    )
                }
                savedStateHandle["pendingChanges"] = ArrayList(validatedChanges)
            }

            if (validatedChanges.isEmpty()) {
                Log.d(logTag, "applyChanges: All pending changes were for non-existent files. Aborting.")
                _uiState.update { it.copy(isApplyingChanges = false, showSummarySheet = false) }
                return@launch
            }

            val finalChanges = synchronizeUnindexedChanges(validatedChanges)
            if (finalChanges == null) {
                _uiState.update { it.copy(isApplyingChanges = false, error = "Failed to sync with MediaStore.") }
                return@launch
            }

            val conversionActions = finalChanges.filter {
                it.action is SwiperAction.Screenshot || it.action is SwiperAction.ScreenshotAndDelete
            }
            val conversionResults = mutableMapOf<String, MediaItem>()
            if (conversionActions.isNotEmpty()) {
                val quality = screenshotJpegQuality.first().toIntOrNull() ?: 90
                val conversionJobs = conversionActions.map { change ->
                    async {
                        val timestamp = when (val action = change.action) {
                            is SwiperAction.Screenshot -> action.timestampMicros
                            is SwiperAction.ScreenshotAndDelete -> action.timestampMicros
                            else -> -1L
                        }
                        fileOperationsHelper.convertVideoToImage(change.item, timestamp, quality).getOrNull()?.let { newItem ->
                            change.item.id to newItem
                        }
                    }
                }
                conversionResults.putAll(conversionJobs.awaitAll().filterNotNull().toMap())
            }

            val finalMoveMap = mutableMapOf<String, String>()
            val itemsToDelete = mutableListOf<MediaItem>()

            for ((itemId, changes) in finalChanges.groupBy { it.item.id }) {
                val originalItem = changes.first().item
                val wasConverted = conversionResults.containsKey(itemId)
                val newImageItem = conversionResults[itemId]

                val hasMove = changes.any { it.action is SwiperAction.Move }
                val hasDelete = changes.any { it.action is SwiperAction.Delete }
                val hasScreenshotAndDelete = changes.any { it.action is SwiperAction.ScreenshotAndDelete }

                if (hasScreenshotAndDelete) {
                    itemsToDelete.add(originalItem)
                    if (newImageItem != null) {
                        changes.find { it.action is SwiperAction.Move }?.let { moveChange ->
                            val moveAction = moveChange.action as SwiperAction.Move
                            finalMoveMap[newImageItem.id] = moveAction.targetFolderPath
                        }
                    }
                } else if (wasConverted && newImageItem != null) {
                    if (hasMove) {
                        val moveAction = changes.first { it.action is SwiperAction.Move }.action as SwiperAction.Move
                        finalMoveMap[newImageItem.id] = moveAction.targetFolderPath
                    }
                    if (hasDelete) {
                        itemsToDelete.add(originalItem)
                    }
                } else {
                    if (hasMove) {
                        val moveAction = changes.first { it.action is SwiperAction.Move }.action as SwiperAction.Move
                        finalMoveMap[originalItem.id] = moveAction.targetFolderPath
                    } else if (hasDelete) {
                        itemsToDelete.add(originalItem)
                    }
                }
            }

            var success = true
            var moveResults: Map<String, MediaItem> = emptyMap()

            if (finalMoveMap.isNotEmpty()) {
                Log.d(logTag, "Executing move for ${finalMoveMap.size} files.")
                moveResults = mediaRepository.moveMedia(finalMoveMap.keys.toList(), finalMoveMap.values.toList())
                if (moveResults.size != finalMoveMap.size) success = false
            }

            if (itemsToDelete.isNotEmpty()) {
                Log.d(logTag, "Executing delete for ${itemsToDelete.size} files.")
                val deleteSuccess = mediaRepository.deleteMedia(itemsToDelete)
                if (!deleteSuccess) success = false
            }

            completeChanges(success, validatedChanges, moveResults)
        }
    }


    private suspend fun synchronizeUnindexedChanges(changes: List<PendingChange>): List<PendingChange>? {
        val unindexedChanges = changes.filter { it.item.uri.scheme == "file" }
        if (unindexedChanges.isEmpty()) {
            return changes // No un-indexed items, return original list
        }

        val pathsToScan = unindexedChanges.map { it.item.id }
        Log.d(logTag, "synchronizeUnindexedChanges: Found ${pathsToScan.size} items with file:// URIs. Scanning.")

        val scanSuccess = mediaRepository.scanPathsAndWait(pathsToScan)
        if (!scanSuccess) {
            Log.e(logTag, "synchronizeUnindexedChanges: scanPathsAndWait FAILED.")
            return null
        }

        Log.d(logTag, "synchronizeUnindexedChanges: Scan successful. Fetching refreshed items.")
        val refreshedItemsMap = mediaRepository.getMediaItemsFromPaths(pathsToScan).associateBy { it.id }

        // Create a new list, updating with refreshed items where available
        return changes.map { originalChange ->
            refreshedItemsMap[originalChange.item.id]?.let { refreshedItem ->
                originalChange.copy(item = refreshedItem) // Update with new MediaItem
            } ?: originalChange // Keep original if it failed to index
        }
    }


    private fun completeChanges(
        success: Boolean,
        originalChanges: List<PendingChange>,
        moveResults: Map<String, MediaItem> = emptyMap()
    ) {
        if (success) {
            viewModelScope.launch {
                val folderDeltas = mutableMapOf<String, FolderDelta>()

                // Calculate net changes for each folder
                originalChanges.forEach { change ->
                    val item = change.item
                    File(item.id).parent?.let { folderPath ->
                        val currentDelta = folderDeltas.getOrDefault(folderPath, FolderDelta(0, 0L))
                        folderDeltas[folderPath] = currentDelta.copy(
                            itemCountChange = currentDelta.itemCountChange - 1,
                            sizeChange = currentDelta.sizeChange - item.size
                        )
                    }
                }
                moveResults.values.forEach { newItem ->
                    File(newItem.id).parent?.let { folderPath ->
                        val currentDelta = folderDeltas.getOrDefault(folderPath, FolderDelta(0, 0L))
                        folderDeltas[folderPath] = currentDelta.copy(
                            itemCountChange = currentDelta.itemCountChange + 1,
                            sizeChange = currentDelta.sizeChange + newItem.size
                        )
                    }
                }

                if (folderDeltas.isNotEmpty()) {
                    folderUpdateEventBus.post(FolderUpdateEvent.FolderBatchUpdate(folderDeltas))
                }

                // Set for in-session logic, MUST use original paths
                val sessionPathsToRemember = originalChanges.map { it.item.id }.toSet()
                sessionProcessedMediaIds.addAll(sessionPathsToRemember)

                // Set for permanent storage, MUST use final paths for files that still exist
                val permanentPathsToStore = mutableSetOf<String>()
                originalChanges.forEach { change ->
                    when (change.action) {
                        is SwiperAction.Keep, is SwiperAction.Screenshot -> {
                            permanentPathsToStore.add(change.item.id)
                        }
                        is SwiperAction.Move, is SwiperAction.ScreenshotAndDelete -> {
                            // Find the new item from moveResults (if it was moved)
                            // or from conversionResults (if it was a screenshot)
                            val newItem = moveResults[change.item.id]
                            if (newItem != null) {
                                permanentPathsToStore.add(newItem.id)
                            }
                        }
                        is SwiperAction.Delete -> {
                            // Do nothing, the file is gone.
                        }
                    }
                }
                if (_rememberProcessedMediaEnabled && permanentPathsToStore.isNotEmpty()) {
                    withContext(NonCancellable) { preferencesRepository.addProcessedMediaPaths(permanentPathsToStore) }
                }

                val emptyChanges = emptyList<PendingChange>()
                val summary = processSummaryLists(emptyChanges, _uiState.value.folderIdToNameMap)
                _uiState.update { it.copy(
                    pendingChanges = emptyChanges,
                    showSummarySheet = false,
                    isApplyingChanges = false,
                    toastMessage = context.getString(R.string.changes_applied_success),
                    toDelete = summary.toDelete,
                    toKeep = summary.toKeep,
                    toConvert = summary.toConvert,
                    groupedMoves = summary.groupedMoves
                )}
                savedStateHandle["pendingChanges"] = null
            }
        } else {
            _uiState.update { it.copy(
                error = context.getString(R.string.failed_apply_changes),
                showSummarySheet = true,
                isApplyingChanges = false
            )}
        }
    }

    fun showAddTargetFolderDialog() {
        _uiState.update { it.copy(showAddTargetFolderDialog = true) }
        val currentTargetPaths = _uiState.value.targetFolders.map { it.first }.toSet()
        val initialPath = _uiState.value.defaultCreationPath

        folderSearchManager.prepareForSearch(
            initialPath = initialPath,
            coroutineScope = viewModelScope,
            excludedFolders = currentTargetPaths
        )
    }

    fun showForgetMediaInFolderDialog() {
        viewModelScope.launch {
            val foldersWithHistory = mediaRepository.getFoldersWithProcessedMedia()
            if (foldersWithHistory.isEmpty()) {
                _uiState.update { it.copy(toastMessage = context.getString(R.string.no_sorted_history_toast)) }
                return@launch
            }
            folderSearchManager.prepareWithPreFilteredList(foldersWithHistory)
            _uiState.update { it.copy(showForgetMediaSearchDialog = true) }
        }
    }

    fun onDialogSearchQueryChanged(query: String) {
        _dialogSearchQuery.value = query
    }

    fun onPathSelected(path: String) {
        folderSearchManager.selectPath(path)
    }

    fun onSearchFocusChanged(isFocused: Boolean) {
        if (!isFocused) {
            folderSearchManager.revertSearchIfEmpty()
        }
    }

    fun dismissAddTargetFolderDialog() {
        folderSearchManager.reset()
        _uiState.update { it.copy(showAddTargetFolderDialog = false) }
    }

    fun dismissForgetMediaSearchDialog() {
        folderSearchManager.reset()
        _uiState.update { it.copy(showForgetMediaSearchDialog = false) }
    }

    fun forgetSortedMediaInFolder(folderPath: String) {
        viewModelScope.launch {
            // Remove from persistent storage
            preferencesRepository.removeProcessedMediaPathsInFolder(folderPath)
            preferencesRepository.removePermanentlySortedFolder(folderPath)

            // Also remove from session-specific history to handle partially-sorted folders
            val removedFromSession = sessionProcessedMediaIds.removeAll { path ->
                try {
                    File(path).parent == folderPath
                } catch (e: Exception) {
                    false
                }
            }
            if (removedFromSession) {
                Log.d(logTag, "Removed session-sorted media from '$folderPath'.")
            }

            _uiState.update { it.copy(toastMessage = context.getString(R.string.forget_success_toast, File(folderPath).name)) }
            // Re-initialize media to reflect the changes immediately
            initializeMedia(bucketIds)
        }
    }


    fun confirmFolderSelection(newFolderName: String, addToFavorites: Boolean, alsoMove: Boolean) {
        val searchState = folderSearchManager.state.value

        if (newFolderName.isNotBlank()) {
            val parentPath = searchState.browsePath ?: searchState.searchQuery
            if (parentPath.isNotBlank()) {
                createAndAddTargetFolder(newFolderName, parentPath, addToFavorites, alsoMove)
            } else {
                _uiState.update { it.copy(toastMessage = context.getString(R.string.select_parent_location_toast)) }
            }
        }
        else {
            val importPath = searchState.browsePath
            if (importPath != null) {
                importTargetFolder(importPath, addToFavorites, alsoMove)
            } else {
                _uiState.update { it.copy(toastMessage = context.getString(R.string.select_folder_import_toast)) }
            }
        }
    }

    fun resetFolderSelectionToDefault() {
        val defaultPath = _uiState.value.defaultCreationPath
        folderSearchManager.selectPath(defaultPath)
    }

    private fun createAndAddTargetFolder(newFolderName: String, parentPath: String, addToFavorites: Boolean, alsoMove: Boolean) {
        viewModelScope.launch {
            mediaRepository.createNewFolder(newFolderName, parentPath).onSuccess { newFolderPath ->
                lastUsedTargetPath = parentPath
                newlyAddedTargetFolders.update { it + (newFolderPath to newFolderName) }
                sessionHiddenTargetFolders.update { it - newFolderPath }
                folderUpdateEventBus.post(FolderUpdateEvent.FolderAdded(newFolderPath, newFolderName))

                if (addToFavorites) {
                    preferencesRepository.addTargetFavoriteFolder(newFolderPath)
                }
                if (alsoMove) {
                    moveToFolder(newFolderPath)
                }
                dismissAddTargetFolderDialog()
            }.onFailure { exception ->
                val errorMessage = context.getString(R.string.error_prefix, exception.message)
                _uiState.update { it.copy(toastMessage = errorMessage) }
            }
        }
    }

    private fun importTargetFolder(path: String, addToFavorites: Boolean, alsoMove: Boolean) {
        viewModelScope.launch {
            lastUsedTargetPath = path
            val name = mediaRepository.getFolderNames(setOf(path))[path] ?: path.substringAfterLast('/')
            newlyAddedTargetFolders.update { it + (path to name) }
            sessionHiddenTargetFolders.update { it - path }
            folderUpdateEventBus.post(FolderUpdateEvent.FolderAdded(path, name))

            if (addToFavorites) {
                preferencesRepository.addTargetFavoriteFolder(path)
            }
            if (alsoMove) {
                moveToFolder(path)
            }
            dismissAddTargetFolderDialog()
        }
    }

    fun showFolderMenu(path: String, pressOffset: DpOffset) {
        _uiState.update { it.copy(folderMenuState = FolderMenuState.Visible(path, pressOffset)) }
    }

    fun dismissFolderMenu() {
        _uiState.update { it.copy(folderMenuState = FolderMenuState.Hidden) }
    }

    fun showRenameDialog(path: String) {
        dismissFolderMenu()
        _uiState.update { it.copy(showRenameDialogForPath = path) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(showRenameDialogForPath = null) }
    }

    fun renameTargetFolder(oldPath: String, newName: String) {
        viewModelScope.launch {
            fileOperationsHelper.renameFolder(oldPath, newName).onSuccess { newPath ->
                preferencesRepository.updateFolderPath(oldPath, newPath)
                if (newlyAddedTargetFolders.value.containsKey(oldPath)) {
                    newlyAddedTargetFolders.update { (it - oldPath) + (newPath to newName) }
                }
                _uiState.update { currentState ->
                    val currentChanges = currentState.pendingChanges
                    val updatedChanges = currentChanges.map { change ->
                        if (change.action is SwiperAction.Move && change.action.targetFolderPath == oldPath) {
                            change.copy(action = SwiperAction.Move(change.item, newPath))
                        } else {
                            change
                        }
                    }
                    val summary = processSummaryLists(updatedChanges, currentState.folderIdToNameMap)
                    currentState.copy(
                        pendingChanges = updatedChanges,
                        toastMessage = context.getString(R.string.folder_renamed_success),
                        showRenameDialogForPath = null,
                        toDelete = summary.toDelete,
                        toKeep = summary.toKeep,
                        toConvert = summary.toConvert,
                        groupedMoves = summary.groupedMoves
                    )
                }
            }.onFailure { error ->
                val errorMessage = context.getString(R.string.error_prefix, error.message)
                _uiState.update { it.copy(
                    toastMessage = errorMessage,
                    showRenameDialogForPath = null
                )}
            }
        }
    }


    fun showMediaItemMenu(offset: DpOffset) {
        _uiState.update { it.copy(showMediaItemMenu = true, mediaItemMenuOffset = offset) }
    }

    fun dismissMediaItemMenu() {
        _uiState.update { it.copy(showMediaItemMenu = false) }
    }

    fun shareCurrentItem() {
        val currentItem = _uiState.value.currentItem ?: return
        dismissMediaItemMenu()

        val shareUri = if (currentItem.uri.scheme == "file") {
            try {
                val file = File(currentItem.id)
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } catch (e: Exception) {
                Log.e(logTag, "Error creating content URI for sharing", e)
                Toast.makeText(context, context.getString(R.string.error_creating_uri), Toast.LENGTH_LONG).show()
                return
            }
        } else {
            currentItem.uri
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(shareUri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.no_app_to_share), Toast.LENGTH_LONG).show()
        }
    }

    fun openCurrentItem() {
        val currentItem = _uiState.value.currentItem ?: return
        dismissMediaItemMenu()
        val viewUri = if (currentItem.uri.scheme == "file") {
            try {
                val file = File(currentItem.id)
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } catch (e: Exception) {
                currentItem.uri // fallback
            }
        } else {
            currentItem.uri
        }

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(viewUri, context.contentResolver.getType(viewUri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(openIntent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.no_app_to_open), Toast.LENGTH_LONG).show()
        }
    }

    fun toggleTargetFavorite(folderPath: String) {
        viewModelScope.launch {
            if (folderPath in _uiState.value.targetFavorites) {
                // Unfavorite action
                preferencesRepository.removeTargetFavoriteFolder(folderPath)
                val shouldRemoveFromBar = preferencesRepository.unfavoriteRemovesFromBarFlow.first()
                if (shouldRemoveFromBar) {
                    sessionHiddenTargetFolders.update { it + folderPath }
                    newlyAddedTargetFolders.update { it - folderPath }
                } else {
                    val folderName = _uiState.value.folderIdToNameMap[folderPath]
                    if (folderName != null) {
                        newlyAddedTargetFolders.update { it + (folderPath to folderName) }
                    }
                }
            } else {
                // Favorite action
                sessionHiddenTargetFolders.update { it - folderPath }
                preferencesRepository.addTargetFavoriteFolder(folderPath)
            }
            dismissFolderMenu()
        }
    }

    fun removeTargetFolder(folderPath: String) {
        viewModelScope.launch {
            sessionHiddenTargetFolders.update { it + folderPath }
            newlyAddedTargetFolders.update { it - folderPath }
            dismissFolderMenu()
        }
    }

    fun onNavigateUp() {
        logJitSummary()
        if (_uiState.value.pendingChanges.isNotEmpty()) {
            _uiState.update { it.copy(showConfirmExitDialog = true) }
        } else {
            sessionHiddenTargetFolders.value = emptySet()
            viewModelScope.launch {
                _navigationEvent.emit(NavigationEvent.NavigateUp)
            }
        }
    }

    fun confirmExit() {
        viewModelScope.launch {
            logJitSummary()
            sessionHiddenTargetFolders.value = emptySet()
            _uiState.update { it.copy(showConfirmExitDialog = false) }
            _navigationEvent.emit(NavigationEvent.NavigateUp)
        }
    }

    fun cancelExit() {
        _uiState.update { it.copy(showConfirmExitDialog = false) }
    }

    fun cancelExitAndShowSummary() {
        _uiState.update { it.copy(showConfirmExitDialog = false, showSummarySheet = true) }
    }

    fun toggleFolderBarExpansion() {
        viewModelScope.launch {
            val isCurrentlyExpanded = _uiState.value.isFolderBarExpanded
            val newExpandedState = !isCurrentlyExpanded
            preferencesRepository.setBottomBarExpanded(newExpandedState)
            _uiState.update { it.copy(isFolderBarExpanded = newExpandedState) }
        }
    }

    fun setFolderBarExpanded(isExpanded: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setBottomBarExpanded(isExpanded)
            _uiState.update { it.copy(isFolderBarExpanded = isExpanded) }
        }
    }

    fun showSummarySheet() { _uiState.update { it.copy(showSummarySheet = true) } }
    fun dismissSummarySheet() { _uiState.update { it.copy(showSummarySheet = false) } }

    fun revertLastChange() {
        val lastChange = _uiState.value.pendingChanges.maxByOrNull { it.timestamp }
        if (lastChange != null) {
            revertChange(lastChange)
        }
    }

    fun revertChange(changeToRevert: PendingChange) {
        _uiState.update { currentState ->
            val updatedPendingChanges = currentState.pendingChanges.filterNot { it.timestamp == changeToRevert.timestamp }
            savedStateHandle["pendingChanges"] = ArrayList(updatedPendingChanges)
            val summary = processSummaryLists(updatedPendingChanges, currentState.folderIdToNameMap)

            val originalItemToRestoreId = changeToRevert.item.id
            val restoredItemIndex = currentState.allMediaItems.indexOfFirst { it.id == originalItemToRestoreId }
            val currentSwiperIndex = currentState.currentIndex
            val shouldKeepSheetOpen = currentState.showSummarySheet && updatedPendingChanges.isNotEmpty()

            var finalState = currentState.copy(
                pendingChanges = updatedPendingChanges,
                showSummarySheet = shouldKeepSheetOpen,
                toDelete = summary.toDelete,
                toKeep = summary.toKeep,
                toConvert = summary.toConvert,
                groupedMoves = summary.groupedMoves
            )

            // Case 1: The reverted item is the one currently being displayed (or was, if sorting is complete).
            if (currentState.isSortingComplete || currentState.currentItem?.id == originalItemToRestoreId) {
                val isScreenshotRevert = changeToRevert.action is SwiperAction.Screenshot
                if (isScreenshotRevert) {
                    finalState = finalState.copy(isCurrentItemPendingConversion = false)
                }
            }

            // Case 2: The reverted item is a previous item in the swiper list.
            if (restoredItemIndex != -1 && (restoredItemIndex < currentSwiperIndex || currentState.isSortingComplete)) {
                finalState = finalState.copy(
                    currentItem = finalState.allMediaItems[restoredItemIndex],
                    currentIndex = restoredItemIndex,
                    isSortingComplete = false,
                    error = null,
                    videoPlaybackPosition = 0L,
                    isVideoMuted = true,
                    isCurrentItemPendingConversion = false
                )
            }
            finalState
        }
    }


    fun resetPendingChanges() {
        _uiState.update { currentState ->
            val emptyChanges = emptyList<PendingChange>()
            val summary = processSummaryLists(emptyChanges, currentState.folderIdToNameMap)
            savedStateHandle["pendingChanges"] = null

            val allProcessedIds = sessionProcessedMediaIds +
                    (if (_rememberProcessedMediaEnabled) processedMediaIds else emptySet())
            val firstUnprocessedIndex = currentState.allMediaItems.indexOfFirst { it.id !in allProcessedIds }

            val (newCurrentItem, newCurrentIndex, newIsSortingComplete) = if (firstUnprocessedIndex != -1) {
                Triple(currentState.allMediaItems[firstUnprocessedIndex], firstUnprocessedIndex, false)
            } else {
                Triple(null, currentState.currentIndex, true)
            }

            currentState.copy(
                pendingChanges = emptyChanges,
                sessionSkippedMediaIds = emptySet(),
                showSummarySheet = false,
                isCurrentItemPendingConversion = false,
                currentItem = newCurrentItem,
                currentIndex = newCurrentIndex,
                isSortingComplete = newIsSortingComplete,
                error = null,
                toDelete = summary.toDelete,
                toKeep = summary.toKeep,
                toConvert = summary.toConvert,
                groupedMoves = summary.groupedMoves
            )
        }
    }

    fun reviewSkippedItems() {
        _uiState.update { currentState ->
            if (currentState.sessionSkippedMediaIds.isEmpty()) return@update currentState

            val firstSkippedIndex = currentState.allMediaItems.indexOfFirst { it.id in currentState.sessionSkippedMediaIds }

            if (firstSkippedIndex != -1) {
                currentState.copy(
                    currentItem = currentState.allMediaItems[firstSkippedIndex],
                    currentIndex = firstSkippedIndex,
                    isSortingComplete = false,
                    sessionSkippedMediaIds = emptySet(),
                    videoPlaybackPosition = 0L,
                    videoPlaybackSpeed = _defaultVideoSpeed,
                    isVideoMuted = true,
                    isCurrentItemPendingConversion = false
                )
            } else {
                currentState // Should not happen if button is visible, but safe fallback
            }
        }
    }

    fun resetProcessedMedia() {
        viewModelScope.launch {
            preferencesRepository.clearProcessedMediaPaths()
            preferencesRepository.clearPermanentlySortedFolders()
            sessionProcessedMediaIds.clear()
            _uiState.update { it.copy(
                toastMessage = context.getString(R.string.sorted_media_reset_toast),
                sessionSkippedMediaIds = emptySet()
            ) }
            initializeMedia(bucketIds)
        }
    }

    fun toggleSummaryViewMode() {
        viewModelScope.launch {
            val nextMode = when (_uiState.value.summaryViewMode) {
                SummaryViewMode.LIST -> SummaryViewMode.GRID
                SummaryViewMode.GRID -> SummaryViewMode.COMPACT
                SummaryViewMode.COMPACT -> SummaryViewMode.LIST
            }
            preferencesRepository.setSummaryViewMode(nextMode)
        }
    }

    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun saveVideoPlaybackPosition(position: Long) {
        if (position > 0) {
            _uiState.update { it.copy(videoPlaybackPosition = position) }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(videoPlaybackSpeed = speed) }
    }

    fun toggleMute(hasAudio: Boolean) {
        if (_uiState.value.currentItem?.isVideo != true) return

        val currentlyMuted = _uiState.value.isVideoMuted
        if (currentlyMuted) {
            if (hasAudio) {
                _uiState.update { it.copy(isVideoMuted = false) }
            } else {
                _uiState.update { it.copy(toastMessage = context.getString(R.string.video_no_audio_toast)) }
            }
        } else {
            _uiState.update { it.copy(isVideoMuted = true) }
        }
    }

    private fun prewarmNextImages() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.allMediaItems.isEmpty() || state.isSortingComplete) return@launch

            val allProcessedIds = sessionProcessedMediaIds +
                    (if (_rememberProcessedMediaEnabled) processedMediaIds else emptySet()) +
                    state.pendingChanges.map { it.item.id }.toSet() +
                    state.sessionSkippedMediaIds

            val nextItems = state.allMediaItems
                .drop(state.currentIndex + 1)
                .filterNot { it.id in allProcessedIds || it.isVideo }
                .take(3)

            if (nextItems.isNotEmpty()) {
                val requests = nextItems.map { ImageRequest.Builder(context).data(it.uri).build() }
                requests.forEach { imageLoader.enqueue(it) }
            }
        }
    }
}

@Parcelize
sealed class SwiperAction : Parcelable {
    @Parcelize
    data class Keep(val item: MediaItem) : SwiperAction()
    @Parcelize
    data class Delete(val item: MediaItem) : SwiperAction()
    @Parcelize
    data class Move(val item: MediaItem, val targetFolderPath: String) : SwiperAction()
    @Parcelize
    data class Screenshot(val item: MediaItem, val timestampMicros: Long = -1L) : SwiperAction()
    @Parcelize
    data class ScreenshotAndDelete(val item: MediaItem, val timestampMicros: Long = -1L) : SwiperAction()
}
