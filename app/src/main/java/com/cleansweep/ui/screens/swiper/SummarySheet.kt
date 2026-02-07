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

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleansweep.R
import com.cleansweep.data.repository.SummaryViewMode
import com.cleansweep.ui.components.FastScrollbar
import com.cleansweep.ui.components.SheetItemCard
import com.cleansweep.util.rememberIsUsingGestureNavigation
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummarySheet(
    pendingChanges: List<PendingChange>,
    toDelete: List<PendingChange>,
    toKeep: List<PendingChange>,
    toConvert: List<PendingChange>,
    groupedMoves: List<Pair<String, List<PendingChange>>>,
    isApplyingChanges: Boolean,
    folderIdNameMap: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onResetChanges: () -> Unit,
    onRevertChange: (PendingChange) -> Unit,
    viewMode: SummaryViewMode = SummaryViewMode.LIST,
    onToggleViewMode: () -> Unit = {},
    applyChangesButtonLabel: String = stringResource(R.string.apply_changes_button),
    cancelChangesButtonLabel: String = stringResource(R.string.cancel),
    sheetScrollState: LazyListState = rememberLazyListState(),
    isMaximized: Boolean = false,
    onDynamicHeightChange: (Boolean) -> Unit = {}
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.confirm_changes_title)) },
            text = { Text(stringResource(R.string.confirm_changes_body)) },
            confirmButton = {
                Button(onClick = {
                    showConfirmDialog = false
                    onConfirm()
                }) {
                    Text(applyChangesButtonLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(cancelChangesButtonLabel)
                }
            }
        )
    }

    SummarySheetContent(
        pendingChanges = pendingChanges,
        toDelete = toDelete,
        toKeep = toKeep,
        toConvert = toConvert,
        groupedMoves = groupedMoves,
        isApplyingChanges = isApplyingChanges,
        folderIdNameMap = folderIdNameMap,
        viewMode = viewMode,
        onToggleViewMode = onToggleViewMode,
        onDismiss = onDismiss,
        onConfirm = {
            if (pendingChanges.isNotEmpty()) {
                showConfirmDialog = true
            }
        },
        onResetChanges = onResetChanges,
        onRevertChange = onRevertChange,
        applyChangesButtonLabel = applyChangesButtonLabel,
        cancelChangesButtonLabel = cancelChangesButtonLabel,
        sheetScrollState = sheetScrollState,
        isMaximized = isMaximized,
        onDynamicHeightChange = onDynamicHeightChange
    )
}

