package com.example.zubflix.util

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

object PlaybackSettings {
    private const val PREFS_NAME = "app_prefs"
    const val KEY_PLAYBACK_MODE = "playback_mode"
    const val MODE_AUTO_PLAY = "AUTO_PLAY"
    const val MODE_MANUAL = "MANUAL"

    const val KEY_EXCLUDED_PROVIDERS = "excluded_autoplay_providers"

    const val KEY_VISIBLE_QUALITY_CHIPS = "visible_quality_chips"
    val ALL_QUALITY_CHIPS = setOf("4K", "1080p", "720p", "480p")
    val DEFAULT_QUALITY_CHIPS = setOf("1080p")

    const val KEY_BUFFER_SIZE_SECONDS = "buffer_size_seconds"
    const val BUFFER_SIZE_AUTO = 0
    const val BUFFER_SIZE_45 = 45
    const val BUFFER_SIZE_120 = 120
    const val BUFFER_SIZE_180 = 180

    val KNOWN_PROVIDERS = arrayOf("Dflix", "MovieBox", "CTGMovies", "MovieLinkBD", "Rtally", "Stremio / Nuvio Addon")

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPlaybackMode(context: Context): String {
        return getPrefs(context).getString(KEY_PLAYBACK_MODE, MODE_AUTO_PLAY) ?: MODE_AUTO_PLAY
    }

    fun setPlaybackMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_PLAYBACK_MODE, mode).apply()
    }

    fun isAutoPlayEnabled(context: Context): Boolean {
        return getPlaybackMode(context) == MODE_AUTO_PLAY
    }

    fun getBufferSizeSeconds(context: Context): Int {
        return getPrefs(context).getInt(KEY_BUFFER_SIZE_SECONDS, BUFFER_SIZE_AUTO)
    }

    fun setBufferSizeSeconds(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt(KEY_BUFFER_SIZE_SECONDS, seconds).apply()
    }

    fun getBufferSizeLabel(seconds: Int): String {
        return when (seconds) {
            BUFFER_SIZE_180 -> "Ultra Buffer (180s Max)"
            BUFFER_SIZE_45 -> "Low Memory (45s Max)"
            else -> "Balanced (120s Max) - Recommended"
        }
    }

    @OptIn(UnstableApi::class)
    fun createLoadControl(context: Context): DefaultLoadControl {
        val userSeconds = getBufferSizeSeconds(context)
        var targetMaxBufferSec = if (userSeconds == BUFFER_SIZE_AUTO) 120 else userSeconds

        // Dynamic RAM safety check: Cap max buffer at 45s on low-RAM devices (< 2GB RAM or low memory state)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            if (memoryInfo.lowMemory || totalRamGb < 2.0) {
                targetMaxBufferSec = targetMaxBufferSec.coerceAtMost(45)
            }
        }

        val maxBufferMs = targetMaxBufferSec * 1000
        val minBufferMs = (maxBufferMs / 2).coerceAtLeast(10000)
        val bufferForPlaybackMs = 1500 // Fast 1.5s startup for near-instant playback
        val bufferForPlaybackAfterRebufferMs = 3000 // 3s smooth recovery after network drops

        val targetBufferBytes = when (targetMaxBufferSec) {
            45 -> 48 * 1024 * 1024
            180 -> 256 * 1024 * 1024
            else -> 160 * 1024 * 1024
        }

        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10000, false) // 10s back buffer for quick rewinds
            .build()
    }

    fun getExcludedProviders(context: Context): Set<String> {
        val defaultSet = setOf("dflix")
        val saved = getPrefs(context).getStringSet(KEY_EXCLUDED_PROVIDERS, null)
        return saved ?: defaultSet
    }

    fun setExcludedProviders(context: Context, providers: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_EXCLUDED_PROVIDERS, providers).apply()
    }

    fun isProviderExcluded(context: Context, providerOrStreamName: String): Boolean {
        val excluded = getExcludedProviders(context)
        if (excluded.isEmpty()) return false
        val lowerName = providerOrStreamName.lowercase()
        return excluded.any { lowerName.contains(it.lowercase()) }
    }

    fun getStreamQualityScore(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("1080p") || lower.contains("fhd") -> 10000
            lower.contains("720p") || lower.contains("hd") -> 6000
            lower.contains("480p") || lower.contains("sd") -> 4000
            lower.contains("360p") -> 2000
            lower.contains("2160p") || lower.contains("4k") -> 1000
            else -> 500
        }
    }

    fun getAutoPlayStreamScore(context: Context, streamName: String, streamUrl: String? = null): Int {
        var score = getStreamQualityScore(streamName)
        val lowerName = streamName.lowercase()
        val lowerUrl = streamUrl?.lowercase() ?: ""
        if (lowerName.contains("movieboxbff") || lowerName.contains("movieboxapp") || lowerName.contains("moviebox app")) {
            score += 5000 // Priority boost for fast, captioned MovieBox BFF native streams
        }
        if (isProviderExcluded(context, streamName)) {
            score -= 10000
        }
        if (lowerName.contains("preview") || lowerUrl.contains("preview") ||
            lowerName.contains("sample") || lowerUrl.contains("sample") ||
            lowerName.contains("trailer") || lowerUrl.contains("trailer") ||
            lowerName.contains("demo") || lowerUrl.contains("demo") ||
            lowerName.contains("10min") || lowerUrl.contains("10min") ||
            lowerUrl.contains("_preview_") || lowerName.contains("_preview_")) {
            score -= 100000 // Heavily penalize preview links to prevent auto-play selection
        }
        return score
    }

    fun getVisibleQualityChips(context: Context): Set<String> {
        val saved = getPrefs(context).getStringSet(KEY_VISIBLE_QUALITY_CHIPS, null)
        return saved ?: DEFAULT_QUALITY_CHIPS
    }

    fun setVisibleQualityChips(context: Context, chips: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_VISIBLE_QUALITY_CHIPS, chips).apply()
    }

    fun isQualityChipVisible(context: Context, quality: String): Boolean {
        return getVisibleQualityChips(context).contains(quality)
    }

    fun getVisibleQualityChipsLabel(context: Context): String {
        val chips = getVisibleQualityChips(context)
        if (chips.size >= ALL_QUALITY_CHIPS.size) {
            return "All Qualities Enabled (4K, 1080p, 720p, 480p)"
        }
        if (chips.isEmpty()) {
            return "No Quality Pills (Only Provider Chips)"
        }
        val ordered = listOf("4K", "1080p", "720p", "480p").filter { chips.contains(it) }
        val label = ordered.joinToString(", ")
        return if (chips == DEFAULT_QUALITY_CHIPS) "$label (Default)" else label
    }
}

