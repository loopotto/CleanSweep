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
import androidx.room.Query
import androidx.room.Upsert
import com.cleansweep.data.db.entity.FileSignatureCache

@Dao
interface FileSignatureDao {
    @Query("SELECT * FROM file_signature_cache")
    suspend fun getAllHashes(): List<FileSignatureCache>

    @Upsert
    suspend fun upsertHashes(hashes: List<FileSignatureCache>)

    @Query("DELETE FROM file_signature_cache WHERE filePath IN (:paths)")
    suspend fun deleteHashesByPath(paths: List<String>)
}
