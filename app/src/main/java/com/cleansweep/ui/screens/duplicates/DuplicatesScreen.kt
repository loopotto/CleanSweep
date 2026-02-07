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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cleansweep.R
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.DuplicateScanScope
import com.cleansweep.domain.model.DuplicateGroup
import com.cleansweep.domain.model.ScanResultGroup
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.ui.components.FastScrollbar
import com.cleansweep.util.rememberIsUsingGestureNavigation
import kotlinx.coroutines.launch
import java.io.File

private const val ZOOM_IN_THRESHOLD = 1.5f
private const val ZOOM_OUT_THRESHOLD = 0.75f
private const val MIN_VISUAL_SCALE = 0.5f
private const val MAX_VISUAL_SCALE = 2.0f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    viewModel: DuplicatesViewModel,
    onNavigateUp: () -> Unit,
    onNavigateToGroup: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showConfirmDeleteDialog by remember { mutableStateOf(false) }
    var showConfirmDeleteAllExactDialog by remember { mutableStateOf(false) }
    val displayedUnscannableFiles by viewModel.displayedUnscannableFiles.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.startScan()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.notification_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val startScanWithPermissionCheck = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                PackageManager.PERMISSION_GRANTED -> viewModel.startScan()
                else -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.startScan()
        }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.toastMessageShown()
        }
    }

    if (showConfirmDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDeleteDialog = false },
            title = { Text(stringResource(R.string.confirm_deletion_title)) },
            text = { Text(stringResource(R.string.confirm_deletion_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSelectedFiles()
                        showConfirmDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showConfirmDeleteAllExactDialog) {
        var doNotAskAgain by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showConfirmDeleteAllExactDialog = false },
            title = { Text(stringResource(R.string.delete_all_exact_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_all_exact_body))
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { doNotAskAgain = !doNotAskAgain },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = doNotAskAgain,
                            onCheckedChange = { doNotAskAgain = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.do_not_ask_again))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (doNotAskAgain) {
                            viewModel.setShowConfirmDeleteAllExact(false)
                        }
                        viewModel.deleteAllExactDuplicates()
                        showConfirmDeleteAllExactDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteAllExactDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (uiState.showUnscannableFilesDialog) {
        UnscannableFilesDialog(
            filePaths = displayedUnscannableFiles,
            totalUnscannableCount = uiState.unscannableFiles.size,
            showHidden = uiState.showHiddenUnscannableFiles,
            onToggleShowHidden = viewModel::toggleShowHiddenUnscannableFiles,
            onDismiss = viewModel::hideUnscannableFiles
        )
    }
    val title = when (uiState.scanState) {
        ScanState.Idle -> stringResource(R.string.duplicate_finder_title)
        ScanState.Scanning, ScanState.Cancelling -> stringResource(R.string.scanning_phase)
        ScanState.Complete -> pluralStringResource(R.plurals.scan_result_title, uiState.resultGroups.size, uiState.resultGroups.size)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.navigate_back)) } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                        }
                    }
                },
                actions = {
                    if (uiState.scanState == ScanState.Complete) {
                        val hasExactDuplicates = uiState.resultGroups.any { it is DuplicateGroup }
                        if (hasExactDuplicates) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text(stringResource(R.string.delete_all_exact_duplicates)) } },
                                state = rememberTooltipState()
                            ) {
                                IconButton(onClick = {
                                    if (uiState.showConfirmDeleteAllExact) {
                                        showConfirmDeleteAllExactDialog = true
                                    } else {
                                        viewModel.deleteAllExactDuplicates()
                                    }
                                }) {
                                    Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.delete_all_exact_duplicates), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(R.string.new_scan)) } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(onClick = viewModel::resetToIdle) {
                                Icon(Icons.Outlined.Cached, contentDescription = stringResource(R.string.new_scan))
                            }
                        }
                    }
                    if (uiState.resultViewMode == ResultViewMode.GRID && uiState.scanState == ScanState.Complete) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(R.string.change_grid_columns)) } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(onClick = viewModel::cycleGridViewZoom) {
                                Icon(Icons.Outlined.ZoomIn, contentDescription = stringResource(R.string.change_grid_columns))
                            }
                        }
                    }
                    if (uiState.scanState == ScanState.Complete && uiState.resultGroups.isNotEmpty()) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(R.string.toggle_view_mode)) } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(onClick = viewModel::toggleResultViewMode) {
                                Icon(
                                    imageVector = if (uiState.resultViewMode == ResultViewMode.LIST) Icons.Outlined.ViewModule else Icons.AutoMirrored.Outlined.ViewList,
                                    contentDescription = stringResource(R.string.toggle_view_mode)
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.scanState == ScanState.Complete && uiState.resultGroups.isNotEmpty()) {
                BottomActionBar(
                    spaceToReclaim = uiState.spaceToReclaim,
                    selectedCount = uiState.selectedForDeletion.size,
                    isDeleting = uiState.isDeleting,
                    onDeleteClick = {
                        if (uiState.selectedForDeletion.isNotEmpty()) {
                            showConfirmDeleteDialog = true
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when (uiState.scanState) {
                ScanState.Idle -> IdleView(
                    scanForExact = uiState.scanForExactDuplicates,
                    scanForSimilar = uiState.scanForSimilarMedia,
                    hasRunOnce = uiState.hasRunDuplicateScanOnce,
                    canLoadFromCache = uiState.canLoadFromCache,
                    scanScope = uiState.scanScope,
                    includeList = uiState.includeList,
                    excludeList = uiState.excludeList,
                    onToggleExact = viewModel::toggleScanForExactDuplicates,
                    onToggleSimilar = viewModel::toggleScanForSimilarMedia,
                    onStartScan = startScanWithPermissionCheck,
                    onLoadCachedResults = { viewModel.loadPersistedResults(isFallback = false) },
                    onNavigateToSettings = onNavigateToSettings
                )
                ScanState.Scanning,
                ScanState.Cancelling -> {
                    if (uiState.resultGroups.isNotEmpty()) {
                        // Background scan view (showing old results)
                        ResultsView(
                            uiState = uiState,
                            viewModel = viewModel,
                            onViewGroup = { onNavigateToGroup(it.uniqueId) },
                            startScanWithPermissionCheck = startScanWithPermissionCheck
                        )
                    } else {
                        // Initial scan view
                        ScanningView(
                            progress = uiState.scanProgress,
                            phase = uiState.scanProgressPhase,
                            onCancelScan = viewModel::cancelScan
                        )
                    }
                }
                ScanState.Complete -> ResultsView(
                    uiState = uiState,
                    viewModel = viewModel,
                    onViewGroup = { onNavigateToGroup(it.uniqueId) },
                    startScanWithPermissionCheck = startScanWithPermissionCheck
                )
            }
        }
    }
}

// =====================================================================================
// Group Details Screen
// =====================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailsScreen(
    viewModel: DuplicatesViewModel,
    groupId: String,
    onNavigateUp: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val group = uiState.detailedGroup
    val context = LocalContext.current

    LaunchedEffect(groupId) {
        viewModel.prepareForGroupDetailView(groupId)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearGroupDetailView()
        }
    }

    BackHandler {
        onNavigateUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (group != null) pluralStringResource(R.plurals.view_group_items_title, group.items.size, group.items.size) else stringResource(R.string.view_group_title)) },
                navigationIcon = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.back)) } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (group != null) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(R.string.change_layout_columns)) } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(onClick = viewModel::cycleDetailViewZoom) {
                                Icon(Icons.Outlined.ZoomIn, contentDescription = stringResource(R.string.change_layout_columns))
                            }
                        }
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text(stringResource(R.string.more_options)) } },
                                state = rememberTooltipState()
                            ) {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                                }
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                val groupIds = group.items.map { it.id }.toSet()
                                val selectedInGroup = uiState.selectedForDeletion.intersect(groupIds)
                                val isAllSelected = selectedInGroup.size == groupIds.size

                                DropdownMenuItem(
                                    text = { Text(if (isAllSelected) stringResource(R.string.unselect_all) else stringResource(R.string.select_all)) },
                                    onClick = {
                                        viewModel.toggleSelectAllInGroup(group)
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keep_oldest)) },
                                    onClick = {
                                        viewModel.selectAllButOldest(group)
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.keep_newest)) },
                                    onClick = {
                                        viewModel.selectAllButNewest(group)
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (group != null) {
                BottomActionBar(
                    spaceToReclaim = uiState.spaceToReclaim,
                    selectedCount = uiState.selectedForDeletion.size,
                    isDeleting = uiState.isDeleting,
                    onDeleteClick = onNavigateUp,
                    actionButtonText = stringResource(R.string.done),
                    actionButtonIcon = Icons.Default.Check
                )
            }
        }
    ) { paddingValues ->
        if (group == null) {
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            var scale by remember { mutableFloatStateOf(1f) }
            val coroutineScope = rememberCoroutineScope()
            val gridState = rememberLazyGridState()

            LaunchedEffect(uiState.detailViewColumnCount) {
                if (scale != 1f) {
                    coroutineScope.launch {
                        animate(scale, 1f) { value, _ -> scale = value }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Box(
                    modifier = Modifier
                        .pointerInput(uiState.detailViewColumnCount) {
                            forEachGesture {
                                awaitPointerEventScope {
                                    var gestureZoom = 1f
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        // Only process zoom if there is more than one pointer
                                        if (event.changes.size > 1) {
                                            val zoom = event.calculateZoom()
                                            scale *= zoom
                                            scale = scale.coerceIn(MIN_VISUAL_SCALE, MAX_VISUAL_SCALE)
                                            gestureZoom *= zoom

                                            if (gestureZoom > ZOOM_IN_THRESHOLD) {
                                                val newCount = (uiState.detailViewColumnCount - 1).coerceIn(1, 3)
                                                if (newCount != uiState.detailViewColumnCount) {
                                                    viewModel.setDetailViewColumnCount(newCount)
                                                    gestureZoom = 1f
                                                }
                                            } else if (gestureZoom < ZOOM_OUT_THRESHOLD) {
                                                val newCount = (uiState.detailViewColumnCount + 1).coerceIn(1, 3)
                                                if (newCount != uiState.detailViewColumnCount) {
                                                    viewModel.setDetailViewColumnCount(newCount)
                                                    gestureZoom = 1f
                                                }
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    coroutineScope.launch {
                                        animate(scale, 1f) { value, _ -> scale = value }
                                    }
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                ) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(uiState.detailViewColumnCount),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(group.items, key = { "detail-${it.id}" }) { item ->
                            DetailImageCard(
                                item = item,
                                isSelected = item.id in uiState.selectedForDeletion,
                                onToggleSelection = { viewModel.toggleSelection(item) },
                                onOpenFile = {
                                    val intent = viewModel.getOpenFileIntent(item)
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.no_app_to_open),
                                                Toast.LENGTH_LONG
                                            )
                                            .show()
                                    }
                                }
                            )
                        }
                    }
                }
                FastScrollbar(
                    state = gridState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                )
            }
        }
    }
}

// =====================================================================================
// Common Private Composables
// =====================================================================================

@Composable
private fun IdleView(
    scanForExact: Boolean,
    scanForSimilar: Boolean,
    hasRunOnce: Boolean,
    canLoadFromCache: Boolean,
    scanScope: DuplicateScanScope,
    includeList: Set<String>,
    excludeList: Set<String>,
    onToggleExact: () -> Unit,
    onToggleSimilar: () -> Unit,
    onStartScan: () -> Unit,
    onLoadCachedResults: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier
        .fillMaxWidth()
        .padding(24.dp)
        .verticalScroll(rememberScrollState())
    ) {
        ScanScopeInfoCard(
            scanScope = scanScope,
            includeList = includeList,
            excludeList = excludeList,
            onNavigateToSettings = onNavigateToSettings
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column {
                ScanTypeSelectorRow(
                    stringResource(R.string.scan_exact_duplicates_title),
                    stringResource(R.string.scan_exact_duplicates_desc),
                    Icons.Outlined.ContentCopy,
                    scanForExact,
                    onToggleExact
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = DividerDefaults.Thickness,
                    color = DividerDefaults.color
                )
                ScanTypeSelectorRow(
                    stringResource(R.string.scan_similar_media_title),
                    stringResource(R.string.scan_similar_media_desc),
                    Icons.Outlined.PhotoLibrary,
                    scanForSimilar,
                    onToggleSimilar
                )
            }
        }

        if (!hasRunOnce) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.info_icon_desc), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.scan_first_time_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onStartScan,
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(50.dp)
        ) {
            Text(stringResource(R.string.start_scan))
        }

        if (canLoadFromCache) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onLoadCachedResults,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text(stringResource(R.string.view_last_scan_results))
            }
        }
    }
}

