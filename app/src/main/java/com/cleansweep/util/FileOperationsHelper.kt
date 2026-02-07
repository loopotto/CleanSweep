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

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.cleansweep.data.model.MediaItem
import com.cleansweep.ui.screens.swiper.PendingChange
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileOperationsHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val logTag ="FileOperationsHelper"

    /**
     * Checks a list of pending changes and returns a new list containing only the changes
     * where the source file still exists on the filesystem.
     * @param changes The list of PendingChange objects to validate.
     * @return A list of PendingChange objects for files that still exist.
     */
    suspend fun filterExistingFiles(changes: List<PendingChange>): List<PendingChange> = withContext(Dispatchers.IO) {
        changes.filter { change ->
            try {
                File(change.item.id).exists()
            } catch (e: SecurityException) {
                Log.w(logTag, "Security exception checking for file existence: ${change.item.id}", e)
                false
            }
        }
    }


    /**
     * Renames a folder on the file system.
     * @param oldPath The current, absolute path of the folder to rename.
     * @param newName The desired new name for the folder (not the full path).
     * @return A Result containing the new absolute path of the folder, or an exception on failure.
     */
    suspend fun renameFolder(oldPath: String, newName: String): Result<String> = withContext(Dispatchers.IO) {
        if (newName.isBlank() || newName.contains(File.separatorChar)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid folder name."))
        }

        try {
            val sourceFolder = File(oldPath)
            if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
                return@withContext Result.failure(IOException("Source folder does not exist or is not a directory."))
            }

            val parentDir = sourceFolder.parentFile
            if (parentDir == null) {
                return@withContext Result.failure(IOException("Cannot rename root directory."))
            }

            val newFolder = File(parentDir, newName)

            if (newFolder.exists()) {
                return@withContext Result.failure(IOException("A folder with the name '$newName' already exists."))
            }

            if (sourceFolder.renameTo(newFolder)) {
                Log.d(logTag, "Successfully renamed folder from $oldPath to ${newFolder.absolutePath}")
                Result.success(newFolder.absolutePath)
            } else {
                Result.failure(IOException("Failed to rename folder."))
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error renaming folder $oldPath to $newName", e)
            Result.failure(e)
        }
    }

    /**
     * Moves all contents from a source folder to a destination folder, then deletes the source folder.
     * @param sourcePath The absolute path of the folder whose contents will be moved.
     * @param destinationPath The absolute path of the target folder.
     * @return A Result containing a Pair of (movedCount, failedCount), or an exception on failure.
     */
    suspend fun moveFolderContents(sourcePath: String, destinationPath: String): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        try {
            val sourceFolder = File(sourcePath)
            val destinationFolder = File(destinationPath)

            if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
                return@withContext Result.failure(IOException("Source folder does not exist."))
            }
            if (destinationFolder.isFile) {
                return@withContext Result.failure(IOException("Destination path points to a file."))
            }

            destinationFolder.mkdirs() // Ensure destination exists

            val filesToMove = sourceFolder.listFiles() ?: emptyArray()
            if (filesToMove.isEmpty()) {
                sourceFolder.delete() // Delete empty source folder
                return@withContext Result.success(Pair(0, 0))
            }

            var movedCount = 0
            var failedCount = 0
            val pathsToScan = mutableListOf(sourcePath, destinationPath)

            filesToMove.forEach { file ->
                val movedFile = FileManager.moveFile(file.absolutePath, destinationPath)
                if (movedFile != null) {
                    movedCount++
                    pathsToScan.add(movedFile.absolutePath)
                } else {
                    failedCount++
                }
            }

            // After moving all contents, delete the now-empty source folder
            if (failedCount == 0) {
                sourceFolder.delete()
            }

            Result.success(Pair(movedCount, failedCount))

        } catch (e: Exception) {
            Log.e(logTag, "Error moving contents from $sourcePath to $destinationPath", e)
            Result.failure(e)
        }
    }

    /**
     * Converts a video file to a single high-quality JPEG image.
     * It extracts a frame, saves it, and returns a new MediaItem for the image.
     * The original video is NOT deleted here; that is the responsibility of the calling logic.
     * @param videoItem The MediaItem representing the video to convert.
     * @param timestampMicros The specific timestamp in microseconds to capture the frame from.
     * @param quality The compression quality of the output JPEG (0-100).
     * @return A Result containing the new MediaItem for the created image, or an exception on failure.
     */
    suspend fun convertVideoToImage(
        videoItem: MediaItem,
        timestampMicros: Long = -1,
        quality: Int = 90
    ): Result<MediaItem> = withContext(Dispatchers.IO) {
        val sourceFile = File(videoItem.id)
        if (!sourceFile.exists()) {
            return@withContext Result.failure(Exception("Source video file not found at ${videoItem.id}"))
        }

        try {
            // 1. Extract frame from video
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoItem.uri)

            // Use getFrameAtTime for a specific timestamp, or getFrameAtIndex(0) for the first frame as a fallback.
            val bitmap = if (timestampMicros >= 0) {
                retriever.getFrameAtTime(timestampMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtIndex(0)
            }
            retriever.release()

            if (bitmap == null) {
                return@withContext Result.failure(Exception("Could not extract a frame from video: ${videoItem.id}"))
            }

            // 2. Save the frame as a new JPEG image
            val newFileName = sourceFile.nameWithoutExtension + ".jpg"
            val newImageFile = File(sourceFile.parent, newFileName)
            FileOutputStream(newImageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)
            }
            Log.d(logTag, "Successfully saved new image to: ${newImageFile.absolutePath} with quality $quality")

            // 3. Scan the new image so MediaStore finds it immediately
            scanPaths(listOf(newImageFile.absolutePath))

            // 4. Create a new MediaItem for the image
            val newMediaItem = createMediaItemFromFile(newImageFile)
            Result.success(newMediaItem)

        } catch (e: Exception) {
            Log.e(logTag, "Failed to convert video to image for ${videoItem.id}", e)
            Result.failure(e)
        }
    }

    /**
     * Triggers a MediaStore scan for a list of file or directory paths.
     * This is essential for ensuring that file operations (creations, deletions, moves)
     * are reflected in the MediaStore, which improves performance for subsequent media queries.
     *
     * @param paths The list of absolute file or directory paths to scan.
     */
    suspend fun scanPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        Log.d(logTag, "Requesting MediaScanner for paths: $paths")
        withContext(Dispatchers.Main) {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { path, uri ->
                Log.d(logTag, "MediaScanner finished for $path. New URI: $uri")
            }
        }
    }

    private fun createMediaItemFromFile(file: File): MediaItem {
        val uri = getUriForFile(file) ?: file.toUri()
        val isVideo = false
        val mimeType = "image/jpeg"

        // Get image dimensions
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight

        return MediaItem(
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
    }

    private fun getUriForFile(file: File): Uri? {
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        val queryUri = MediaStore.Files.getContentUri("external")

        context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val id = cursor.getLong(idColumn)
                return android.content.ContentUris.withAppendedId(queryUri, id)
            }
        }
        return null
    }
}