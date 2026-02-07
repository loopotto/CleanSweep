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

package com.cleansweep.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.data.repository.AppLocale
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.domain.bus.AppLifecycleEventBus
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.ui.theme.AppTheme
import com.cleansweep.ui.theme.predefinedAccentColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    private val mediaRepository: MediaRepository,
    private val appLifecycleEventBus: AppLifecycleEventBus
) : ViewModel() {

    val isReady: StateFlow<Boolean> = combine(
        preferencesRepository.themeFlow,
        preferencesRepository.appLocaleFlow,
        preferencesRepository.isOnboardingCompletedFlow
    ) { _, _, _ -> true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    val currentTheme: StateFlow<AppTheme> = preferencesRepository.themeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppTheme.SYSTEM
        )

    val appLocale: StateFlow<AppLocale> = preferencesRepository.appLocaleFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppLocale.SYSTEM
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
            initialValue = predefinedAccentColors.first().key
        )

    val isOnboardingCompleted: StateFlow<Boolean?> = preferencesRepository.isOnboardingCompletedFlow
        .map<Boolean, Boolean?> { it }
        .onStart { emit(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    init {
        viewModelScope.launch {
            mediaRepository.cleanupGhostFolders()
        }
    }

    /**
     * Called when the app is brought to the foreground. Checks for underlying
     * file system changes and, only if changes are found, invalidates caches
     * and broadcasts a resume event.
     */
    fun onAppResumed() {
        viewModelScope.launch {
            val wasInvalidated = mediaRepository.checkForChangesAndInvalidate()
            if (wasInvalidated) {
                // Only post the event if a change was actually detected.
                appLifecycleEventBus.postAppResumed()
            }
        }
    }
}
