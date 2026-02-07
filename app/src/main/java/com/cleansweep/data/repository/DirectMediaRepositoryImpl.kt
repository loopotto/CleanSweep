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

package com.cleansweep.data.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.cleansweep.R
import com.cleansweep.data.db.dao.FolderDetailsDao
import com.cleansweep.data.db.entity.FolderDetailsCache
import com.cleansweep.data.model.MediaItem
import com.cleansweep.di.AppModule
import com.cleansweep.domain.bus.AppLifecycleEventBus
import com.cleansweep.domain.bus.FolderUpdateEvent
import com.cleansweep.domain.bus.FolderUpdateEventBus
import com.cleansweep.domain.model.FolderDetails
import com.cleansweep.domain.model.IndexingStatus
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.util.FileManager
import com.cleansweep.util.FileOperationsHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class DirectMediaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
    private val folderDetailsDao: FolderDetailsDao,
    private val preferencesRepository: PreferencesRepository,
    @AppModule.ApplicationScope private val externalScope: CoroutineScope,
    private val fileOperationsHelper: FileOperationsHelper,
    private val folderUpdateEventBus: FolderUpdateEventBus,
    private val appLifecycleEventBus: AppLifecycleEventBus
) : MediaRepository {

    private val logTag ="DirectMediaRepo"
    private val dimensionlogTag ="ImageDimensionDebug"

    private var folderDetailsCache: List<FolderDetails>? = null
    private var lastFileDiscoveryCache: List<File>? = null

    @Volatile
    private var lastKnownFolderState: Set<String>? = null

    private val _isPerformingBackgroundRefresh = MutableStateFlow(false)
    override val isPerformingBackgroundRefresh: StateFlow<Boolean> = _isPerformingBackgroundRefresh.asStateFlow()
    private val isBackgroundRefreshRunning = AtomicBoolean(false)

    // Translated folder name used for exclusion filters
    private val localizedToEditFolderName: String by lazy { context.getString(R.string.folder_name_to_edit) }


    init {
        // Listen for folder updates and apply them directly to the DB cache.
        listenForFolderUpdates()
        // Listen for app lifecycle events to invalidate session caches
        listenForAppLifecycle()
    }

    private fun listenForAppLifecycle() {
        externalScope.launch {
            appLifecycleEventBus.appResumeEvent.collect {
                // On-resume logic is now handled by MainViewModel calling checkForChangesAndInvalidate()
            }
        }
    }

    private fun listenForFolderUpdates() {
        externalScope.launch(Dispatchers.IO) {
            folderUpdateEventBus.events.collect { event ->
                Log.d(logTag, "Repository received folder update event: $event")
                when (event) {
                    is FolderUpdateEvent.FolderBatchUpdate -> {
                        val pathsToDelete = mutableListOf<String>()
                        val updatesToUpsert = mutableListOf<FolderDetailsCache>()

                        event.updates.forEach { (path, delta) ->
                            val folder = folderDetailsDao.getFolderByPath(path)
                            if (folder != null) {
                                val newItemCount = folder.itemCount + delta.itemCountChange
                                if (newItemCount <= 0) {
                                    pathsToDelete.add(path)
                                } else {
                                    updatesToUpsert.add(folder.copy(
                                        itemCount = newItemCount,
                                        totalSize = (folder.totalSize + delta.sizeChange).coerceAtLeast(0)
                                    ))
                                }
                            }
                        }

                        if (pathsToDelete.isNotEmpty()) {
                            folderDetailsDao.deleteByPath(pathsToDelete)
                        }
                        if (updatesToUpsert.isNotEmpty()) {
                            folderDetailsDao.upsertAll(updatesToUpsert)
                        }
                    }
                    is FolderUpdateEvent.FolderAdded -> {
                        if (folderDetailsDao.getFolderByPath(event.path) == null) {
                            val newCacheEntry = FolderDetailsCache(
                                path = event.path,
                                name = event.name,
                                itemCount = 0,
                                totalSize = 0,
                                isSystemFolder = false // Default
                            )
                            folderDetailsDao.upsert(newCacheEntry)
                        }
                    }
                    is FolderUpdateEvent.FullRefreshRequired -> {
                        // The event signals that a significant change happened elsewhere.
                        // We invalidate our caches. The next observer of our flows
                        // will automatically trigger a full rescan.
                        invalidateFolderCache()
                    }
                }
            }
        }
    }

    // Helper class to cache MediaStore query results
    private data class MediaStoreCache(
        val id: Long,
        val displayName: String?,
        val mimeType: String?,
        val dateAdded: Long,
        val dateModified: Long,
        val size: Long,
        val bucketId: String?,
        val bucketName: String?,
        val isVideo: Boolean,
        val width: Int,
        val height: Int,
        val orientation: Int
    )

    override suspend fun checkForChangesAndInvalidate(): Boolean = withContext(Dispatchers.IO) {
        if (isBackgroundRefreshRunning.compareAndSet(false, true)) {
            Log.d("CacheDebug", "Starting background folder refresh check.")
            var changesFound = false
            try {
                // This check is now only for existing caches. The initial scan is triggered by the observer.
                val hasCache = folderDetailsDao.getAll().first().isNotEmpty()
                if (!hasCache) {
                    Log.d("CacheDebug", "Cache is empty, skipping on-resume check. Observer will trigger initial scan.")
                    return@withContext false
                }

                _isPerformingBackgroundRefresh.value = true

                // --- SCAN ---
                val (mediaFolderDetails, _) = performSinglePassFileSystemScan()
                val targetFolders = findViableTargetFolders()
                val newMediaFolderCacheEntries = mediaFolderDetails.map { it.toFolderDetailsCache() }
                val newTargetFolderCacheEntries = targetFolders.map { (path, name) ->
                    FolderDetailsCache(path = path, name = name, itemCount = 0, totalSize = 0, isSystemFolder = false)
                }
                val allNewFolderCacheEntries = (newMediaFolderCacheEntries + newTargetFolderCacheEntries)
                    .distinctBy { it.path }

                // --- COMPARE ---
                val currentCache = folderDetailsDao.getAll().first().toSet()
                val newCache = allNewFolderCacheEntries.toSet()

                if (currentCache != newCache) {
                    Log.d("CacheDebug", "Change detected. Old count: ${currentCache.size}, New count: ${newCache.size}. Updating cache.")
                    changesFound = true
                    // --- ATOMIC SWAP ---
                    folderDetailsDao.replaceAll(allNewFolderCacheEntries)
                    Log.d("CacheDebug", "Cache atomically updated via DAO transaction.")
                } else {
                    Log.d("CacheDebug", "No external changes detected. Cache is up-to-date.")
                }
            } catch (e: Exception) {
                Log.e("CacheDebug", "Error during background folder refresh", e)
                changesFound = false // Don't signal a change if an error occurred
            } finally {
                _isPerformingBackgroundRefresh.value = false
                isBackgroundRefreshRunning.set(false)
                Log.d("CacheDebug", "Background folder refresh check finished.")
            }
            return@withContext changesFound
        } else {
            Log.d("CacheDebug", "Skipping background refresh: already in progress.")
            return@withContext false
        }
    }

    private fun invalidateCaches() {
        folderDetailsCache = null
        lastFileDiscoveryCache = null // Invalidate file discovery cache
        externalScope.launch {
            folderDetailsDao.clear()
        }
    }

    override fun invalidateFolderCache() {
        Log.d("CacheDebug", "Forced invalidation. Clearing all folder caches (Memory, DB, and state).")
        lastKnownFolderState = null
        invalidateCaches()
    }

    override suspend fun getCachedFolderSnapshot(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        return@withContext folderDetailsDao.getAll().first().map { it.path to it.name }
    }

    private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")
    private val supportedVideoExtensions = setOf("mp4", "webm", "mkv", "3gp", "mov", "avi", "mpg", "mpeg", "wmv", "flv")

    override suspend fun cleanupGhostFolders() { /* No-op */ }

    override fun getMediaFromBuckets(bucketIds: List<String>): Flow<List<MediaItem>> = flow {
        if (bucketIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        Log.d(logTag, "Starting batched media fetch for buckets: $bucketIds")

        // Step 1: Pre-fetch all available MediaStore data for the target buckets into a map for fast lookups.
        val mediaStoreDataMap = getMediaStoreDataForBuckets(bucketIds)
        Log.d(logTag, "Pre-fetched ${mediaStoreDataMap.size} items from MediaStore.")

        // Step 2: Get a lightweight list of all File objects from the file system, NON-RECURSIVELY.
        val fileSystemFiles = withContext(Dispatchers.IO) {
            bucketIds.flatMap { bucketPath ->
                try {
                    File(bucketPath)
                        .listFiles { file -> file.isFile && isMediaFile(file) }
                        ?.toList() ?: emptyList()
                } catch (e: Exception) {
                    Log.e(logTag, "Error reading files from bucket: $bucketPath", e)
                    emptyList<File>()
                }
            }.toSet()
        }
        Log.d(logTag, "Direct non-recursive file scan found ${fileSystemFiles.size} total files.")


        // Step 3: Combine and sort the list of File objects to establish the processing order.
        val sortedFileIndex = withContext(Dispatchers.Default) {
            val allFilePaths = fileSystemFiles.map { it.absolutePath } + mediaStoreDataMap.keys
            allFilePaths.distinct()
                .mapNotNull { path -> try { File(path) } catch (e: Exception) { null } }
                .filter { it.exists() }
                .sortedByDescending { it.lastModified() }
        }
        Log.d(logTag, "Discovered and sorted ${sortedFileIndex.size} unique files.")

        // Step 4: Process the sorted list, stream results, and track un-indexed files.
        val initialBatchSize = 5
        val subsequentBatchSize = 20
        val batch = mutableListOf<MediaItem>()
        val unindexedPathsToScan = mutableListOf<String>()
        var isFirstBatch = true

        for (file in sortedFileIndex) {
            currentCoroutineContext().ensureActive()

            val mediaItem = mediaStoreDataMap[file.absolutePath]?.let { cache ->
                createMediaItemFromMediaStore(file, cache)
            } ?: run {
                unindexedPathsToScan.add(file.absolutePath) // This file was missed by MediaStore, queue it for indexing.
                createMediaItemFromFile(file)
            }

            mediaItem?.let { batch.add(it) }

            val currentBatchSize = if (isFirstBatch) initialBatchSize else subsequentBatchSize
            if (batch.size >= currentBatchSize) {
                emit(batch.toList())
                batch.clear()
                if (isFirstBatch) isFirstBatch = false
            }
        }

        if (batch.isNotEmpty()) {
            emit(batch)
        }
        Log.d(logTag, "Finished streaming all items.")

        // Step 5: Proactively index any files that were missed by MediaStore for the next session.
        if (unindexedPathsToScan.isNotEmpty()) {
            Log.d(logTag, "Found ${unindexedPathsToScan.size} un-indexed files. Triggering background scan.")
            externalScope.launch {
                scanFolders(unindexedPathsToScan)
            }
        }
    }.flowOn(Dispatchers.Default)


    private suspend fun getMediaStoreDataForBuckets(bucketPaths: List<String>?): Map<String, MediaStoreCache> = withContext(Dispatchers.IO) {
        val cacheMap = mutableMapOf<String, MediaStoreCache>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.WIDTH, MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION
        )

        val selection: String?
        val selectionArgs: Array<String>?

        if (bucketPaths != null) {
            // CORRECT WAY: Use BUCKET_ID for non-recursive folder selection.
            // BUCKET_ID is a hash of the lowercase folder path.
            val calculatedBucketIds = bucketPaths.map { it.lowercase(Locale.ROOT).hashCode().toString() }
            if (calculatedBucketIds.isEmpty()) {
                return@withContext emptyMap()
            }

            // Create a WHERE clause with placeholders: "BUCKET_ID IN (?,?,?)"
            val placeholders = calculatedBucketIds.joinToString(separator = ",") { "?" }
            selection = "${MediaStore.Files.FileColumns.BUCKET_ID} IN ($placeholders)" +
                    " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"

            selectionArgs = (calculatedBucketIds +
                    arrayOf(
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                    )).toTypedArray()
        } else {
            // Original behavior for full scans (no specific buckets).
            selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
        }

        val queryUri = MediaStore.Files.getContentUri("external")

        try {
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn)
                    if (!path.isNullOrBlank()) {
                        cacheMap[path] = MediaStoreCache(
                            id = cursor.getLong(idColumn),
                            displayName = cursor.getString(nameColumn),
                            mimeType = cursor.getString(mimeColumn),
                            dateAdded = cursor.getLong(dateAddedColumn),
                            dateModified = cursor.getLong(dateModifiedColumn),
                            size = cursor.getLong(sizeColumn),
                            bucketId = cursor.getString(bucketIdColumn),
                            bucketName = cursor.getString(bucketNameColumn),
                            isVideo = cursor.getInt(mediaTypeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                            width = cursor.getInt(widthColumn),
                            height = cursor.getInt(heightColumn),
                            orientation = cursor.getInt(orientationColumn)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Failed to pre-fetch MediaStore data", e)
        }
        return@withContext cacheMap
    }

    private fun createMediaItemFromMediaStore(file: File, cache: MediaStoreCache): MediaItem {
        val queryUri = MediaStore.Files.getContentUri("external")

        val needsSwap = cache.orientation == 90 || cache.orientation == 270
        val finalWidth = if (needsSwap) cache.height else cache.width
        val finalHeight = if (needsSwap) cache.width else cache.height

        return MediaItem(
            id = file.absolutePath,
            uri = ContentUris.withAppendedId(queryUri, cache.id),
            displayName = cache.displayName ?: file.name,
            mimeType = cache.mimeType ?: "application/octet-stream",
            dateAdded = cache.dateAdded * 1000, // MediaStore dates are in seconds
            dateModified = cache.dateModified * 1000,
            size = cache.size,
            bucketId = cache.bucketId ?: file.parent ?: "",
            bucketName = cache.bucketName ?: file.parentFile?.name ?: "",
            isVideo = cache.isVideo,
            width = finalWidth,
            height = finalHeight
        )
    }

    override suspend fun getAllFolderPaths(): List<String> = withContext(Dispatchers.IO) {
        val paths = observeAllFolders().first().map { it.first }
        Log.d("CacheDebug", "Returning snapshot of folder paths: ${paths.size} items.")
        return@withContext paths
    }

    private suspend fun createMediaItemFromFile(file: File): MediaItem? = withContext(Dispatchers.IO) {
        try {
            val extension = file.extension.lowercase(Locale.ROOT)
            val isVideo = supportedVideoExtensions.contains(extension)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
            val uri = file.toUri()

            var width = 0
            var height = 0

            if (isVideo) {
                try {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(file.absolutePath)
                        val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                        if (rotation == 90 || rotation == 270) {
                            width = videoHeight
                            height = videoWidth
                        } else {
                            width = videoWidth
                            height = videoHeight
                        }
                    }
                } catch (e: Exception) {
                    Log.e(dimensionlogTag, "Failed to get dimensions for video: ${file.path}", e)
                }
            } else { // Is image
                try {
                    val request = ImageRequest.Builder(context).data(file).size(Size.ORIGINAL).build()
                    imageLoader.execute(request).drawable?.let {
                        width = it.intrinsicWidth
                        height = it.intrinsicHeight
                    }
                } catch (e: Exception) {
                    Log.e(dimensionlogTag, "Failed to get dimensions for image: ${file.path}", e)
                }
            }

            MediaItem(
                id = file.absolutePath,
                uri = uri,
                displayName = file.name,
                mimeType = mimeType,
                dateAdded = file.lastModified(),
                dateModified = file.lastModified(),
                size = file.length(),
                bucketId = file.parent ?: "",
                bucketName = file.parentFile?.name ?: "",
                isVideo = isVideo,
                width = width,
                height = height
            )
        } catch (e: Exception) {
            Log.e(logTag, "Failed to create MediaItem for file: ${file.path}", e)
            null
        }
    }

    override suspend fun createNewFolder(
        folderName: String,
        parentDirectory: String?
    ): Result<String> {
        return try {
            val baseDir = if (!parentDirectory.isNullOrBlank()) {
                File(parentDirectory)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }

            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val newFolder = File(baseDir, folderName)
            if (newFolder.exists()) {
                Result.success(newFolder.absolutePath)
            } else {
                if (newFolder.mkdir()) {
                    fileOperationsHelper.scanPaths(listOf(newFolder.absolutePath))
                    Result.success(newFolder.absolutePath)
                } else {
                    Result.failure(Exception("Failed to create directory at ${newFolder.absolutePath}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isMediaFile(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.ROOT)
        return supportedImageExtensions.contains(extension) || supportedVideoExtensions.contains(extension)
    }

    override suspend fun moveMediaToFolder(mediaId: String, targetFolderId: String): MediaItem? {
        return withContext(Dispatchers.IO) {
            try {
                val sourceFile = findFileById(mediaId) ?: return@withContext null
                val newFile = FileManager.moveFile(sourceFile.absolutePath, targetFolderId)
                    ?: return@withContext null

                if (newFile.exists()) {
                    fileOperationsHelper.scanPaths(listOf(sourceFile.absolutePath, newFile.absolutePath))
                    createMediaItemFromFile(newFile)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun findFileById(mediaId: String): File? {
        return try {
            val file = File(mediaId)
            if (file.exists() && file.isFile) {
                file
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun deleteMedia(items: List<MediaItem>): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext true

        val (indexedItems, unindexedItems) = items.partition { it.uri.scheme == "content" }

        var indexedSuccess = true
        if (indexedItems.isNotEmpty()) {
            Log.d(logTag, "Deleting ${indexedItems.size} indexed media items using ContentResolver.")
            try {
                // All media items from MediaStore share this base URI
                val queryUri = MediaStore.Files.getContentUri("external")
                val ids = indexedItems.map { ContentUris.parseId(it.uri) }.toTypedArray()
                val placeholders = ids.joinToString { "?" }
                val selection = "${MediaStore.Files.FileColumns._ID} IN ($placeholders)"
                val selectionArgs = ids.map { it.toString() }.toTypedArray()

                val rowsDeleted = context.contentResolver.delete(queryUri, selection, selectionArgs)
                Log.d(logTag, "ContentResolver deleted $rowsDeleted rows.")
                if (rowsDeleted != indexedItems.size) {
                    Log.w(logTag, "MediaStore delete mismatch. Expected: ${indexedItems.size}, Actual: $rowsDeleted.")
                    indexedSuccess = false
                }
            } catch (e: Exception) {
                Log.e(logTag, "Error deleting indexed media.", e)
                indexedSuccess = false
            }
        }

        var unindexedSuccess = true
        if (unindexedItems.isNotEmpty()) {
            Log.d(logTag, "Deleting ${unindexedItems.size} un-indexed media items using direct File API.")
            var successCount = 0
            val deletedPaths = mutableListOf<String>()
            unindexedItems.forEach { item ->
                // For unindexed items, 'id' is the absolute path
                if (FileManager.deleteFile(item.id)) {
                    deletedPaths.add(item.id)
                    successCount++
                } else {
                    Log.w(logTag, "Failed to delete un-indexed file: ${item.id}")
                }
            }
            if (deletedPaths.isNotEmpty()) {
                fileOperationsHelper.scanPaths(deletedPaths)
            }
            if (successCount != unindexedItems.size) {
                unindexedSuccess = false
            }
        }

        return@withContext indexedSuccess && unindexedSuccess
    }


    override suspend fun moveMedia(
        mediaIds: List<String>,
        targetFolders: List<String>
    ): Map<String, MediaItem> {
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, MediaItem>()
            val pathsToScan = mutableListOf<String>()

            mediaIds.forEachIndexed { index, mediaId ->
                if (index < targetFolders.size) {
                    val mediaItem = moveMediaToFolder(mediaId, targetFolders[index])
                    if (mediaItem != null) {
                        result[mediaId] = mediaItem
                        pathsToScan.add(mediaId) // Original path
                        pathsToScan.add(mediaItem.id) // New path
                    }
                }
            }
            if (result.isNotEmpty()) {
                fileOperationsHelper.scanPaths(pathsToScan)
            }
            result
        }
    }

    override fun getRecentFolders(limit: Int): Flow<List<Pair<String, String>>> = flow {
        val recentFolders = mutableMapOf<String, Long>()
        val queue: Queue<File> = ArrayDeque()

        val root = Environment.getExternalStorageDirectory()
        if (root != null && root.exists()) {
            queue.add(root)
        }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll()
            if (directory == null || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(
                    directory.absolutePath
                )
            ) continue

            getMostRecentFileInDirectory(directory)?.let {
                recentFolders[directory.absolutePath] = it.lastModified()
            }

            try {
                directory.listFiles { file -> file.isDirectory }?.forEach { subDir ->
                    queue.add(subDir)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        val sortedFolders = recentFolders.toList()
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first to File(it.first).name }

        emit(sortedFolders)
    }.flowOn(Dispatchers.IO)

    private fun getMostRecentFileInDirectory(directory: File): File? {
        return try {
            directory.listFiles()
                ?.filter { it.isFile && isMediaFile(it) }
                ?.maxByOrNull { it.lastModified() }
        } catch (e: SecurityException) {
            null
        }
    }

    override fun getFoldersSortedByRecentMedia(): Flow<List<Pair<String, String>>> {
        return getRecentFolders(50)
    }

    override suspend fun getFolderExistence(folderIds: Set<String>): Map<String, Boolean> {
        return withContext(Dispatchers.IO) {
            folderIds.associateWith { folderId ->
                try {
                    File(folderId).exists()
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    override suspend fun getFolderNames(folderIds: Set<String>): Map<String, String> {
        return withContext(Dispatchers.IO) {
            folderIds.associateWith { path ->
                try {
                    File(path).name
                } catch (e: Exception) {
                    path
                }
            }
        }
    }

    private fun isSafeDestination(folderPath: String): Boolean {
        val externalStoragePath = Environment.getExternalStorageDirectory().absolutePath
        val androidDataPath = "$externalStoragePath/Android"
        return !folderPath.startsWith(androidDataPath, ignoreCase = true)
    }

    override suspend fun getFoldersFromPaths(folderPaths: Set<String>): List<Pair<String, String>> {
        return withContext(Dispatchers.IO) {
            folderPaths.mapNotNull { path ->
                try {
                    val file = File(path)
                    if (file.exists() && file.isDirectory) {
                        path to file.name
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun isConventionalSystemChildFolder(folder: File, standardPaths: Set<String>): Boolean {
        val conventionalNames = setOf("camera", "screenshots", "restored")
        val parentPath = folder.parent ?: return false
        return parentPath in standardPaths && folder.name.lowercase(Locale.ROOT) in conventionalNames
    }
    override suspend fun hasCache(): Boolean = withContext(Dispatchers.IO) {
        return@withContext folderDetailsDao.getAll().first().isNotEmpty()
    }

    override suspend fun getInitialFolderDetails(): List<FolderDetails> = withContext(Dispatchers.IO) {
        val dbCache = folderDetailsDao.getAll().first()
        if (dbCache.isNotEmpty()) {
            return@withContext dbCache
                .filter { it.itemCount > 0 }
                .map { it.toFolderDetails() }
        } else {
            // First launch scenario: no cache exists. Trigger a blocking scan to populate it.
            return@withContext getMediaFoldersWithDetails(forceRefresh = true)
        }
    }

    override suspend fun getMediaFoldersWithDetails(forceRefresh: Boolean): List<FolderDetails> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                val dbCache = folderDetailsDao.getAll().first()
                if (dbCache.isNotEmpty()) {
                    Log.d("CacheDebug", "L2 Cache Hit: Returning folder details from database.")
                    val details = dbCache.map { it.toFolderDetails() }.filter { it.itemCount > 0 }
                    folderDetailsCache = details
                    return@withContext details
                }
            }

            Log.d("CacheDebug", "Force refresh or empty cache. Starting two-stage scan.")
            // STAGE 1: Scan for media folders and atomically replace the cache.
            val mediaFolders = scanAndCacheMediaFolders()

            // STAGE 2: Launch a background, fire-and-forget task to find all other folders.
            externalScope.launch {
                scanAndCacheNonMediaFolders()
            }
            return@withContext mediaFolders
        }

    override fun observeMediaFoldersWithDetails(): Flow<List<FolderDetails>> =
        folderDetailsDao.getAll()
            .map { cacheList ->
                cacheList
                    .filter { it.itemCount > 0 }
                    .map { it.toFolderDetails() }
            }
            .flowOn(Dispatchers.IO)

    /**
     * STAGE 1 of the scan. Finds only folders containing media, calculates their details,
     * saves them to the database, and returns the result. This is the fast path for the UI.
     * This now uses the atomic replaceAll to prevent flicker.
     */
    private suspend fun scanAndCacheMediaFolders(): List<FolderDetails> = withContext(Dispatchers.IO) {
        val (finalDetailsList, discoveredFiles) = performSinglePassFileSystemScan()
        Log.d("CacheDebug", "Scan Stage 1 (Media) complete. Found ${finalDetailsList.size} folders.")

        lastFileDiscoveryCache = discoveredFiles
        folderDetailsCache = finalDetailsList

        // The atomic replaceAll in the DAO prevents flicker. If no folders are found,
        // it will correctly clear the cache.
        folderDetailsDao.replaceAll(finalDetailsList.map { it.toFolderDetailsCache() })

        return@withContext finalDetailsList
    }

    /**
     * STAGE 2 of the scan. Finds all folders that are viable move targets (empty or contain only other folders).
     * This runs in the background and updates the cache to make it fully comprehensive for features like FolderSearchManager.
     */
    private suspend fun scanAndCacheNonMediaFolders() = withContext(Dispatchers.IO) {
        Log.d("CacheDebug", "Scan Stage 2 (Target Folders) started...")
        val targetFolders = findViableTargetFolders()
        if (targetFolders.isNotEmpty()) {
            val cacheEntries = targetFolders.map { (path, name) ->
                FolderDetailsCache(
                    path = path,
                    name = name,
                    itemCount = 0,
                    totalSize = 0,
                    isSystemFolder = false // Default for non-media folders
                )
            }
            folderDetailsDao.upsertAll(cacheEntries)
            Log.d("CacheDebug", "Scan Stage 2 complete. Cached ${cacheEntries.size} additional target folders.")
        } else {
            Log.d("CacheDebug", "Scan Stage 2 complete. No new target folders found.")
        }
    }

    private suspend fun findViableTargetFolders(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val targetFolders = mutableListOf<Pair<String, String>>()
        val queue: Queue<File> = ArrayDeque()

        Environment.getExternalStorageDirectory()?.let { queue.add(it) }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll() ?: continue

            // Basic safety and exclusion checks
            if (!directory.exists() || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(directory.absolutePath)) {
                continue
            }

            // Exclude translated 'To Edit' folder
            if (directory.name == localizedToEditFolderName) {
                continue
            }

            try {
                val files = directory.listFiles()
                if (files != null) {
                    // Check if the directory itself contains any files. If so, it's not a viable target.
                    if (files.none { it.isFile }) {
                        targetFolders.add(directory.absolutePath to directory.name)
                    }

                    // Add all subdirectories to the queue for traversal, regardless of the parent's status.
                    files.filter { it.isDirectory }.forEach { queue.add(it) }
                }
            } catch (e: Exception) {
                Log.w(logTag, "findViableTargetFolders: Failed to access directory: ${directory.path}", e)
            }
        }
        return@withContext targetFolders
    }

    /**
     * Performs a highly efficient, single-pass scan of the file system to discover all media
     * folders and calculate their details simultaneously.
     *
     * @return A Pair containing the list of discovered [FolderDetails] and a flat list of all [File] objects found.
     */
    private suspend fun performSinglePassFileSystemScan(): Pair<List<FolderDetails>, List<File>> = withContext(Dispatchers.IO) {
        val processedPaths = preferencesRepository.processedMediaPathsFlow.first()
        val permanentlySortedFolders = preferencesRepository.permanentlySortedFoldersFlow.first()
        val standardSystemDirectoryPaths = getStandardSystemDirectoryPaths()
        val primarySystemPaths = getPrimarySystemDirectoryPaths()

        // Map: Folder Path -> Pair(Mutable List of Files, isMediaFolder)
        val folderContents = mutableMapOf<String, Pair<MutableList<File>, Boolean>>()
        val allDiscoveredFiles = mutableListOf<File>()
        val queue: Queue<File> = ArrayDeque()

        Environment.getExternalStorageDirectory()?.let { queue.add(it) }

        // Phase 1: Discover all files and categorize them by parent folder in a single traversal.
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll() ?: continue

            // Check using translated folder name for exclusion
            if (directory.name == localizedToEditFolderName || !directory.exists() || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(directory.absolutePath)) {
                continue
            }

            try {
                val files = directory.listFiles()
                if (files.isNullOrEmpty()) continue

                for (file in files) {
                    if (file.isDirectory) {
                        queue.add(file)
                    } else {
                        val parentPath = file.parent ?: continue
                        val entry = folderContents.getOrPut(parentPath) { Pair(mutableListOf(), false) }
                        entry.first.add(file)
                        // If we find even one media file, mark the folder as a media folder.
                        if (!entry.second && isMediaFile(file)) {
                            folderContents[parentPath] = entry.copy(second = true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(logTag, "Failed to access directory during single-pass scan: ${directory.path}", e)
            }
        }

        // Phase 2: Process the collected data into FolderDetails, filtering and calculating stats.
        val folderDetailsList = mutableListOf<FolderDetails>()
        folderContents.entries
            .filter { it.value.second } // Only process folders that were marked as containing media.
            .forEach { (folderPath, content) ->
                if (folderPath in permanentlySortedFolders) return@forEach // Skip permanently sorted folders.

                val folderFile = File(folderPath)
                var itemCount = 0
                var totalSize = 0L

                content.first.forEach { file ->
                    if (isMediaFile(file)) {
                        allDiscoveredFiles.add(file)
                        if (file.absolutePath !in processedPaths) {
                            itemCount++
                            totalSize += file.length()
                        }
                    }
                }

                if (itemCount > 0) {
                    val isSystem = folderPath in standardSystemDirectoryPaths || isConventionalSystemChildFolder(folderFile, standardSystemDirectoryPaths)
                    val isPrimarySystem = folderPath in primarySystemPaths
                    folderDetailsList.add(
                        FolderDetails(
                            path = folderPath,
                            name = folderFile.name,
                            itemCount = itemCount,
                            totalSize = totalSize,
                            isSystemFolder = isSystem,
                            isPrimarySystemFolder = isPrimarySystem
                        )
                    )
                }
            }
        return@withContext Pair(folderDetailsList, allDiscoveredFiles)
    }

    override suspend fun handleFolderRename(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        val oldCacheEntry = folderDetailsDao.getFolderByPath(oldPath)
        if (oldCacheEntry != null) {
            folderDetailsDao.deleteByPath(listOf(oldPath))
            val newName = File(newPath).name
            val newCacheEntry = oldCacheEntry.copy(path = newPath, name = newName)
            folderDetailsDao.upsert(newCacheEntry)
        }
        fileOperationsHelper.scanPaths(listOf(oldPath, newPath))
    }

    override suspend fun handleFolderMove(sourcePath: String, destinationPath: String) = withContext(Dispatchers.IO) {
        // 1. Remove the source folder from the cache as it's now empty and deleted.
        folderDetailsDao.deleteByPath(listOf(sourcePath))

        // 2. Rescan the destination folder to update its details.
        rescanSingleFolderAndUpdateCache(destinationPath)

        // 3. Notify MediaStore about the changes.
        fileOperationsHelper.scanPaths(listOf(sourcePath, destinationPath))
    }

    private suspend fun rescanSingleFolderAndUpdateCache(folderPath: String) {
        val folderFile = File(folderPath)
        if (!folderFile.exists() || !folderFile.isDirectory) {
            // If the folder doesn't exist anymore, ensure it's removed from cache.
            folderDetailsDao.deleteByPath(listOf(folderPath))
            return
        }

        var itemCount = 0
        var totalSize = 0L
        val processedPaths = preferencesRepository.processedMediaPathsFlow.first()

        try {
            folderFile.listFiles()?.forEach { file ->
                if (file.isFile && isMediaFile(file)) {
                    if (file.absolutePath !in processedPaths) {
                        itemCount++
                        totalSize += file.length()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Could not list files in $folderPath during single rescan", e)
        }

        if (itemCount > 0) {
            val standardSystemDirectoryPaths = getStandardSystemDirectoryPaths()
            val primarySystemPaths = getPrimarySystemDirectoryPaths()
            val isSystem = folderPath in standardSystemDirectoryPaths || isConventionalSystemChildFolder(folderFile, standardSystemDirectoryPaths)
            val isPrimarySystem = folderPath in primarySystemPaths
            val details = FolderDetails(
                path = folderPath,
                name = folderFile.name,
                itemCount = itemCount,
                totalSize = totalSize,
                isSystemFolder = isSystem,
                isPrimarySystemFolder = isPrimarySystem
            )
            folderDetailsDao.upsert(details.toFolderDetailsCache())
        } else {
            // If no media is left, remove it from the cache.
            folderDetailsDao.deleteByPath(listOf(folderPath))
        }
    }


    override suspend fun isDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            File(path).isDirectory
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getSubdirectories(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.isDirectory) return@withContext emptyList()

            file.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.map { it.absolutePath }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getPrimarySystemDirectoryPaths(): Set<String> {
        val primarySystemRootPaths = setOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS)
        ).mapNotNull { it?.absolutePath }.toSet()

        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

        val primarySystemSubFolderPaths = setOf(
            File(dcimDir, "Camera").absolutePath,
            File(picturesDir, "Screenshots").absolutePath
        )

        return primarySystemRootPaths + primarySystemSubFolderPaths
    }

    private fun getStandardSystemDirectoryPaths(): Set<String> {
        return setOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_SCREENSHOTS),
        ).mapNotNull { it?.absolutePath }.toSet()
    }

    override suspend fun getFoldersWithProcessedMedia(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val processedFolders = preferencesRepository.processedMediaPathsFlow.first()
            .mapNotNull { path ->
                try {
                    File(path).parent
                } catch (e: Exception) {
                    null
                }
            }

        val permanentlySorted = preferencesRepository.permanentlySortedFoldersFlow.first()

        val allHiddenFolderPaths = (processedFolders + permanentlySorted).distinct()

        return@withContext allHiddenFolderPaths.mapNotNull { path ->
            try {
                path to File(path).name
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.second.lowercase(Locale.ROOT) }
    }

    override suspend fun getUnindexedMediaPaths(): List<String> = withContext(Dispatchers.IO) {
        Log.d(logTag, "Starting scan for unindexed media paths.")
        val fileSystemPaths = getAllMediaFilePaths()
        val mediaStorePaths = getMediaStoreKnownPaths()
        val unindexedPaths = fileSystemPaths - mediaStorePaths
        Log.d(logTag, "Differential check found ${unindexedPaths.size} unindexed paths.")
        return@withContext unindexedPaths.toList()
    }

    override suspend fun getMediaStoreKnownPaths(): Set<String> = withContext(Dispatchers.IO) {
        val mediaStorePaths = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val queryUri = MediaStore.Files.getContentUri("external")

        try {
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    cursor.getString(dataColumn)?.let { path ->
                        mediaStorePaths.add(path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Failed to query MediaStore for all paths", e)
        }
        return@withContext mediaStorePaths
    }

    override suspend fun getAllMediaFilePaths(): Set<String> = withContext(Dispatchers.IO) {
        val fileSystemPaths = mutableSetOf<String>()
        val queue: Queue<File> = ArrayDeque()
        Environment.getExternalStorageDirectory()?.let { queue.add(it) }

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = queue.poll() ?: continue

            if (!directory.exists() || !directory.canRead() || directory.name.startsWith(".") || !isSafeDestination(directory.absolutePath)) {
                continue
            }

            try {
                val files = directory.listFiles()
                if (files.isNullOrEmpty()) continue

                for (file in files) {
                    if (file.isDirectory) {
                        queue.add(file)
                    } else if (isMediaFile(file)) {
                        fileSystemPaths.add(file.absolutePath)
                    }
                }
            } catch (e: Exception) {
                Log.w(logTag, "Could not access or list files in: ${directory.path}", e)
            }
        }
        return@withContext fileSystemPaths
    }


    private fun FolderDetailsCache.toFolderDetails(): FolderDetails {
        // This conversion is mainly for observers that might see data before a full scan enrichment.
        // We perform the check here for completeness.
        val primarySystemPaths = getPrimarySystemDirectoryPaths()
        return FolderDetails(
            path = this.path,
            name = this.name,
            itemCount = this.itemCount,
            totalSize = this.totalSize,
            isSystemFolder = this.isSystemFolder,
            isPrimarySystemFolder = this.path in primarySystemPaths
        )
    }

    private fun FolderDetails.toFolderDetailsCache(): FolderDetailsCache = FolderDetailsCache(
        path = this.path,
        name = this.name,
        itemCount = this.itemCount,
        totalSize = this.totalSize,
        isSystemFolder = this.isSystemFolder
    )

    override fun observeAllFolders(): Flow<List<Pair<String, String>>> {
        // The complete, unified list from the single source of truth.
        return folderDetailsDao.getAll().map { cacheList ->
            cacheList
                .map { it.path to it.name }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }
    }

    override suspend fun scanFolders(folderPaths: List<String>) {
        fileOperationsHelper.scanPaths(folderPaths)
    }

    override suspend fun scanPathsAndWait(paths: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext true
        Log.d(logTag, "Requesting synchronous MediaScanner for paths: $paths")

        return@withContext suspendCancellableCoroutine { continuation ->
            val pathsToScan = paths.toTypedArray()
            val scanCount = AtomicInteger(paths.size)

            val listener = MediaScannerConnection.OnScanCompletedListener { path, uri ->
                Log.d(logTag, "MediaScanner finished for $path. New URI: $uri")
                if (scanCount.decrementAndGet() == 0) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
            }

            continuation.invokeOnCancellation {
                // The coroutine was cancelled. We don't need to do anything special here as
                // the MediaScannerConnection is fire-and-forget from the API perspective.
            }

            try {
                MediaScannerConnection.scanFile(context, pathsToScan, null, listener)
            } catch (e: Exception) {
                Log.e(logTag, "MediaScannerConnection.scanFile threw an exception", e)
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    override suspend fun getMediaItemsFromPaths(paths: List<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaItems = mutableListOf<MediaItem>()
        if (paths.isEmpty()) return@withContext emptyList()

        val mediaStoreData = getMediaStoreDataForBuckets(null)

        paths.forEach { path ->
            val file = File(path)
            if (file.exists()) {
                val cache = mediaStoreData[path]
                if (cache != null) {
                    mediaItems.add(createMediaItemFromMediaStore(file, cache))
                } else {
                    // Fallback for files that might not have made it into the cache yet
                    createMediaItemFromFile(file)?.let { mediaItems.add(it) }
                }
            }
        }

        if (mediaItems.size != paths.size) {
            Log.w(logTag, "Path-to-MediaItem conversion mismatch. In: ${paths.size}, Out: ${mediaItems.size}. Some files may not be in MediaStore or failed to convert.")
        }
        return@withContext mediaItems
    }

    override suspend fun removeFoldersFromCache(paths: Set<String>) = withContext(Dispatchers.IO) {
        if (paths.isNotEmpty()) {
            Log.d("CacheDebug", "Surgically removing ${paths.size} folders from cache: $paths")
            folderDetailsDao.deleteByPath(paths.toList())
        }
    }

    override suspend fun getIndexingStatus(): IndexingStatus = withContext(Dispatchers.IO) {
        val fileSystemPaths = getAllMediaFilePaths()
        val mediaStorePaths = getMediaStoreKnownPaths()

        val totalInFileSystem = fileSystemPaths.size
        val indexedInMediaStore = mediaStorePaths.intersect(fileSystemPaths).size

        return@withContext IndexingStatus(indexed = indexedInMediaStore, total = totalInFileSystem)
    }


    override suspend fun triggerFullMediaStoreScan(): Boolean = withContext(Dispatchers.IO) {
        val unindexedPaths = getUnindexedMediaPaths()
        if (unindexedPaths.isEmpty()) {
            Log.d(logTag, "Triggering full scan: No unindexed media found.")
            return@withContext true
        }
        Log.d(logTag, "Triggering full scan for ${unindexedPaths.size} items.")
        return@withContext scanPathsAndWait(unindexedPaths)
    }
}
