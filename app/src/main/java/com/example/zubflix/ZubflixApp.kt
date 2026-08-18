package com.example.zubflix

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.request.RequestOptions
import com.example.zubflix.cloudstream.CloudStreamInitializer
import com.lagradost.cloudstream3.CloudStreamApp
import java.io.File

/**
 * Main Application class for Zubflix.
 * Configures global memory limits, image caching (Coil & Glide),
 * and initializes background streaming engines.
 */
class ZubflixApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize CloudStream host & bridge
        CloudStreamApp.install(this)
        CloudStreamInitializer.init(this)

        // 2. Initialize Glide memory optimizations (RGB_565 and 100MB disk cache)
        initGlide()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB disk limit
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .bitmapConfig(Bitmap.Config.RGB_565) // 50% RAM reduction on grid posters
            .crossfade(true)
            .build()
    }

    private fun initGlide() {
        try {
            val calculator = MemorySizeCalculator.Builder(this)
                .setMemoryCacheScreens(2f)
                .build()

            val builder = GlideBuilder()
                .setMemoryCache(LruResourceCache(calculator.memoryCacheSize.toLong()))
                .setDiskCache(InternalCacheDiskCacheFactory(this, "glide_image_cache", 100L * 1024 * 1024))
                .setDefaultRequestOptions(
                    RequestOptions()
                        .format(DecodeFormat.PREFER_RGB_565)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                )

            Glide.init(this, builder)
        } catch (e: Exception) {
            // Safe fallback if already initialized
        }
    }
}
