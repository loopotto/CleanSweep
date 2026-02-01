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

package com.cleansweep.domain.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cleansweep.data.db.dao.ScanResultCacheDao
import com.cleansweep.data.db.dao.SimilarityDenialDao
import com.cleansweep.data.db.dao.UnreadableFileCacheDao
import com.cleansweep.data.db.entity.SimilarityDenial
import com.cleansweep.data.db.entity.UnreadableFileCache
import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.ScanResultGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class ScanScopeType {
    FULL,
    SCOPED
}

/**
 * A data class to hold the fully validated results loaded from the database.
 * @param timestamp The time the original scan was completed.
 * @param scopeType The scope of the scan that produced these results.
 */
data class PersistedScanResult(
    val groups: List<ScanResultGroup>,
    val unscannableFiles: List<String>,
    val timestamp: Long,
    val scopeType: ScanScopeType
)

@Singleton
class DuplicatesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unreadableFileCacheDao: UnreadableFileCacheDao,
    private val scanResultCacheDao: ScanResultCacheDao,
    private val similarityDenialDao: SimilarityDenialDao
) {
    companion object {
        private const val PREFS_NAME = "duplicates_prefs"
        private const val KEY_HIDDEN_GROUP_IDS = "hidden_group_ids"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Logical Denial (Flag as Incorrect) ---

    /**
     * Retrieves all denied similarity pairs as a set of unique keys.
     * Each key is formatted as "pathA|pathB" where pathA < pathB.
     */
    suspend fun getSimilarityDenialKeys(): Set<String> = withContext(Dispatchers.IO) {
        similarityDenialDao.getAllDenialKeys().toSet()
    }

    /**
     * Records all possible item pairs within a group as "incorrect matches."
     * This prevents the algorithm from ever linking these specific files again.
     */
    suspend fun addSimilarityDenials(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        if (items.size < 2) return@withContext

        val denials = mutableListOf<SimilarityDenial>()
        for (i in items.indices) {
            for (j in i + 1 until items.size) {
                val pathA = items[i].id
                val pathB = items[j].id
                denials.add(
                    SimilarityDenial(
                        pairKey = SimilarityDenial.createKey(pathA, pathB),
                        pathA = pathA,
                        pathB = pathB
                    )
                )
            }
        }
        similarityDenialDao.insertDenials(denials)
    }

    // --- Hidden Groups ---

    suspend fun getHiddenGroupIds(): Set<String> = withContext(Dispatchers.IO) {
        prefs.getStringSet(KEY_HIDDEN_GROUP_IDS, emptySet()) ?: emptySet()
    }

    suspend fun hideGroupId(groupId: String) = withContext(Dispatchers.IO) {
        val currentHiddenIds = getHiddenGroupIds().toMutableSet()
        currentHiddenIds.add(groupId)
        prefs.edit { putStringSet(KEY_HIDDEN_GROUP_IDS, currentHiddenIds) }
    }

    suspend fun getUnreadableFileCache(): List<UnreadableFileCache> = withContext(Dispatchers.IO) {
        return@withContext unreadableFileCacheDao.getAll()
    }

    suspend fun updateUnreadableFileCache(newlyUnreadable: List<File>) = withContext(Dispatchers.IO) {
        if (newlyUnreadable.isEmpty()) return@withContext

        val cacheEntries = newlyUnreadable.map {
            UnreadableFileCache(
                filePath = it.absolutePath,
                lastModified = it.lastModified(),
                size = it.length()
            )
        }
        unreadableFileCacheDao.upsertAll(cacheEntries)
    }

    suspend fun clearUnreadableFileCache() = withContext(Dispatchers.IO) {
        unreadableFileCacheDao.clear()
    }

    // --- Scan Result Caching ---

    suspend fun saveScanResults(
        groups: List<ScanResultGroup>,
        unscannableFiles: List<String>,
        scopeType: ScanScopeType,
        timestamp: Long? = null
    ) = withContext(Dispatchers.IO) {
        scanResultCacheDao.saveScanResults(groups, unscannableFiles, scopeType, timestamp)
    }

    /**
     * Performs a full validation of the cached scan results.
     * @return `true` if there is at least one valid group of duplicates, `false` otherwise.
     */
    suspend fun hasValidCachedResults(): Boolean = withContext(Dispatchers.IO) {
        val loadedData = scanResultCacheDao.loadLatestScanResults() ?: return@withContext false
        // The Pair contains the Triple and the scope. We only need the Triple for this check.
        val (rawData, _) = loadedData
        val (rawGroups, _) = rawData // Unscannable files are ignored for this check

        for (group in rawGroups) {
            val validatedItems = group.items.filter { isMediaItemStillValid(it) }
            if (validatedItems.size > 1) {
                // Found at least one valid group, no need to check further.
                return@withContext true
            }
        }

        return@withContext false
    }

    /**
     * Loads the latest scan results from the cache and performs a robust validation to ensure
     * the files still exist and have not been modified. It prioritizes SCOPED results over FULL.
     *
     * @return A [PersistedScanResult] object if valid results are found, otherwise null.
     */
    suspend fun loadLatestScanResults(): PersistedScanResult? = withContext(Dispatchers.IO) {
        val loadedData = scanResultCacheDao.loadLatestScanResults() ?: return@withContext null
        val (rawData, scopeType) = loadedData
        val (rawGroups, unscannableFiles, timestamp) = rawData

        val validatedGroups = rawGroups.mapNotNull { group ->
            val validatedItems = group.items.filter { isMediaItemStillValid(it) }

            if (validatedItems.size > 1) {
                group.withUpdatedItems(validatedItems)
            } else {
                null // Discard group if it no longer has duplicates
            }
        }

        if (validatedGroups.isEmpty() && unscannableFiles.isEmpty()) {
            return@withContext null // No valid data remains after validation
        }

        return@withContext PersistedScanResult(
            groups = validatedGroups,
            unscannableFiles = unscannableFiles,
            timestamp = timestamp,
            scopeType = scopeType
        )
    }

    suspend fun clearAllScanResults() = withContext(Dispatchers.IO) {
        scanResultCacheDao.clearAllScanResults()
    }

    suspend fun clearScopedScanResults() = withContext(Dispatchers.IO) {
        scanResultCacheDao.clearScanResultsByScope(ScanScopeType.SCOPED)
    }

    /**
     * Checks if a MediaItem from the cache is still valid by comparing its path,
     * last modified time, and size with the file on disk.
     */
    private fun isMediaItemStillValid(item: MediaItem): Boolean {
        return try {
            val file = File(item.id)
            // Compare timestamps at the second level to tolerate minor filesystem/mediastore discrepancies.
            val fileModifiedSeconds = file.lastModified() / 1000
            val itemModifiedSeconds = item.dateModified / 1000

            file.exists() && fileModifiedSeconds == itemModifiedSeconds && file.length() == item.size
        } catch (e: Exception) {
            false
        }
    }
}