@Composable
private fun RevertableSheetItemCard(
    change: PendingChange,
    viewMode: SummaryViewMode,
    onRevert: () -> Unit
) {
    Box {
        SheetItemCard(
            item = change.item,
            viewMode = viewMode,
            modifier = Modifier.fillMaxWidth()
        )
        IconButton(
            onClick = onRevert,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = stringResource(R.string.undo_last_action),
                tint = Color.White,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

private const val LIST_MODE_THRESHOLD = 8
private const val GRID_MODE_THRESHOLD = 8
private const val COMPACT_MODE_THRESHOLD = 9

@Composable
private fun calculateRowCount(
    viewMode: SummaryViewMode,
    pendingChanges: List<PendingChange>,
    groupedMoves: List<Pair<String, List<PendingChange>>>,
    toDelete: List<PendingChange>,
    toKeep: List<PendingChange>,
    toConvert: List<PendingChange>
): Int {
    var rowCount: Double = 0.0

    if (groupedMoves.isNotEmpty()) rowCount += groupedMoves.size * 0.5
    if (toDelete.isNotEmpty()) rowCount += 0.5
    if (toKeep.isNotEmpty()) rowCount += 0.5
    if (toConvert.isNotEmpty()) rowCount += 0.5

    when (viewMode) {
        SummaryViewMode.LIST -> {
            rowCount += pendingChanges.size
        }
        SummaryViewMode.GRID -> {
            val columns = 4.0
            groupedMoves.forEach { (_, changes) -> rowCount += ceil(changes.size / columns) }
            rowCount += ceil(toDelete.size / columns)
            rowCount += ceil(toKeep.size / columns)
            rowCount += ceil(toConvert.size / columns)
        }
        SummaryViewMode.COMPACT -> {
            val columns = 8.0
            groupedMoves.forEach { (_, changes) -> rowCount += ceil(changes.size / columns) }
            rowCount += ceil(toDelete.size / columns)
            rowCount += ceil(toKeep.size / columns)
            rowCount += ceil(toConvert.size / columns)
        }
    }
    return ceil(rowCount).toInt()
}


@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun SummarySheetContent(
    pendingChanges: List<PendingChange>,
    toDelete: List<PendingChange>,
    toKeep: List<PendingChange>,
    toConvert: List<PendingChange>,
    groupedMoves: List<Pair<String, List<PendingChange>>>,
    isApplyingChanges: Boolean,
    folderIdNameMap: Map<String, String>,
    viewMode: SummaryViewMode,
    onToggleViewMode: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onResetChanges: () -> Unit,
    onRevertChange: (PendingChange) -> Unit,
    applyChangesButtonLabel: String,
    cancelChangesButtonLabel: String,
    sheetScrollState: LazyListState,
    isMaximized: Boolean,
    onDynamicHeightChange: (Boolean) -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val isGestureMode = rememberIsUsingGestureNavigation()

    val threshold = when (viewMode) {
        SummaryViewMode.LIST -> LIST_MODE_THRESHOLD
        SummaryViewMode.GRID -> GRID_MODE_THRESHOLD
        SummaryViewMode.COMPACT -> COMPACT_MODE_THRESHOLD
    }

    val totalRowCount = calculateRowCount(
        viewMode = viewMode,
        pendingChanges = pendingChanges,
        groupedMoves = groupedMoves,
        toDelete = toDelete,
        toKeep = toKeep,
        toConvert = toConvert
    )

    val shouldMaximize = isMaximized && totalRowCount > threshold

    LaunchedEffect(shouldMaximize) {
        onDynamicHeightChange(shouldMaximize)
    }

    val containerModifier = if (shouldMaximize) {
        Modifier.fillMaxSize().padding(top = 8.dp)
    } else {
        Modifier.fillMaxWidth().wrapContentHeight().padding(top = 8.dp)
    }

    Column(modifier = containerModifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.summary_title_format, pendingChanges.size), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleViewMode) {
                Icon(
                    imageVector = when (viewMode) {
                        SummaryViewMode.LIST -> Icons.AutoMirrored.Filled.List
                        SummaryViewMode.GRID -> Icons.Default.GridView
                        SummaryViewMode.COMPACT -> Icons.Default.Apps
                    },
                    contentDescription = stringResource(R.string.toggle_view_mode)
                )
            }
        }

        Box(
            modifier = if (shouldMaximize) {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = screenHeight * 0.65f)
                    .wrapContentHeight(Alignment.Top)
            }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = sheetScrollState,
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                groupedMoves.forEach { (folderId, changesInGroup) ->
                    item {
                        val folderName = folderIdNameMap[folderId] ?: folderId.substringAfterLast('/')
                        CategoryHeader(title = stringResource(R.string.move_to_folder_header, folderName, changesInGroup.size), icon = Icons.AutoMirrored.Filled.DriveFileMove, iconTint = MaterialTheme.colorScheme.primary)
                    }
                    if (viewMode == SummaryViewMode.LIST) {
                        items(changesInGroup, key = { "move_${it.item.id}" }) { change ->
                            MediaItemRow(change = change, subtitle = null, onRevert = { onRevertChange(change) })
                        }
                    } else {
                        val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                        items(changesInGroup.chunked(columns), key = { row -> row.joinToString { it.item.id } }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until columns) {
                                    val change = row.getOrNull(i)
                                    if (change != null) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            RevertableSheetItemCard(
                                                change = change,
                                                viewMode = viewMode,
                                                onRevert = { onRevertChange(change) }
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (toConvert.isNotEmpty()) {
                    item { CategoryHeader(title = stringResource(R.string.convert_to_image_header, toConvert.size), icon = Icons.Default.Image, iconTint = MaterialTheme.colorScheme.tertiary) }
                    if (viewMode == SummaryViewMode.LIST) {
                        items(toConvert, key = { "convert_${it.item.id}" }) { change ->
                            MediaItemRow(change = change, subtitle = null, onRevert = { onRevertChange(change) })
                        }
                    } else {
                        val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                        items(toConvert.chunked(columns), key = { row -> row.joinToString { it.item.id } }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until columns) {
                                    val change = row.getOrNull(i)
                                    if (change != null) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            RevertableSheetItemCard(
                                                change = change,
                                                viewMode = viewMode,
                                                onRevert = { onRevertChange(change) }
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (toDelete.isNotEmpty()) {
                    item { CategoryHeader(title = stringResource(R.string.delete_header, toDelete.size), icon = Icons.Default.Delete, iconTint = MaterialTheme.colorScheme.error) }
                    if (viewMode == SummaryViewMode.LIST) {
                        items(toDelete, key = { "delete_${it.item.id}" }) { change ->
                            MediaItemRow(change = change, subtitle = null, onRevert = { onRevertChange(change) })
                        }
                    } else {
                        val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                        items(toDelete.chunked(columns), key = { row -> row.joinToString { it.item.id } }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until columns) {
                                    val change = row.getOrNull(i)
                                    if (change != null) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            RevertableSheetItemCard(
                                                change = change,
                                                viewMode = viewMode,
                                                onRevert = { onRevertChange(change) }
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                if (toKeep.isNotEmpty()) {
                    item { CategoryHeader(title = stringResource(R.string.keep_header, toKeep.size), icon = Icons.Default.Check, iconTint = MaterialTheme.colorScheme.secondary) }
                    if (viewMode == SummaryViewMode.LIST) {
                        items(toKeep, key = { "keep_${it.item.id}" }) { change ->
                            MediaItemRow(change = change, subtitle = stringResource(R.string.current_path_prefix, change.item.bucketName), onRevert = { onRevertChange(change) })
                        }
                    } else {
                        val columns = if (viewMode == SummaryViewMode.GRID) 4 else 8
                        items(toKeep.chunked(columns), key = { row -> row.joinToString { it.item.id } }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                for (i in 0 until columns) {
                                    val change = row.getOrNull(i)
                                    if (change != null) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            RevertableSheetItemCard(
                                                change = change,
                                                viewMode = viewMode,
                                                onRevert = { onRevertChange(change) }
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            FastScrollbar(
                state = sheetScrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = if (isGestureMode) 4.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { onResetChanges(); onDismiss() }, modifier = Modifier.weight(1f)) { Text(cancelChangesButtonLabel) }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f), enabled = !isApplyingChanges && pendingChanges.isNotEmpty()) {
                if (isApplyingChanges) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(applyChangesButtonLabel)
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String, icon: ImageVector, iconTint: Color) {
    Column {
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MediaItemRow(change: PendingChange, subtitle: String? = null, onRevert: () -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            SheetItemCard(item = change.item, viewMode = SummaryViewMode.LIST)
            IconButton(
                onClick = onRevert,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = stringResource(R.string.undo_last_action),
                    tint = Color.White,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = change.item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
