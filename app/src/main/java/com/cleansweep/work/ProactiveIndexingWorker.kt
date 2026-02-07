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

package com.cleansweep.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cleansweep.domain.repository.MediaRepository
import com.cleansweep.util.ThumbnailPrewarmer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class ProactiveIndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Injected dependencies
    private val mediaRepository: MediaRepository,
    private val thumbnailPrewarmer: ThumbnailPrewarmer
) : CoroutineWorker(appContext, workerParams) {

    private val logTag ="ProactiveIndexingWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(logTag, "Starting proactive indexing background work.")
        return@withContext try {
            val unindexedPaths = mediaRepository.getUnindexedMediaPaths()

            if (unindexedPaths.isNotEmpty()) {
                Log.d(logTag, "Found ${unindexedPaths.size} un-indexed media files. Starting pre-warm process.")
                // Use the robust pre-warmer instead of a simple scan request.
                thumbnailPrewarmer.prewarm(unindexedPaths)
                Log.d(logTag, "Pre-warm process initiated for un-indexed files.")
            } else {
                Log.d(logTag, "No un-indexed media found. Work complete.")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(logTag, "Proactive indexing failed", e)
            Result.failure()
        }
    }
}