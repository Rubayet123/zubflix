package com.example.zubflix.stremio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import android.net.Uri

data class SubtitleSourceMetadata(
    val name: String,
    val priority: Int, // lower = higher priority (e.g., 1 is highest)
    val isInternal: Boolean,
    val description: String = ""
)

object SubtitlePrioritizationManager {
    private const val PREFS_NAME = "subtitle_prioritization_prefs"
    private const val KEY_PRIORITIES = "source_priorities"
    
    // Default source configurations with priority metadata
    private val defaultSources = listOf(
        SubtitleSourceMetadata("Direct Stream", 1, true, "Subtitles embedded directly in the stream link"),
        SubtitleSourceMetadata("Pengu Movibox", 2, true, "Pengu addon subtitle provider"),
        SubtitleSourceMetadata("FrostStream", 3, false, "FrostStream addon subtitle provider"),
        SubtitleSourceMetadata("OpenSubtitles v3", 4, false, "OpenSubtitles third party provider")
    )

    fun getSources(context: Context): List<SubtitleSourceMetadata> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PRIORITIES, null) ?: return defaultSources
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<SubtitleSourceMetadata>>() {}.type
            com.google.gson.Gson().fromJson(json, type) ?: defaultSources
        } catch (e: Exception) {
            defaultSources
        }
    }

    fun saveSources(context: Context, sources: List<SubtitleSourceMetadata>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = com.google.gson.Gson().toJson(sources)
        prefs.edit().putString(KEY_PRIORITIES, json).apply()
    }

    /**
     * Determines the priority of a subtitle based on its label/name and the currently selected stream provider name.
     */
    fun getSubtitlePriority(
        label: String,
        selectedStreamProvider: String?,
        context: Context
    ): Int {
        val sources = getSources(context)
        
        // 1. Direct/Stream-provided subtitles get highest priority
        if (label.contains("Source", ignoreCase = true) || label.contains("Initial", ignoreCase = true) || label.contains("Direct", ignoreCase = true)) {
            return 1
        }
        
        // 2. If the subtitle matches the currently selected stream's provider (the internal provider), it gets priority 2
        if (selectedStreamProvider != null && label.contains(selectedStreamProvider, ignoreCase = true)) {
            return 2
        }
        
        // 3. Look up other sources in metadata priority list
        val matchingSource = sources.firstOrNull { source ->
            label.contains(source.name, ignoreCase = true)
        }
        
        if (matchingSource != null) {
            // Adjust priority: if it's marked as internal but doesn't match selected stream, demote it slightly
            return if (matchingSource.isInternal) {
                matchingSource.priority + 5
            } else {
                matchingSource.priority + 10
            }
        }
        
        // Default fallback for unknown external subtitles
        return 100
    }
}
