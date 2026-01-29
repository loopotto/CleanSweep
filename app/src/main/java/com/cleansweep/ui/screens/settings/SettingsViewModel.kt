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

package com.cleansweep.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.data.repository.AddFolderFocusTarget
import com.cleansweep.data.repository.DuplicateScanScope
import com.cleansweep.data.repository.FolderBarLayout
import com.cleansweep.data.repository.FolderNameLayout
import com.cleansweep.data.repository.FolderSelectionMode
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.data.repository.SimilarityThresholdLevel
import com.cleansweep.data.repository.SwipeDownAction
import com.cleansweep.data.repository.SwipeSensitivity
import com.cleansweep.data.repository.UnselectScanScope
import com.cleansweep.domain.bus.AppLifecycleEventBus
import com.cleansweep.domain.bus.FolderUpdateEvent
import com.cleansweep.domain.bus.FolderUpdateEventBus
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.domain.usecase.SimilarFinderUseCase
import com.cleansweep.domain.util.HiddenFileFilter
import com.cleansweep.ui.components.FolderSearchManager
import com.cleansweep.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import javax.inject.Inject

data class DetailedIndexingStatus(
    val indexed: Int,
    val total: Int,
    val unindexedUserFiles: Int,
    val unindexedHiddenFiles: Int
)

