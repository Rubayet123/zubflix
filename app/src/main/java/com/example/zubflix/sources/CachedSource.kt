package com.example.zubflix.sources

import android.content.Context
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSource

/**
 * Universal Caching Wrapper for all Streaming Sources.
 * Decorates any StreamingSource with memory-based caching and configurable TTL.
 */
class CachedSource(
    val source: StreamingSource,
    private val context: Context
) : StreamingSource {

    override val name: String get() = source.name
    override val hasBackdropSupport: Boolean get() = source.hasBackdropSupport

    private var cachedHomeCategories: List<StreamingCategory>? = null
    private var lastCacheTime: Long = 0
    private val gson = com.google.gson.Gson()
    private val cacheFileName = "${name.lowercase().replace(" ", "_")}_cache.json"

    override suspend fun getHomeCategories(): List<StreamingCategory> {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        // Default TTL is 2 hours (user requested 2,4,8...)
        val ttlHours = prefs.getInt("cache_ttl_hours", 2)
        val ttlMs = ttlHours * 3600000L
        val now = System.currentTimeMillis()

        // Load metadata from prefs
        lastCacheTime = prefs.getLong("${name}_last_cache_time", 0L)

        // 1. Check memory cache first
        if (cachedHomeCategories != null && (now - lastCacheTime) < ttlMs) {
            return cachedHomeCategories!!
        }

        // 2. Check disk cache if memory is empty
        if (cachedHomeCategories == null) {
            val diskCache = loadFromDisk()
            if (diskCache != null && (now - lastCacheTime) < ttlMs) {
                cachedHomeCategories = diskCache.map { processCategory(it) }
                return cachedHomeCategories!!
            }
        }

        // 3. Scrape fresh data
        val result = try {
            source.getHomeCategories().map { processCategory(it) }
        } catch (e: Exception) {
            // If scrape fails, try to return expired disk cache as fallback
            loadFromDisk()?.map { processCategory(it) } ?: emptyList()
        }
        
        if (result.isNotEmpty()) {
            cachedHomeCategories = result
            lastCacheTime = now
            prefs.edit()
                .putLong("${name}_last_cache_time", now)
                .apply()
            saveToDisk(result)
        }
        return result
    }

    private fun saveToDisk(data: List<StreamingCategory>) {
        try {
            val json = gson.toJson(data)
            context.openFileOutput(cacheFileName, Context.MODE_PRIVATE).use {
                it.write(json.toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromDisk(): List<StreamingCategory>? {
        return try {
            val file = context.getFileStreamPath(cacheFileName)
            if (!file.exists()) return null
            
            val json = context.openFileInput(cacheFileName).bufferedReader().use { it.readText() }
            val type = object : com.google.gson.reflect.TypeToken<List<StreamingCategory>>() {}.type
            gson.fromJson<List<StreamingCategory>>(json, type)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<StreamingItem> = 
        source.search(query).map { it.copy(sourceName = source.name) }

    override suspend fun getDetails(id: String): StreamingItem? = 
        source.getDetails(id)?.copy(sourceName = source.name)

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> =
        source.getCategoryContent(categoryId, page).map { it.copy(sourceName = source.name) }

    private fun processCategory(category: StreamingCategory): StreamingCategory {
        val shouldKeepAll = category.hideViewMore || 
                category.id == "continue_watching" || 
                category.id.contains("network") || 
                category.id.contains("genre") || 
                category.id.contains("region") || 
                category.items.any { it.isCategory }
        val processedItems = if (shouldKeepAll) category.items else category.items.take(12)
        return category.copy(items = processedItems.map { it.copy(sourceName = source.name) })
    }

    override suspend fun getSeasonEpisodes(itemId: String, seasonNumber: Int): List<StreamingEpisode> =
        source.getSeasonEpisodes(itemId, seasonNumber)

    override suspend fun extractVideoLinks(data: String): Map<String, String> =
        source.extractVideoLinks(data)

    suspend fun extractVideoLinksStreaming(
        data: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        onStreamFound: suspend (streams: Map<String, String>) -> Unit
    ) {
        val src = source
        if (src is SingleCloudStreamPluginSource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else if (src is NuvioSource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else if (src is MovieLinkBDSource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else if (src is MovieBoxWebSource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else if (src is MovieBoxAppSource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else if (src is MovieBoxINSource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else if (src is CtgMoviesSource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else if (src is CinefreakSource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else if (src is RtallySource) {
            src.extractVideoLinksStreaming(data, onProgress, onStreamFound)
        } else {
            val streams = extractVideoLinks(data)
            if (streams.isNotEmpty()) {
                onStreamFound(streams)
            }
        }
    }

    override fun invalidateCache() {
        cachedHomeCategories = null
        lastCacheTime = 0
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("${name}_last_cache_time")
            .apply()
        try {
            context.deleteFile(cacheFileName)
        } catch (e: Exception) {}
        source.invalidateCache()
    }

    fun updateCacheSize(sizeMb: Long) {
        // Just invalidate on large config changes
        invalidateCache()
    }
}
