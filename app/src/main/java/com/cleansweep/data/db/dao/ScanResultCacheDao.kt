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

package com.cleansweep.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cleansweep.data.db.entity.MediaItemRefCacheEntry
import com.cleansweep.data.db.entity.ScanResultGroupCacheEntry
import com.cleansweep.data.db.entity.toCacheEntry
import com.cleansweep.data.db.entity.toDuplicateGroup
import com.cleansweep.data.db.entity.toSimilarGroup
import com.cleansweep.data.db.entity.toUnscannableFilesCacheEntry
import com.cleansweep.domain.model.DuplicateGroup
import com.cleansweep.domain.model.ScanResultGroup
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.domain.repository.ScanScopeType

@Dao
interface ScanResultCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScanResultGroup(group: ScanResultGroupCacheEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaItemRefs(items: List<MediaItemRefCacheEntry>)

    @Query("SELECT * FROM scan_result_groups WHERE groupType != 'UNSCANNABLE_SUMMARY' AND scopeType = :scopeType ORDER BY timestamp DESC")
    suspend fun getAllScanResultGroups(scopeType: String): List<ScanResultGroupCacheEntry>

    @Query("SELECT * FROM scan_result_groups WHERE groupType = 'UNSCANNABLE_SUMMARY' AND scopeType = :scopeType LIMIT 1")
    suspend fun getUnscannableFilesEntry(scopeType: String): ScanResultGroupCacheEntry?

    @Query("SELECT * FROM media_item_refs WHERE groupId = :groupId")
    suspend fun getMediaItemRefsForGroup(groupId: String): List<MediaItemRefCacheEntry>

    @Query("DELETE FROM scan_result_groups WHERE scopeType = :scopeType")
    suspend fun clearScanResultGroupsByScope(scopeType: String)

    @Query("DELETE FROM media_item_refs WHERE groupId IN (SELECT uniqueId FROM scan_result_groups WHERE scopeType = :scopeType)")
    suspend fun clearMediaItemRefsByScope(scopeType: String)

    @Transaction
    suspend fun clearScanResultsByScope(scopeType: ScanScopeType) {
        // Must clear refs first due to the dependency in the query
        clearMediaItemRefsByScope(scopeType.name)
        clearScanResultGroupsByScope(scopeType.name)
    }

    @Transaction
    suspend fun clearAllScanResults() {
        clearScanResultsByScope(ScanScopeType.SCOPED)
        clearScanResultsByScope(ScanScopeType.FULL)
    }

    @Transaction
    suspend fun saveScanResults(
        groups: List<ScanResultGroup>,
        unscannableFiles: List<String>,
        scopeType: ScanScopeType,
        timestampOverride: Long? = null
    ) {
        // If it's a FULL scan, clear everything. If it's SCOPED, only clear SCOPED.
        if (scopeType == ScanScopeType.FULL) {
            clearAllScanResults()
        } else {
            clearScanResultsByScope(ScanScopeType.SCOPED)
        }

        val timestamp = timestampOverride ?: System.currentTimeMillis()

        // Save actual groups
        groups.forEach { group ->
            when (group) {
                is DuplicateGroup -> {
                    upsertScanResultGroup(group.toCacheEntry(timestamp, scopeType))
                    upsertMediaItemRefs(group.items.map { it.toCacheEntry(group.uniqueId) })
                }
                is SimilarGroup -> {
                    upsertScanResultGroup(group.toCacheEntry(timestamp, scopeType))
                    upsertMediaItemRefs(group.items.map { it.toCacheEntry(group.uniqueId) })
                }
            }
        }

        // Save unscannable files summary
        if (unscannableFiles.isNotEmpty()) {
            val unscannableEntry = unscannableFiles.toUnscannableFilesCacheEntry(timestamp, scopeType)
            upsertScanResultGroup(unscannableEntry)
        }
    }

    private suspend fun loadResultsForScope(scopeType: ScanScopeType): Triple<List<ScanResultGroup>, List<String>, Long>? {
        val groupEntries = getAllScanResultGroups(scopeType.name)
        val unscannableEntry = getUnscannableFilesEntry(scopeType.name)
        val timestamp = groupEntries.firstOrNull()?.timestamp ?: unscannableEntry?.timestamp

        if (groupEntries.isEmpty() && unscannableEntry == null || timestamp == null) {
            return null // No results to load for this scope
        }

        val results = mutableListOf<ScanResultGroup>()
        for (groupEntry in groupEntries) {
            val mediaItemRefs = getMediaItemRefsForGroup(groupEntry.uniqueId)
            // Re-add group only if it's still valid (at least 2 items)
            if (mediaItemRefs.size > 1) {
                when (groupEntry.groupType) {
                    "EXACT" -> results.add(mediaItemRefs.toDuplicateGroup(groupEntry))
                    "SIMILAR" -> results.add(mediaItemRefs.toSimilarGroup(groupEntry))
                }
            }
        }

        val loadedUnscannableFiles = unscannableEntry?.unscannableFilePaths ?: emptyList()

        return Triple(results.toList(), loadedUnscannableFiles, timestamp)
    }

    /**
     * Loads the latest scan results, prioritizing SCOPED results over FULL results.
     * This ensures the user sees the result of their most recent action.
     */
    @Transaction
    suspend fun loadLatestScanResults(): Pair<Triple<List<ScanResultGroup>, List<String>, Long>, ScanScopeType>? {
        // Prioritize loading the most recent user action, which would be a scoped scan.
        val scopedResults = loadResultsForScope(ScanScopeType.SCOPED)
        if (scopedResults != null) {
            return Pair(scopedResults, ScanScopeType.SCOPED)
        }

        // If no scoped results, fall back to the full scan results.
        val fullResults = loadResultsForScope(ScanScopeType.FULL)
        if (fullResults != null) {
            return Pair(fullResults, ScanScopeType.FULL)
        }

        return null
    }
}
