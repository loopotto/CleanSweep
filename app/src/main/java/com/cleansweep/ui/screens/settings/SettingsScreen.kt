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

import android.content.ClipData
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cleansweep.R
import com.cleansweep.data.repository.AddFolderFocusTarget
import com.cleansweep.data.repository.AppLocale
import com.cleansweep.data.repository.DuplicateScanScope
import com.cleansweep.data.repository.FolderBarLayout
import com.cleansweep.data.repository.FolderNameLayout
import com.cleansweep.data.repository.FolderSelectionMode
import com.cleansweep.data.repository.SimilarityThresholdLevel
import com.cleansweep.data.repository.SwipeDownAction
import com.cleansweep.data.repository.SwipeSensitivity
import com.cleansweep.data.repository.UnselectScanScope
import com.cleansweep.ui.components.AppDialog
import com.cleansweep.ui.components.FolderSearchDialog
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.ui.theme.predefinedAccentColors
import com.cleansweep.util.rememberIsUsingGestureNavigation
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalResources

@Composable
private fun SettingsItem(
    title: AnnotatedString,
    summary: AnnotatedString,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null || onLongClick != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onClick?.invoke() },
                onLongPress = { onLongClick?.invoke() }
            )
        }
    } else Modifier

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsItem(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    SettingsItem(
        title = AnnotatedString(title),
        summary = AnnotatedString(summary),
        onClick = onClick,
        onLongClick = onLongClick
    )
}

private data class SettingContent(
    val titleRes: Int,
    val summaryAnnotated: AnnotatedString? = null,
    val keywords: List<String> = emptyList(),
    val content: @Composable () -> Unit
)

