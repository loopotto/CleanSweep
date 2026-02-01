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

package com.cleansweep.di

import android.content.Context
import androidx.room.Room
import com.cleansweep.data.db.CleanSweepDatabase
import com.cleansweep.data.db.dao.FileSignatureDao
import com.cleansweep.data.db.dao.FolderDetailsDao
import com.cleansweep.data.db.dao.PHashDao
import com.cleansweep.data.db.dao.ScanResultCacheDao
import com.cleansweep.data.db.dao.SimilarityDenialDao
import com.cleansweep.data.db.dao.UnreadableFileCacheDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CleanSweepDatabase {
        return Room.databaseBuilder(
            context,
            CleanSweepDatabase::class.java,
            CleanSweepDatabase.DATABASE_NAME
        ).addMigrations(
            CleanSweepDatabase.MIGRATION_1_2,
            CleanSweepDatabase.MIGRATION_2_3
        ).build()
    }

    @Provides
    @Singleton
    fun provideFileSignatureDao(database: CleanSweepDatabase): FileSignatureDao {
        return database.fileSignatureDao()
    }

    @Provides
    @Singleton
    fun providePHashDao(database: CleanSweepDatabase): PHashDao {
        return database.pHashDao()
    }

    @Provides
    @Singleton
    fun provideFolderDetailsDao(database: CleanSweepDatabase): FolderDetailsDao {
        return database.folderDetailsDao()
    }

    @Provides
    @Singleton
    fun provideUnreadableFileCacheDao(database: CleanSweepDatabase): UnreadableFileCacheDao {
        return database.unreadableFileCacheDao()
    }

    @Provides
    @Singleton
    fun provideScanResultCacheDao(database: CleanSweepDatabase): ScanResultCacheDao {
        return database.scanResultCacheDao()
    }

    @Provides
    @Singleton
    fun provideSimilarityDenialDao(database: CleanSweepDatabase): SimilarityDenialDao {
        return database.similarityDenialDao()
    }
}
