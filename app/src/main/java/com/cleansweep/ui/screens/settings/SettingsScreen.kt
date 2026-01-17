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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cleansweep.data.repository.AddFolderFocusTarget
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
    val keywords: List<String>,
    val content: @Composable () -> Unit
)

private data class SettingSection(
    val title: String,
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
    val useDynamicColors by viewModel.useDynamicColors.collectAsState()
    val accentColorKey by viewModel.accentColorKey.collectAsState()
    val compactFolderView by viewModel.compactFolderView.collectAsState()
    val hideFilename by viewModel.hideFilename.collectAsState()
    val invertSwipe by viewModel.invertSwipe.collectAsState()
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

    // Duplicate Scan Scope states
    val duplicateScanScope by viewModel.duplicateScanScope.collectAsState()
    val duplicateScanIncludeList by viewModel.duplicateScanIncludeList.collectAsState()
    val duplicateScanExcludeList by viewModel.duplicateScanExcludeList.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            viewModel.toastMessageShown()
        }
    }

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

    val isUsingGestureNav = rememberIsUsingGestureNavigation()
    val bottomPadding = if (isUsingGestureNav) 16.dp else 48.dp
    val supportsDynamicColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var showAboutSortMediaDialog by remember { mutableStateOf(false) }


    LaunchedEffect(uiState.isSearchActive) {
        if (uiState.isSearchActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    if (uiState.showConfirmSimilarityChangeDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSetSimilarityThresholdLevel() },
            title = { Text("Reset Similarity Cache?") },
            text = { Text("Changing this setting requires clearing the similar media cache. The next scan will take longer as it re-analyzes all photos and videos. This is a one-time process. Do you want to continue?") },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmSetSimilarityThresholdLevel() }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSetSimilarityThresholdLevel() }) {
                    Text("Cancel")
                }
            }
        )
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
                            placeholder = { Text("Search settings...") },
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
                        Text("Settings")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleSearch) {
                        Icon(
                            imageVector = if (uiState.isSearchActive) Icons.Default.Clear else Icons.Default.Search,
                            contentDescription = if (uiState.isSearchActive) "Close search" else "Search settings"
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
                    title = "Appearance",
                    items = listOf(
                        SettingContent(keywords = listOf("theme", "dark", "light", "amoled", "system")) {
                            ExposedDropdownMenu(
                                title = "Theme",
                                description = getThemeDescription(currentTheme),
                                options = AppTheme.entries,
                                selectedOption = currentTheme,
                                onOptionSelected = { viewModel.setTheme(it) },
                                getDisplayName = { getThemeDisplayName(it) })
                        },
                        SettingContent(keywords = listOf("dynamic colors", "system theme", "material you")) {
                            SettingSwitch(
                                title = "Use Dynamic Colors",
                                description = if (supportsDynamicColors) "Use colors from your system theme" else "Requires Android 12 or newer",
                                checked = useDynamicColors,
                                onCheckedChange = { viewModel.setUseDynamicColors(it) },
                                enabled = supportsDynamicColors
                            )
                        },
                        SettingContent(keywords = listOf("accent color", "customize colors")) {
                            AnimatedVisibility(visible = !useDynamicColors || !supportsDynamicColors) {
                                AccentColorSetting(
                                    currentAccentKey = accentColorKey,
                                    onClick = viewModel::showAccentColorDialog
                                )
                            }
                        },
                        SettingContent(keywords = listOf("folder name position", "above", "below", "hidden")) {
                            ExposedDropdownMenu(
                                title = "Folder Name Position",
                                description = "Choose where to display the current folder name on the sorting screen.",
                                options = FolderNameLayout.entries,
                                selectedOption = folderNameLayout,
                                onOptionSelected = { viewModel.setFolderNameLayout(it) },
                                getDisplayName = { getFolderNameLayoutDisplayName(it) }
                            )
                        },
                        SettingContent(keywords = listOf("compact folder view", "list style")) {
                            SettingSwitch(
                                title = "Compact Folder View",
                                description = "Show folders in a more compact list style",
                                checked = compactFolderView,
                                onCheckedChange = { viewModel.setCompactFolderView(it) })
                        },
                        SettingContent(keywords = listOf("initial based folder icons", "legacy", "letter icon")) {
                            SettingSwitch(
                                title = "Use Initial-based Folder Icons",
                                description = "Use the first letter of a folder's name as its icon",
                                checked = useLegacyFolderIcons,
                                onCheckedChange = { viewModel.setUseLegacyFolderIcons(it) })
                        },
                        SettingContent(keywords = listOf("hide media filename", "overlay")) {
                            SettingSwitch(
                                title = "Hide Media Filename",
                                description = "Hide the filename overlay on media cards",
                                checked = hideFilename,
                                onCheckedChange = { viewModel.setHideFilename(it) })
                        },
                        SettingContent(keywords = listOf("folder bar layout", "horizontal", "vertical", "scrolling")) {
                            ExposedDropdownMenu(
                                title = "Folder Bar Layout",
                                description = "Choose the scrolling direction for the 'Move to' bar.",
                                options = FolderBarLayout.entries,
                                selectedOption = folderBarLayout,
                                onOptionSelected = { viewModel.setFolderBarLayout(it) },
                                getDisplayName = { layout ->
                                    when (layout) {
                                        FolderBarLayout.HORIZONTAL -> "Horizontal"
                                        FolderBarLayout.VERTICAL -> "Vertical"
                                    }
                                })
                        },
                        SettingContent(keywords = listOf("skip partial expansion", "review changes sheet", "animation")) {
                            SettingSwitch(
                                title = "Skip Partial Expansion",
                                description = "The summary sheet will open directly to its full size.",
                                checked = skipPartialExpansion,
                                onCheckedChange = { viewModel.onSkipPartialExpansionChanged(it) })
                        },
                        SettingContent(keywords = listOf("use full-screen summary", "maximize", "height")) {
                            SettingSwitch(
                                title = "Use Full-Screen Summary",
                                description = "When enabled, the sheet's maximum height will fill the screen when needed.",
                                checked = useFullScreenSummarySheet,
                                onCheckedChange = { viewModel.onUseFullScreenSummarySheetChanged(it) })
                        }
                    )
                ),
                SettingSection(
                    title = "Behavior",
                    items = listOf(
                        SettingContent(keywords = listOf("swipe sensitivity", "low", "medium", "high")) {
                            ExposedDropdownMenu(
                                title = "Swipe Sensitivity",
                                description = "Adjust how far you need to swipe to trigger an action.",
                                options = SwipeSensitivity.entries,
                                selectedOption = swipeSensitivity,
                                onOptionSelected = { viewModel.setSwipeSensitivity(it) },
                                getDisplayName = { getSwipeSensitivityDisplayName(it) })
                        },
                        SettingContent(keywords = listOf("swipe down action", "gesture", "shortcut", "edit", "skip", "add folder", "share", "open")) {
                            ExposedDropdownMenu(
                                title = "Swipe Down Action",
                                description = "Assign a shortcut to the swipe down gesture on a media card.",
                                options = SwipeDownAction.entries,
                                selectedOption = swipeDownAction,
                                onOptionSelected = { viewModel.setSwipeDownAction(it) },
                                getDisplayName = { getSwipeDownActionDisplayName(it) }
                            )
                        },
                        SettingContent(keywords = listOf("default video speed", "playback")) {
                            val videoSpeedOptions = listOf(1.0f, 1.5f, 2.0f)
                            ExposedDropdownMenu(
                                title = "Default Video Speed",
                                description = "Set the default playback speed for videos.",
                                options = videoSpeedOptions,
                                selectedOption = defaultVideoSpeed,
                                onOptionSelected = { viewModel.setDefaultVideoSpeed(it) },
                                getDisplayName = { speed -> "${speed}x" }
                            )
                        },
                        SettingContent(keywords = listOf("screenshot also deletes original video")) {
                            SettingSwitch(
                                title = "Screenshot also deletes original video",
                                description = "When taking a screenshot from a video, automatically queue the video for deletion.",
                                checked = screenshotDeletesVideo,
                                onCheckedChange = { viewModel.setScreenshotDeletesVideo(it) }
                            )
                        },
                        SettingContent(keywords = listOf("screenshot quality", "jpeg")) {
                            val qualityOptions = listOf("95", "90", "85", "75")
                            ExposedDropdownMenu(
                                title = "Screenshot Quality (JPEG)",
                                description = "Higher quality results in larger file sizes. Default is 90.",
                                options = qualityOptions,
                                selectedOption = screenshotJpegQuality,
                                onOptionSelected = { viewModel.setScreenshotJpegQuality(it) },
                                getDisplayName = { quality ->
                                    when(quality) {
                                        "95" -> "High (95)"
                                        "90" -> "Good (90)"
                                        "85" -> "Balanced (85)"
                                        "75" -> "Low (75)"
                                        else -> "$quality"
                                    }
                                }
                            )
                        },
                        SettingContent(keywords = listOf("folder selection mode", "all", "remember", "none")) {
                            ExposedDropdownMenu(
                                title = "Folder Selection Mode",
                                description = getFolderSelectionModeDescription(folderSelectionMode),
                                options = FolderSelectionMode.entries,
                                selectedOption = folderSelectionMode,
                                onOptionSelected = { viewModel.setFolderSelectionMode(it) },
                                getDisplayName = { getFolderSelectionModeDisplayName(it) })
                        },
                        SettingContent(keywords = listOf("invert swipe actions", "left", "right", "keep", "delete")) {
                            SettingSwitch(
                                title = "Invert Swipe Actions",
                                description = "Invert the swipe actions: left to keep, right to delete",
                                checked = invertSwipe,
                                onCheckedChange = { viewModel.setInvertSwipe(it) })
                        },
                        SettingContent(keywords = listOf("hide skip button")) {
                            SettingSwitch(
                                title = "Hide Skip Button",
                                description = "If enabled, the 'Skip' button will be hidden from the bottom bar.",
                                checked = hideSkipButton,
                                onCheckedChange = { viewModel.setHideSkipButton(it) }
                            )
                        },
                        SettingContent(keywords = listOf("add to favorites by default", "target folder")) {
                            SettingSwitch(
                                title = "Add to Favorites by Default",
                                description = "Pre-select the 'Add to Favorites' toggle when adding a new target folder.",
                                checked = addFavoriteToTargetByDefault,
                                onCheckedChange = { viewModel.setAddFavoriteToTargetByDefault(it) }
                            )
                        },
                        SettingContent(keywords = listOf("unfavorite also removes", "hide folder")) {
                            SettingSwitch(
                                title = "Unfavorite Also Removes",
                                description = "If enabled, unfavoriting a folder also hides it for the session.",
                                checked = unfavoriteRemovesFromBar,
                                onCheckedChange = { viewModel.setUnfavoriteRemovesFromBar(it) }
                            )
                        },
                        SettingContent(keywords = listOf("hint on existing folder name")) {
                            SettingSwitch(
                                title = "Hint on Existing Folder Name",
                                description = "Show a hint if a folder with the same name exists while you type.",
                                checked = hintOnExistingFolderName,
                                onCheckedChange = { viewModel.setHintOnExistingFolderName(it) }
                            )
                        },
                        SettingContent(keywords = listOf("initial dialog focus", "search path", "folder name")) {
                            ExposedDropdownMenu(
                                title = "Initial Dialog Focus",
                                description = "When adding a folder, choose which field gets focus first.",
                                options = AddFolderFocusTarget.entries,
                                selectedOption = addFolderFocusTarget,
                                onOptionSelected = { viewModel.setAddFolderFocusTarget(it) },
                                getDisplayName = { getAddFolderFocusTargetDisplayName(it) })
                        },
                        SettingContent(keywords = listOf("show favorites in setup")) {
                            SettingSwitch(
                                title = "Show Favorites in Setup",
                                description = "Show favorite folders during the setup process",
                                checked = showFavoritesInSetup,
                                onCheckedChange = { viewModel.setShowFavoritesInSetup(it) })
                        },
                        SettingContent(keywords = listOf("default album location", "pictures", "dcim", "movies", "custom folder")) {
                            DefaultAlbumLocationSetting(viewModel, defaultPath, pathOptions)
                        },
                        SettingContent(keywords = listOf("remember organized media", "skip media", "reset history")) {
                            RememberMediaSetting(viewModel, rememberProcessedMedia)
                        },
                        SettingContent(keywords = listOf("forget sorted media", "reappear")) {
                            ForgetSortedMediaSetting(viewModel)
                        },
                        SettingContent(keywords = listOf("search autofocus enabled", "search bar")) {
                            SettingSwitch(
                                title = "Search Autofocus Enabled",
                                description = "Autofocus on search bar when opening the app",
                                checked = searchAutofocusEnabled,
                                onCheckedChange = { viewModel.setSearchAutofocusEnabled(it) })
                        },
                        SettingContent(keywords = listOf("unselect all behavior", "search", "global", "visible")) {
                            ExposedDropdownMenu(
                                title = "'Unselect All' Behavior in Search",
                                description = getUnselectAllScopeDescription(unselectAllInSearchScope),
                                options = UnselectScanScope.entries,
                                selectedOption = unselectAllInSearchScope,
                                onOptionSelected = { viewModel.setUnselectAllInSearchScope(it) },
                                getDisplayName = { getUnselectAllScopeDisplayName(it) })
                        }
                    )
                ),
                SettingSection(
                    title = "Duplicate Finder",
                    items = listOf(
                        SettingContent(keywords = listOf("similarity level", "duplicates", "strict", "balanced", "loose")) {
                            ExposedDropdownMenu(
                                title = "Similarity Level",
                                description = getSimilarityLevelDescription(similarityThresholdLevel),
                                options = SimilarityThresholdLevel.entries,
                                selectedOption = similarityThresholdLevel,
                                onOptionSelected = { viewModel.setSimilarityThresholdLevel(it) },
                                getDisplayName = { getSimilarityLevelDisplayName(it) })
                        },
                        SettingContent(keywords = listOf("scan scope", "include", "exclude", "whitelist", "blacklist")) {
                            ExposedDropdownMenu(
                                title = "Scan Scope",
                                description = getScanScopeDescription(duplicateScanScope, duplicateScanIncludeList, duplicateScanExcludeList),
                                options = DuplicateScanScope.entries,
                                selectedOption = duplicateScanScope,
                                onOptionSelected = { viewModel.setDuplicateScanScope(it) },
                                getDisplayName = { getScanScopeDisplayName(it) }
                            )
                        },
                        SettingContent(keywords = listOf("manage list", "folders", "include", "exclude")) {
                            AnimatedVisibility(
                                visible = duplicateScanScope != DuplicateScanScope.ALL_FILES,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                val (title, list) = if (duplicateScanScope == DuplicateScanScope.INCLUDE_LIST) {
                                    "Manage Include List" to duplicateScanIncludeList
                                } else {
                                    "Manage Exclude List" to duplicateScanExcludeList
                                }

                                Column {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.showDuplicateScanScopeDialog() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("$title (${list.size})")
                                    }
                                }
                            }
                        }
                    )
                ),
                SettingSection(
                    title = "Advanced",
                    items = listOf(
                        SettingContent(keywords = listOf("media indexing status", "mediastore", "scan")) {
                            MediaIndexingStatusItem(
                                status = uiState.indexingStatus,
                                isStatusLoading = uiState.isIndexingStatusLoading,
                                isScanning = uiState.isIndexing,
                                onRefresh = viewModel::refreshIndexingStatus,
                                onScan = viewModel::triggerFullScan,
                                onViewFiles = viewModel::showUnindexedFilesDialog
                            )
                        },
                        SettingContent(keywords = listOf("export target favorites", "save", "backup")) {
                            SettingsItem(
                                title = "Export Target Favorites",
                                summary = "Save your favorite target folders to a file.",
                                onClick = { exportFavoritesLauncher.launch("cleansweep_target_favorites.json") }
                            )
                        },
                        SettingContent(keywords = listOf("import target favorites", "load", "restore")) {
                            SettingsItem(
                                title = "Import Target Favorites",
                                summary = "Load favorite target folders from a file.",
                                onClick = { importFavoritesLauncher.launch(arrayOf("application/json", "text/plain")) }
                            )
                        },
                        SettingContent(keywords = listOf("reset dialog warnings", "confirmation")) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.resetDialogWarnings() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Reset Dialog Warnings",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Reshow all confirmation dialogs.",
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
                    title = "Help & Support",
                    items = listOf(
                        SettingContent(keywords = listOf("onboarding tutorial", "replay", "help")) {
                            Column {
                                Text("Onboarding Tutorial", style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "Replay the onboarding experience to learn app features.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { viewModel.resetOnboarding() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Replay Tutorial")
                                }
                            }
                        }
                    )
                ),
                SettingSection(
                    title = "About",
                    items = listOf(
                        SettingContent(keywords = listOf("version", "build")) {
                            SettingsItem(
                                title = "Version",
                                summary = viewModel.appVersion,
                                onLongClick = viewModel::copyAppVersionToClipboard
                            )
                        },
                        SettingContent(keywords = listOf("about cleansweep", "info")) {
                            SettingsItem(
                                title = "About CleanSweep",
                                summary = "Learn more about CleanSweep",
                                onClick = { showAboutSortMediaDialog = true }
                            )
                        },
                        SettingContent(keywords = listOf("github", "source code")) {
                            val uriHandler = LocalUriHandler.current
                            SettingsItem(
                                title = "GitHub",
                                summary = "github.com/LoopOtto/CleanSweep",
                                onClick = { uriHandler.openUri("https://github.com/LoopOtto/CleanSweep") }
                            )
                        },
                        SettingContent(keywords = listOf("gitlab", "source code", "mirror")) {
                            val uriHandler = LocalUriHandler.current
                            SettingsItem(
                                title = "GitLab",
                                summary = "gitlab.com/LoopOtto/CleanSweep (read-only mirror)",
                                onClick = { uriHandler.openUri("https://gitlab.com/LoopOtto/cleansweep") }
                            )
                        },
                        SettingContent(keywords = listOf("open-source licenses", "libraries")) {
                            SettingsItem(
                                title = "Open-Source Licenses",
                                summary = "View open-source licenses",
                                onClick = onNavigateToLibraries
                            )
                        }
                    )
                )
            )
        }

        val filteredSections = remember(debouncedSearchQuery, settingSections) {
            if (debouncedSearchQuery.isBlank()) {
                settingSections
            } else {
                settingSections.mapNotNull { section ->
                    val sectionTitleMatches = section.title.contains(debouncedSearchQuery, ignoreCase = true)
                    val matchingItems = section.items.filter { item ->
                        item.keywords.any { it.contains(debouncedSearchQuery, ignoreCase = true) }
                    }
                    if (sectionTitleMatches || matchingItems.isNotEmpty()) {
                        // If the section title matches, show all items, otherwise show only matching items
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
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPadding)
        ) {
            filteredSections.forEach { section ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Spacer between sections
                    if (filteredSections.first() != section) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    section.items.forEach { item ->
                        item.content()
                    }
                }
            }

            // Show a message if no results are found
            if (filteredSections.isEmpty() && debouncedSearchQuery.isNotBlank()) {
                Text(
                    text = "No settings found for \"$debouncedSearchQuery\"",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
            title = { Text("About CleanSweep", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("Version: ${viewModel.appVersion}", style = MaterialTheme.typography.bodyLarge) },
            buttons = {
                TextButton(onClick = { showAboutSortMediaDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (uiState.showDuplicateScanScopeDialog) {
        val (title, list, isForInclude) = if (duplicateScanScope == DuplicateScanScope.INCLUDE_LIST) {
            Triple("Manage Include List", duplicateScanIncludeList, true)
        } else {
            Triple("Manage Exclude List", duplicateScanExcludeList, false)
        }
        DuplicateScanScopeManagementDialog(
            title = title,
            folderList = list.toList(),
            onDismiss = viewModel::dismissDuplicateScanScopeDialog,
            onAddFolder = { viewModel.showDuplicateScanScopeFolderSearch(isForInclude) },
            onRemoveFolder = viewModel::removeFolderFromScanScopeList
        )
    }

    if (uiState.showDuplicateScanScopeFolderSearch) {
        FolderSearchDialog(
            state = folderSearchState,
            title = "Select Folder",
            searchLabel = "Search or enter path...",
            confirmButtonText = "Select",
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
            title = { Text("Some Folders Not Found") },
            text = {
                Column {
                    Text("The following folders from your backup file do not exist. Do you want to create them?")
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
                    Text("Skip")
                }
                Button(onClick = { viewModel.createAndImportMissingFolders() }) {
                    Text("Create")
                }
            }
        )
    }

    if (uiState.showDefaultPathSearchDialog) {
        FolderSearchDialog(
            state = folderSearchState,
            title = "Select Default Folder",
            searchLabel = "Search or enter path...",
            confirmButtonText = "Select",
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
            title = "Forget Sorted Media",
            searchLabel = "Search for a folder...",
            confirmButtonText = "Forget",
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
            title = { Text("Confirm Action") },
            text = { Text("Are you sure you want to forget all sorted media history for the folder '${File(uiState.folderToForget ?: "").name}'? This folder will reappear for sorting if it still contains media.") },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("forgetFolder") }) { Text("Cancel") }
                Button(onClick = viewModel::confirmForgetSortedMediaInFolder) { Text("Confirm") }
            }
        )
    }

    if (uiState.showResetDialogsConfirmation) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("resetWarnings") },
            title = { Text("Reset All Dialog Warnings?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "This will make all confirmation dialogs reappear, even if you selected 'Don't ask again'.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("resetWarnings") }) { Text("Cancel") }
                Button(onClick = viewModel::confirmResetDialogWarnings) { Text("Reset") }
            }
        )
    }

    if (uiState.showResetHistoryConfirmation) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("resetHistory") },
            showDontAskAgain = true,
            dontAskAgainChecked = uiState.dontAskAgainResetHistory,
            onDontAskAgainChanged = { viewModel.onDontAskAgainChanged("resetHistory", it) },
            title = { Text("Reset Sorted Media History?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "This will clear the record of all media you have sorted. Previously sorted items will reappear in new sessions. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("resetHistory") }) { Text("Cancel") }
                Button(onClick = viewModel::confirmResetHistory) { Text("Reset") }
            }
        )
    }

    if (uiState.showResetSourceFavoritesConfirmation) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("resetSource") },
            showDontAskAgain = true,
            dontAskAgainChecked = uiState.dontAskAgainResetSourceFavorites,
            onDontAskAgainChanged = { viewModel.onDontAskAgainChanged("resetSource", it) },
            title = { Text("Reset Source Favorites?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Are you sure you want to clear all source folder favorites? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("resetSource") }) { Text("Cancel") }
                Button(onClick = viewModel::confirmClearSourceFavorites) { Text("Reset") }
            }
        )
    }

    if (uiState.showResetTargetFavoritesConfirmation) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDialog("resetTarget") },
            showDontAskAgain = true,
            dontAskAgainChecked = uiState.dontAskAgainResetTargetFavorites,
            onDontAskAgainChanged = { viewModel.onDontAskAgainChanged("resetTarget", it) },
            title = { Text("Reset Target Favorites?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Are you sure you want to clear all target folder favorites? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = { viewModel.dismissDialog("resetTarget") }) { Text("Reset") }
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
                Text("No folders have been added to this list. The scan will run as if this setting were disabled.")
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
                Text("Add Folder")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text("Close")
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

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unindexed Media Files") },
        text = {
            Column {
                if (filePaths.isEmpty() && totalUnindexedCount > 0) {
                    Text(
                        text = "No unindexed user files found. This is the ideal state. Any remaining files are likely hidden system or temporary files, which can be viewed with the toggle below.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (filePaths.isEmpty()) {
                    Text(
                        text = "No unindexed files were found on your device.",
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
                                "These files are on your device but are not known by the MediaStore. These will never be indexed by the MediaStore by design."
                            } else {
                                "These files are on your device but are not known by the MediaStore. Maybe scanning will index them."
                            }
                            Text(
                                text = descriptionText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        groupedFiles.forEach { (directory, files) ->
                            item {
                                Text(
                                    text = directory,
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
                    Text("Show hidden/temporary files")
                }
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text("Close")
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
        isScanning -> "Scanning for unindexed media..."
        isStatusLoading -> "Loading status..."
        status == null -> "Press the refresh icon to check status." // Initial state
        else -> {
            val percentage = if (status.total > 0) (status.indexed.toDouble() / status.total * 100) else 100.0
            val formattedPercentage = String.format(java.util.Locale.US, "%.1f%%", percentage)
            val totalFormatted = NumberFormat.getInstance().format(status.total)
            val indexedFormatted = NumberFormat.getInstance().format(status.indexed)
            "Indexed Media: $indexedFormatted of $totalFormatted files ($formattedPercentage)"
        }
    }

    val supportingText = if (status != null && !isScanning && !isStatusLoading) {
        if (status.total > status.indexed) {
            val unindexedTotal = status.unindexedUserFiles + status.unindexedHiddenFiles
            val breakdown = "(${status.unindexedUserFiles} user files, ${status.unindexedHiddenFiles} hidden/system files)"
            "$unindexedTotal files are not in the MediaStore index.\n$breakdown"
        } else {
            "All media files are known to the MediaStore."
        }
    } else null

    ListItem(
        headlineContent = { Text("Media Indexing Status") },
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
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Status / Scan Now")
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
            Text(text = "Accent Color", style = MaterialTheme.typography.titleMedium)
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
        title = { Text("Customize colors", style = MaterialTheme.typography.headlineSmall) },
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
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onColorSelected(localSelectedKey) }) {
                Text("OK")
            }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ExposedDropdownMenu(
    title: String,
    description: String,
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    getDisplayName: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
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
                    .menuAnchor()
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
    title: String,
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
            Text(text = title, style = MaterialTheme.typography.titleMedium)
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
        Text(text = "Default Album Location", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Choose the default folder where new albums are created.",
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
            Text("Search for Custom Folder")
        }
        Spacer(modifier = Modifier.height(8.dp))
        val currentPathDisplay = if (defaultPath.isNotBlank()) {
            val standardOption = pathOptions.find { it.second == defaultPath }
            if (standardOption != null) "Current: ${standardOption.first}" else "Current: .../${defaultPath.takeLast(30)}"
        } else "No default folder selected"
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
            title = "Remember Organized Media",
            description = "Skip showing media you've already moved in future sessions",
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
                Text("Reset All Sorted Media History")
            }
        }
    }
}

@Composable
private fun ForgetSortedMediaSetting(viewModel: SettingsViewModel) {
    Column {
        Text("Forget Sorted Media in Folder", style = MaterialTheme.typography.titleMedium)
        Text(
            "Choose a folder to make its media appear for sorting again.",
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
            Text("Select Folder to Forget")
        }
    }
}

private fun getSwipeSensitivityDisplayName(sensitivity: SwipeSensitivity): String {
    return when (sensitivity) {
        SwipeSensitivity.LOW -> "Low"
        SwipeSensitivity.MEDIUM -> "Medium"
        SwipeSensitivity.HIGH -> "High"
    }
}

private fun getSwipeDownActionDisplayName(action: SwipeDownAction): String {
    return when (action) {
        SwipeDownAction.NONE -> "None"
        SwipeDownAction.MOVE_TO_EDIT -> "Move to 'To Edit'"
        SwipeDownAction.SKIP_ITEM -> "Skip Item"
        SwipeDownAction.ADD_TARGET_FOLDER -> "Add Target Folder"
        SwipeDownAction.SHARE -> "Share"
        SwipeDownAction.OPEN_WITH -> "Open With..."
    }
}

private fun getSimilarityLevelDisplayName(level: SimilarityThresholdLevel): String {
    return when (level) {
        SimilarityThresholdLevel.STRICT -> "Strict"
        SimilarityThresholdLevel.BALANCED -> "Balanced"
        SimilarityThresholdLevel.LOOSE -> "Loose"
    }
}

private fun getSimilarityLevelDescription(level: SimilarityThresholdLevel): String {
    return when (level) {
        SimilarityThresholdLevel.STRICT -> "Fewer matches, very low chance of false positives."
        SimilarityThresholdLevel.BALANCED -> "A good balance between accuracy and number of matches."
        SimilarityThresholdLevel.LOOSE -> "More matches, but may include unrelated media."
    }
}

private fun getScanScopeDisplayName(scope: DuplicateScanScope): String {
    return when (scope) {
        DuplicateScanScope.ALL_FILES -> "Scan All Files"
        DuplicateScanScope.INCLUDE_LIST -> "Include Specific Folders"
        DuplicateScanScope.EXCLUDE_LIST -> "Exclude Specific Folders"
    }
}

private fun getScanScopeDescription(
    scope: DuplicateScanScope,
    includeList: Set<String>,
    excludeList: Set<String>
): String {
    return when (scope) {
        DuplicateScanScope.ALL_FILES -> "Scan all media on your device."
        DuplicateScanScope.INCLUDE_LIST -> "Only scan media within the ${includeList.size} selected folders."
        DuplicateScanScope.EXCLUDE_LIST -> "Scan all media except for the ${excludeList.size} selected folders."
    }
}


private fun getFolderNameLayoutDisplayName(layout: FolderNameLayout): String {
    return when (layout) {
        FolderNameLayout.ABOVE -> "Above Media Card"
        FolderNameLayout.BELOW -> "Below Media Card"
        FolderNameLayout.HIDDEN -> "Hidden"
    }
}

private fun getThemeDisplayName(theme: AppTheme): String {
    return when (theme) {
        AppTheme.SYSTEM -> "Follow System"
        AppTheme.LIGHT -> "Light"
        AppTheme.DARK -> "Dark"
        AppTheme.DARKER -> "Darker"
        AppTheme.AMOLED -> "AMOLED"
    }
}

private fun getThemeDescription(theme: AppTheme): String {
    return when (theme) {
        AppTheme.SYSTEM -> "Automatically switch themes based on system settings"
        AppTheme.LIGHT -> "A bright, light-colored theme"
        AppTheme.DARK -> "A dark theme for low-light environments"
        AppTheme.DARKER -> "A darker theme following Material Design standards"
        AppTheme.AMOLED -> "A pure black theme optimized for AMOLED displays"
    }
}

private fun getFolderSelectionModeDisplayName(mode: FolderSelectionMode): String {
    return when (mode) {
        FolderSelectionMode.ALL -> "All Folders"
        FolderSelectionMode.REMEMBER -> "Remember Previous"
        FolderSelectionMode.NONE -> "None"
    }
}

private fun getFolderSelectionModeDescription(mode: FolderSelectionMode): String {
    return when (mode) {
        FolderSelectionMode.ALL -> "Select all folders by default"
        FolderSelectionMode.REMEMBER -> "Remember and select previously chosen folders"
        FolderSelectionMode.NONE -> "Don't select any folders by default"
    }
}

private fun getAddFolderFocusTargetDisplayName(target: AddFolderFocusTarget): String {
    return when (target) {
        AddFolderFocusTarget.SEARCH_PATH -> "Search Path"
        AddFolderFocusTarget.FOLDER_NAME -> "New Folder Name"
        AddFolderFocusTarget.NONE -> "None"
    }
}

private fun getUnselectAllScopeDisplayName(scope: UnselectScanScope): String {
    return when (scope) {
        UnselectScanScope.GLOBAL -> "Unselect Everything"
        UnselectScanScope.VISIBLE_ONLY -> "Unselect Visible Only"
    }
}

private fun getUnselectAllScopeDescription(scope: UnselectScanScope): String {
    return when (scope) {
        UnselectScanScope.GLOBAL -> "Clears the entire selection, even items hidden by the current search filter."
        UnselectScanScope.VISIBLE_ONLY -> "Only unselects items currently visible in the search results. Hidden selections remain selected."
    }
}
