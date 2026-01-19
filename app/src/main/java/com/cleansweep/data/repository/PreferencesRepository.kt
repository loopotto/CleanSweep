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

package com.cleansweep.data.repository

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.ui.theme.predefinedAccentColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class FolderSelectionMode {
    ALL,
    REMEMBER,
    NONE
}

enum class SummaryViewMode {
    LIST,
    GRID,
    COMPACT
}

enum class FolderBarLayout {
    HORIZONTAL,
    VERTICAL
}

enum class AddFolderFocusTarget {
    SEARCH_PATH,
    FOLDER_NAME,
    NONE
}

enum class SwipeSensitivity {
    LOW,
    MEDIUM,
    HIGH
}

enum class FolderNameLayout {
    ABOVE,
    BELOW,
    HIDDEN
}

enum class SimilarityThresholdLevel {
    STRICT,
    BALANCED,
    LOOSE
}

enum class DuplicateScanScope {
    ALL_FILES,
    INCLUDE_LIST,
    EXCLUDE_LIST
}

enum class SwipeDownAction {
    NONE,
    MOVE_TO_EDIT,
    SKIP_ITEM,
    ADD_TARGET_FOLDER,
    SHARE,
    OPEN_WITH
}

enum class UnselectScanScope {
    GLOBAL,
    VISIBLE_ONLY
}


