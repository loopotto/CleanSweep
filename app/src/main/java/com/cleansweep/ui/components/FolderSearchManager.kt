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

package com.cleansweep.ui.components

import androidx.compose.runtime.Stable
import com.cleansweep.domain.repository.MediaRepository
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@Stable
data class FolderSearchState(
    val searchQuery: String = "",
    val browsePath: String? = null,
    val displayedResults: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isPreFilteredMode: Boolean = false,
    val allFolders: List<Pair<String, String>> = emptyList()
)

@ViewModelScoped
class FolderSearchManager @Inject constructor(
    private val mediaRepository: MediaRepository
) {
    private val _state = MutableStateFlow(FolderSearchState())
    val state: StateFlow<FolderSearchState> = _state.asStateFlow()

    private val calculationContext = Dispatchers.Default

    private fun calculateResults(
        query: String,
        browsePath: String?,
        sourceFolders: List<Pair<String, String>>,
        isPreFiltered: Boolean
    ): List<String> {
        val results = if (query.isNotBlank()) {
            sourceFolders.filter { (path, name) ->
                name.contains(query, ignoreCase = true) || path.contains(query, ignoreCase = true)
            }
        } else if (!browsePath.isNullOrBlank()) {
            sourceFolders.filter { (path, _) ->
                // Check if the item's path is a direct child of the browsePath
                val parentDir = File(path).parent
                parentDir?.equals(browsePath, ignoreCase = true) == true
            }
        } else {
            // Display root folders when not browsing or searching
            if (isPreFiltered) {
                sourceFolders
            } else {
                val allPaths = sourceFolders.map { it.first }.toSet()
                sourceFolders.filter { (path, _) ->
                    val parent = File(path).parent
                    parent == null || !allPaths.contains(parent)
                }
            }
        }
        return results.map { it.first }.sortedBy { it.lowercase() }
    }

    fun prepareForSearch(
        initialPath: String?,
        coroutineScope: CoroutineScope,
        excludedFolders: Set<String> = emptySet()
    ) {
        coroutineScope.launch {
            _state.update { it.copy(isLoading = true, isPreFilteredMode = false) }

            // Fetch the complete, fresh list of folders just once from the single source of truth.
            val allFolders = mediaRepository.observeAllFolders().first()
            val availableFolders = allFolders.filterNot { it.first in excludedFolders }

            val initialResults = withContext(calculationContext) {
                calculateResults(
                    query = "",
                    browsePath = initialPath,
                    sourceFolders = availableFolders,
                    isPreFiltered = false
                )
            }

            _state.update {
                it.copy(
                    browsePath = initialPath,
                    isLoading = false,
                    displayedResults = initialResults,
                    allFolders = availableFolders
                )
            }
        }
    }


    fun prepareWithPreFilteredList(
        folders: List<Pair<String, String>>,
        initialPath: String? = null
    ) {
        _state.update {
            it.copy(
                browsePath = initialPath,
                isPreFilteredMode = true,
                isLoading = false,
                displayedResults = calculateResults(
                    query = "",
                    browsePath = initialPath,
                    sourceFolders = folders,
                    isPreFiltered = true
                ),
                allFolders = folders
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _state.update { currentState ->
            currentState.copy(
                searchQuery = query,
                // Do not nullify browsePath. Preserve it during search.
                displayedResults = calculateResults(query, currentState.browsePath, currentState.allFolders, currentState.isPreFilteredMode)
            )
        }
    }

    fun selectPath(path: String) {
        _state.update { currentState ->
            currentState.copy(
                browsePath = path,
                searchQuery = "",
                displayedResults = calculateResults("", path, currentState.allFolders, currentState.isPreFilteredMode)
            )
        }
    }

    fun selectSingleResultOrSelf() {
        val currentState = _state.value
        if (currentState.searchQuery.isNotBlank() && currentState.displayedResults.size == 1) {
            selectPath(currentState.displayedResults.first())
        }
    }

    fun revertSearchIfEmpty() {
        _state.update { currentState ->
            // Only revert if a search is active AND it has yielded no results.
            if (currentState.searchQuery.isNotBlank() && currentState.displayedResults.isEmpty()) {
                currentState.copy(
                    searchQuery = "", // Clear the query
                    // Recalculate results based on the preserved browsePath
                    displayedResults = calculateResults("", currentState.browsePath, currentState.allFolders, currentState.isPreFilteredMode)
                )
            } else {
                currentState // No change needed
            }
        }
    }

    fun reset() {
        _state.value = FolderSearchState()
    }
}
