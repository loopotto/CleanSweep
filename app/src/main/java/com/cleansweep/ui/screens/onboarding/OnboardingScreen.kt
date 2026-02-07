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

package com.cleansweep.ui.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.cleansweep.R
import com.cleansweep.ui.components.AppDialog
import com.cleansweep.util.PermissionManager
import kotlinx.coroutines.launch

data class OnboardingPage(
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val primaryColor: Color = Color.Unspecified
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val pages = listOf(
        OnboardingPage(
            titleRes = R.string.onboarding_p1_title,
            descriptionRes = R.string.onboarding_p1_desc,
            icon = Icons.Default.PhotoLibrary
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_p2_title,
            descriptionRes = R.string.onboarding_p2_desc,
            icon = Icons.Default.SwapHoriz
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_p3_title,
            descriptionRes = R.string.onboarding_p3_desc,
            icon = Icons.Default.FolderOpen
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_p4_title,
            descriptionRes = R.string.onboarding_p4_desc,
            icon = Icons.Default.ControlPointDuplicate
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_p5_title,
            descriptionRes = R.string.onboarding_p5_desc,
            icon = Icons.Default.VideoLibrary
        ),
        OnboardingPage(
            titleRes = R.string.onboarding_p6_title,
            descriptionRes = R.string.onboarding_p6_desc,
            icon = Icons.Default.Speed
        )
    )

    // Check if we have All Files Access (for Android 11+)
    var hasAllFilesAccess by remember { mutableStateOf(PermissionManager.hasAllFilesAccess()) }
    var showPermissionScreen by remember { mutableStateOf(false) }
    var showCloseDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Storage permission launcher for MANAGE_EXTERNAL_STORAGE - only initialize if we have a ComponentActivity
    val storagePermissionLauncher = remember {
        try {
            val activity = context as? ComponentActivity
            if (activity != null) {
                PermissionManager.registerAllFilesAccessLauncher(activity) { granted ->
                    hasAllFilesAccess = granted
                    if (!granted) {
                        showPermissionScreen = true
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            println("Error creating permission launcher: ${e.message}")
            null
        }
    }

    // Update the state when returning from settings
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val newPermissionState = PermissionManager.hasAllFilesAccess()
                if (newPermissionState != hasAllFilesAccess) {
                    hasAllFilesAccess = newPermissionState
                    if (newPermissionState) {
                        // Permission was granted, dismiss permission screen
                        showPermissionScreen = false
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.size - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Header with skip button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp)
                .height(48.dp), // Fixed height for consistent positioning
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Always reserve space for skip button to maintain consistent layout
            if (!isLastPage) {
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pages.size - 1)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // Use same sized box to maintain layout consistency
                Box(
                    modifier = Modifier
                        .size(width = 68.dp, height = 48.dp)
                )
            }
        }

        // Pager content
        if (showPermissionScreen) {
            PermissionRequiredScreen(
                onGrant = {
                    storagePermissionLauncher?.let { launcher ->
                        PermissionManager.requestAllFilesAccess(context, launcher)
                    } ?: run {
                        // Fallback: try to open settings directly
                        try {
                            val intent = PermissionManager.createAllFilesAccessIntent(context)
                            if (intent != null) {
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            // If all fails, keep showing permission screen
                        }
                    }
                },
                onClose = {
                    showCloseDialog = true
                    // Don't hide permission screen here - keep it visible behind dialog
                }
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                OnboardingPageContent(
                    page = pages[page],
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Page indicators
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(pages.size) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isSelected) 12.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                )
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalArrangement = if (isLastPage) Arrangement.Center else Arrangement.SpaceBetween
        ) {
            if (!isLastPage) {
                if (pagerState.currentPage > 0) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        modifier = Modifier
                            .height(56.dp)
                            .weight(1f)
                            .padding(end = 8.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            stringResource(R.string.onboarding_previous),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = if (pagerState.currentPage > 0) {
                        Modifier
                            .height(56.dp)
                            .weight(1f)
                            .padding(start = 8.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    },
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        stringResource(R.string.onboarding_next),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // Last page - check permissions
                if (!hasAllFilesAccess) {
                    Button(
                        onClick = {
                            storagePermissionLauncher?.let { launcher ->
                                PermissionManager.requestAllFilesAccess(context, launcher)
                            } ?: run {
                                // Fallback if launcher couldn't be created
                                showPermissionScreen = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            stringResource(R.string.onboarding_grant_permission),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.completeOnboarding()
                            onComplete()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            stringResource(R.string.onboarding_get_started),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    if (showCloseDialog) {
        AppDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text(stringResource(R.string.onboarding_close_dialog_title), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    stringResource(R.string.onboarding_close_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = {
                    showCloseDialog = false
                    showPermissionScreen = true
                }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                        .launch {
                            (context as? ComponentActivity)?.finish()
                        }
                }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Card(
            modifier = Modifier.size(120.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Title
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            text = stringResource(page.descriptionRes),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.2
        )
    }
}

@Composable
private fun PermissionRequiredScreen(
    onGrant: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.permission_screen_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.permission_screen_desc),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.permission_screen_instruction),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.open_settings))
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.close_app))
            }
        }
    }
}