private data class SettingSection(
    val titleRes: Int,
    val items: List<SettingContent>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    onNavigateToLibraries: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val debouncedSearchQuery by viewModel.debouncedSearchQuery.collectAsState()
    val scope = rememberCoroutineScope()
    val folderSearchState by viewModel.folderSearchManager.state.collectAsState()
    val displayedUnindexedFiles by viewModel.displayedUnindexedFiles.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    val currentLocale by viewModel.currentLocale.collectAsState()
    val useDynamicColors by viewModel.useDynamicColors.collectAsState()
    val accentColorKey by viewModel.accentColorKey.collectAsState()
    val compactFolderView by viewModel.compactFolderView.collectAsState()
    val hideFilename by viewModel.hideFilename.collectAsState()
    val invertSwipe by viewModel.invertSwipe.collectAsState()
    val fullScreenSwipe by viewModel.fullScreenSwipe.collectAsState()
    val folderSelectionMode by viewModel.folderSelectionMode.collectAsState()
    val rememberProcessedMedia by viewModel.rememberProcessedMedia.collectAsState()
    val unfavoriteRemovesFromBar by viewModel.unfavoriteRemovesFromBar.collectAsState()
    val hideSkipButton by viewModel.hideSkipButton.collectAsState()
    val defaultPath by viewModel.defaultAlbumCreationPath.collectAsState()
    val showFavoritesInSetup by viewModel.showFavoritesInSetup.collectAsState()
    val searchAutofocusEnabled by viewModel.searchAutofocusEnabled.collectAsState()
    val skipPartialExpansion by viewModel.skipPartialExpansion.collectAsState()
    val useFullScreenSummarySheet by viewModel.useFullScreenSummarySheet.collectAsState()
    val folderBarLayout by viewModel.folderBarLayout.collectAsState()
    val folderNameLayout by viewModel.folderNameLayout.collectAsState()
    val useLegacyFolderIcons by viewModel.useLegacyFolderIcons.collectAsState()
    val addFolderFocusTarget by viewModel.addFolderFocusTarget.collectAsState()
    val swipeSensitivity by viewModel.swipeSensitivity.collectAsState()
    val swipeDownAction by viewModel.swipeDownAction.collectAsState()
    val addFavoriteToTargetByDefault by viewModel.addFavoriteToTargetByDefault.collectAsState()
    val hintOnExistingFolderName by viewModel.hintOnExistingFolderName.collectAsState()
    val pathOptions = viewModel.standardAlbumDirectories
    val defaultVideoSpeed by viewModel.defaultVideoSpeed.collectAsState()
    val screenshotDeletesVideo by viewModel.screenshotDeletesVideo.collectAsState()
    val screenshotJpegQuality by viewModel.screenshotJpegQuality.collectAsState()
    val similarityThresholdLevel by viewModel.similarityThresholdLevel.collectAsState()
    val unselectAllInSearchScope by viewModel.unselectAllInSearchScope.collectAsState()

    val duplicateScanScope by viewModel.duplicateScanScope.collectAsState()
    val duplicateScanIncludeList by viewModel.duplicateScanIncludeList.collectAsState()
    val duplicateScanExcludeList by viewModel.duplicateScanExcludeList.collectAsState()

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val configuration = LocalConfiguration.current

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            viewModel.toastMessageShown()
        }
    }

    // Hoist the string resource to avoid calling it inside the callback
    val defaultExportFilename = stringResource(R.string.export_filename_default)
    val exportFavoritesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportTargetFavorites(it) }
    }

    val importFavoritesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importTargetFavorites(it) }
    }

    val isGestureMode = rememberIsUsingGestureNavigation()
    val supportsDynamicColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var showAboutSortMediaDialog by remember { mutableStateOf(false) }
    var showFundingDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.isSearchActive) {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = viewModel::onSearchQueryChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            placeholder = { Text(stringResource(R.string.search_settings_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            )
                        )
                    } else {
                        Text(stringResource(R.string.settings))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleSearch) {
                        Icon(
                            imageVector = if (uiState.isSearchActive) Icons.Default.Clear else Icons.Default.Search,
                            contentDescription = if (uiState.isSearchActive) stringResource(R.string.close_search) else stringResource(R.string.search_settings_icon_desc)
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        val settingSections = remember {
            listOf(
                SettingSection(
                    titleRes = R.string.appearance_section_title,
                    items = listOf(
                        SettingContent(titleRes = R.string.language_title, keywords = listOf("translation", "it", "en")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.language_title,
                                descriptionRes = R.string.language_desc,
                                options = AppLocale.entries,
                                selectedOption = currentLocale,
                                onOptionSelected = { viewModel.setAppLocale(it) },
                                getDisplayName = { getAppLocaleDisplayName(it) })
                        },
                        SettingContent(titleRes = R.string.theme_title, keywords = listOf("dark", "light", "amoled", "system")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.theme_title,
                                descriptionRes = getThemeDescriptionRes(currentTheme),
                                options = AppTheme.entries,
                                selectedOption = currentTheme,
                                onOptionSelected = { viewModel.setTheme(it) },
                                getDisplayName = { getThemeDisplayName(it) })
                        },
                        SettingContent(titleRes = R.string.dynamic_colors_title, keywords = listOf("system theme", "material you")) {
                            val desc = if (supportsDynamicColors) stringResource(R.string.dynamic_colors_desc) else stringResource(R.string.dynamic_colors_req_android_12)
                            SettingSwitch(
                                titleRes = R.string.dynamic_colors_title,
                                description = desc,
                                checked = useDynamicColors,
                                onCheckedChange = { viewModel.setUseDynamicColors(it) },
                                enabled = supportsDynamicColors
                            )
                        },
                        SettingContent(titleRes = R.string.accent_color_title, keywords = listOf("customize colors")) {
                            AnimatedVisibility(visible = !useDynamicColors || !supportsDynamicColors) {
                                AccentColorSetting(
                                    currentAccentKey = accentColorKey,
                                    onClick = viewModel::showAccentColorDialog
                                )
                            }
                        },
                        SettingContent(titleRes = R.string.folder_name_position_title, keywords = listOf("above", "below", "hidden")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.folder_name_position_title,
                                descriptionRes = R.string.folder_name_position_desc,
                                options = FolderNameLayout.entries,
                                selectedOption = folderNameLayout,
                                onOptionSelected = { viewModel.setFolderNameLayout(it) },
                                getDisplayName = { getFolderNameLayoutDisplayName(it) }
                            )
                        },
                        SettingContent(titleRes = R.string.compact_folder_view_title, keywords = listOf("list style")) {
                            SettingSwitch(
                                titleRes = R.string.compact_folder_view_title,
                                descriptionRes = R.string.compact_folder_view_desc,
                                checked = compactFolderView,
                                onCheckedChange = { viewModel.setCompactFolderView(it) })
                        },
                        SettingContent(titleRes = R.string.legacy_folder_icons_title, keywords = listOf("legacy", "letter icon")) {
                            SettingSwitch(
                                titleRes = R.string.legacy_folder_icons_title,
                                descriptionRes = R.string.legacy_folder_icons_desc,
                                checked = useLegacyFolderIcons,
                                onCheckedChange = { viewModel.setUseLegacyFolderIcons(it) })
                        },
                        SettingContent(titleRes = R.string.hide_media_filename_title, keywords = listOf("overlay")) {
                            SettingSwitch(
                                titleRes = R.string.hide_media_filename_title,
                                descriptionRes = R.string.hide_media_filename_desc,
                                checked = hideFilename,
                                onCheckedChange = { viewModel.setHideFilename(it) })
                        },
                        SettingContent(titleRes = R.string.folder_bar_layout_title, keywords = listOf("horizontal", "vertical", "scrolling")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.folder_bar_layout_title,
                                descriptionRes = R.string.folder_bar_layout_desc,
                                options = FolderBarLayout.entries,
                                selectedOption = folderBarLayout,
                                onOptionSelected = { viewModel.setFolderBarLayout(it) },
                                getDisplayName = { layout ->
                                    when (layout) {
                                        FolderBarLayout.HORIZONTAL -> stringResource(R.string.layout_horizontal)
                                        FolderBarLayout.VERTICAL -> stringResource(R.string.layout_vertical)
                                    }
                                })
                        },
                        SettingContent(titleRes = R.string.skip_partial_expansion_title, keywords = listOf("review changes sheet", "animation")) {
                            SettingSwitch(
                                titleRes = R.string.skip_partial_expansion_title,
                                descriptionRes = R.string.skip_partial_expansion_desc,
                                checked = skipPartialExpansion,
                                onCheckedChange = { viewModel.onSkipPartialExpansionChanged(it) })
                        },
                        SettingContent(titleRes = R.string.use_full_screen_summary_title, keywords = listOf("maximize", "height")) {
                            SettingSwitch(
                                titleRes = R.string.use_full_screen_summary_title,
                                descriptionRes = R.string.use_full_screen_summary_desc,
                                checked = useFullScreenSummarySheet,
                                onCheckedChange = { viewModel.onUseFullScreenSummarySheetChanged(it) })
                        }
                    )
                ),
                SettingSection(
                    titleRes = R.string.behavior_section_title,
                    items = listOf(
                        SettingContent(titleRes = R.string.swipe_sensitivity_title, keywords = listOf("low", "medium", "high")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.swipe_sensitivity_title,
                                descriptionRes = R.string.swipe_sensitivity_desc,
                                options = SwipeSensitivity.entries,
                                selectedOption = swipeSensitivity,
                                onOptionSelected = { viewModel.setSwipeSensitivity(it) },
                                getDisplayName = { getSwipeSensitivityDisplayName(it) })
                        },
                        SettingContent(titleRes = R.string.swipe_down_action_title, keywords = listOf("gesture", "shortcut", "edit", "skip", "add folder", "share", "open")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.swipe_down_action_title,
                                descriptionRes = R.string.swipe_down_action_desc,
                                options = SwipeDownAction.entries,
                                selectedOption = swipeDownAction,
                                onOptionSelected = { viewModel.setSwipeDownAction(it) },
                                getDisplayName = { getSwipeDownActionDisplayName(it) }
                            )
                        },
                        SettingContent(titleRes = R.string.full_screen_swipe_title, keywords = listOf("gesture area", "background")) {
                            SettingSwitch(
                                titleRes = R.string.full_screen_swipe_title,
                                descriptionRes = R.string.full_screen_swipe_desc,
                                checked = fullScreenSwipe,
                                onCheckedChange = { viewModel.setFullScreenSwipe(it) })
                        },
                        SettingContent(titleRes = R.string.default_video_speed_title, keywords = listOf("playback")) {
                            val videoSpeedOptions = listOf(1.0f, 1.5f, 2.0f)
                            ExposedDropdownMenu(
                                titleRes = R.string.default_video_speed_title,
                                descriptionRes = R.string.default_video_speed_desc,
                                options = videoSpeedOptions,
                                selectedOption = defaultVideoSpeed,
                                onOptionSelected = { viewModel.setDefaultVideoSpeed(it) },
                                getDisplayName = { speed -> "${speed}x" }
                            )
                        },
                        SettingContent(titleRes = R.string.screenshot_deletes_video_title, keywords = listOf("screenshot also deletes original video")) {
                            SettingSwitch(
                                titleRes = R.string.screenshot_deletes_video_title,
                                descriptionRes = R.string.screenshot_deletes_video_desc,
                                checked = screenshotDeletesVideo,
                                onCheckedChange = { viewModel.setScreenshotDeletesVideo(it) }
                            )
                        },
                        SettingContent(titleRes = R.string.screenshot_quality_title, keywords = listOf("jpeg")) {
                            val qualityOptions = listOf("95", "90", "85", "75")
                            ExposedDropdownMenu(
                                titleRes = R.string.screenshot_quality_title,
                                descriptionRes = R.string.screenshot_quality_desc,
                                options = qualityOptions,
                                selectedOption = screenshotJpegQuality,
                                onOptionSelected = { viewModel.setScreenshotJpegQuality(it) },
                                getDisplayName = { quality ->
                                    when(quality) {
                                        "95" -> stringResource(R.string.quality_high)
                                        "90" -> stringResource(R.string.quality_good)
                                        "85" -> stringResource(R.string.quality_balanced)
                                        "75" -> stringResource(R.string.quality_low)
                                        else -> quality
                                    }
                                }
                            )
                        },
                        SettingContent(titleRes = R.string.folder_selection_mode_title, keywords = listOf("all", "remember", "none")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.folder_selection_mode_title,
                                descriptionRes = getFolderSelectionModeDescriptionRes(folderSelectionMode),
                                options = FolderSelectionMode.entries,
                                selectedOption = folderSelectionMode,
                                onOptionSelected = { viewModel.setFolderSelectionMode(it) },
                                getDisplayName = { getFolderSelectionModeDisplayName(it) })
                        },
                        SettingContent(titleRes = R.string.show_favorites_setup_title, keywords = listOf("show favorites in setup")) {
                            SettingSwitch(
                                titleRes = R.string.show_favorites_setup_title,
                                descriptionRes = R.string.show_favorites_setup_desc,
                                checked = showFavoritesInSetup,
                                onCheckedChange = { viewModel.setShowFavoritesInSetup(it) })
                        },
                        SettingContent(titleRes = R.string.invert_swipe_title, keywords = listOf("left", "right", "keep", "delete")) {
                            SettingSwitch(
                                titleRes = R.string.invert_swipe_title,
                                descriptionRes = R.string.invert_swipe_desc,
                                checked = invertSwipe,
                                onCheckedChange = { viewModel.setInvertSwipe(it) })
                        },
                        SettingContent(titleRes = R.string.hide_skip_button_title, keywords = listOf("hide skip button")) {
                            SettingSwitch(
                                titleRes = R.string.hide_skip_button_title,
                                descriptionRes = R.string.hide_skip_button_desc,
                                checked = hideSkipButton,
                                onCheckedChange = { viewModel.setHideSkipButton(it) }
                            )
                        },
                        SettingContent(titleRes = R.string.add_fav_by_default_title, keywords = listOf("target folder")) {
                            SettingSwitch(
                                titleRes = R.string.add_fav_by_default_title,
                                descriptionRes = R.string.add_fav_by_default_desc,
                                checked = addFavoriteToTargetByDefault,
                                onCheckedChange = { viewModel.setAddFavoriteToTargetByDefault(it) }
                            )
                        },
                        SettingContent(titleRes = R.string.unfav_removes_from_bar_title, keywords = listOf("hide folder")) {
                            SettingSwitch(
                                titleRes = R.string.unfav_removes_from_bar_title,
                                descriptionRes = R.string.unfav_removes_from_bar_desc,
                                checked = unfavoriteRemovesFromBar,
                                onCheckedChange = { viewModel.setUnfavoriteRemovesFromBar(it) }
                            )
                        },
                        SettingContent(titleRes = R.string.hint_on_existing_folder_title, keywords = listOf("hint on existing folder name")) {
                            SettingSwitch(
                                titleRes = R.string.hint_on_existing_folder_title,
                                descriptionRes = R.string.hint_on_existing_folder_desc,
                                checked = hintOnExistingFolderName,
                                onCheckedChange = { viewModel.setHintOnExistingFolderName(it) }
                            )
                        },
                        SettingContent(titleRes = R.string.initial_dialog_focus_title, keywords = listOf("search path", "folder name")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.initial_dialog_focus_title,
                                descriptionRes = R.string.initial_dialog_focus_desc,
                                options = AddFolderFocusTarget.entries,
                                selectedOption = addFolderFocusTarget,
                                onOptionSelected = { viewModel.setAddFolderFocusTarget(it) },
                                getDisplayName = { getAddFolderFocusTargetDisplayName(it) })
                        },
                        SettingContent(titleRes = R.string.default_album_location_title, keywords = listOf("pictures", "dcim", "movies", "custom folder")) {
                            DefaultAlbumLocationSetting(viewModel, defaultPath, pathOptions)
                        },
                        SettingContent(titleRes = R.string.remember_organized_media_title, keywords = listOf("remember organized media", "skip media", "reset history")) {
                            RememberMediaSetting(viewModel, rememberProcessedMedia)
                        },
                        SettingContent(titleRes = R.string.forget_sorted_media_folder_title, keywords = listOf("forget sorted media", "reappear")) {
                            ForgetSortedMediaSetting(viewModel)
                        },
                        SettingContent(titleRes = R.string.search_autofocus_title, keywords = listOf("search bar")) {
                            SettingSwitch(
                                titleRes = R.string.search_autofocus_title,
                                descriptionRes = R.string.search_autofocus_desc,
                                checked = searchAutofocusEnabled,
                                onCheckedChange = { viewModel.setSearchAutofocusEnabled(it) })
                        },
                        SettingContent(titleRes = R.string.unselect_all_behavior_title, keywords = listOf("global", "visible")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.unselect_all_behavior_title,
                                descriptionRes = getUnselectAllScopeDescriptionRes(unselectAllInSearchScope),
                                options = UnselectScanScope.entries,
                                selectedOption = unselectAllInSearchScope,
                                onOptionSelected = { viewModel.setUnselectAllInSearchScope(it) },
                                getDisplayName = { getUnselectAllScopeDisplayName(it) })
                        }
                    )
                ),
                SettingSection(
                    titleRes = R.string.duplicate_finder_section_title,
                    items = listOf(
                        SettingContent(titleRes = R.string.similarity_level_title, keywords = listOf("duplicates", "strict", "balanced", "loose")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.similarity_level_title,
                                descriptionRes = getSimilarityLevelDescriptionRes(similarityThresholdLevel),
                                options = SimilarityThresholdLevel.entries,
                                selectedOption = similarityThresholdLevel,
                                onOptionSelected = { viewModel.setSimilarityThresholdLevel(it) },
                                getDisplayName = { getSimilarityLevelDisplayName(it) })
                        },
                        SettingContent(titleRes = R.string.scan_scope_title, keywords = listOf("whitelist", "blacklist")) {
                            ExposedDropdownMenu(
                                titleRes = R.string.scan_scope_title,
                                description = getScanScopeDescription(duplicateScanScope, duplicateScanIncludeList, duplicateScanExcludeList),
                                options = DuplicateScanScope.entries,
                                selectedOption = duplicateScanScope,
                                onOptionSelected = { viewModel.setDuplicateScanScope(it) },
                                getDisplayName = { getScanScopeDisplayName(it) }
                            )
                        },
                        SettingContent(titleRes = R.string.manage_include_list, keywords = listOf("folders", "include", "exclude")) {
                            AnimatedVisibility(
                                visible = duplicateScanScope != DuplicateScanScope.ALL_FILES,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                val title = if (duplicateScanScope == DuplicateScanScope.INCLUDE_LIST) {
                                    stringResource(R.string.manage_include_list)
                                } else {
                                    stringResource(R.string.manage_exclude_list)
                                }
                                val listSize = if (duplicateScanScope == DuplicateScanScope.INCLUDE_LIST) duplicateScanIncludeList.size else duplicateScanExcludeList.size

                                Column {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.showDuplicateScanScopeDialog() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.manage_list_format, title, listSize))
                                    }
                                }
                            }
                        }
                    )
                ),
                SettingSection(
                    titleRes = R.string.advanced_section_title,
                    items = listOf(
                        SettingContent(titleRes = R.string.media_indexing_status_title, keywords = listOf("mediastore", "scan")) {
                            MediaIndexingStatusItem(
                                status = uiState.indexingStatus,
                                isStatusLoading = uiState.isIndexingStatusLoading,
                                isScanning = uiState.isIndexing,
                                onRefresh = viewModel::refreshIndexingStatus,
                                onScan = viewModel::triggerFullScan,
                                onViewFiles = viewModel::showUnindexedFilesDialog
                            )
                        },
                        SettingContent(titleRes = R.string.export_target_favorites_title, keywords = listOf("save", "backup")) {
                            SettingsItem(
                                title = stringResource(R.string.export_target_favorites_title),
                                summary = stringResource(R.string.export_target_favorites_desc),
                                onClick = { exportFavoritesLauncher.launch(defaultExportFilename) }
                            )
                        },
                        SettingContent(titleRes = R.string.import_target_favorites_title, keywords = listOf("load", "restore")) {
                            SettingsItem(
                                title = stringResource(R.string.import_target_favorites_title),
                                summary = stringResource(R.string.import_target_favorites_desc),
                                onClick = { importFavoritesLauncher.launch(arrayOf("application/json", "text/plain")) }
                            )
                        },
                        SettingContent(titleRes = R.string.reset_dialog_warnings_title, keywords = listOf("confirmation")) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.resetDialogWarnings() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.reset_dialog_warnings_title),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.reset_dialog_warnings_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                ),
                SettingSection(
                    titleRes = R.string.help_support_section_title,
                    items = listOf(
                        SettingContent(titleRes = R.string.onboarding_tutorial_title, keywords = listOf("replay", "help")) {
                            Column {
                                Text(stringResource(R.string.onboarding_tutorial_title), style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.onboarding_tutorial_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.resetOnboarding() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.replay_tutorial_button))
                                }
                            }
                        }
                    )
                ),
                SettingSection(
                    titleRes = R.string.about_section_title,
                    items = listOf(
                        SettingContent(titleRes = R.string.support_development_title, keywords = listOf("donate", "funding", "crypto", "bitcoin")) {
                            SettingsItem(
                                title = stringResource(R.string.support_development_title),
                                summary = stringResource(R.string.support_development_desc),
                                onClick = { showFundingDialog = true }
                            )
                        },
                        SettingContent(titleRes = R.string.version_title, keywords = listOf("build")) {
                            val versionString = viewModel.appVersion
                            val copyMessage = stringResource(R.string.app_version_copied, versionString)
                            SettingsItem(
                                title = stringResource(R.string.version_title),
                                summary = versionString,
                                onLongClick = {
                                    scope.launch {
                                        val clipData = ClipData.newPlainText("label", versionString)
                                        val clipEntry = ClipEntry(clipData)
                                        clipboard.setClipEntry(clipEntry)
                                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                                            android.widget.Toast.makeText(context, copyMessage, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        },
                        SettingContent(titleRes = R.string.about_cleansweep_title, keywords = listOf("info")) {
                            SettingsItem(
                                title = stringResource(R.string.about_cleansweep_title),
                                summary = stringResource(R.string.about_cleansweep_desc),
                                onClick = { showAboutSortMediaDialog = true }
                            )
                        },
                        SettingContent(titleRes = R.string.github_summary, keywords = listOf("source code")) {
                            val uriHandler = LocalUriHandler.current
                            val url = stringResource(R.string.github_summary)
                            SettingsItem(
                                title = stringResource(R.string.github_title),
                                summary = url,
                                onClick = { uriHandler.openUri("https://$url") }
                            )
                        },
                        SettingContent(titleRes = R.string.gitlab_summary, keywords = listOf("mirror")) {
                            val uriHandler = LocalUriHandler.current
                            // Removing "(read-only mirror)" suffix for URL
                            val url = stringResource(R.string.gitlab_summary).substringBefore(" ")
                            SettingsItem(
                                title = stringResource(R.string.gitlab_title),
                                summary = stringResource(R.string.gitlab_summary),
                                onClick = { uriHandler.openUri("https://$url") }
                            )
                        },
                        SettingContent(titleRes = R.string.open_source_licenses_title, keywords = listOf("libraries")) {
                            SettingsItem(
                                title = stringResource(R.string.open_source_licenses_title),
                                summary = stringResource(R.string.open_source_licenses_desc),
                                onClick = onNavigateToLibraries
                            )
                        }
                    )
                )
            )
        }

        val resources = LocalResources.current
        val filteredSections = remember(debouncedSearchQuery, settingSections, configuration) {
            if (debouncedSearchQuery.isBlank()) {
                settingSections
            } else {
                settingSections.mapNotNull { section ->
                    val sectionTitle = resources.getString(section.titleRes)
                    val sectionTitleMatches = sectionTitle.contains(debouncedSearchQuery, ignoreCase = true)
                    val matchingItems = section.items.filter { item ->
                        val itemTitle = resources.getString(item.titleRes)
                        itemTitle.contains(debouncedSearchQuery, ignoreCase = true) ||
                                item.keywords.any { it.contains(debouncedSearchQuery, ignoreCase = true) }
                    }
                    if (sectionTitleMatches || matchingItems.isNotEmpty()) {
                        section.copy(items = if (sectionTitleMatches) section.items else matchingItems)
                    } else {
                        null
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (isGestureMode) 16.dp else 48.dp)
        ) {
            filteredSections.forEach { section ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (filteredSections.first() != section) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Text(
                        text = stringResource(section.titleRes),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    section.items.forEach { item ->
                        item.content()
                    }
                }
            }

            if (filteredSections.isEmpty() && debouncedSearchQuery.isNotBlank()) {
                Text(
                    text = stringResource(R.string.no_settings_found, debouncedSearchQuery),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showFundingDialog) {
        FundingDialog(onDismiss = { showFundingDialog = false })
    }

    if (uiState.showUnindexedFilesDialog) {
        UnindexedFilesDialog(
            filePaths = displayedUnindexedFiles,
            totalUnindexedCount = uiState.unindexedFilePaths.size,
            showHidden = uiState.showHiddenUnindexedFiles,
            onToggleShowHidden = viewModel::toggleShowHiddenUnindexedFiles,
            onDismiss = viewModel::dismissUnindexedFilesDialog
        )
    }

    if (uiState.showAccentColorDialog) {
        AccentColorDialog(
            currentAccentKey = accentColorKey,
            onDismiss = viewModel::dismissAccentColorDialog,
            onColorSelected = viewModel::setAccentColor
        )
    }

    if (showAboutSortMediaDialog) {
        AppDialog(
            onDismissRequest = { showAboutSortMediaDialog = false },
            title = { Text(stringResource(R.string.about_cleansweep_title), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(stringResource(R.string.version_title) + ": ${viewModel.appVersion}", style = MaterialTheme.typography.bodyLarge) },
            buttons = {
                TextButton(onClick = { showAboutSortMediaDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (uiState.showDuplicateScanScopeDialog) {
        val title = if (duplicateScanScope == DuplicateScanScope.INCLUDE_LIST) stringResource(R.string.manage_include_list) else stringResource(R.string.manage_exclude_list)
        val folderList = if (duplicateScanScope == DuplicateScanScope.INCLUDE_LIST) duplicateScanIncludeList else duplicateScanExcludeList
        val isForInclude = duplicateScanScope == DuplicateScanScope.INCLUDE_LIST

        DuplicateScanScopeManagementDialog(
            title = title,
            folderList = folderList.toList(),
            onDismiss = viewModel::dismissDuplicateScanScopeDialog,
            onAddFolder = { viewModel.showDuplicateScanScopeFolderSearch(isForInclude) },
            onRemoveFolder = viewModel::removeFolderFromScanScopeList
        )
    }

    if (uiState.showDuplicateScanScopeFolderSearch) {
        FolderSearchDialog(
            state = folderSearchState,
            title = stringResource(R.string.add_folder),
            searchLabel = stringResource(R.string.search_hint) + "…",
            confirmButtonText = stringResource(R.string.confirm),
            autoConfirmOnSelection = false,
            onDismiss = viewModel::dismissFolderSearchDialog,
            onQueryChanged = viewModel.folderSearchManager::updateSearchQuery,
            onFolderSelected = viewModel::onPathSelected,
            onConfirm = {
                val selectedPath = folderSearchState.browsePath
                if (selectedPath != null) {
                    viewModel.addFolderToScanScopeList(selectedPath)
                }
            },
            onSearch = { scope.launch { viewModel.folderSearchManager.selectSingleResultOrSelf() } },
            formatListItemTitle = { formatPathForDisplay(it) }
        )
    }

    val missingFolders = uiState.missingImportedFolders
    if (missingFolders != null) {
        AppDialog(
            onDismissRequest = { viewModel.dismissMissingFoldersDialog() },
            title = { Text(stringResource(R.string.some_folders_not_found_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.some_folders_not_found_body))
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                        missingFolders.forEach { path ->
                            Text(".../${path.takeLast(35)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissMissingFoldersDialog() }) {
                    Text(stringResource(R.string.skip))
                }
                Button(onClick = { viewModel.createAndImportMissingFolders() }) {
                    Text(stringResource(R.string.create))
                }
            }
        )
    }

    if (uiState.showDefaultPathSearchDialog) {
        FolderSearchDialog(
            state = folderSearchState,
            title = stringResource(R.string.default_album_location_title),
            searchLabel = stringResource(R.string.search_hint) + "…",
            confirmButtonText = stringResource(R.string.confirm),
            autoConfirmOnSelection = false,
            onDismiss = viewModel::dismissFolderSearchDialog,
            onQueryChanged = viewModel.folderSearchManager::updateSearchQuery,
            onFolderSelected = viewModel::onPathSelected,
            onConfirm = viewModel::confirmDefaultPathSelection,
            onSearch = { scope.launch { viewModel.folderSearchManager.selectSingleResultOrSelf() } },
            formatListItemTitle = { formatPathForDisplay(it) }
        )
    }

    if (uiState.showForgetMediaSearchDialog) {
        FolderSearchDialog(
            state = folderSearchState,
            title = stringResource(R.string.forget_sorted_media_title),
            searchLabel = stringResource(R.string.search_hint) + "…",
            confirmButtonText = stringResource(R.string.forget_action),
            autoConfirmOnSelection = true,
            onDismiss = viewModel::dismissFolderSearchDialog,
            onQueryChanged = viewModel.folderSearchManager::updateSearchQuery,
            onFolderSelected = { path -> viewModel.forgetSortedMediaInFolder(path) },
            onConfirm = { /* Not needed here */ },
            onSearch = { scope.launch { viewModel.folderSearchManager.selectSingleResultOrSelf() } },
            formatListItemTitle = { formatPathForDisplay(it) }
        )
    }

    if (uiState.showConfirmForgetFolderDialog) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("forgetFolder") },
            showDontAskAgain = true,
            dontAskAgainChecked = uiState.dontAskAgainForgetFolder,
            onDontAskAgainChanged = { isChecked -> viewModel.onDontAskAgainChanged("forgetFolder", isChecked) },
            title = { Text(stringResource(R.string.forget_confirm_title)) },
            text = { Text(stringResource(R.string.forget_confirm_body, File(uiState.folderToForget ?: "").name)) },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("forgetFolder") }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = viewModel::confirmForgetSortedMediaInFolder) { Text(stringResource(R.string.confirm)) }
            }
        )
    }

    if (uiState.showResetDialogsConfirmation) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("resetWarnings") },
            title = { Text(stringResource(R.string.reset_all_warnings_title), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    stringResource(R.string.reset_all_warnings_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("resetWarnings") }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = viewModel::confirmResetDialogWarnings) { Text(stringResource(R.string.reset)) }
            }
        )
    }

    if (uiState.showResetHistoryConfirmation) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("resetHistory") },
            showDontAskAgain = true,
            dontAskAgainChecked = uiState.dontAskAgainResetHistory,
            onDontAskAgainChanged = { viewModel.onDontAskAgainChanged("resetHistory", it) },
            title = { Text(stringResource(R.string.reset_history_title), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    stringResource(R.string.reset_history_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("resetHistory") }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = viewModel::confirmResetHistory) { Text(stringResource(R.string.reset)) }
            }
        )
    }

    if (uiState.showResetSourceFavoritesConfirmation) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("resetSource") },
            showDontAskAgain = true,
            dontAskAgainChecked = uiState.dontAskAgainResetSourceFavorites,
            onDontAskAgainChanged = { viewModel.onDontAskAgainChanged("resetSource", it) },
            title = { Text(stringResource(R.string.reset_source_favs_title), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    stringResource(R.string.reset_source_favs_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("resetSource") }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = viewModel::confirmClearSourceFavorites) { Text(stringResource(R.string.reset)) }
            }
        )
    }

    if (uiState.showResetTargetFavoritesConfirmation) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("resetTarget") },
            showDontAskAgain = true,
            dontAskAgainChecked = uiState.dontAskAgainResetTargetFavorites,
            onDontAskAgainChanged = { viewModel.onDontAskAgainChanged("resetTarget", it) },
            title = { Text(stringResource(R.string.reset_target_favs_title), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    stringResource(R.string.reset_target_favs_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("resetTarget") }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = viewModel::confirmClearTargetFavorites) { Text(stringResource(R.string.reset)) }
            }
        )
    }
}

