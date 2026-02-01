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

package com.cleansweep.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a pair of files that the user has explicitly flagged as NOT being similar.
 * This acts as a "logical denial" to prevent the algorithm from grouping them in the future.
 */
@Entity(tableName = "similarity_denials")
data class SimilarityDenial(
    @PrimaryKey val pairKey: String, // Format: "pathA|pathB" where pathA < pathB
    val pathA: String,
    val pathB: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun createKey(p1: String, p2: String): String {
            return if (p1 < p2) "$p1|$p2" else "$p2|$p1"
        }
    }
}
