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
import android.os.Build
import androidx.work.WorkManager
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.cleansweep.domain.bus.FileModificationEventBus
import com.cleansweep.util.ProactiveIndexer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // Define a custom qualifier for the application-wide coroutine scope
    @Retention(AnnotationRetention.BINARY)
    @Qualifier
    annotation class ApplicationScope

    // Qualifier for the GIF ImageLoader ---
    @Retention(AnnotationRetention.BINARY)
    @Qualifier
    annotation class GifImageLoader

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        // Use SupervisorJob so that if one coroutine fails, it doesn't cancel the whole scope
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .respectCacheHeaders(false)
            .build()
    }

    // --- Provider for the GIF-specific ImageLoader ---
    @Provides
    @Singleton
    @GifImageLoader
    fun provideGifImageLoader(@ApplicationContext context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(ImageDecoderDecoder.Factory())
            }
            .respectCacheHeaders(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideFileModificationEventBus(): FileModificationEventBus {
        return FileModificationEventBus()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideProactiveIndexer(@ApplicationContext context: Context): ProactiveIndexer {
        return ProactiveIndexer(context)
    }
}
