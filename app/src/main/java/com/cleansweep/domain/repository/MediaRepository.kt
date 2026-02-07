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

import com.cleansweep.data.model.MediaItem
import com.cleansweep.domain.model.FolderDetails
import com.cleansweep.domain.model.IndexingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MediaRepository {
    fun getMediaFromBuckets(bucketIds: List<String>): Flow<List<MediaItem>>
    suspend fun createNewFolder(folderName: String, parentDirectory: String?): Result<String>
    suspend fun moveMediaToFolder(mediaId: String, targetFolderId: String): MediaItem?
    suspend fun deleteMedia(items: List<MediaItem>): Boolean
    suspend fun moveMedia(mediaIds: List<String>, targetFolders: List<String>): Map<String, MediaItem>
    suspend fun getFolderExistence(folderIds: Set<String>): Map<String, Boolean>
    suspend fun getFolderNames(folderIds: Set<String>): Map<String, String>
    suspend fun getFoldersFromPaths(folderPaths: Set<String>): List<Pair<String, String>>
    fun getRecentFolders(limit: Int): Flow<List<Pair<String, String>>>
    fun getFoldersSortedByRecentMedia(): Flow<List<Pair<String, String>>>
    suspend fun getFoldersWithProcessedMedia(): List<Pair<String, String>>
    suspend fun getAllFolderPaths(): List<String>
    suspend fun getCachedFolderSnapshot(): List<Pair<String, String>>
    suspend fun getInitialFolderDetails(): List<FolderDetails>
    suspend fun hasCache(): Boolean
    suspend fun checkForChangesAndInvalidate(): Boolean
    fun invalidateFolderCache()
    suspend fun cleanupGhostFolders()
    suspend fun getMediaFoldersWithDetails(forceRefresh: Boolean): List<FolderDetails>
    fun observeMediaFoldersWithDetails(): Flow<List<FolderDetails>>
    suspend fun handleFolderRename(oldPath: String, newPath: String)
    suspend fun handleFolderMove(sourcePath: String, destinationPath: String)
    suspend fun isDirectory(path: String): Boolean
    suspend fun getSubdirectories(path: String): List<String>
    fun observeAllFolders(): Flow<List<Pair<String, String>>>
    suspend fun scanFolders(folderPaths: List<String>)
    suspend fun scanPathsAndWait(paths: List<String>): Boolean
    suspend fun getUnindexedMediaPaths(): List<String>
    suspend fun getMediaStoreKnownPaths(): Set<String>
    suspend fun getAllMediaFilePaths(): Set<String>
    suspend fun getMediaItemsFromPaths(paths: List<String>): List<MediaItem>
    suspend fun removeFoldersFromCache(paths: Set<String>)
    suspend fun getIndexingStatus(): IndexingStatus
    suspend fun triggerFullMediaStoreScan(): Boolean
    val isPerformingBackgroundRefresh: StateFlow<Boolean>
}