@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val DEFAULT_ALBUM_CREATION_PATH =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath
        private val KEY_DEFAULT_ALBUM_PATH = stringPreferencesKey("default_album_creation_path")
    }

    private object PreferencesKeys {
        val THEME = stringPreferencesKey("theme")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val COMPACT_FOLDER_VIEW = booleanPreferencesKey("compact_folder_view")
        val HIDE_FILENAME = booleanPreferencesKey("hide_filename")
        val INVERT_SWIPE = booleanPreferencesKey("invert_swipe")
        val FULL_SCREEN_SWIPE = booleanPreferencesKey("full_screen_swipe")
        val FOLDER_SELECTION_MODE = stringPreferencesKey("folder_selection_mode")
        val PREVIOUS_BUCKETS = stringPreferencesKey("previous_buckets")
        val SUMMARY_VIEW_MODE = stringPreferencesKey("summary_view_mode")
        val PROCESSED_MEDIA_PATHS = stringPreferencesKey("processed_media_paths")
        val REMEMBER_PROCESSED_MEDIA = booleanPreferencesKey("remember_processed_media")
        val SOURCE_FAVORITE_FOLDERS = stringPreferencesKey("source_favorite_folders")
        val TARGET_FAVORITE_FOLDERS = stringPreferencesKey("target_favorite_folders")
        val SHOW_FAVORITES_FIRST_IN_SETUP = booleanPreferencesKey("show_favorites_first_in_setup")
        val SEARCH_AUTOFOCUS_ENABLED = booleanPreferencesKey("search_autofocus_enabled")
        val SKIP_PARTIAL_EXPANSION = booleanPreferencesKey("expand_summary_sheet") // Renamed, key kept for migration
        val USE_FULL_SCREEN_SUMMARY_SHEET = booleanPreferencesKey("use_full_screen_summary_sheet")
        val FOLDER_BAR_LAYOUT = stringPreferencesKey("folder_bar_layout")
        val FOLDER_NAME_LAYOUT = stringPreferencesKey("folder_name_layout")
        val USE_LEGACY_FOLDER_ICONS = booleanPreferencesKey("use_legacy_folder_icons")
        val ADD_FOLDER_FOCUS_TARGET = stringPreferencesKey("add_folder_focus_target")
        val SWIPE_SENSITIVITY = stringPreferencesKey("swipe_sensitivity")
        val PERMANENTLY_SORTED_FOLDERS = stringPreferencesKey("permanently_sorted_folders")
        val ADD_FAVORITE_TO_TARGET_BY_DEFAULT = booleanPreferencesKey("add_favorite_to_target_by_default")
        val HINT_ON_EXISTING_FOLDER_NAME = booleanPreferencesKey("hint_on_existing_folder_name")
        val USE_DYNAMIC_COLORS = booleanPreferencesKey("use_dynamic_colors")
        val ACCENT_COLOR_KEY = stringPreferencesKey("accent_color_key")
        val BOTTOM_BAR_EXPANDED = booleanPreferencesKey("bottom_bar_expanded")
        val UNFAVORITE_REMOVES_FROM_BAR = booleanPreferencesKey("unfavorite_removes_from_bar")
        val HAS_RUN_DUPLICATE_SCAN_ONCE = booleanPreferencesKey("has_run_duplicate_scan_once")
        val SCREENSHOT_DELETES_VIDEO = booleanPreferencesKey("screenshot_deletes_video")
        val SCREENSHOT_JPEG_QUALITY = stringPreferencesKey("screenshot_jpeg_quality")
        val SIMILARITY_THRESHOLD_LEVEL = stringPreferencesKey("similarity_threshold_level")
        val HIDE_SWIPER_SKIP_BUTTON = booleanPreferencesKey("hide_swiper_skip_button")
        val DUPLICATE_SCAN_SCOPE = stringPreferencesKey("duplicate_scan_scope")
        val DUPLICATE_SCAN_INCLUDE_LIST = stringPreferencesKey("duplicate_scan_include_list")
        val DUPLICATE_SCAN_EXCLUDE_LIST = stringPreferencesKey("duplicate_scan_exclude_list")
        val SWIPE_DOWN_ACTION = stringPreferencesKey("swipe_down_action")

        val DEFAULT_VIDEO_SPEED = floatPreferencesKey("default_video_speed")

        // Dialog Confirmation Preferences
        val SHOW_CONFIRM_MARK_AS_SORTED = booleanPreferencesKey("show_confirm_mark_as_sorted")
        val SHOW_CONFIRM_RESET_ALL_HISTORY = booleanPreferencesKey("show_confirm_reset_all_history")
        val SHOW_CONFIRM_FORGET_FOLDER = booleanPreferencesKey("show_confirm_forget_folder")
        val SHOW_CONFIRM_RESET_SOURCE_FAVS = booleanPreferencesKey("show_confirm_reset_source_favs")
        val SHOW_CONFIRM_RESET_TARGET_FAVS = booleanPreferencesKey("show_confirm_reset_target_favs")
        val SHOW_CONFIRM_DELETE_ALL_EXACT = booleanPreferencesKey("show_confirm_delete_all_exact")
        val UNSELECT_ALL_IN_SEARCH_SCOPE = stringPreferencesKey("unselect_all_in_search_scope")
    }

    val themeFlow: Flow<AppTheme> = context.dataStore.data
        .map { preferences ->
            val themeName = preferences[PreferencesKeys.THEME] ?: AppTheme.SYSTEM.name
            try {
                AppTheme.valueOf(themeName)
            } catch (e: IllegalArgumentException) {
                AppTheme.SYSTEM
            }
        }

    val useDynamicColorsFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USE_DYNAMIC_COLORS] ?: true
        }

    val accentColorKeyFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ACCENT_COLOR_KEY] ?: predefinedAccentColors.first().key
        }

    val isOnboardingCompletedFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] ?: false
        }

    val compactFolderViewFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.COMPACT_FOLDER_VIEW] ?: false
        }

    val hideFilenameFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.HIDE_FILENAME] ?: false
        }

    val invertSwipeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.INVERT_SWIPE] ?: false
        }

    val fullScreenSwipeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.FULL_SCREEN_SWIPE] ?: false
        }

    val folderSelectionModeFlow: Flow<FolderSelectionMode> = context.dataStore.data
        .map { preferences ->
            val name = preferences[PreferencesKeys.FOLDER_SELECTION_MODE]
                ?: FolderSelectionMode.REMEMBER.name
            FolderSelectionMode.valueOf(name)
        }.catch { _ ->
            emit(FolderSelectionMode.REMEMBER)
        }

    val previouslySelectedBucketsFlow: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            val bucketString = preferences[PreferencesKeys.PREVIOUS_BUCKETS] ?: ""
            if (bucketString.isEmpty()) emptyList() else bucketString.split(",")
        }

    val summaryViewModeFlow: Flow<SummaryViewMode> = context.dataStore.data
        .map { preferences ->
            val modeName =
                preferences[PreferencesKeys.SUMMARY_VIEW_MODE] ?: SummaryViewMode.LIST.name
            try {
                SummaryViewMode.valueOf(modeName)
            } catch (e: IllegalArgumentException) {
                SummaryViewMode.LIST
            }
        }

    val rememberProcessedMediaFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.REMEMBER_PROCESSED_MEDIA] ?: true
        }

    val unfavoriteRemovesFromBarFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.UNFAVORITE_REMOVES_FROM_BAR] ?: false
        }

    val hideSkipButtonFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.HIDE_SWIPER_SKIP_BUTTON] ?: false
        }

    val processedMediaPathsFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            val pathsString = preferences[PreferencesKeys.PROCESSED_MEDIA_PATHS] ?: ""
            if (pathsString.isEmpty()) {
                emptySet()
            } else {
                pathsString.split(",").filter { it.isNotBlank() }.toSet()
            }
        }

    val sourceFavoriteFoldersFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            val favoritesString = preferences[PreferencesKeys.SOURCE_FAVORITE_FOLDERS] ?: ""
            if (favoritesString.isEmpty()) {
                emptySet()
            } else {
                favoritesString.split(",").filter { it.isNotBlank() }.toSet()
            }
        }

    val targetFavoriteFoldersFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            val favoritesString = preferences[PreferencesKeys.TARGET_FAVORITE_FOLDERS] ?: ""
            if (favoritesString.isEmpty()) {
                emptySet()
            } else {
                favoritesString.split(",").filter { it.isNotBlank() }.toSet()
            }
        }

    val permanentlySortedFoldersFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            val pathsString = preferences[PreferencesKeys.PERMANENTLY_SORTED_FOLDERS] ?: ""
            if (pathsString.isEmpty()) {
                emptySet()
            } else {
                pathsString.split(",").filter { it.isNotBlank() }.toSet()
            }
        }

    val hasRunDuplicateScanOnceFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.HAS_RUN_DUPLICATE_SCAN_ONCE] ?: false
        }

    val addFavoriteToTargetByDefaultFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ADD_FAVORITE_TO_TARGET_BY_DEFAULT] ?: true
        }

    val hintOnExistingFolderNameFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.HINT_ON_EXISTING_FOLDER_NAME] ?: true
        }

    val showFavoritesFirstInSetupFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_FAVORITES_FIRST_IN_SETUP] ?: true
        }

    val defaultAlbumCreationPathFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_DEFAULT_ALBUM_PATH] ?: DEFAULT_ALBUM_CREATION_PATH
        }

    val searchAutofocusEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SEARCH_AUTOFOCUS_ENABLED] ?: false
        }

    val skipPartialExpansionFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SKIP_PARTIAL_EXPANSION] ?: true
        }

    val useFullScreenSummarySheetFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USE_FULL_SCREEN_SUMMARY_SHEET] ?: false
        }

    val folderBarLayoutFlow: Flow<FolderBarLayout> = context.dataStore.data
        .map { preferences ->
            val layoutName =
                preferences[PreferencesKeys.FOLDER_BAR_LAYOUT] ?: FolderBarLayout.HORIZONTAL.name
            try {
                FolderBarLayout.valueOf(layoutName)
            } catch (e: IllegalArgumentException) {
                FolderBarLayout.HORIZONTAL
            }
        }

    val folderNameLayoutFlow: Flow<FolderNameLayout> = context.dataStore.data
        .map { preferences ->
            val layoutName =
                preferences[PreferencesKeys.FOLDER_NAME_LAYOUT] ?: FolderNameLayout.ABOVE.name
            try {
                FolderNameLayout.valueOf(layoutName)
            } catch (e: IllegalArgumentException) {
                FolderNameLayout.ABOVE
            }
        }

    val useLegacyFolderIconsFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USE_LEGACY_FOLDER_ICONS] ?: false
        }

    val addFolderFocusTargetFlow: Flow<AddFolderFocusTarget> = context.dataStore.data
        .map { preferences ->
            val targetName = preferences[PreferencesKeys.ADD_FOLDER_FOCUS_TARGET] ?: AddFolderFocusTarget.SEARCH_PATH.name
            try {
                AddFolderFocusTarget.valueOf(targetName)
            } catch (e: IllegalArgumentException) {
                AddFolderFocusTarget.SEARCH_PATH
            }
        }

    val swipeSensitivityFlow: Flow<SwipeSensitivity> = context.dataStore.data
        .map { preferences ->
            val sensitivityName = preferences[PreferencesKeys.SWIPE_SENSITIVITY] ?: SwipeSensitivity.MEDIUM.name
            try {
                SwipeSensitivity.valueOf(sensitivityName)
            } catch (e: IllegalArgumentException) {
                SwipeSensitivity.MEDIUM
            }
        }

    val swipeDownActionFlow: Flow<SwipeDownAction> = context.dataStore.data
        .map { preferences ->
            val actionName = preferences[PreferencesKeys.SWIPE_DOWN_ACTION] ?: SwipeDownAction.NONE.name
            try {
                SwipeDownAction.valueOf(actionName)
            } catch (e: IllegalArgumentException) {
                SwipeDownAction.NONE
            }
        }

    val bottomBarExpandedFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.BOTTOM_BAR_EXPANDED] ?: false
        }

    val defaultVideoSpeedFlow: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.DEFAULT_VIDEO_SPEED] ?: 1.0f
        }

    val screenshotDeletesVideoFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SCREENSHOT_DELETES_VIDEO] ?: false
        }

    val screenshotJpegQualityFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SCREENSHOT_JPEG_QUALITY] ?: "90"
        }

    val similarityThresholdLevelFlow: Flow<SimilarityThresholdLevel> = context.dataStore.data
        .map { preferences ->
            val levelName = preferences[PreferencesKeys.SIMILARITY_THRESHOLD_LEVEL] ?: SimilarityThresholdLevel.BALANCED.name
            try {
                SimilarityThresholdLevel.valueOf(levelName)
            } catch (e: IllegalArgumentException) {
                SimilarityThresholdLevel.BALANCED
            }
        }

    val duplicateScanScopeFlow: Flow<DuplicateScanScope> = context.dataStore.data
        .map { preferences ->
            val scopeName = preferences[PreferencesKeys.DUPLICATE_SCAN_SCOPE] ?: DuplicateScanScope.ALL_FILES.name
            try {
                DuplicateScanScope.valueOf(scopeName)
            } catch (e: IllegalArgumentException) {
                DuplicateScanScope.ALL_FILES
            }
        }

    val duplicateScanIncludeListFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            val listString = preferences[PreferencesKeys.DUPLICATE_SCAN_INCLUDE_LIST] ?: ""
            if (listString.isEmpty()) emptySet() else listString.split(",").filter { it.isNotBlank() }.toSet()
        }

    val duplicateScanExcludeListFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            val listString = preferences[PreferencesKeys.DUPLICATE_SCAN_EXCLUDE_LIST] ?: ""
            if (listString.isEmpty()) emptySet() else listString.split(",").filter { it.isNotBlank() }.toSet()
        }


    // Dialog Confirmation Flows
    val showConfirmMarkAsSortedFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.SHOW_CONFIRM_MARK_AS_SORTED] ?: true }

    val showConfirmResetAllHistoryFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.SHOW_CONFIRM_RESET_ALL_HISTORY] ?: true }

    val showConfirmForgetFolderFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.SHOW_CONFIRM_FORGET_FOLDER] ?: true }

    val showConfirmResetSourceFavsFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.SHOW_CONFIRM_RESET_SOURCE_FAVS] ?: true }

    val showConfirmResetTargetFavsFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.SHOW_CONFIRM_RESET_TARGET_FAVS] ?: true }

    val showConfirmDeleteAllExactFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.SHOW_CONFIRM_DELETE_ALL_EXACT] ?: true }

    val unselectAllInSearchScopeFlow: Flow<UnselectScanScope> = context.dataStore.data
        .map { preferences ->
            val scopeName = preferences[PreferencesKeys.UNSELECT_ALL_IN_SEARCH_SCOPE] ?: UnselectScanScope.GLOBAL.name
            try {
                UnselectScanScope.valueOf(scopeName)
            } catch (e: IllegalArgumentException) {
                UnselectScanScope.GLOBAL
            }
        }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME] = theme.name
        }
    }

    suspend fun setUseDynamicColors(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_DYNAMIC_COLORS] = enabled
        }
    }

    suspend fun setAccentColorKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ACCENT_COLOR_KEY] = key
        }
    }

    suspend fun setOnboardingCompleted() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] = true
        }
    }

    suspend fun setCompactFolderView(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.COMPACT_FOLDER_VIEW] = enabled
        }
    }

    suspend fun setHideFilename(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HIDE_FILENAME] = enabled
        }
    }

    suspend fun setInvertSwipe(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INVERT_SWIPE] = enabled
        }
    }

    suspend fun setFullScreenSwipe(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_SCREEN_SWIPE] = enabled
        }
    }

    suspend fun setFolderSelectionMode(mode: FolderSelectionMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDER_SELECTION_MODE] = mode.name
        }
    }

    suspend fun savePreviouslySelectedBuckets(bucketIds: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PREVIOUS_BUCKETS] = bucketIds.joinToString(",")
        }
    }

    suspend fun setSummaryViewMode(mode: SummaryViewMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SUMMARY_VIEW_MODE] = mode.name
        }
    }

    suspend fun setRememberProcessedMedia(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.REMEMBER_PROCESSED_MEDIA] = enabled
        }
    }

    suspend fun setUnfavoriteRemovesFromBar(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.UNFAVORITE_REMOVES_FROM_BAR] = enabled
        }
    }

    suspend fun setHideSkipButton(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HIDE_SWIPER_SKIP_BUTTON] = enabled
        }
    }

    suspend fun addProcessedMediaPaths(mediaPaths: Set<String>) {
        if (mediaPaths.isEmpty()) return
        context.dataStore.edit { preferences ->
            val currentPaths = preferences[PreferencesKeys.PROCESSED_MEDIA_PATHS] ?: ""
            val pathsSet = if (currentPaths.isEmpty()) {
                mediaPaths
            } else {
                currentPaths.split(",")
                    .filter { it.isNotBlank() }
                    .toMutableSet()
                    .apply { addAll(mediaPaths) }
            }
            preferences[PreferencesKeys.PROCESSED_MEDIA_PATHS] = pathsSet.joinToString(",")
        }
    }

    suspend fun clearProcessedMediaPaths() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.PROCESSED_MEDIA_PATHS)
        }
    }

    suspend fun removeProcessedMediaPathsInFolder(folderPath: String) {
        context.dataStore.edit { preferences ->
            val currentPaths = preferences[PreferencesKeys.PROCESSED_MEDIA_PATHS] ?: ""
            if (currentPaths.isNotEmpty()) {
                val newPaths = currentPaths.split(',')
                    .filter { path ->
                        if (path.isBlank()) return@filter false
                        val parent = try { File(path).parent } catch (e: Exception) { null }
                        parent != folderPath
                    }
                    .joinToString(",")
                preferences[PreferencesKeys.PROCESSED_MEDIA_PATHS] = newPaths
            }
        }
    }

    suspend fun resetOnboarding() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.ONBOARDING_COMPLETED)
        }
    }

    suspend fun addSourceFavoriteFolder(folderPath: String) {
        context.dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.SOURCE_FAVORITE_FOLDERS] ?: ""
            val favoritesSet = if (currentFavorites.isEmpty()) {
                setOf(folderPath)
            } else {
                currentFavorites.split(",").filter { it.isNotBlank() }.toMutableSet().apply {
                    add(folderPath)
                }
            }
            preferences[PreferencesKeys.SOURCE_FAVORITE_FOLDERS] = favoritesSet.joinToString(",")
        }
    }

    suspend fun addTargetFavoriteFolder(folderPath: String) {
        context.dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.TARGET_FAVORITE_FOLDERS] ?: ""
            val favoritesSet = if (currentFavorites.isEmpty()) {
                setOf(folderPath)
            } else {
                currentFavorites.split(",").filter { it.isNotBlank() }.toMutableSet().apply {
                    add(folderPath)
                }
            }
            preferences[PreferencesKeys.TARGET_FAVORITE_FOLDERS] = favoritesSet.joinToString(",")
        }
    }

    suspend fun addTargetFavoriteFolders(folderPaths: Set<String>) {
        if (folderPaths.isEmpty()) return
        context.dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.TARGET_FAVORITE_FOLDERS] ?: ""
            val favoritesSet = if (currentFavorites.isEmpty()) {
                folderPaths
            } else {
                currentFavorites.split(",").filter { it.isNotBlank() }.toMutableSet().apply {
                    addAll(folderPaths)
                }
            }
            preferences[PreferencesKeys.TARGET_FAVORITE_FOLDERS] = favoritesSet.joinToString(",")
        }
    }

    suspend fun removeSourceFavoriteFolder(folderPath: String) {
        context.dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.SOURCE_FAVORITE_FOLDERS] ?: ""
            if (currentFavorites.isNotEmpty()) {
                val favoritesSet = currentFavorites.split(",")
                    .filter { it.isNotBlank() && it != folderPath }
                    .toSet()
                preferences[PreferencesKeys.SOURCE_FAVORITE_FOLDERS] = favoritesSet.joinToString(",")
            }
        }
    }

    suspend fun removeTargetFavoriteFolder(folderPath: String) {
        context.dataStore.edit { preferences ->
            val currentFavorites = preferences[PreferencesKeys.TARGET_FAVORITE_FOLDERS] ?: ""
            if (currentFavorites.isNotEmpty()) {
                val favoritesSet = currentFavorites.split(",")
                    .filter { it.isNotBlank() && it != folderPath }
                    .toSet()
                preferences[PreferencesKeys.TARGET_FAVORITE_FOLDERS] = favoritesSet.joinToString(",")
            }
        }
    }

    suspend fun clearAllSourceFavorites() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.SOURCE_FAVORITE_FOLDERS)
        }
    }

    suspend fun clearAllTargetFavorites() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.TARGET_FAVORITE_FOLDERS)
        }
    }

    suspend fun setShowFavoritesFirstInSetup(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_FAVORITES_FIRST_IN_SETUP] = enabled
        }
    }

    suspend fun setDefaultAlbumCreationPath(path: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DEFAULT_ALBUM_PATH] = path
        }
    }

    suspend fun setSearchAutofocusEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SEARCH_AUTOFOCUS_ENABLED] = enabled
        }
    }

    suspend fun setSkipPartialExpansion(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SKIP_PARTIAL_EXPANSION] = enabled
        }
    }

    suspend fun setUseFullScreenSummarySheet(useFullScreen: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_FULL_SCREEN_SUMMARY_SHEET] = useFullScreen
        }
    }

    suspend fun setFolderBarLayout(layout: FolderBarLayout) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDER_BAR_LAYOUT] = layout.name
        }
    }

    suspend fun setFolderNameLayout(layout: FolderNameLayout) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FOLDER_NAME_LAYOUT] = layout.name
        }
    }

    suspend fun setUseLegacyFolderIcons(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_LEGACY_FOLDER_ICONS] = enabled
        }
    }

    suspend fun setAddFolderFocusTarget(target: AddFolderFocusTarget) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ADD_FOLDER_FOCUS_TARGET] = target.name
        }
    }

    suspend fun setSwipeSensitivity(sensitivity: SwipeSensitivity) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SWIPE_SENSITIVITY] = sensitivity.name
        }
    }

    suspend fun setSwipeDownAction(action: SwipeDownAction) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SWIPE_DOWN_ACTION] = action.name
        }
    }

    suspend fun setBottomBarExpanded(isExpanded: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BOTTOM_BAR_EXPANDED] = isExpanded
        }
    }

    suspend fun setDefaultVideoSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_VIDEO_SPEED] = speed
        }
    }

    suspend fun setScreenshotDeletesVideo(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SCREENSHOT_DELETES_VIDEO] = enabled
        }
    }

    suspend fun setScreenshotJpegQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SCREENSHOT_JPEG_QUALITY] = quality
        }
    }

    suspend fun setSimilarityThresholdLevel(level: SimilarityThresholdLevel) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SIMILARITY_THRESHOLD_LEVEL] = level.name
        }
    }

    suspend fun setDuplicateScanScope(scope: DuplicateScanScope) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DUPLICATE_SCAN_SCOPE] = scope.name
        }
    }

    suspend fun setDuplicateScanIncludeList(folders: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DUPLICATE_SCAN_INCLUDE_LIST] = folders.joinToString(",")
        }
    }

    suspend fun setDuplicateScanExcludeList(folders: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DUPLICATE_SCAN_EXCLUDE_LIST] = folders.joinToString(",")
        }
    }

    suspend fun setHasRunDuplicateScanOnce() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAS_RUN_DUPLICATE_SCAN_ONCE] = true
        }
    }

    suspend fun updateFolderPath(oldPath: String, newPath: String) {
        context.dataStore.edit { preferences ->
            val keysToUpdate = listOf(
                PreferencesKeys.SOURCE_FAVORITE_FOLDERS,
                PreferencesKeys.TARGET_FAVORITE_FOLDERS,
                PreferencesKeys.PREVIOUS_BUCKETS,
                KEY_DEFAULT_ALBUM_PATH,
                PreferencesKeys.PERMANENTLY_SORTED_FOLDERS,
                PreferencesKeys.DUPLICATE_SCAN_INCLUDE_LIST,
                PreferencesKeys.DUPLICATE_SCAN_EXCLUDE_LIST
            )

            keysToUpdate.forEach { key ->
                val currentValue = preferences[key]
                if (currentValue != null && currentValue.contains(oldPath)) {
                    // Use a more robust replacement to avoid partial matches
                    val pathSet = currentValue.split(',').map {
                        if (it == oldPath) newPath else it
                    }.toSet()
                    preferences[key] = pathSet.joinToString(",")
                }
            }
        }
    }

    suspend fun addPermanentlySortedFolder(folderPath: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.PERMANENTLY_SORTED_FOLDERS] ?: ""
            val set = current.split(",").filter { it.isNotBlank() }.toMutableSet()
            set.add(folderPath)
            preferences[PreferencesKeys.PERMANENTLY_SORTED_FOLDERS] = set.joinToString(",")
        }
    }

    suspend fun removePermanentlySortedFolder(folderPath: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.PERMANENTLY_SORTED_FOLDERS] ?: ""
            if (current.isNotEmpty()) {
                val set = current.split(",").filter { it.isNotBlank() && it != folderPath }.toSet()
                preferences[PreferencesKeys.PERMANENTLY_SORTED_FOLDERS] = set.joinToString(",")
            }
        }
    }

    suspend fun clearPermanentlySortedFolders() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.PERMANENTLY_SORTED_FOLDERS)
        }
    }

    suspend fun setAddFavoriteToTargetByDefault(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ADD_FAVORITE_TO_TARGET_BY_DEFAULT] = enabled
        }
    }

    suspend fun setHintOnExistingFolderName(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HINT_ON_EXISTING_FOLDER_NAME] = enabled
        }
    }

    // Dialog Confirmation Setters
    suspend fun setShowConfirmMarkAsSorted(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferencesKeys.SHOW_CONFIRM_MARK_AS_SORTED] = enabled }
    }

    suspend fun setShowConfirmResetAllHistory(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferencesKeys.SHOW_CONFIRM_RESET_ALL_HISTORY] = enabled }
    }

    suspend fun setShowConfirmForgetFolder(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferencesKeys.SHOW_CONFIRM_FORGET_FOLDER] = enabled }
    }

    suspend fun setShowConfirmResetSourceFavs(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferencesKeys.SHOW_CONFIRM_RESET_SOURCE_FAVS] = enabled }
    }

    suspend fun setShowConfirmResetTargetFavs(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferencesKeys.SHOW_CONFIRM_RESET_TARGET_FAVS] = enabled }
    }

    suspend fun setShowConfirmDeleteAllExact(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[PreferencesKeys.SHOW_CONFIRM_DELETE_ALL_EXACT] = enabled }
    }

    suspend fun resetDialogConfirmations() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_CONFIRM_MARK_AS_SORTED] = true
            preferences[PreferencesKeys.SHOW_CONFIRM_RESET_ALL_HISTORY] = true
            preferences[PreferencesKeys.SHOW_CONFIRM_FORGET_FOLDER] = true
            preferences[PreferencesKeys.SHOW_CONFIRM_RESET_SOURCE_FAVS] = true
            preferences[PreferencesKeys.SHOW_CONFIRM_RESET_TARGET_FAVS] = true
            preferences[PreferencesKeys.SHOW_CONFIRM_DELETE_ALL_EXACT] = true
        }
    }

    suspend fun setUnselectAllInSearchScope(scope: UnselectScanScope) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.UNSELECT_ALL_IN_SEARCH_SCOPE] = scope.name
        }
    }
}
