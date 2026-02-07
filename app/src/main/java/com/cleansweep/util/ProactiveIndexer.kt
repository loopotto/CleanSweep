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
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cleansweep.work.ProactiveIndexingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProactiveIndexer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)
    private val logTag ="ProactiveIndexer"
    companion object {
        private const val UNIQUE_WORK_NAME = "GlobalMediaIndex"
    }

    /**
     * Schedules a unique, low-priority background job to find and index any media
     * on the device that is not currently known to the MediaStore.
     *
     * It uses WorkManager's unique work policy to ensure that this job is not
     * scheduled multiple times if one is already pending or running.
     * The job is constrained to only run when the device has storage is not low,
     * making it a low-impact maintenance task.
     */
    fun scheduleGlobalIndex() {
        Log.d(logTag, "Attempting to schedule global proactive indexing work.")

        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()

        val indexingRequest = OneTimeWorkRequestBuilder<ProactiveIndexingWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        // Enqueue the work as unique, keeping the existing work if it's already scheduled.
        // This prevents race conditions and redundant scans on multiple app startups.
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            indexingRequest
        )
        Log.d(logTag, "Enqueue request sent to WorkManager with KEEP policy.")
    }
}