private fun formatPathForDisplay(path: String): Pair<String, String> {
    val file = File(path)
    val name = file.name
    val parentPath = file.parent?.replace("/storage/emulated/0", "") ?: ""
    val displayParent = if (parentPath.length > 30) "...${parentPath.takeLast(27)}" else parentPath
    return Pair(name, displayParent)
}

@Composable
private fun DuplicateScanScopeManagementDialog(
    title: String,
    folderList: List<String>,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (folderList.isEmpty()) {
                Text(stringResource(R.string.no_folders_added_to_list))
            } else {
                LazyColumn(modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)) {
                    items(folderList) { path ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = ".../${path.takeLast(35)}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveFolder(path) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove folder")
                            }
                        }
                    }
                }
            }
        },
        buttons = {
            OutlinedButton(onClick = onAddFolder) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_folder))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun UnindexedFilesDialog(
    filePaths: List<String>,
    totalUnindexedCount: Int,
    showHidden: Boolean,
    onToggleShowHidden: () -> Unit,
    onDismiss: () -> Unit
) {
    val groupedFiles = remember(filePaths) {
        filePaths.groupBy { File(it).parent ?: "Unknown Location" }
    }
    val unknownLocation = stringResource(R.string.unknown_location)

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unindexed_files_dialog_title)) },
        text = {
            Column {
                if (filePaths.isEmpty() && totalUnindexedCount > 0) {
                    Text(
                        text = stringResource(R.string.unindexed_files_ideal_state),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (filePaths.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_unindexed_files_found),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (filePaths.isNotEmpty()) {
                    LazyColumn(modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)) {
                        item {
                            val descriptionText = if (showHidden) {
                                stringResource(R.string.unindexed_files_system_desc)
                            } else {
                                stringResource(R.string.unindexed_files_user_desc)
                            }
                            Text(
                                text = descriptionText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        groupedFiles.forEach { (directory, files) ->
                            val dirName = if (directory == "Unknown Location") unknownLocation else directory
                            item {
                                Text(
                                    text = dirName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                                HorizontalDivider()
                            }
                            items(files) { filePath ->
                                val file = File(filePath)
                                val formattedPath = "${file.parentFile?.name ?: "..."}/${file.name}"
                                Text(
                                    text = formattedPath,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleShowHidden)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = showHidden, onCheckedChange = { onToggleShowHidden() })
                    Text(stringResource(R.string.show_hidden_temp_files))
                }
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun MediaIndexingStatusItem(
    status: DetailedIndexingStatus?,
    isStatusLoading: Boolean,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onViewFiles: () -> Unit
) {
    val statusText = when {
        isScanning -> stringResource(R.string.indexing_status_scanning)
        isStatusLoading -> stringResource(R.string.indexing_status_loading)
        status == null -> stringResource(R.string.indexing_status_initial)
        else -> {
            val percentage = if (status.total > 0) (status.indexed.toDouble() / status.total * 100) else 100.0
            val formattedPercentage = String.format(java.util.Locale.US, "%.1f%%", percentage)
            val totalFormatted = NumberFormat.getInstance().format(status.total)
            val indexedFormatted = NumberFormat.getInstance().format(status.indexed)
            stringResource(R.string.indexing_status_format, indexedFormatted, totalFormatted, formattedPercentage)
        }
    }

    val supportingText = if (status != null && !isScanning && !isStatusLoading) {
        if (status.total > status.indexed) {
            val unindexedTotal = status.unindexedUserFiles + status.unindexedHiddenFiles
            val breakdown = stringResource(R.string.indexing_status_breakdown, status.unindexedUserFiles, status.unindexedHiddenFiles)
            pluralStringResource(R.plurals.indexing_status_unindexed_count, unindexedTotal, unindexedTotal) + "\n$breakdown"
        } else {
            stringResource(R.string.indexing_status_all_indexed)
        }
    } else null

    ListItem(
        headlineContent = { Text(stringResource(R.string.media_indexing_status_title)) },
        supportingContent = {
            Column {
                Text(statusText)
                if (supportingText != null) {
                    val hasUnindexedFiles = status != null && status.total > status.indexed
                    val clickableModifier = if (hasUnindexedFiles) {
                        Modifier.clickable { onViewFiles() }
                    } else {
                        Modifier
                    }
                    val textColor = if(hasUnindexedFiles) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

                    Spacer(Modifier.height(4.dp))
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor,
                        modifier = clickableModifier
                    )
                }
            }
        },
        leadingContent = {
            if (isStatusLoading || isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(Icons.Default.Info, contentDescription = null)
            }
        },
        trailingContent = {
            IconButton(
                onClick = {
                    if (status != null && status.total > status.indexed) {
                        onScan()
                    } else {
                        onRefresh()
                    }
                },
                enabled = !isScanning && !isStatusLoading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_scan_icon_desc))
            }
        },
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun AccentColorSetting(
    currentAccentKey: String,
    onClick: () -> Unit
) {
    val currentAccent = predefinedAccentColors.find { it.key == currentAccentKey }
        ?: predefinedAccentColors.first()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.accent_color_title), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = currentAccent.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

@Composable
private fun AccentColorDialog(
    currentAccentKey: String,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit
) {
    var localSelectedKey by remember { mutableStateOf(currentAccentKey) }
    val isDark = isSystemInDarkTheme()

    val selectedAccent = predefinedAccentColors.find { it.key == localSelectedKey }
        ?: predefinedAccentColors.first()

    fun Color.toHexString(): String {
        return String.format("#%06X", (0xFFFFFF and this.toArgb()))
    }

    val selectedColorForHex = if (isDark) selectedAccent.darkColor else selectedAccent.lightColor

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.customize_colors_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            val gradientColors = remember(isDark) {
                predefinedAccentColors.map { if (isDark) it.darkColor else it.lightColor }
            }

            Column {
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                val index = (fraction * (gradientColors.size - 1)).roundToInt()
                                localSelectedKey = predefinedAccentColors[index].key
                            }
                        }
                ) {
                    drawRoundRect(
                        brush = Brush.linearGradient(colors = gradientColors),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())
                    )

                    val selectedIndex = predefinedAccentColors.indexOfFirst { it.key == localSelectedKey }
                    if (selectedIndex != -1) {
                        val positionFraction = selectedIndex.toFloat() / (gradientColors.size - 1).toFloat()
                        val indicatorX = (size.width * positionFraction).coerceIn(12.dp.toPx(), size.width - 12.dp.toPx())

                        drawCircle(
                            color = Color.White,
                            radius = 12.dp.toPx(),
                            center = Offset(indicatorX, size.height / 2)
                        )
                        drawCircle(
                            color = gradientColors[selectedIndex],
                            radius = 8.dp.toPx(),
                            center = Offset(indicatorX, size.height / 2)
                        )
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.2f),
                            radius = 12.dp.toPx(),
                            center = Offset(indicatorX, size.height / 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = selectedColorForHex.toHexString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onColorSelected(localSelectedKey) }) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ExposedDropdownMenu(
    titleRes: Int,
    descriptionRes: Int,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    getDisplayName: @Composable (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                    .fillMaxWidth(),
                readOnly = true,
                value = getDisplayName(selectedOption),
                onValueChange = {},
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(getDisplayName(option)) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ExposedDropdownMenu(
    titleRes: Int,
    description: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    getDisplayName: @Composable (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                    .fillMaxWidth(),
                readOnly = true,
                value = getDisplayName(selectedOption),
                onValueChange = {},
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(getDisplayName(option)) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    titleRes: Int,
    descriptionRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SettingSwitch(
    titleRes: Int,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun DefaultAlbumLocationSetting(
    viewModel: SettingsViewModel,
    defaultPath: String,
    pathOptions: List<Pair<String, String>>
) {
    Column {
        Text(text = stringResource(R.string.default_album_location_title), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.default_album_location_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            pathOptions.forEach { (name, path) ->
                FilterChip(
                    selected = defaultPath == path,
                    onClick = { viewModel.onDefaultAlbumPathChanged(path) },
                    label = {
                        Text(
                            text = name,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.showDefaultPathSearchDialog() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.search_custom_folder))
        }
        Spacer(modifier = Modifier.height(8.dp))
        val currentPathDisplay = if (defaultPath.isNotBlank()) {
            val standardOption = pathOptions.find { it.second == defaultPath }
            if (standardOption != null) stringResource(R.string.current_path_prefix, standardOption.first) else stringResource(R.string.current_path_prefix, ".../${defaultPath.takeLast(30)}")
        } else stringResource(R.string.no_default_folder_selected)
        Text(
            text = currentPathDisplay,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RememberMediaSetting(viewModel: SettingsViewModel, rememberProcessedMedia: Boolean) {
    Column {
        SettingSwitch(
            titleRes = R.string.remember_organized_media_title,
            descriptionRes = R.string.remember_organized_media_desc,
            checked = rememberProcessedMedia,
            onCheckedChange = { viewModel.setRememberProcessedMedia(it) }
        )
        AnimatedVisibility(visible = rememberProcessedMedia) {
            OutlinedButton(
                onClick = { viewModel.resetProcessedMediaIds() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.reset_organized_media_history))
            }
        }
    }
}

@Composable
private fun ForgetSortedMediaSetting(viewModel: SettingsViewModel) {
    Column {
        Text(stringResource(R.string.forget_sorted_media_folder_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.forget_sorted_media_folder_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.showForgetMediaSearchDialog() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.select_folder_to_forget))
        }
    }
}

@Composable
private fun getAppLocaleDisplayName(locale: AppLocale): String {
    return when (locale) {
        AppLocale.SYSTEM -> stringResource(R.string.language_system)
        AppLocale.ENGLISH -> stringResource(R.string.language_english)
        AppLocale.ITALIAN -> stringResource(R.string.language_italian)
    }
}

@Composable
private fun getSwipeSensitivityDisplayName(sensitivity: SwipeSensitivity): String {
    return when (sensitivity) {
        SwipeSensitivity.LOW -> stringResource(R.string.sensitivity_low)
        SwipeSensitivity.MEDIUM -> stringResource(R.string.sensitivity_medium)
        SwipeSensitivity.HIGH -> stringResource(R.string.sensitivity_high)
    }
}

@Composable
private fun getSwipeDownActionDisplayName(action: SwipeDownAction): String {
    return when (action) {
        SwipeDownAction.NONE -> stringResource(R.string.action_none)
        SwipeDownAction.MOVE_TO_EDIT -> stringResource(R.string.move_to_to_edit)
        SwipeDownAction.SKIP_ITEM -> stringResource(R.string.skip_item)
        SwipeDownAction.ADD_TARGET_FOLDER -> stringResource(R.string.add_target_folder)
        SwipeDownAction.SHARE -> stringResource(R.string.share)
        SwipeDownAction.OPEN_WITH -> stringResource(R.string.open_with)
    }
}

@Composable
private fun getSimilarityLevelDisplayName(level: SimilarityThresholdLevel): String {
    return when (level) {
        SimilarityThresholdLevel.STRICT -> stringResource(R.string.similarity_level_strict)
        SimilarityThresholdLevel.BALANCED -> stringResource(R.string.similarity_level_balanced)
        SimilarityThresholdLevel.LOOSE -> stringResource(R.string.similarity_level_loose)
    }
}

@Composable
private fun getSimilarityLevelDescriptionRes(level: SimilarityThresholdLevel): Int {
    return when (level) {
        SimilarityThresholdLevel.STRICT -> R.string.similarity_level_strict_desc
        SimilarityThresholdLevel.BALANCED -> R.string.similarity_level_balanced_desc
        SimilarityThresholdLevel.LOOSE -> R.string.similarity_level_loose_desc
    }
}

@Composable
private fun getScanScopeDisplayName(scope: DuplicateScanScope): String {
    return when (scope) {
        DuplicateScanScope.ALL_FILES -> stringResource(R.string.scan_scope_all)
        DuplicateScanScope.INCLUDE_LIST -> stringResource(R.string.scan_scope_include)
        DuplicateScanScope.EXCLUDE_LIST -> stringResource(R.string.scan_scope_exclude)
    }
}

@Composable
private fun getScanScopeDescription(
    scope: DuplicateScanScope,
    includeList: Set<String>,
    excludeList: Set<String>
): String {
    return when (scope) {
        DuplicateScanScope.ALL_FILES -> stringResource(R.string.scan_scope_all_desc)
        DuplicateScanScope.INCLUDE_LIST -> pluralStringResource(R.plurals.scan_scope_include_desc, includeList.size, includeList.size)
        DuplicateScanScope.EXCLUDE_LIST -> pluralStringResource(R.plurals.scan_scope_exclude_desc, excludeList.size, excludeList.size)
    }
}

@Composable
private fun getFolderNameLayoutDisplayName(layout: FolderNameLayout): String {
    return when (layout) {
        FolderNameLayout.ABOVE -> stringResource(R.string.folder_name_layout_above)
        FolderNameLayout.BELOW -> stringResource(R.string.folder_name_layout_below)
        FolderNameLayout.HIDDEN -> stringResource(R.string.folder_name_layout_hidden)
    }
}

@Composable
private fun getThemeDisplayName(theme: AppTheme): String {
    return when (theme) {
        AppTheme.SYSTEM -> stringResource(R.string.theme_system_display)
        AppTheme.LIGHT -> stringResource(R.string.theme_light_display)
        AppTheme.DARK -> stringResource(R.string.theme_dark_display)
        AppTheme.DARKER -> stringResource(R.string.theme_darker_display)
        AppTheme.AMOLED -> stringResource(R.string.theme_amoled_display)
    }
}

@Composable
private fun getThemeDescriptionRes(theme: AppTheme): Int {
    return when (theme) {
        AppTheme.SYSTEM -> R.string.theme_system_desc
        AppTheme.LIGHT -> R.string.theme_light_desc
        AppTheme.DARK -> R.string.theme_dark_desc
        AppTheme.DARKER -> R.string.theme_darker_desc
        AppTheme.AMOLED -> R.string.theme_amoled_desc
    }
}

@Composable
private fun getFolderSelectionModeDisplayName(mode: FolderSelectionMode): String {
    return when (mode) {
        FolderSelectionMode.ALL -> stringResource(R.string.mode_all_folders)
        FolderSelectionMode.REMEMBER -> stringResource(R.string.mode_remember_previous)
        FolderSelectionMode.NONE -> stringResource(R.string.mode_none)
    }
}

@Composable
private fun getFolderSelectionModeDescriptionRes(mode: FolderSelectionMode): Int {
    return when (mode) {
        FolderSelectionMode.ALL -> R.string.desc_mode_all
        FolderSelectionMode.REMEMBER -> R.string.desc_mode_remember
        FolderSelectionMode.NONE -> R.string.desc_mode_none
    }
}

@Composable
private fun getAddFolderFocusTargetDisplayName(target: AddFolderFocusTarget): String {
    return when (target) {
        AddFolderFocusTarget.SEARCH_PATH -> stringResource(R.string.focus_search_path)
        AddFolderFocusTarget.FOLDER_NAME -> stringResource(R.string.focus_folder_name)
        AddFolderFocusTarget.NONE -> stringResource(R.string.action_none)
    }
}

@Composable
private fun getUnselectAllScopeDisplayName(scope: UnselectScanScope): String {
    return when (scope) {
        UnselectScanScope.GLOBAL -> stringResource(R.string.unselect_everything)
        UnselectScanScope.VISIBLE_ONLY -> stringResource(R.string.unselect_visible_only)
    }
}

@Composable
private fun getUnselectAllScopeDescriptionRes(scope: UnselectScanScope): Int {
    return when (scope) {
        UnselectScanScope.GLOBAL -> R.string.desc_unselect_global
        UnselectScanScope.VISIBLE_ONLY -> R.string.desc_unselect_visible
    }
}

@Composable
private fun FundingDialog(onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    data class CryptoOption(val name: String, val network: String, val address: String)

    val options = remember {
        listOf(
            CryptoOption("Bitcoin", "BTC", "bc1qmzse7fuatzjws5a0n4evm9pjnj9sqmy0y6epu6"),
            CryptoOption("Ethereum", "ETH (ERC20)", "0xb4e7a72a06b606fecb1deb965573da07fcd86107"),
            CryptoOption("Solana", "SOL", "4sXJt424WjL3zcazC7mAccZAzeEpNreg2cpQQ8r7wMWr"),
            CryptoOption("BNB", "BSC (BEP20)", "0xb4e7a72a06b606fecb1deb965573da07fcd86107"),
            CryptoOption("USDT", "ETH/BSC", "0xb4e7a72a06b606fecb1deb965573da07fcd86107"),
            CryptoOption("XRP", "Ripple", "rp6jyrwvSrkKghyqXznZZuqM9TedrKiKEb"),
            CryptoOption("Litecoin", "LTC", "ltc1q8ey9y66frmlqg800m266vucjjux2672f6v2ffl"),
            CryptoOption("Tron", "TRC20", "TFSu7BfSjaiV1CioQgLaVAJsmNtPq4Cv1J"),
            CryptoOption("Monero", "XMR", "42Bh2WEy8LG6D4US4wCJ9aRKtuaKWgUM3Y35xBeCDgmvhWYhoJG9MbPPkW7o3GAGF1MNskK3jwXcGjByFDNrugZhEkqBrMT")
        )
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.funding_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.funding_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(options) { option ->
                        val copyMsg = stringResource(R.string.address_copied)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val clipData = ClipData.newPlainText("label", option.address)
                                        val clipEntry = ClipEntry(clipData)
                                        clipboard.setClipEntry(clipEntry)
                                        android.widget.Toast.makeText(context, copyMsg, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = option.name, style = MaterialTheme.typography.titleSmall)
                                Text(text = option.network, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = option.address, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_address_icon_desc), tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.funding_dialog_footer),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
