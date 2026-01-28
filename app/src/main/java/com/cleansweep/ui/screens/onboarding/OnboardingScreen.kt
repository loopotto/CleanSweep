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
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.cleansweep.ui.components.AppDialog
import com.cleansweep.util.PermissionManager
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val description: String,
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
            title = "Welcome to CleanSweep",
            description = "Take control of your gallery. A simple, powerful way to sort thousands of photos and videos.\nReclaim your time and your storage space.",
            icon = Icons.Default.PhotoLibrary
        ),
        OnboardingPage(
            title = "Simple Swiping",
            description = "Swipe right to keep, swipe left to delete. Made a mistake? The Undo button has your back! Not sure yet? Use the Skip button to decide later.",
            icon = Icons.Default.SwapHoriz
        ),
        OnboardingPage(
            title = "Powerful Sorting",
            description = "Tap folders to instantly move files. Long-press an item to quickly send it to a 'To Edit' folder or access other shortcuts.",
            icon = Icons.Default.FolderOpen
        ),
        OnboardingPage(
            title = "Find Similar Photos",
            description = "Our duplicate finder goes beyond exact copies to also find visually similar photos and videos. You can even adjust the sensitivity in the settings!",
            icon = Icons.Default.ControlPointDuplicate
        ),
        OnboardingPage(
            title = "Video Superpowers",
            description = "Fly through videos with playback speed controls. You can even capture a perfect moment by saving a single frame as a new photo.",
            icon = Icons.Default.VideoLibrary
        ),
        OnboardingPage(
            title = "You're in Control",
            description = "You've learned the basics. Now you're ready to organize your media faster than ever before.",
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
                text = "CleanSweep",
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
                        "Skip",
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
                            "Previous",
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
                        "Next",
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
                            "Grant All Files Access",
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
                            "Get Started",
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
            title = { Text("Close CleanSweep", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "CleanSweep cannot function without All Files Access permission. We tried to but it ended up being required for CleanSweep to work properly for most users.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            buttons = {
                TextButton(onClick = {
                    showCloseDialog = false
                    showPermissionScreen = true
                }) { Text("Cancel") }
                Button(onClick = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                        .launch {
                            (context as? ComponentActivity)?.finish()
                        }
                }) { Text("Close") }
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
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description
        Text(
            text = page.description,
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
                text = "All Files Access Required",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "CleanSweep needs All Files Access permission to organize your photos and videos across all folders on your device.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Please grant the permission in your device settings to use CleanSweep.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Settings")
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Close App")
            }
        }
    }
}
