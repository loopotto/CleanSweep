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

package com.cleansweep.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.Size
import androidx.core.graphics.scale
import com.cleansweep.data.db.dao.PHashDao
import com.cleansweep.data.db.entity.PHashCache
import com.cleansweep.data.db.entity.SimilarityDenial
import com.cleansweep.data.model.MediaItem
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.data.repository.SimilarityThresholdLevel
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.domain.repository.DuplicatesRepository
import com.cleansweep.domain.util.PHashUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.min

class SimilarFinderUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pHashDao: PHashDao,
    private val preferencesRepository: PreferencesRepository,
    private val duplicatesRepository: DuplicatesRepository
) {
    private val TAG = "SimilarMediaFinder"

    /**
     * Data class to hold the results of the scan, including skipped files.
     */
    data class SimilarScanResult(
        val groups: List<SimilarGroup>,
        val skippedFilePaths: List<String>
    )

    private object ColorHistogramUtil {
        private const val BINS_PER_CHANNEL = 4
        private const val HISTOGRAM_SIZE = BINS_PER_CHANNEL * BINS_PER_CHANNEL * BINS_PER_CHANNEL
        private const val QUANTIZATION_FACTOR = 256 / BINS_PER_CHANNEL

        fun generateHistogramString(bitmap: Bitmap): String {
            val scaledBitmap = bitmap.scale(16, 16)
            val histogram = IntArray(HISTOGRAM_SIZE)
            val pixels = IntArray(scaledBitmap.width * scaledBitmap.height)
            scaledBitmap.getPixels(pixels, 0, scaledBitmap.width, 0, 0, scaledBitmap.width, scaledBitmap.height)
            if (!scaledBitmap.isRecycled) scaledBitmap.recycle()

            for (pixel in pixels) {
                val r = Color.red(pixel) / QUANTIZATION_FACTOR
                val g = Color.green(pixel) / QUANTIZATION_FACTOR
                val b = Color.blue(pixel) / QUANTIZATION_FACTOR
                val index = (r * BINS_PER_CHANNEL + g) * BINS_PER_CHANNEL + b
                histogram[index]++
            }
            return histogram.joinToString(",")
        }

        fun areHistogramsSimilar(histStrA: String, histStrB: String, threshold: Double): Boolean {
            try {
                val histA = histStrA.split(',').map { it.toIntOrNull() ?: 0 }
                val histB = histStrB.split(',').map { it.toIntOrNull() ?: 0 }
                if (histA.size != HISTOGRAM_SIZE || histB.size != HISTOGRAM_SIZE) return false

                var intersection = 0.0
                var totalA = 0.0
                var totalB = 0.0
                for (i in 0 until HISTOGRAM_SIZE) {
                    intersection += min(histA[i], histB[i])
                    totalA += histA[i]
                    totalB += histB[i]
                }
                if (totalA == 0.0 || totalB == 0.0) return totalA == totalB
                val normalizedIntersection = (2.0 * intersection) / (totalA + totalB)
                val distance = 1.0 - normalizedIntersection
                return distance <= threshold
            } catch (e: Exception) {
                Log.e("ColorHistogramUtil", "Failed to compare histograms", e)
                return false
            }
        }
    }

    /**
     * Scans the provided media items and finds groups that are visually similar.
     */
    suspend fun findSimilar(
        allMediaItems: List<MediaItem>,
        onProgress: (itemsProcessed: Int) -> Unit,
        pathsToExclude: Set<String> = emptySet()
    ): SimilarScanResult = withContext(Dispatchers.Default) {
        Log.d(TAG, "Starting similar media scan.")

        val pHashCache = pHashDao.getAllHashes().associateBy { it.filePath }
        if (allMediaItems.isEmpty()) {
            onProgress(0)
            return@withContext SimilarScanResult(emptyList(), emptyList())
        }

        Log.d(TAG, "Found ${allMediaItems.size} total media items to analyze.")
        val skippedFiles = mutableListOf<String>()

        val mediaToProcess = allMediaItems.filter { it.id !in pathsToExclude }
        val itemsToHashMap = mediaToProcess.associateBy { it.id }
        val itemsToHash = mediaToProcess.filter { mediaItem ->
            val cached = pHashCache[mediaItem.id]
            cached == null || cached.lastModified != mediaItem.dateModified || cached.size != mediaItem.size ||
                    (!mediaItem.isVideo && cached.histogram == null)
        }

        val fullHashMap = pHashCache.toMutableMap()
        if (itemsToHash.isNotEmpty()) {
            val hashingResult = processInParallel(itemsToHash, onProgress)
            val newHashes = hashingResult.newHashes
            skippedFiles.addAll(hashingResult.skippedFilePaths)

            if (newHashes.isNotEmpty()) {
                pHashDao.upsertHashes(newHashes)
                newHashes.forEach { fullHashMap[it.filePath] = it }
            }
        }

        // Report any remaining items that didn't need hashing as "processed"
        val remainingCount = allMediaItems.size - itemsToHash.size
        if (remainingCount > 0) {
            onProgress(remainingCount)
        }

        val allCurrentPaths = itemsToHashMap.keys
        val allDeviceMediaMap = allMediaItems.associateBy { it.id }
        val deletedPaths = pHashCache.keys - allCurrentPaths
        if (deletedPaths.isNotEmpty()) {
            pHashDao.deleteHashesByPath(deletedPaths.toList())
        }

        val similarityLevel = preferencesRepository.similarityThresholdLevelFlow.first()
        val denialKeys = duplicatesRepository.getSimilarityDenialKeys()

        val finalGroups = groupSimilarMedia(fullHashMap, allCurrentPaths, allDeviceMediaMap, similarityLevel, denialKeys)

        Log.d(TAG, "Similar media scan finished.")
        return@withContext SimilarScanResult(finalGroups, skippedFiles)
    }

    private data class HashingResult(val newHashes: List<PHashCache>, val skippedFilePaths: List<String>)

    private suspend fun processInParallel(
        items: List<MediaItem>,
        onProgress: (itemsProcessed: Int) -> Unit
    ): HashingResult = coroutineScope {
        if (items.isEmpty()) return@coroutineScope HashingResult(emptyList(), emptyList())

        val newHashes = mutableListOf<PHashCache>()
        val skippedPaths = mutableListOf<String>()

        val CHUNK_SIZE = (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(1)
        items.chunked(CHUNK_SIZE).forEach { chunk ->
            val results = chunk.map { mediaItem ->
                async {
                    try {
                        generateHashes(mediaItem)?.let { (pHash, histogram) ->
                            if (pHash != null) {
                                PHashCache(mediaItem.id, mediaItem.dateModified, mediaItem.size, pHash, histogram)
                            } else null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error while processing ${mediaItem.id}. Skipping file.", e)
                        skippedPaths.add(mediaItem.id)
                        null
                    }
                }
            }.awaitAll()

            newHashes.addAll(results.filterNotNull())
            onProgress(chunk.size)
        }

        HashingResult(newHashes, skippedPaths)
    }

    private suspend fun groupSimilarMedia(
        fullHashMap: Map<String, PHashCache>,
        allPaths: Set<String>,
        allDeviceMediaMap: Map<String, MediaItem>,
        similarityLevel: SimilarityThresholdLevel,
        denialKeys: Set<String>
    ): List<SimilarGroup> = coroutineScope {
        val imagePaths = allPaths.filter { allDeviceMediaMap[it]?.isVideo == false }
        val videoPaths = allPaths.filter { allDeviceMediaMap[it]?.isVideo == true }

        val imageBuckets = buildBuckets(fullHashMap, imagePaths)
        val videoBuckets = buildBuckets(fullHashMap, videoPaths)

        val imageGroupsDeferred = async { groupImages(imageBuckets, fullHashMap, similarityLevel, denialKeys) }
        val videoGroupsDeferred = async { groupVideos(videoBuckets, fullHashMap, similarityLevel, denialKeys) }

        val imageGroups = imageGroupsDeferred.await()
        val videoGroups = videoGroupsDeferred.await()

        (imageGroups + videoGroups)
            .mapNotNull { (groupId, paths) ->
                val items = paths.mapNotNull { allDeviceMediaMap[it] }
                if (items.size > 1) {
                    SimilarGroup(
                        pHash = groupId,
                        items = items.sortedBy { it.dateModified }
                    )
                } else null
            }.sortedByDescending { it.items.sumOf { item -> item.size } }
    }

    private fun buildBuckets(
        fullHashMap: Map<String, PHashCache>,
        allPaths: List<String>
    ): Map<String, List<String>> {
        val BUCKET_KEY_LENGTH = 2
        return allPaths.mapNotNull { path ->
            fullHashMap[path]?.let { it to path }
        }.groupBy(
            { (cache, _) ->
                cache.pHash.substringBefore(',').take(BUCKET_KEY_LENGTH)
            },
            { (_, path) -> path }
        )
    }

    private suspend fun groupImages(
        imageBuckets: Map<String, List<String>>,
        fullHashMap: Map<String, PHashCache>,
        similarityLevel: SimilarityThresholdLevel,
        denialKeys: Set<String>
    ): Map<String, Set<String>> = coroutineScope {
        val groups = mutableMapOf<String, MutableSet<String>>()
        val visited = mutableSetOf<String>()

        val pHashSimilarityThreshold = when (similarityLevel) {
            SimilarityThresholdLevel.STRICT -> 3
            SimilarityThresholdLevel.BALANCED -> 5
            SimilarityThresholdLevel.LOOSE -> 8
        }
        val colorHistogramDistanceThreshold = when (similarityLevel) {
            SimilarityThresholdLevel.STRICT -> 0.35
            SimilarityThresholdLevel.BALANCED -> 0.5
            SimilarityThresholdLevel.LOOSE -> 0.65
        }
        val pHashScreenshotThreshold = 2

        val sortedBucketKeys = imageBuckets.keys.sorted()
        val bucketKeyIndexMap = sortedBucketKeys.withIndex().associate { (index, key) -> key to index }

        for ((_, pathsInBucket) in imageBuckets) {
            for (pathA in pathsInBucket) {
                ensureActive()
                if (pathA in visited) continue

                val cacheA = fullHashMap[pathA] ?: continue
                val currentGroup = mutableSetOf(pathA)
                visited.add(pathA)

                val bucketKey = cacheA.pHash.take(2)
                val bucketIndex = bucketKeyIndexMap[bucketKey] ?: continue
                val searchIndices = (bucketIndex - 1)..(bucketIndex + 1)
                val searchSpacePaths = searchIndices.flatMap { index ->
                    sortedBucketKeys.getOrNull(index)?.let { key -> imageBuckets[key] } ?: emptyList()
                }

                for (pathB in searchSpacePaths) {
                    if (pathA == pathB || pathB in visited) continue
                    val cacheB = fullHashMap[pathB] ?: continue

                    // Logical Denial check: Have these files been flagged as different by the user?
                    if (SimilarityDenial.createKey(pathA, pathB) in denialKeys) continue

                    val pHashThreshold = if (isScreenshot(pathA) || isScreenshot(pathB)) pHashScreenshotThreshold else pHashSimilarityThreshold
                    if (PHashUtil.hammingDistance(cacheA.pHash, cacheB.pHash) <= pHashThreshold) {
                        val histA = cacheA.histogram
                        val histB = cacheB.histogram
                        if (histA != null && histB != null && ColorHistogramUtil.areHistogramsSimilar(histA, histB, colorHistogramDistanceThreshold)) {
                            currentGroup.add(pathB)
                        }
                    }
                }

                if (currentGroup.size > 1) {
                    val groupId = "img-${cacheA.pHash}"
                    groups.getOrPut(groupId) { mutableSetOf() }.addAll(currentGroup)
                    visited.addAll(currentGroup)
                }
            }
        }
        groups.mapValues { it.value }
    }

    private suspend fun groupVideos(
        videoBuckets: Map<String, List<String>>,
        fullHashMap: Map<String, PHashCache>,
        similarityLevel: SimilarityThresholdLevel,
        denialKeys: Set<String>
    ): Map<String, Set<String>> = coroutineScope {
        val groups = mutableMapOf<String, MutableSet<String>>()
        val visited = mutableSetOf<String>()

        val videoPHashThreshold = when (similarityLevel) {
            SimilarityThresholdLevel.STRICT -> 2
            SimilarityThresholdLevel.BALANCED -> 4
            SimilarityThresholdLevel.LOOSE -> 7
        }

        val sortedBucketKeys = videoBuckets.keys.sorted()
        val bucketKeyIndexMap = sortedBucketKeys.withIndex().associate { (index, key) -> key to index }

        for ((_, pathsInBucket) in videoBuckets) {
            for (pathA in pathsInBucket) {
                ensureActive()
                if (pathA in visited) continue

                val cacheA = fullHashMap[pathA] ?: continue
                val currentGroup = mutableSetOf(pathA)
                visited.add(pathA)

                val bucketKey = cacheA.pHash.substringBefore(',').take(2)
                val bucketIndex = bucketKeyIndexMap[bucketKey] ?: continue
                val searchIndices = (bucketIndex - 1)..(bucketIndex + 1)
                val searchSpacePaths = searchIndices.flatMap { index ->
                    sortedBucketKeys.getOrNull(index)?.let { key -> videoBuckets[key] } ?: emptyList()
                }

                for (pathB in searchSpacePaths) {
                    if (pathA == pathB || pathB in visited) continue
                    val cacheB = fullHashMap[pathB] ?: continue

                    // Logical Denial check: Have these files been flagged as different by the user?
                    if (SimilarityDenial.createKey(pathA, pathB) in denialKeys) continue

                    if (areVideoHashesSimilar(cacheA.pHash, cacheB.pHash, videoPHashThreshold)) {
                        currentGroup.add(pathB)
                    }
                }

                if (currentGroup.size > 1) {
                    visited.addAll(currentGroup)
                    val groupId = "vid-${cacheA.pHash.substringBefore(',')}"
                    groups.getOrPut(groupId) { mutableSetOf() }.addAll(currentGroup)
                }
            }
        }
        groups.mapValues { it.value }
    }

    private fun areVideoHashesSimilar(hashesA: String, hashesB: String, threshold: Int): Boolean {
        val hashListA = hashesA.split(',').filter { it.isNotBlank() }
        val hashListB = hashesB.split(',').filter { it.isNotBlank() }
        if (hashListA.isEmpty() || hashListB.isEmpty()) return false

        // Check if any hash in A is similar to any hash in B
        return hashListA.any { hashA ->
            hashListB.any { hashB ->
                PHashUtil.hammingDistance(hashA, hashB) <= threshold
            }
        }
    }

    private fun isScreenshot(filePath: String): Boolean {
        return filePath.contains("/screenshots", ignoreCase = true) || File(filePath).name.startsWith("screen", ignoreCase = true)
    }

    private fun generateHashes(mediaItem: MediaItem): Pair<String?, String?>? {
        return if (mediaItem.isVideo) {
            val bitmaps = getVideoFrames(mediaItem)
            if (bitmaps.isEmpty()) return null
            val pHashes = bitmaps.mapNotNull {
                try {
                    val scaledBitmap = it.scale(9, 8)
                    val hash = PHashUtil.calculateDHash(scaledBitmap)
                    scaledBitmap.recycle()
                    hash
                } finally {
                    if (!it.isRecycled) it.recycle()
                }
            }
            if (pHashes.isEmpty()) null else Pair(pHashes.joinToString(","), null)
        } else {
            val bitmap = getThumbnail(mediaItem, 256)
            bitmap?.let {
                try {
                    val pHashBitmap = it.scale(9, 8)
                    val pHash = PHashUtil.calculateDHash(pHashBitmap)
                    val histogram = ColorHistogramUtil.generateHistogramString(it)
                    pHashBitmap.recycle()
                    Pair(pHash, histogram)
                } finally {
                    if (!it.isRecycled) it.recycle()
                }
            }
        }
    }

    private fun getThumbnail(mediaItem: MediaItem, size: Int): Bitmap? {
        return try {
            context.contentResolver.loadThumbnail(mediaItem.uri, Size(size, size), null)
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail generation failed for ${mediaItem.id}. Message: ${e.message}")
            null
        }
    }

    private fun getVideoFrames(mediaItem: MediaItem): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        val frameList = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(context, mediaItem.uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0) return emptyList()

            // Timestamps: 10% and 50% into the video
            val frameTimestamps = listOf(
                (durationMs * 0.10).toLong() * 1000,
                (durationMs * 0.50).toLong() * 1000
            )

            for (timeUs in frameTimestamps) {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                    frameList.add(it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame extraction failed for ${mediaItem.id}", e)
            frameList.forEach { it.recycle() }
            return emptyList()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
        return frameList
    }
}
