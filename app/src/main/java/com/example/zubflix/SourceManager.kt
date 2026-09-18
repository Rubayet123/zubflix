package com.example.zubflix

import com.example.zubflix.model.StreamingSource
import com.example.zubflix.sources.CachedSource
import com.example.zubflix.sources.CircleFtpSource
import com.example.zubflix.sources.ICCFtpSource
import com.example.zubflix.sources.DhakaFlixSource
import com.example.zubflix.sources.NuvioSource
import com.example.zubflix.sources.DiscoveryFtpSource
import com.example.zubflix.sources.MovieLinkBDSource
import com.example.zubflix.sources.CtgMoviesSource
import com.example.zubflix.sources.MovieBoxWebSource
import com.example.zubflix.sources.MovieBoxAppSource
import com.example.zubflix.sources.MovieBoxINSource
import com.example.zubflix.sources.RtallySource
import com.example.zubflix.sources.MovieBlastSource
import com.example.zubflix.sources.CinefreakSource
import com.example.zubflix.sources.CastleTvSource
import com.example.zubflix.sources.VegaMoviesSource
import com.example.zubflix.sources.HDGharTvSource
import com.example.zubflix.sources.CloudStreamSource
import com.example.zubflix.sources.SingleCloudStreamPluginSource
import com.example.zubflix.cloudstream.CloudStreamPluginManager
import android.content.Context

object SourceManager {
    private var allSources: List<StreamingSource> = emptyList()
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val rawSources = listOf(
            NuvioSource(context),
            MovieBoxINSource(),
            MovieBoxWebSource(),
            MovieBoxAppSource(),
            MovieBlastSource(context),
            CinefreakSource(),
            CastleTvSource(),
            VegaMoviesSource(),
            HDGharTvSource(),
            CtgMoviesSource(),
            MovieLinkBDSource(),
            RtallySource(context),
            ICCFtpSource(),
            DhakaFlixSource(context),
            DiscoveryFtpSource(),
            CircleFtpSource()
        )
        // Wrap everything in CachedSource once
        allSources = rawSources.map { CachedSource(it, context) }
    }

    fun isCloudStreamHomeCatalogEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_cloudstream_home_catalog", false)
    }

    fun setCloudStreamHomeCatalogEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enable_cloudstream_home_catalog", enabled).apply()
    }

    private fun getDynamicCloudStreamSources(context: Context): List<StreamingSource> {
        if (!isCloudStreamHomeCatalogEnabled(context)) {
            return emptyList()
        }
        return try {
            val installedPlugins = CloudStreamPluginManager.getInstalledPlugins(context).filter { it.isEnabled }
            installedPlugins
                .filterNot { plugin ->
                    plugin.name.contains("MegaProvider", ignoreCase = true) ||
                    plugin.id.contains("MegaProvider", ignoreCase = true)
                }
                .map { plugin ->
                    CachedSource(SingleCloudStreamPluginSource(plugin, context), context)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getAllSources(context: Context? = null): List<StreamingSource> {
        val ctx = context ?: appContext
        if (allSources.isEmpty() && ctx != null) {
            initialize(ctx)
        }
        if (ctx == null) return allSources
        val csSources = getDynamicCloudStreamSources(ctx)
        return allSources + csSources
    }

    fun getOrderedSources(context: Context): List<StreamingSource> {
        if (allSources.isEmpty()) {
            initialize(context)
        }
        val csSources = getDynamicCloudStreamSources(context)
        val combined = allSources + csSources
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedOrderStr = prefs.getString("source_order", null)
        if (savedOrderStr.isNullOrEmpty()) {
            return combined
        }
        val savedNames = savedOrderStr.split(",").map { 
            it.trim()
        }.toMutableList()

        val sorted = combined.sortedBy { source ->
            val idx = savedNames.indexOf(source.name)
            if (idx >= 0) {
                idx
            } else {
                Int.MAX_VALUE
            }
        }
        return sorted
    }

    fun saveSourceOrder(context: Context, orderedSources: List<StreamingSource>) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val orderStr = orderedSources.joinToString(",") { it.name }
        prefs.edit().putString("source_order", orderStr).apply()
    }

    fun isSourceEnabled(context: Context, sourceName: String): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("source_enabled_$sourceName", true)
    }

    fun setSourceEnabled(context: Context, sourceName: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("source_enabled_$sourceName", enabled).apply()
    }

    fun getEnabledSources(context: Context): List<StreamingSource> {
        val ordered = getOrderedSources(context)
        val enabled = ordered.filter { isSourceEnabled(context, it.name) }
        return if (enabled.isEmpty()) ordered else enabled
    }

    fun getSelectedSource(context: Context): StreamingSource {
        val enabled = getEnabledSources(context)
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val selectedName = prefs.getString("selected_source", null)
        val found = enabled.find { it.name.equals(selectedName, ignoreCase = true) }
        if (found != null &&
            !found.name.contains("CloudStream Extensions", ignoreCase = true) &&
            !found.name.contains("MegaProvider", ignoreCase = true)
        ) {
            return found
        }
        val defaultSource = enabled.firstOrNull {
            !it.name.contains("CloudStream Extensions", ignoreCase = true) &&
            !it.name.contains("MegaProvider", ignoreCase = true)
        } ?: allSources.firstOrNull() ?: MovieBoxWebSource()
        setSelectedSource(context, defaultSource.name)
        return defaultSource
    }

    fun setSelectedSource(context: Context, sourceName: String) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_source", sourceName).apply()
    }

    fun getSourceByName(name: String): StreamingSource? {
        if (name.isBlank()) return null
        val sources = getAllSources()
        // First priority: Exact name match
        val exactMatch = sources.find {
            val sName = if (it is CachedSource) it.source.name else it.name
            sName.equals(name, ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch

        // Second priority: Fuzzy substring fallback
        return sources.find {
            val sName = if (it is CachedSource) it.source.name else it.name
            sName.contains(name, ignoreCase = true) ||
            name.contains(sName, ignoreCase = true)
        }
    }

    fun setMaxCacheSize(context: Context, sizeMb: Long) {
        allSources.forEach { source ->
            if (source is CachedSource) {
                source.updateCacheSize(sizeMb)
            }
        }
    }

    fun invalidateAllCaches() {
        allSources.forEach { it.invalidateCache() }
    }

    fun getTmdbApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val key = prefs.getString("tmdb_api_key", "")?.trim()
        return if (!key.isNullOrEmpty()) key else "68e094699525b18a70bab2f86b1fa706"
    }

    fun isTmdbBackdropsEnabled(context: Context): Boolean = 
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("use_tmdb_backdrops", true)

    fun isTmdbCastEnabled(context: Context): Boolean = 
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("use_tmdb_cast", true)
        
    fun isTmdbRatingsEnabled(context: Context): Boolean = 
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("use_tmdb_ratings", true)
        
    fun isTmdbGenresEnabled(context: Context): Boolean = 
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("use_tmdb_genres", true)
        
    fun isTmdbPlotEnabled(context: Context): Boolean = 
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("use_tmdb_plot", true)

    fun isPersistentTmdbCacheEnabled(context: Context): Boolean = 
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("persistent_tmdb_cache", true)

    fun isHomeScreenRatingsEnabled(context: Context): Boolean = 
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("home_screen_ratings", false)
}
