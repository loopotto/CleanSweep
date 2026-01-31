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

package com.cleansweep.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Detects whether the device is using gesture navigation or button navigation
 */
@Composable
fun rememberIsUsingGestureNavigation(): Boolean {
    val density = LocalDensity.current
    val navBarInsets = WindowInsets.navigationBars.getBottom(density)

    val isGestureNav by remember(navBarInsets) {
        derivedStateOf {
            // A simple heuristic: if the navigation bar is very thin, it's gesture navigation.
            navBarInsets < with(density) { 40.dp.toPx() }
        }
    }
    return isGestureNav
}