data class SettingsUiState(
    val toastMessage: String? = null,
    val showDefaultPathSearchDialog: Boolean = false,
    val showForgetMediaSearchDialog: Boolean = false,
    val showAccentColorDialog: Boolean = false,
    val showResetDialogsConfirmation: Boolean = false,
    val showResetHistoryConfirmation: Boolean = false,
    val dontAskAgainResetHistory: Boolean = false,
    val showResetSourceFavoritesConfirmation: Boolean = false,
    val dontAskAgainResetSourceFavorites: Boolean = false,
    val showResetTargetFavoritesConfirmation: Boolean = false,
    val dontAskAgainResetTargetFavorites: Boolean = false,
    val missingImportedFolders: List<String>? = null,
    val showConfirmForgetFolderDialog: Boolean = false,
    val folderToForget: String? = null,
    val dontAskAgainForgetFolder: Boolean = false,
    val indexingStatus: DetailedIndexingStatus? = null,
    val isIndexingStatusLoading: Boolean = false, // To show spinner for quick refresh
    val isIndexing: Boolean = false, // For the long-running full scan
    val showConfirmSimilarityChangeDialog: Boolean = false,
    val pendingSimilarityLevel: SimilarityThresholdLevel? = null,
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val showDuplicateScanScopeDialog: Boolean = false,
    val showDuplicateScanScopeFolderSearch: Boolean = false,
    val isSearchingForIncludeList: Boolean = true,
    val unindexedFilePaths: List<String> = emptyList(),
    val showUnindexedFilesDialog: Boolean = false,
    val showHiddenUnindexedFiles: Boolean = false
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val mediaRepository: MediaRepository,
    private val similarFinderUseCase: SimilarFinderUseCase,
    private val appLifecycleEventBus: AppLifecycleEventBus,
    private val folderUpdateEventBus: FolderUpdateEventBus,
    val folderSearchManager: FolderSearchManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val debouncedSearchQuery: StateFlow<String> = _uiState
        .map { it.searchQuery }
        .debounce(200L)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val displayedUnindexedFiles: StateFlow<List<String>> = uiState.map { state ->
        if (state.showHiddenUnindexedFiles) {
            state.unindexedFilePaths
        } else {
            state.unindexedFilePaths.filterNot { HiddenFileFilter.isUiHidden(it) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentTheme: StateFlow<AppTheme> = preferencesRepository.themeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppTheme.SYSTEM
        )

    val useDynamicColors: StateFlow<Boolean> = preferencesRepository.useDynamicColorsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val accentColorKey: StateFlow<String> = preferencesRepository.accentColorKeyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "DEFAULT_BLUE"
        )

    val compactFolderView: StateFlow<Boolean> = preferencesRepository.compactFolderViewFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val hideFilename: StateFlow<Boolean> = preferencesRepository.hideFilenameFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val invertSwipe: StateFlow<Boolean> = preferencesRepository.invertSwipeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val fullScreenSwipe: StateFlow<Boolean> = preferencesRepository.fullScreenSwipeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val folderSelectionMode: StateFlow<FolderSelectionMode> =
        preferencesRepository.folderSelectionModeFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = FolderSelectionMode.REMEMBER
            )

    val rememberProcessedMedia: StateFlow<Boolean> =
        preferencesRepository.rememberProcessedMediaFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = true
            )

    val unfavoriteRemovesFromBar: StateFlow<Boolean> =
        preferencesRepository.unfavoriteRemovesFromBarFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = true
            )

    val hideSkipButton: StateFlow<Boolean> =
        preferencesRepository.hideSkipButtonFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = true
            )

    val defaultAlbumCreationPath: StateFlow<String> =
        preferencesRepository.defaultAlbumCreationPathFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val showFavoritesInSetup: StateFlow<Boolean> =
        preferencesRepository.showFavoritesFirstInSetupFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = true
            )

    val searchAutofocusEnabled: StateFlow<Boolean> =
        preferencesRepository.searchAutofocusEnabledFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )

    val skipPartialExpansion: StateFlow<Boolean> =
        preferencesRepository.skipPartialExpansionFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = true
            )

    val useFullScreenSummarySheet: StateFlow<Boolean> =
        preferencesRepository.useFullScreenSummarySheetFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )

    val folderBarLayout: StateFlow<FolderBarLayout> =
        preferencesRepository.folderBarLayoutFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = FolderBarLayout.HORIZONTAL
            )

    val folderNameLayout: StateFlow<FolderNameLayout> =
        preferencesRepository.folderNameLayoutFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = FolderNameLayout.ABOVE
            )

    val useLegacyFolderIcons: StateFlow<Boolean> =
        preferencesRepository.useLegacyFolderIconsFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )

    val addFolderFocusTarget: StateFlow<AddFolderFocusTarget> =
        preferencesRepository.addFolderFocusTargetFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = AddFolderFocusTarget.SEARCH_PATH
            )

    val swipeSensitivity: StateFlow<SwipeSensitivity> =
        preferencesRepository.swipeSensitivityFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SwipeSensitivity.MEDIUM
            )

    val swipeDownAction: StateFlow<SwipeDownAction> =
        preferencesRepository.swipeDownActionFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SwipeDownAction.NONE
            )

    val addFavoriteToTargetByDefault: StateFlow<Boolean> =
        preferencesRepository.addFavoriteToTargetByDefaultFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = true
            )

    val hintOnExistingFolderName: StateFlow<Boolean> =
        preferencesRepository.hintOnExistingFolderNameFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = true
            )

    val defaultVideoSpeed: StateFlow<Float> =
        preferencesRepository.defaultVideoSpeedFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 1.0f
            )

    val screenshotDeletesVideo: StateFlow<Boolean> =
        preferencesRepository.screenshotDeletesVideoFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false
            )

    val screenshotJpegQuality: StateFlow<String> =
        preferencesRepository.screenshotJpegQualityFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = "90"
            )

    val similarityThresholdLevel: StateFlow<SimilarityThresholdLevel> =
        preferencesRepository.similarityThresholdLevelFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SimilarityThresholdLevel.BALANCED
            )

    val duplicateScanScope: StateFlow<DuplicateScanScope> =
        preferencesRepository.duplicateScanScopeFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = DuplicateScanScope.ALL_FILES
            )

    val duplicateScanIncludeList: StateFlow<Set<String>> =
        preferencesRepository.duplicateScanIncludeListFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptySet()
            )

    val duplicateScanExcludeList: StateFlow<Set<String>> =
        preferencesRepository.duplicateScanExcludeListFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptySet()
            )

    val unselectAllInSearchScope: StateFlow<UnselectScanScope> =
        preferencesRepository.unselectAllInSearchScopeFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = UnselectScanScope.GLOBAL
            )


    val standardAlbumDirectories: List<Pair<String, String>> = listOf(
        "Pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath,
        "DCIM" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath,
        "Movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
    )

    val appVersion: String by lazy {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }

    init {
        observeAppLifecycle()
    }

    private fun observeAppLifecycle() {
        viewModelScope.launch {
            appLifecycleEventBus.appResumeEvent.collect {
                if (_uiState.value.showDefaultPathSearchDialog || _uiState.value.showForgetMediaSearchDialog) {
                    dismissFolderSearchDialog()
                    showToast("Folder list refreshed, please reopen the dialog.")
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleSearch() {
        _uiState.update {
            val isNowActive = !it.isSearchActive
            it.copy(
                isSearchActive = isNowActive,
                searchQuery = if (!isNowActive) "" else it.searchQuery // Clear query on close
            )
        }
    }

    fun exportTargetFavorites(uri: Uri) {
        viewModelScope.launch {
            try {
                val favorites = preferencesRepository.targetFavoriteFoldersFlow.first()
                if (favorites.isEmpty()) {
                    showToast("No target favorites to export.")
                    return@launch
                }

                val existenceMap = mediaRepository.getFolderExistence(favorites)
                val existingFavorites = favorites.filter { existenceMap[it] == true }
                val skippedCount = favorites.size - existingFavorites.size

                if (existingFavorites.isEmpty()) {
                    showToast("No existing favorite folders found to export.")
                    return@launch
                }

                val jsonArray = JSONArray(existingFavorites)
                val jsonString = jsonArray.toString(2)

                context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { fileOutputStream ->
                        fileOutputStream.write(jsonString.toByteArray())
                    }
                }

                val toastMessage = "Exported ${existingFavorites.size} favorites." +
                        if (skippedCount > 0) " $skippedCount non-existent favorites were skipped." else ""
                showToast(toastMessage)

            } catch (e: Exception) {
                showToast("Error exporting favorites: ${e.message}")
            }
        }
    }

    fun importTargetFavorites(uri: Uri) {
        viewModelScope.launch {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.use {
                    BufferedReader(InputStreamReader(it)).readText()
                } ?: throw Exception("Could not read file.")

                val jsonArray = JSONArray(jsonString)
                val importedPaths = (0 until jsonArray.length())
                    .map { jsonArray.optString(it) }
                    .filter { !it.isNullOrBlank() }
                    .toSet()

                if (importedPaths.isEmpty()) {
                    showToast("No valid folder paths found in the selected file.")
                    return@launch
                }

                val existenceMap = mediaRepository.getFolderExistence(importedPaths)
                val existingPaths = importedPaths.filter { existenceMap[it] == true }.toSet()
                val missingPaths = importedPaths.filter { existenceMap[it] == false }

                if (existingPaths.isNotEmpty()) {
                    preferencesRepository.addTargetFavoriteFolders(existingPaths)
                }

                if (missingPaths.isNotEmpty()) {
                    _uiState.update { it.copy(missingImportedFolders = missingPaths) }
                    showToast("Imported ${existingPaths.size} folders. Some folders were not found.")
                } else {
                    showToast("Imported ${existingPaths.size} favorites successfully.")
                }

            } catch (e: JSONException) {
                showToast("Import failed: Invalid file format.")
            } catch (e: Exception) {
                showToast("Error importing favorites: ${e.message}")
            }
        }
    }

    fun createAndImportMissingFolders() {
        viewModelScope.launch {
            val missingFolders = _uiState.value.missingImportedFolders ?: return@launch
            val createdFolders = mutableSetOf<String>()

            missingFolders.forEach { path ->
                val parent = File(path).parent
                val name = File(path).name
                if (parent != null && name.isNotBlank()) {
                    mediaRepository.createNewFolder(name, parent).onSuccess {
                        createdFolders.add(it)
                    }
                }
            }

            if (createdFolders.isNotEmpty()) {
                preferencesRepository.addTargetFavoriteFolders(createdFolders)
                showToast("Created and imported ${createdFolders.size} missing folders.")
            } else {
                showToast("Could not create the missing folders.")
            }
            dismissMissingFoldersDialog()
        }
    }

    fun dismissMissingFoldersDialog() {
        _uiState.update { it.copy(missingImportedFolders = null) }
    }


    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            preferencesRepository.setTheme(theme)
        }
    }

    fun setUseDynamicColors(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUseDynamicColors(enabled)
        }
    }

    fun setAccentColor(key: String) {
        viewModelScope.launch {
            preferencesRepository.setAccentColorKey(key)
            dismissAccentColorDialog()
        }
    }

    fun showAccentColorDialog() {
        _uiState.update { it.copy(showAccentColorDialog = true) }
    }

    fun dismissAccentColorDialog() {
        _uiState.update { it.copy(showAccentColorDialog = false) }
    }

    fun resetOnboarding() {
        viewModelScope.launch {
            preferencesRepository.resetOnboarding()
            showToast("Onboarding tutorial will be shown on next app launch.")
        }
    }

    fun setCompactFolderView(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setCompactFolderView(enabled)
        }
    }

    fun setHideFilename(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setHideFilename(enabled)
        }
    }

    fun setInvertSwipe(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setInvertSwipe(enabled)
        }
    }

    fun setFullScreenSwipe(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setFullScreenSwipe(enabled)
        }
    }

    fun setFolderSelectionMode(mode: FolderSelectionMode) {
        viewModelScope.launch {
            preferencesRepository.setFolderSelectionMode(mode)
        }
    }

    fun setRememberProcessedMedia(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setRememberProcessedMedia(enabled)
        }
    }

    fun setUnfavoriteRemovesFromBar(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUnfavoriteRemovesFromBar(enabled)
        }
    }

    fun setHideSkipButton(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setHideSkipButton(enabled)
        }
    }

    fun setShowFavoritesInSetup(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShowFavoritesFirstInSetup(enabled)
        }
    }

    fun setSearchAutofocusEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setSearchAutofocusEnabled(enabled)
        }
    }

    fun onSkipPartialExpansionChanged(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setSkipPartialExpansion(enabled)
        }
    }

    fun onUseFullScreenSummarySheetChanged(useFullScreen: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUseFullScreenSummarySheet(useFullScreen)
        }
    }

    fun setFolderBarLayout(layout: FolderBarLayout) {
        viewModelScope.launch {
            preferencesRepository.setFolderBarLayout(layout)
        }
    }

    fun setFolderNameLayout(layout: FolderNameLayout) {
        viewModelScope.launch {
            preferencesRepository.setFolderNameLayout(layout)
        }
    }

    fun setUseLegacyFolderIcons(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setUseLegacyFolderIcons(enabled)
        }
    }

    fun setAddFolderFocusTarget(target: AddFolderFocusTarget) {
        viewModelScope.launch {
            preferencesRepository.setAddFolderFocusTarget(target)
        }
    }

    fun setSwipeSensitivity(sensitivity: SwipeSensitivity) {
        viewModelScope.launch {
            preferencesRepository.setSwipeSensitivity(sensitivity)
        }
    }

    fun setSwipeDownAction(action: SwipeDownAction) {
        viewModelScope.launch {
            preferencesRepository.setSwipeDownAction(action)
        }
    }

    fun setSimilarityThresholdLevel(level: SimilarityThresholdLevel) {
        if (level == similarityThresholdLevel.value) return

        viewModelScope.launch {
            val hasScannedBefore = preferencesRepository.hasRunDuplicateScanOnceFlow.first()

            if (hasScannedBefore) {
                // A scan has run before, so a cache might exist. Show the confirmation dialog.
                _uiState.update {
                    it.copy(
                        showConfirmSimilarityChangeDialog = true,
                        pendingSimilarityLevel = level
                    )
                }
            } else {
                // No scan has ever run, so no cache to clear. Apply the setting directly.
                performSimilarityThresholdChange(level)
            }
        }
    }

    fun confirmSetSimilarityThresholdLevel() {
        viewModelScope.launch {
            val levelToSet = _uiState.value.pendingSimilarityLevel ?: return@launch
            performSimilarityThresholdChange(levelToSet)
            showToast("Similarity level set. The next scan will re-analyze all media.")
            _uiState.update {
                it.copy(
                    showConfirmSimilarityChangeDialog = false,
                    pendingSimilarityLevel = null
                )
            }
        }
    }

    private fun performSimilarityThresholdChange(level: SimilarityThresholdLevel) {
        viewModelScope.launch {
            similarFinderUseCase.clearPHashCache()
            preferencesRepository.setSimilarityThresholdLevel(level)
        }
    }


    fun dismissSetSimilarityThresholdLevel() {
        _uiState.update {
            it.copy(
                showConfirmSimilarityChangeDialog = false,
                pendingSimilarityLevel = null
            )
        }
    }

    fun setAddFavoriteToTargetByDefault(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAddFavoriteToTargetByDefault(enabled)
        }
    }

    fun setHintOnExistingFolderName(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setHintOnExistingFolderName(enabled)
        }
    }

    fun setDefaultVideoSpeed(speed: Float) {
        viewModelScope.launch {
            preferencesRepository.setDefaultVideoSpeed(speed)
        }
    }

    fun setScreenshotDeletesVideo(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setScreenshotDeletesVideo(enabled)
        }
    }

    fun setScreenshotJpegQuality(quality: String) {
        viewModelScope.launch {
            preferencesRepository.setScreenshotJpegQuality(quality)
        }
    }

    fun resetProcessedMediaIds() {
        viewModelScope.launch {
            if (preferencesRepository.processedMediaPathsFlow.first().isEmpty()) {
                showToast("No sorted media history to reset.")
                return@launch
            }
            val shouldShowDialog = preferencesRepository.showConfirmResetAllHistoryFlow.first()
            if (shouldShowDialog) {
                _uiState.update { it.copy(showResetHistoryConfirmation = true) }
            } else {
                performResetProcessedMediaIds()
            }
        }
    }

    fun confirmResetDialogWarnings() {
        viewModelScope.launch {
            preferencesRepository.resetDialogConfirmations()
            showToast("All dialog warnings have been reset.")
            _uiState.update { it.copy(showResetDialogsConfirmation = false) }
        }
    }

    fun confirmResetHistory() {
        viewModelScope.launch {
            if (_uiState.value.dontAskAgainResetHistory) {
                preferencesRepository.setShowConfirmResetAllHistory(false)
            }
            performResetProcessedMediaIds()
        }
    }

    private fun performResetProcessedMediaIds() {
        viewModelScope.launch {
            preferencesRepository.clearProcessedMediaPaths()
            preferencesRepository.clearPermanentlySortedFolders()
            folderUpdateEventBus.post(FolderUpdateEvent.FullRefreshRequired)
            showToast("Sorted media history has been reset.")
            _uiState.update { it.copy(showResetHistoryConfirmation = false, dontAskAgainResetHistory = false) }
        }
    }

    fun forgetSortedMediaInFolder(folderPath: String) {
        viewModelScope.launch {
            val shouldShowDialog = preferencesRepository.showConfirmForgetFolderFlow.first()
            if (shouldShowDialog) {
                _uiState.update {
                    it.copy(
                        showConfirmForgetFolderDialog = true,
                        folderToForget = folderPath
                    )
                }
            } else {
                performForgetSortedMediaInFolder(folderPath)
            }
            // Dismiss the search dialog regardless
            dismissFolderSearchDialog()
        }
    }

    fun confirmForgetSortedMediaInFolder() {
        viewModelScope.launch {
            val folderPath = _uiState.value.folderToForget ?: return@launch
            if (_uiState.value.dontAskAgainForgetFolder) {
                preferencesRepository.setShowConfirmForgetFolder(false)
            }
            performForgetSortedMediaInFolder(folderPath)
        }
    }

    private fun performForgetSortedMediaInFolder(folderPath: String) {
        viewModelScope.launch {
            preferencesRepository.removeProcessedMediaPathsInFolder(folderPath)
            preferencesRepository.removePermanentlySortedFolder(folderPath)
            folderUpdateEventBus.post(FolderUpdateEvent.FullRefreshRequired)
            showToast("Sorted history for '${File(folderPath).name}' has been forgotten.")
            _uiState.update {
                it.copy(
                    showConfirmForgetFolderDialog = false,
                    folderToForget = null,
                    dontAskAgainForgetFolder = false
                )
            }
        }
    }

    fun clearSourceFavorites() {
        viewModelScope.launch {
            val favorites = preferencesRepository.sourceFavoriteFoldersFlow.first()
            if (favorites.isEmpty()) {
                showToast("No source favorites to reset.")
                return@launch
            }

            val shouldShowDialog = preferencesRepository.showConfirmResetSourceFavsFlow.first()
            if (shouldShowDialog) {
                _uiState.update { it.copy(showResetSourceFavoritesConfirmation = true) }
            } else {
                performClearSourceFavorites()
            }
        }
    }

    fun confirmClearSourceFavorites() {
        viewModelScope.launch {
            if (_uiState.value.dontAskAgainResetSourceFavorites) {
                preferencesRepository.setShowConfirmResetSourceFavs(false)
            }
            performClearSourceFavorites()
        }
    }

    private fun performClearSourceFavorites() {
        viewModelScope.launch {
            preferencesRepository.clearAllSourceFavorites()
            showToast("Source folder favorites cleared.")
            _uiState.update { it.copy(showResetSourceFavoritesConfirmation = false, dontAskAgainResetSourceFavorites = false) }
        }
    }

    fun clearTargetFavorites() {
        viewModelScope.launch {
            val favorites = preferencesRepository.targetFavoriteFoldersFlow.first()
            if (favorites.isEmpty()) {
                showToast("No target favorites to reset.")
                return@launch
            }

            val shouldShowDialog = preferencesRepository.showConfirmResetTargetFavsFlow.first()
            if (shouldShowDialog) {
                _uiState.update { it.copy(showResetTargetFavoritesConfirmation = true) }
            } else {
                performClearTargetFavorites()
            }
        }
    }

    fun confirmClearTargetFavorites() {
        viewModelScope.launch {
            if (_uiState.value.dontAskAgainResetTargetFavorites) {
                preferencesRepository.setShowConfirmResetTargetFavs(false)
            }
            performClearTargetFavorites()
        }
    }

    private fun performClearTargetFavorites() {
        viewModelScope.launch {
            preferencesRepository.clearAllTargetFavorites()
            showToast("Target folder favorites cleared.")
            _uiState.update { it.copy(showResetTargetFavoritesConfirmation = false, dontAskAgainResetTargetFavorites = false) }
        }
    }

    fun onDefaultAlbumPathChanged(newPath: String) {
        viewModelScope.launch {
            preferencesRepository.setDefaultAlbumCreationPath(newPath)
        }
    }

    fun showDefaultPathSearchDialog() {
        viewModelScope.launch {
            folderSearchManager.prepareForSearch(
                initialPath = defaultAlbumCreationPath.value,
                coroutineScope = viewModelScope
            )
            _uiState.update { it.copy(showDefaultPathSearchDialog = true) }
        }
    }

    fun showForgetMediaSearchDialog() {
        viewModelScope.launch {
            val foldersWithHistory = mediaRepository.getFoldersWithProcessedMedia()
            if (foldersWithHistory.isEmpty()) {
                showToast("No sorted media history found.")
                return@launch
            }
            folderSearchManager.prepareWithPreFilteredList(foldersWithHistory)
            _uiState.update { it.copy(showForgetMediaSearchDialog = true) }
        }
    }

    fun onPathSelected(path: String) {
        viewModelScope.launch {
            folderSearchManager.selectPath(path)
        }
    }

    fun dismissFolderSearchDialog() {
        folderSearchManager.reset()
        _uiState.update { it.copy(
            showDefaultPathSearchDialog = false,
            showForgetMediaSearchDialog = false,
            showDuplicateScanScopeFolderSearch = false
        ) }
    }

    fun confirmDefaultPathSelection() {
        val selectedPath = folderSearchManager.state.value.browsePath
        if (selectedPath != null) {
            onDefaultAlbumPathChanged(selectedPath)
        }
        dismissFolderSearchDialog()
    }

    fun resetDialogWarnings() {
        _uiState.update { it.copy(showResetDialogsConfirmation = true) }
    }

    fun dismissDialog(dialog: String) {
        when(dialog) {
            "resetWarnings" -> _uiState.update { it.copy(showResetDialogsConfirmation = false) }
            "resetHistory" -> _uiState.update { it.copy(showResetHistoryConfirmation = false, dontAskAgainResetHistory = false) }
            "resetSource" -> _uiState.update { it.copy(showResetSourceFavoritesConfirmation = false, dontAskAgainResetSourceFavorites = false) }
            "resetTarget" -> _uiState.update { it.copy(showResetTargetFavoritesConfirmation = false, dontAskAgainResetTargetFavorites = false) }
            "forgetFolder" -> _uiState.update { it.copy(showConfirmForgetFolderDialog = false, folderToForget = null, dontAskAgainForgetFolder = false) }
        }
    }

    fun onDontAskAgainChanged(dialog: String, isChecked: Boolean) {
        when(dialog) {
            "resetHistory" -> _uiState.update { it.copy(dontAskAgainResetHistory = isChecked) }
            "resetSource" -> _uiState.update { it.copy(dontAskAgainResetSourceFavorites = isChecked) }
            "resetTarget" -> _uiState.update { it.copy(dontAskAgainResetTargetFavorites = isChecked) }
            "forgetFolder" -> _uiState.update { it.copy(dontAskAgainForgetFolder = isChecked) }
        }
    }

    fun refreshIndexingStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isIndexingStatusLoading = true, indexingStatus = null, unindexedFilePaths = emptyList()) }
            val status = mediaRepository.getIndexingStatus()
            val unindexedPaths = mediaRepository.getUnindexedMediaPaths()
            val unindexedHidden = unindexedPaths.count { path -> HiddenFileFilter.isUiHidden(path) }
            val unindexedUser = unindexedPaths.size - unindexedHidden

            _uiState.update {
                it.copy(
                    isIndexingStatusLoading = false,
                    indexingStatus = DetailedIndexingStatus(
                        indexed = status.indexed,
                        total = status.total,
                        unindexedUserFiles = unindexedUser,
                        unindexedHiddenFiles = unindexedHidden
                    ),
                    unindexedFilePaths = unindexedPaths
                )
            }
        }
    }

    fun triggerFullScan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isIndexing = true) }
            val success = mediaRepository.triggerFullMediaStoreScan()
            if (success) {
                showToast("Full device scan completed.")
            } else {
                showToast("Scan failed or was interrupted.")
            }
            refreshIndexingStatus() // Refresh status after scan
            _uiState.update { it.copy(isIndexing = false) }
        }
    }

    fun showUnindexedFilesDialog() {
        if (_uiState.value.unindexedFilePaths.isNotEmpty()) {
            _uiState.update { it.copy(showUnindexedFilesDialog = true) }
        }
    }

    fun dismissUnindexedFilesDialog() {
        _uiState.update { it.copy(showUnindexedFilesDialog = false, showHiddenUnindexedFiles = false) }
    }

    fun toggleShowHiddenUnindexedFiles() {
        _uiState.update { it.copy(showHiddenUnindexedFiles = !it.showHiddenUnindexedFiles) }
    }


    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun toastMessageShown() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // --- Duplicate Scan Scope ---
    fun showDuplicateScanScopeDialog() {
        _uiState.update { it.copy(showDuplicateScanScopeDialog = true) }
    }

    fun dismissDuplicateScanScopeDialog() {
        _uiState.update { it.copy(showDuplicateScanScopeDialog = false) }
    }

    fun setDuplicateScanScope(scope: DuplicateScanScope) {
        viewModelScope.launch {
            preferencesRepository.setDuplicateScanScope(scope)
        }
    }

    fun setUnselectAllInSearchScope(scope: UnselectScanScope) {
        viewModelScope.launch {
            preferencesRepository.setUnselectAllInSearchScope(scope)
        }
    }

    fun showDuplicateScanScopeFolderSearch(isForIncludeList: Boolean) {
        viewModelScope.launch {
            val currentList = if (isForIncludeList) {
                duplicateScanIncludeList.value
            } else {
                duplicateScanExcludeList.value
            }
            folderSearchManager.prepareForSearch(
                initialPath = defaultAlbumCreationPath.value.ifBlank { null },
                coroutineScope = viewModelScope,
                excludedFolders = currentList
            )
            _uiState.update { it.copy(
                showDuplicateScanScopeFolderSearch = true,
                isSearchingForIncludeList = isForIncludeList
            ) }
        }
    }

    fun addFolderToScanScopeList(path: String) {
        viewModelScope.launch {
            if (_uiState.value.isSearchingForIncludeList) {
                val newList = duplicateScanIncludeList.value.toMutableSet().apply { add(path) }
                preferencesRepository.setDuplicateScanIncludeList(newList)
            } else {
                val newList = duplicateScanExcludeList.value.toMutableSet().apply { add(path) }
                preferencesRepository.setDuplicateScanExcludeList(newList)
            }
        }
        dismissFolderSearchDialog()
    }

    fun removeFolderFromScanScopeList(path: String) {
        viewModelScope.launch {
            val scope = duplicateScanScope.value
            if (scope == DuplicateScanScope.INCLUDE_LIST) {
                val newList = duplicateScanIncludeList.value.toMutableSet().apply { remove(path) }
                preferencesRepository.setDuplicateScanIncludeList(newList)
            } else if (scope == DuplicateScanScope.EXCLUDE_LIST) {
                val newList = duplicateScanExcludeList.value.toMutableSet().apply { remove(path) }
                preferencesRepository.setDuplicateScanExcludeList(newList)
            }
        }
    }
}
