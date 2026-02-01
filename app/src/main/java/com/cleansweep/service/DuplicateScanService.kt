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

package com.cleansweep.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import com.cleansweep.R
import com.cleansweep.data.repository.DuplicateScanScope
import com.cleansweep.data.repository.PreferencesRepository
import com.cleansweep.domain.model.DuplicateGroup
import com.cleansweep.domain.model.ScanResultGroup
import com.cleansweep.domain.model.SimilarGroup
import com.cleansweep.domain.repository.DuplicatesRepository
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.domain.repository.ScanScopeType
import com.cleansweep.domain.usecase.DuplicateFinderUseCase
import com.cleansweep.domain.usecase.SimilarFinderUseCase
import com.cleansweep.domain.util.HiddenFileFilter
import com.cleansweep.ui.MainActivity
import com.cleansweep.ui.navigation.DUPLICATES_GRAPH_ROUTE
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class DuplicateScanService : LifecycleService() {

    @Inject
    lateinit var stateHolder: DuplicateScanStateHolder
    @Inject
    lateinit var duplicateFinderUseCase: DuplicateFinderUseCase
    @Inject
    lateinit var similarFinderUseCase: SimilarFinderUseCase
    @Inject
    lateinit var duplicatesRepository: DuplicatesRepository
    @Inject
    lateinit var mediaRepository: MediaRepository
    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var notificationManager: NotificationManager
    private var scanJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private data class ProgressTracker(
        val totalUnits: Int,
        var completedUnits: Int = 0,
        var currentPhase: String
    ) {
        fun add(units: Int) {
            completedUnits += units
        }
        fun getProgress(): Float {
            return if (totalUnits == 0) 1f else (completedUnits.toFloat() / totalUnits.toFloat()).coerceIn(0f, 1f)
        }
        fun setPhase(phase: String) {
            currentPhase = phase
        }
    }

    companion object {
        const val ACTION_START_SCAN = "com.cleansweep.ACTION_START_SCAN"
        const val ACTION_CANCEL_SCAN = "com.cleansweep.ACTION_CANCEL_SCAN"
        const val EXTRA_SCAN_EXACT = "com.cleansweep.EXTRA_SCAN_EXACT"
        const val EXTRA_SCAN_SIMILAR = "com.cleansweep.EXTRA_SCAN_SIMILAR"
        private const val PROGRESS_CHANNEL_ID = "DUPLICATE_SCAN_CHANNEL"
        private const val RESULT_CHANNEL_ID = "DUPLICATE_RESULT_CHANNEL"
        private const val PROGRESS_NOTIFICATION_ID = 101
        private const val RESULT_NOTIFICATION_ID = 102

        private val DEEP_LINK_URI = "app://com.cleansweep/$DUPLICATES_GRAPH_ROUTE".toUri()

        private const val PHASE_GATHERING = "Phase 1: Gathering files..."
        private const val PHASE_FILTERING = "Phase 2: Filtering files..."
        private const val PHASE_PREPARING = "Phase 3: Preparing media..."
        private const val PHASE_HASHING_EXACT = "Scanning for exact duplicates..."
        private const val PHASE_HASHING_SIMILAR = "Scanning for similar media..."

        // Define progress allocation for each phase
        private const val GATHERING_PROGRESS = 8
        private const val FILTERING_PROGRESS = 2
        private const val PREPARING_PROGRESS = 10
        private const val HASHING_PROGRESS = 80
        private const val BASE_PROGRESS_AFTER_PREPARE = GATHERING_PROGRESS + FILTERING_PROGRESS + PREPARING_PROGRESS
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        Log.d("DuplicateScanService", "Service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_SCAN -> {
                if (scanJob?.isActive != true) {
                    val scanForExact = intent.getBooleanExtra(EXTRA_SCAN_EXACT, true)
                    val scanForSimilar = intent.getBooleanExtra(EXTRA_SCAN_SIMILAR, true)
                    Log.d("DuplicateScanService", "Received START_SCAN with exact=$scanForExact, similar=$scanForSimilar")
                    startForeground(PROGRESS_NOTIFICATION_ID, createProgressNotification(0, stateHolder.state.value.progressPhase ?: "Preparing..."))
                    startScan(scanForExact, scanForSimilar)
                }
            }
            ACTION_CANCEL_SCAN -> {
                Log.d("DuplicateScanService", "Received CANCEL_SCAN action.")
                scanJob?.cancel(CancellationException("User initiated cancellation"))
            }
        }

        return START_NOT_STICKY
    }

    private fun acquireWakeLock(timeout: Long) {
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CleanSweep::DuplicateScanWakeLock"
            ).apply {
                acquire(timeout)
            }
            Log.d("DuplicateScanService", "WakeLock acquired with a timeout of ${timeout}ms.")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
            Log.d("DuplicateScanService", "WakeLock released.")
        }
    }

    private fun updateSharedProgress(tracker: ProgressTracker) {
        val progress = tracker.getProgress()
        stateHolder.setProgress(progress, tracker.currentPhase)
        notificationManager.notify(
            PROGRESS_NOTIFICATION_ID,
            createProgressNotification((progress * 100).toInt(), tracker.currentPhase)
        )
    }

    private suspend fun validateAndFilterPaths(allPaths: List<String>): Pair<List<String>, ScanScopeType> {
        val scanScope = preferencesRepository.duplicateScanScopeFlow.first()
        if (scanScope == DuplicateScanScope.ALL_FILES) {
            return Pair(allPaths, ScanScopeType.FULL)
        }

        val listToValidate = if (scanScope == DuplicateScanScope.INCLUDE_LIST) {
            preferencesRepository.duplicateScanIncludeListFlow.first()
        } else {
            preferencesRepository.duplicateScanExcludeListFlow.first()
        }

        if (listToValidate.isEmpty()) {
            return Pair(allPaths, ScanScopeType.FULL) // No rules to apply, treat as full scan
        }

        val validPaths = listToValidate.filter { File(it).exists() }.toSet()
        val removedCount = listToValidate.size - validPaths.size
        if (removedCount > 0) {
            Log.d("DuplicateScanService", "Removed $removedCount non-existent folders from scan scope settings.")
            if (scanScope == DuplicateScanScope.INCLUDE_LIST) {
                preferencesRepository.setDuplicateScanIncludeList(validPaths)
            } else {
                preferencesRepository.setDuplicateScanExcludeList(validPaths)
            }
            val plural = if (removedCount > 1) "s" else ""
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Removed $removedCount non-existent folder$plural from scan scope settings.", Toast.LENGTH_LONG).show()
            }
        }

        if (validPaths.isEmpty()) {
            return Pair(allPaths, ScanScopeType.FULL) // No valid rules left to apply, treat as full scan
        }

        val filteredPaths = if (scanScope == DuplicateScanScope.INCLUDE_LIST) {
            allPaths.filter { path ->
                validPaths.any { includePath ->
                    path.startsWith(includePath)
                }
            }
        } else { // EXCLUDE_LIST
            allPaths.filterNot { path ->
                validPaths.any { excludePath ->
                    path.startsWith(excludePath)
                }
            }
        }
        return Pair(filteredPaths, ScanScopeType.SCOPED)
    }

    private fun startScan(scanForExact: Boolean, scanForSimilar: Boolean) {
        Log.d("DuplicateScanService", "startScan invoked.")
        scanJob = serviceScope.launch {
            val allUnreadableOrUnscannableFiles = mutableSetOf<String>()
            lateinit var scanScopeType: ScanScopeType

            try {
                // Quick check for immediate cancellation
                ensureActive()

                // The ViewModel is the source of truth for the initial phase, which we read from the state holder
                val tracker = ProgressTracker(100, 0, stateHolder.state.value.progressPhase ?: PHASE_PREPARING)
                updateSharedProgress(tracker) // Immediately report the correct starting phase

                // --- Phase 0: Load existing cache of unreadable files ---
                val cachedUnreadable = duplicatesRepository.getUnreadableFileCache()
                val cachedUnreadableMap = cachedUnreadable.associateBy { it.filePath }
                Log.d("DuplicateScanService", "Loaded ${cachedUnreadable.size} items from unreadable file cache.")

                // --- Phase 1: Gathering & Filtering by Scope ---
                tracker.setPhase(PHASE_GATHERING)
                updateSharedProgress(tracker)
                Log.d("DuplicateScanService", "Phase 1: Finding all media files.")
                val allFileSystemPaths = mediaRepository.getAllMediaFilePaths()
                val (scopedPaths, determinedScopeType) = validateAndFilterPaths(allFileSystemPaths.toList())
                scanScopeType = determinedScopeType
                Log.d("DuplicateScanService", "Scan scope is ${scanScopeType.name}. Paths after filtering: ${scopedPaths.size}/${allFileSystemPaths.size}")


                // Calculate dynamic timeout and acquire wakelock based on scoped paths
                val videoExtensions = setOf(".mp4", ".mkv", ".webm", ".3gp", ".mov")
                val videoCount = scopedPaths.count { path ->
                    videoExtensions.any { ext -> path.endsWith(ext, ignoreCase = true) }
                }
                val imageCount = scopedPaths.size - videoCount
                // Heuristic: 2 min base + 150ms/image + 750ms/video
                val timeout = 120_000L + (imageCount * 150L) + (videoCount * 750L)
                acquireWakeLock(timeout)

                // Forceful cancellation check after blocking I/O
                if (!isActive) throw CancellationException("Cancelled after gathering file paths.")

                Log.d("DuplicateScanService", "Found ${scopedPaths.size} total files from file system within scope.")
                tracker.add(GATHERING_PROGRESS)
                updateSharedProgress(tracker)

                // --- Phase 2: Pre-filtering ---
                tracker.setPhase(PHASE_FILTERING)
                updateSharedProgress(tracker)
                val filesToProcess = scopedPaths
                    .filterNot { HiddenFileFilter.isPathExcludedFromScan(it) }
                    .map { File(it) }
                    .filter { file ->
                        val cached = cachedUnreadableMap[file.absolutePath]
                        if (cached != null && cached.lastModified == file.lastModified() && cached.size == file.length()) {
                            allUnreadableOrUnscannableFiles.add(file.absolutePath)
                            false // Exclude from processing
                        } else {
                            true // Include in processing
                        }
                    }
                // Forceful cancellation check after potentially long filter operation
                if (!isActive) throw CancellationException("Cancelled after filtering file paths.")

                Log.d("DuplicateScanService", "After hidden file and cache filters: ${filesToProcess.size} files to process.")
                tracker.add(FILTERING_PROGRESS)
                updateSharedProgress(tracker)


                // --- Phase 3: Fetching All MediaItem objects ---
                tracker.setPhase(PHASE_PREPARING)
                updateSharedProgress(tracker)
                val allMediaItems = mediaRepository.getMediaItemsFromPaths(filesToProcess.map { it.absolutePath })

                // Forceful cancellation check after blocking I/O
                if (!isActive) throw CancellationException("Cancelled after preparing media items.")

                Log.d("DuplicateScanService", "MediaItem conversion complete. Found ${allMediaItems.size} items.")
                tracker.add(PREPARING_PROGRESS)
                updateSharedProgress(tracker)

                // Report files that couldn't be converted to MediaItems
                if (filesToProcess.size > allMediaItems.size) {
                    val convertedPaths = allMediaItems.map { it.id }.toSet()
                    val failedConversionPaths = filesToProcess.filter { it.absolutePath !in convertedPaths }.map { it.absolutePath }
                    allUnreadableOrUnscannableFiles.addAll(failedConversionPaths)
                    Log.w("DuplicateScanService", "${failedConversionPaths.size} files failed to be converted to MediaItems.")
                }

                // --- Hashing Progress Setup ---
                var totalHashingUnits = 0
                if (scanForExact) totalHashingUnits += allMediaItems.size
                if (scanForSimilar) totalHashingUnits += allMediaItems.size
                val hashingProgress = ProgressTracker(totalHashingUnits, 0, "")

                fun updateHashingProgress(processedUnits: Int) {
                    hashingProgress.add(processedUnits)
                    // Scale hashing progress to the remaining percentage of the bar
                    val hashingPercentage = (hashingProgress.getProgress() * HASHING_PROGRESS).toInt()
                    tracker.completedUnits = BASE_PROGRESS_AFTER_PREPARE + hashingPercentage
                    tracker.currentPhase = hashingProgress.currentPhase
                    updateSharedProgress(tracker)
                }

                // --- Phase 4: Run Duplicate Finders ---
                var exactResults = listOf<DuplicateGroup>()
                if (scanForExact) {
                    hashingProgress.setPhase(PHASE_HASHING_EXACT)
                    updateSharedProgress(tracker) // Show the initial hashing phase name
                    Log.d("DuplicateScanService", "Executing exact duplicates scan.")
                    val exactScanResult = duplicateFinderUseCase.findDuplicates(
                        allMediaItems = allMediaItems,
                        onProgress = { itemsProcessed -> updateHashingProgress(itemsProcessed) }
                    )
                    exactResults = exactScanResult.groups
                    allUnreadableOrUnscannableFiles.addAll(exactScanResult.skippedFilePaths)
                    Log.d("DuplicateScanService", "Exact duplicates scan complete. Found ${exactResults.size} groups, skipped ${exactScanResult.skippedFilePaths.size} files.")
                }
                ensureActive()

                var similarResults = listOf<SimilarGroup>()
                if (scanForSimilar) {
                    hashingProgress.setPhase(PHASE_HASHING_SIMILAR)
                    updateSharedProgress(tracker) // Show the initial hashing phase name
                    Log.d("DuplicateScanService", "Executing similar media scan.")
                    val similarScanResult = similarFinderUseCase.findSimilar(
                        allMediaItems = allMediaItems,
                        onProgress = { itemsProcessed -> updateHashingProgress(itemsProcessed) }
                    )
                    similarResults = similarScanResult.groups
                    allUnreadableOrUnscannableFiles.addAll(similarScanResult.skippedFilePaths)
                    Log.d("DuplicateScanService", "Similar media scan complete. Found ${similarResults.size} groups, skipped ${similarScanResult.skippedFilePaths.size} files.")
                }
                ensureActive()

                // --- Phase 5: Combine and Finalize Results ---
                val exactResultFileSets = exactResults.map { it.items.map { item -> item.id }.toSet() }.toSet()
                val filteredSimilarResults = similarResults.filterNot { similarGroup ->
                    val similarSet = similarGroup.items.map { item -> item.id }.toSet()
                    similarSet in exactResultFileSets
                }

                val combinedResults = (exactResults + filteredSimilarResults)
                    .sortedWith(
                        compareBy<ScanResultGroup> { it !is DuplicateGroup }
                            .thenByDescending { it.items.sumOf { item -> item.size } }
                    )

                val hiddenGroupIds = duplicatesRepository.getHiddenGroupIds()
                val finalFilteredResults = if (hiddenGroupIds.isNotEmpty()) {
                    // We filter groups by compositionId to satisfy the requirement that a group
                    // only stays hidden if its exact membership remains unchanged.
                    combinedResults.filterNot { it.compositionId in hiddenGroupIds }
                } else {
                    combinedResults
                }

                Log.d("DuplicateScanService", "Scan complete. Final groups: ${finalFilteredResults.size}, Total unreadable/unscannable files: ${allUnreadableOrUnscannableFiles.size}")

                // Update the unreadable cache for next time
                if (allUnreadableOrUnscannableFiles.isNotEmpty()) {
                    val filesToCache = allUnreadableOrUnscannableFiles.mapNotNull {
                        try { File(it) } catch (e: Exception) { null }
                    }.filter { it.exists() }
                    duplicatesRepository.updateUnreadableFileCache(filesToCache)
                    Log.d("DuplicateScanService", "Updated unreadable file cache with ${filesToCache.size} items.")
                }
                preferencesRepository.setHasRunDuplicateScanOnce()

                val completionTimestamp = System.currentTimeMillis()
                stateHolder.setComplete(
                    results = finalFilteredResults,
                    unscannableFiles = allUnreadableOrUnscannableFiles.toList(),
                    timestamp = completionTimestamp,
                    scanScopeType = scanScopeType
                )

                // --- Persist the final results to the database ---
                duplicatesRepository.saveScanResults(
                    groups = finalFilteredResults,
                    unscannableFiles = allUnreadableOrUnscannableFiles.toList(),
                    scopeType = scanScopeType,
                    timestamp = completionTimestamp
                )
                Log.d("DuplicateScanService", "Saved final scan results to the database with scope: ${scanScopeType.name}.")

            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.d("DuplicateScanService", "Scan was cancelled. Reason: ${e.message}")
                    stateHolder.setCancelled()
                } else {
                    Log.e("DuplicateScanService", "Error during scan", e)
                    stateHolder.setError("Error during scan: ${e.message}")
                }
            } finally {
                Log.d("DuplicateScanService", "Scan job finally block. Final state: ${stateHolder.state.value.scanState}.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                showFinalNotification(stateHolder.state.value)
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    private fun createProgressNotification(progress: Int, phase: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val cancelIntent = Intent(this, DuplicateScanService::class.java).apply { action = ACTION_CANCEL_SCAN }
        val cancelPendingIntent = PendingIntent.getService(this, 1, cancelIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setContentTitle("Scanning for Duplicates")
            .setContentText(phase)
            .setSmallIcon(R.drawable.ic_duplicates_scan)
            .setProgress(100, progress, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_cancel, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun showFinalNotification(finalState: DuplicateScanState) {
        val intent = Intent(Intent.ACTION_VIEW, DEEP_LINK_URI).apply {
            `package` = packageName
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        when (finalState.scanState) {
            BackgroundScanState.Complete -> {
                val exactCount = finalState.results.count { it is DuplicateGroup }
                val similarCount = finalState.results.count { it is SimilarGroup }
                val skippedCount = finalState.unscannableFiles.size
                val summaryLines = mutableListOf<String>()

                if (exactCount > 0) summaryLines.add("Exact Duplicates: $exactCount groups")
                if (similarCount > 0) summaryLines.add("Similar Media: $similarCount groups")
                if (skippedCount > 0) summaryLines.add("Skipped: $skippedCount unreadable files")

                notificationBuilder
                    .setSmallIcon(R.drawable.ic_duplicates_scan)
                    .setContentTitle("Scan Complete")

                if (summaryLines.isEmpty()) {
                    notificationBuilder.setContentText("No duplicates found.")
                } else {
                    val inboxStyle = NotificationCompat.InboxStyle()
                        .setBigContentTitle("Scan Complete")
                    summaryLines.forEach { inboxStyle.addLine(it) }

                    if (finalState.unscannableFiles.isNotEmpty() && finalState.unscannableFiles.size <= 5) {
                        inboxStyle.addLine("") // separator
                        finalState.unscannableFiles.forEach { path ->
                            inboxStyle.addLine(File(path).name)
                        }
                    }
                    notificationBuilder.setContentText(summaryLines.joinToString(" • "))
                    notificationBuilder.setStyle(inboxStyle)
                }
            }
            BackgroundScanState.Cancelled -> {
                notificationBuilder
                    .setSmallIcon(R.drawable.ic_duplicates_scan)
                    .setContentTitle("Scan Cancelled")
                    .setContentText("The duplicate scan was cancelled.")
            }
            BackgroundScanState.Error -> {
                notificationBuilder
                    .setSmallIcon(R.drawable.ic_duplicates_scan)
                    .setContentTitle("Scan Failed")
                    .setContentText(finalState.errorMessage ?: "An unknown error occurred.")
            }
            else -> return
        }
        notificationManager.notify(RESULT_NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun createNotificationChannels() {
        val progressChannel = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            "Duplicate Scan Progress",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows the progress of the duplicate files scan."
            setSound(null, null)
        }
        val resultChannel = NotificationChannel(
            RESULT_CHANNEL_ID,
            "Duplicate Scan Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows the final result of the duplicate files scan."
        }
        notificationManager.createNotificationChannel(progressChannel)
        notificationManager.createNotificationChannel(resultChannel)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        notificationManager.cancel(PROGRESS_NOTIFICATION_ID)
        releaseWakeLock()
        Log.d("DuplicateScanService", "Service destroyed, progress notification cancelled.")
    }
}