@Composable
fun ScanScopeInfoCard(
    scanScope: DuplicateScanScope,
    includeList: Set<String>,
    excludeList: Set<String>,
    onNavigateToSettings: () -> Unit
) {
    AnimatedVisibility(visible = scanScope != DuplicateScanScope.ALL_FILES) {
        var expanded by remember { mutableStateOf(false) }

        val (title, list) = if (scanScope == DuplicateScanScope.INCLUDE_LIST) {
            pluralStringResource(R.plurals.scan_limited_to_folders, includeList.size, includeList.size) to includeList
        } else {
            pluralStringResource(R.plurals.scan_ignores_folders, excludeList.size, excludeList.size) to excludeList
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = stringResource(R.string.scan_filter_active),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(Modifier.padding(top = 12.dp)) {
                        HorizontalDivider(
                            modifier = Modifier.padding(bottom = 12.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f)
                        )
                        if (list.isEmpty()) {
                            Text(
                                stringResource(R.string.no_folders_in_list),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        } else {
                            list.take(5).forEach { path ->
                                Text(
                                    text = "• .../${path.takeLast(35)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (list.size > 5) {
                                Text(
                                    text = pluralStringResource(R.plurals.list_more_items_suffix, list.size - 5, list.size - 5),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                        TextButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(stringResource(R.string.manage))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanTypeSelectorRow(title: String, description: String, icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .selectable(selected = isSelected, onClick = onClick, role = Role.Checkbox)
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Checkbox(checked = isSelected, onCheckedChange = { onClick() })
    }
}

@Composable
private fun ScanningView(progress: Float, phase: String?, onCancelScan: () -> Unit) {
    val isCancellable = phase != stringResource(R.string.scanning_preparing_phase)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
        Text(phase ?: stringResource(R.string.scanning_phase), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(
            onClick = onCancelScan,
            enabled = isCancellable
        ) {
            Text(stringResource(R.string.cancel_scan))
        }
    }
}

@Composable
private fun ResultsView(
    uiState: DuplicatesUiState,
    viewModel: DuplicatesViewModel,
    onViewGroup: (ScanResultGroup) -> Unit,
    startScanWithPermissionCheck: () -> Unit
) {
    val context = LocalContext.current
    val showEmptyMessage = uiState.resultGroups.isEmpty() && (uiState.unscannableFiles.isEmpty() || !uiState.showUnscannableSummaryCard)

    Column(Modifier.fillMaxSize()) {
        if (uiState.scanState == ScanState.Scanning) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(uiState.scanProgressPhase ?: stringResource(R.string.scanning_phase), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { uiState.scanProgress }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (showEmptyMessage) {
            val message = when {
                uiState.scanForExactDuplicates && uiState.scanForSimilarMedia -> stringResource(R.string.no_exact_or_similar_found)
                uiState.scanForExactDuplicates -> stringResource(R.string.no_exact_found)
                uiState.scanForSimilarMedia -> stringResource(R.string.no_similar_found)
                else -> stringResource(R.string.no_results_found)
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(message, modifier = Modifier.padding(16.dp))
            }
        } else {
            when (uiState.resultViewMode) {
                ResultViewMode.LIST -> ListView(
                    uiState = uiState,
                    onToggleSelection = viewModel::toggleSelection,
                    onToggleSelectAll = viewModel::toggleSelectAllInGroup,
                    onSelectAllButOldest = viewModel::selectAllButOldest,
                    onSelectAllButNewest = viewModel::selectAllButNewest,
                    onHideGroup = viewModel::hideGroup,
                    onFlagAsIncorrect = viewModel::flagAsIncorrect,
                    onOpenFile = { mediaItem ->
                        val intent = viewModel.getOpenFileIntent(mediaItem)
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, context.getString(R.string.no_app_to_open), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShowUnscannableFiles = viewModel::showUnscannableFiles,
                    onDismissUnscannableSummary = viewModel::dismissUnscannableSummaryCard,
                    onDismissStaleResultsBanner = viewModel::dismissStaleResultsBanner,
                    onRescan = startScanWithPermissionCheck
                )
                ResultViewMode.GRID -> GridView(
                    uiState = uiState,
                    onViewGroup = onViewGroup,
                    onColumnCountChange = viewModel::setGridViewColumnCount,
                    onToggleSelectAll = viewModel::toggleSelectAllInGroup,
                    onSelectAllButOldest = viewModel::selectAllButOldest,
                    onSelectAllButNewest = viewModel::selectAllButNewest,
                    onHideGroup = viewModel::hideGroup,
                    onFlagAsIncorrect = viewModel::flagAsIncorrect,
                    onShowUnscannableFiles = viewModel::showUnscannableFiles,
                    onDismissUnscannableSummary = viewModel::dismissUnscannableSummaryCard,
                    onDismissStaleResultsBanner = viewModel::dismissStaleResultsBanner,
                    onRescan = startScanWithPermissionCheck
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StaleResultsWarningCard(
    timestamp: Long,
    onDismiss: () -> Unit,
    onRescan: () -> Unit
) {
    val formattedDate = remember(timestamp) {
        DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = stringResource(R.string.warning),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = stringResource(R.string.stale_results_warning, formattedDate),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = stringResource(R.string.run_new_scan_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(stringResource(R.string.dismiss_warning)) } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.dismiss_warning),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
            TextButton(
                onClick = onRescan,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 8.dp, bottom = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ),
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
            ) {
                Text(stringResource(R.string.rescan))
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnscannableFilesSummaryCard(
    count: Int,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = stringResource(R.string.warning),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    val countText = pluralStringResource(R.plurals.unreadable_files_count_plurals, count, count)
                    Text(
                        text = countText,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = stringResource(R.string.unreadable_files_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(stringResource(R.string.dismiss_warning)) } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dismiss_warning),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun UnscannableFilesDialog(
    filePaths: List<String>,
    totalUnscannableCount: Int,
    showHidden: Boolean,
    onToggleShowHidden: () -> Unit,
    onDismiss: () -> Unit
) {
    val groupedFiles = remember(filePaths) {
        filePaths.groupBy { File(it).parent ?: "Unknown Location" }
    }
    val unknownLocation = stringResource(R.string.unknown_location)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unreadable_files_dialog_title)) },
        text = {
            Column {
                if (filePaths.isEmpty() && totalUnscannableCount > 0) {
                    Text(
                        text = stringResource(R.string.no_unreadable_user_files),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (filePaths.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_unreadable_files_device),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                ) {
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
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun ListView(
    uiState: DuplicatesUiState,
    onToggleSelection: (MediaItem) -> Unit,
    onToggleSelectAll: (ScanResultGroup) -> Unit,
    onSelectAllButOldest: (ScanResultGroup) -> Unit,
    onSelectAllButNewest: (ScanResultGroup) -> Unit,
    onHideGroup: (ScanResultGroup) -> Unit,
    onFlagAsIncorrect: (ScanResultGroup) -> Unit,
    onOpenFile: (MediaItem) -> Unit,
    onShowUnscannableFiles: () -> Unit,
    onDismissUnscannableSummary: () -> Unit,
    onDismissStaleResultsBanner: () -> Unit,
    onRescan: () -> Unit
) {
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp), // Keep horizontal padding in cards
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // This Box ensures the padding doesn't compound with LazyColumn's arrangement spacing
                Box {
                    if (uiState.staleResultsInfo != null && !uiState.staleResultsInfo.isDismissed && uiState.scanState != ScanState.Scanning) {
                        StaleResultsWarningCard(
                            timestamp = uiState.staleResultsInfo.timestamp,
                            onDismiss = onDismissStaleResultsBanner,
                            onRescan = onRescan
                        )
                    }
                }
            }

            if (uiState.showUnscannableSummaryCard) {
                item {
                    UnscannableFilesSummaryCard(
                        count = uiState.nonHiddenUnscannableFilesCount,
                        onClick = onShowUnscannableFiles,
                        onDismiss = onDismissUnscannableSummary
                    )
                }
            }

            items(uiState.resultGroups, key = { it.uniqueId }) { group ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    when (group) {
                        is DuplicateGroup -> DuplicateGroupCard(group, uiState.selectedForDeletion, onToggleSelection, onToggleSelectAll, onSelectAllButOldest, onSelectAllButNewest, onHideGroup, onOpenFile)
                        is SimilarGroup -> SimilarMediaGroupCard(group, uiState.selectedForDeletion, onToggleSelection, onToggleSelectAll, onSelectAllButOldest, onSelectAllButNewest, onHideGroup, onFlagAsIncorrect, onOpenFile)
                    }
                }
            }
        }
        FastScrollbar(
            state = listState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
        )
    }
}

@Composable
private fun GridView(
    uiState: DuplicatesUiState,
    onViewGroup: (ScanResultGroup) -> Unit,
    onColumnCountChange: (Int) -> Unit,
    onToggleSelectAll: (ScanResultGroup) -> Unit,
    onSelectAllButOldest: (ScanResultGroup) -> Unit,
    onSelectAllButNewest: (ScanResultGroup) -> Unit,
    onHideGroup: (ScanResultGroup) -> Unit,
    onFlagAsIncorrect: (ScanResultGroup) -> Unit,
    onShowUnscannableFiles: () -> Unit,
    onDismissUnscannableSummary: () -> Unit,
    onDismissStaleResultsBanner: () -> Unit,
    onRescan: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    val coroutineScope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    LaunchedEffect(uiState.gridViewColumnCount) {
        if (scale != 1f) {
            coroutineScope.launch {
                animate(scale, 1f) { value, _ -> scale = value }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .pointerInput(uiState.gridViewColumnCount) {
                    forEachGesture {
                        awaitPointerEventScope {
                            var gestureZoom = 1f
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                // Only process zoom if there is more than one pointer
                                if (event.changes.size > 1) {
                                    val zoom = event.calculateZoom()
                                    scale *= zoom
                                    scale = scale.coerceIn(MIN_VISUAL_SCALE, MAX_VISUAL_SCALE)
                                    gestureZoom *= zoom

                                    if (gestureZoom > ZOOM_IN_THRESHOLD) {
                                        val newCount = (uiState.gridViewColumnCount - 1).coerceIn(2, 4)
                                        if (newCount != uiState.gridViewColumnCount) {
                                            onColumnCountChange(newCount)
                                            gestureZoom = 1f // Reset logical zoom
                                        }
                                    } else if (gestureZoom < ZOOM_OUT_THRESHOLD) {
                                        val newCount = (uiState.gridViewColumnCount + 1).coerceIn(2, 4)
                                        if (newCount != uiState.gridViewColumnCount) {
                                            onColumnCountChange(newCount)
                                            gestureZoom = 1f // Reset logical zoom
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })

                            coroutineScope.launch {
                                animate(scale, 1f) { value, _ -> scale = value }
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(uiState.gridViewColumnCount),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(span = { GridItemSpan(uiState.gridViewColumnCount) }) {
                    Column {
                        if (uiState.staleResultsInfo != null && !uiState.staleResultsInfo.isDismissed && uiState.scanState != ScanState.Scanning) {
                            Box(modifier = Modifier.padding(bottom = 8.dp, start = 8.dp, end = 8.dp)) {
                                StaleResultsWarningCard(
                                    timestamp = uiState.staleResultsInfo.timestamp,
                                    onDismiss = onDismissStaleResultsBanner,
                                    onRescan = onRescan
                                )
                            }
                        }
                        if (uiState.showUnscannableSummaryCard) {
                            Box(modifier = Modifier.padding(bottom = 8.dp, start = 8.dp, end = 8.dp)) {
                                UnscannableFilesSummaryCard(
                                    count = uiState.nonHiddenUnscannableFilesCount,
                                    onClick = onShowUnscannableFiles,
                                    onDismiss = onDismissUnscannableSummary
                                )
                            }
                        }
                    }
                }

                items(uiState.resultGroups, key = { "grid-${it.uniqueId}" }) { group ->
                    GridGroupCard(
                        group = group,
                        selectedIds = uiState.selectedForDeletion,
                        onCardClick = onViewGroup,
                        onToggleSelectAll = onToggleSelectAll,
                        onSelectAllButOldest = onSelectAllButOldest,
                        onSelectAllButNewest = onSelectAllButNewest,
                        onHideGroup = onHideGroup,
                        onFlagAsIncorrect = onFlagAsIncorrect
                    )
                }
            }
        }
        FastScrollbar(
            state = gridState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
        )
    }
}

private enum class SelectionState { NONE, PARTIAL, ALL }

@Composable
private fun GridGroupCard(
    group: ScanResultGroup,
    selectedIds: Set<String>,
    onCardClick: (ScanResultGroup) -> Unit,
    onToggleSelectAll: (ScanResultGroup) -> Unit,
    onSelectAllButOldest: (ScanResultGroup) -> Unit,
    onSelectAllButNewest: (ScanResultGroup) -> Unit,
    onHideGroup: (ScanResultGroup) -> Unit,
    onFlagAsIncorrect: (ScanResultGroup) -> Unit
) {
    val groupIds = remember(group.items) { group.items.map { it.id }.toSet() }
    val selectedInGroup = remember(selectedIds, groupIds) { selectedIds.intersect(groupIds) }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val isAllSelected = selectedInGroup.size == groupIds.size

    val selectionState = when {
        selectedInGroup.isEmpty() -> SelectionState.NONE
        isAllSelected -> SelectionState.ALL
        else -> SelectionState.PARTIAL
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(group) {
                    detectTapGestures(
                        onTap = { onCardClick(group) },
                        onLongPress = { showMenu = true }
                    )
                }
        ) {
            val imageRequest = remember(group.items.first().uri) {
                ImageRequest.Builder(context)
                    .data(group.items.first().uri)
                    .size(256) // Request a small thumbnail for the grid view
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = stringResource(R.string.primary_image_desc),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            if (group.items.first().isVideo) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = stringResource(R.string.video),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                        .padding(4.dp)
                        .size(16.dp)
                )
            }

            val groupInfoText = when (group) {
                is DuplicateGroup -> pluralStringResource(R.plurals.copies_count_suffix, group.items.size - 1, group.items.size - 1)
                is SimilarGroup -> pluralStringResource(R.plurals.similar_count_suffix, group.items.size - 1, group.items.size - 1)
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                shape = MaterialTheme.shapes.small,
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = groupInfoText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            val (overlayColor, icon) = when (selectionState) {
                SelectionState.ALL -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) to Icons.Filled.CheckCircle
                SelectionState.PARTIAL -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f) to null
                SelectionState.NONE -> Color.Transparent to null
            }
            
            if (selectionState != SelectionState.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(overlayColor),
                    contentAlignment = Alignment.Center
                ) {
                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = stringResource(R.string.selection_state),
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (isAllSelected) stringResource(R.string.unselect_all) else stringResource(R.string.select_all)) },
                    onClick = { onToggleSelectAll(group); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keep_oldest)) },
                    onClick = { onSelectAllButOldest(group); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.keep_newest)) },
                    onClick = { onSelectAllButNewest(group); showMenu = false }
                )
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                if (group is SimilarGroup) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.flag_as_incorrect)) },
                        onClick = { onFlagAsIncorrect(group); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.NotInterested, contentDescription = null) }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.hide_group)) },
                    onClick = { onHideGroup(group); showMenu = false },
                    leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedIds: Set<String>,
    onToggleSelection: (MediaItem) -> Unit,
    onToggleSelectAll: (ScanResultGroup) -> Unit,
    onSelectAllButOldest: (ScanResultGroup) -> Unit,
    onSelectAllButNewest: (ScanResultGroup) -> Unit,
    onHideGroup: (ScanResultGroup) -> Unit,
    onOpenFile: (MediaItem) -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val groupIds = group.items.map { it.id }.toSet()
    val selectedInGroup = selectedIds.intersect(groupIds)
    val isAllSelected = selectedInGroup.size == groupIds.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(pluralStringResource(R.plurals.duplicates_found_count, group.items.size, group.items.size), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.size_per_file, android.text.format.Formatter.formatShortFileSize(context, group.sizePerFile)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.more_options)) } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hide_group)) },
                            onClick = { onHideGroup(group); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            group.items.forEach { item ->
                MediaItemRow(item, item.id in selectedIds, { onToggleSelection(item) }, onOpenFile = { onOpenFile(item) })
                HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = DividerDefaults.Thickness, color = DividerDefaults.color)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSelectAllButOldest(group) },
                    Modifier
                        .weight(0.8f)
                        .wrapContentHeight()
                        .heightIn(min = 58.dp)
                ) {
                    Text(stringResource(R.string.keep_oldest))
                }

                OutlinedButton(
                    onClick = { onSelectAllButNewest(group) },
                    Modifier
                        .weight(0.8f)
                        .wrapContentHeight()
                        .heightIn(min = 58.dp)
                ) {
                    Text(stringResource(R.string.keep_newest))
                }

                OutlinedButton(
                    onClick = { onToggleSelectAll(group) },
                    Modifier
                        .weight(1f)
                        .wrapContentHeight()
                        .heightIn(min = 58.dp)
                ) {
                    Text(if (isAllSelected) stringResource(R.string.unselect_all) else stringResource(R.string.select_all))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimilarMediaGroupCard(
    group: SimilarGroup,
    selectedIds: Set<String>,
    onToggleSelection: (MediaItem) -> Unit,
    onToggleSelectAll: (ScanResultGroup) -> Unit,
    onSelectAllButOldest: (ScanResultGroup) -> Unit,
    onSelectAllButNewest: (ScanResultGroup) -> Unit,
    onHideGroup: (ScanResultGroup) -> Unit,
    onFlagAsIncorrect: (ScanResultGroup) -> Unit,
    onOpenFile: (MediaItem) -> Unit
) {
    val totalSize = remember(group.items) { group.items.sumOf { it.size } }
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val groupIds = group.items.map { it.id }.toSet()
    val selectedInGroup = selectedIds.intersect(groupIds)
    val isAllSelected = selectedInGroup.size == groupIds.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(pluralStringResource(R.plurals.similar_media_found_count, group.items.size, group.items.size), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.total_group_size, android.text.format.Formatter.formatShortFileSize(context, totalSize)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(stringResource(R.string.more_options)) } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.flag_as_incorrect)) },
                            onClick = { onFlagAsIncorrect(group); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.NotInterested, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.hide_group)) },
                            onClick = { onHideGroup(group); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            group.items.forEach { item ->
                MediaItemRow(item, item.id in selectedIds, { onToggleSelection(item) }, showFileSize = true, onOpenFile = { onOpenFile(item) })
                HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = DividerDefaults.Thickness, color = DividerDefaults.color)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSelectAllButOldest(group) },
                    Modifier
                        .weight(0.8f)
                        .wrapContentHeight()
                        .heightIn(min = 58.dp)
                ) {
                    Text(stringResource(R.string.keep_oldest))
                }

                OutlinedButton(
                    onClick = { onSelectAllButNewest(group) },
                    Modifier
                        .weight(0.8f)
                        .wrapContentHeight()
                        .heightIn(min = 58.dp)
                ) {
                    Text(stringResource(R.string.keep_newest))
                }

                OutlinedButton(
                    onClick = { onToggleSelectAll(group) },
                    Modifier
                        .weight(1f)
                        .wrapContentHeight()
                        .heightIn(min = 58.dp)
                ) {
                    Text(if (isAllSelected) stringResource(R.string.unselect_all) else stringResource(R.string.select_all))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaItemRow(
    item: MediaItem,
    isSelected: Boolean,
    onToggle: () -> Unit,
    showFileSize: Boolean = false,
    onOpenFile: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggle() },
                    onLongPress = { showMenu = true }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        Box(contentAlignment = Alignment.Center) {
            val imageRequest = remember(item.uri) {
                ImageRequest.Builder(context)
                    .data(item.uri)
                    .size(128)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = item.displayName,
                modifier = Modifier.size(48.dp),
                contentScale = ContentScale.Crop
            )
            if (item.isVideo) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = stringResource(R.string.video),
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.extraSmall)
                        .padding(2.dp)
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open)) },
                    onClick = { onOpenFile(); showMenu = false },
                    leadingIcon = { Icon(Icons.Default.OpenInFull, null) }
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("...${File(item.id).parent?.takeLast(30) ?: ""}/${item.displayName}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (showFileSize) {
                Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, item.size), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun BottomActionBar(
    spaceToReclaim: Long,
    selectedCount: Int,
    isDeleting: Boolean,
    onDeleteClick: () -> Unit,
    actionButtonText: String = stringResource(R.string.delete),
    actionButtonIcon: ImageVector = Icons.Default.Delete
) {
    val context = LocalContext.current
    val isGestureMode = rememberIsUsingGestureNavigation()
    Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = if (isGestureMode) 0.dp else 4.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reclaim_space_format, android.text.format.Formatter.formatShortFileSize(context, spaceToReclaim)), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                val fileCountText = pluralStringResource(R.plurals.files_selected_count_plurals, selectedCount, selectedCount)
                Text(stringResource(R.string.files_selected_format, fileCountText), style = MaterialTheme.typography.bodySmall)            }
            Button(onClick = onDeleteClick, enabled = !isDeleting && (selectedCount > 0 || actionButtonText != stringResource(R.string.delete))) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(actionButtonIcon, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(actionButtonText)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailImageCard(
    item: MediaItem,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onOpenFile: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(item) {
                detectTapGestures(
                    onTap = { onToggleSelection() },
                    onLongPress = { showMenu = true }
                )
            },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 8.dp else 2.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val imageRequest = remember(item.uri) {
                ImageRequest.Builder(context)
                    .data(item.uri)
                    .size(512) // Request a decent size for the detail view
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                )
            )

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_full_screen)) },
                    onClick = {
                        onOpenFile()
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.OpenInFull, contentDescription = null) }
                )
            }

            val file = remember { File(item.id) }
            val compactPath = remember { "${file.parentFile?.name ?: "..."}/${file.name}" }

            if (item.isVideo) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = stringResource(R.string.video),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                        .padding(4.dp)
                        .size(16.dp)
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.6f)
                    .padding(8.dp),
                shape = MaterialTheme.shapes.small,
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = compactPath,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                shape = MaterialTheme.shapes.small,
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = android.text.format.Formatter.formatShortFileSize(context, item.size),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
