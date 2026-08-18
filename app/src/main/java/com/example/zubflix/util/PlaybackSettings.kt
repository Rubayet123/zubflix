package com.example.zubflix.util

import android.content.Context
import android.content.SharedPreferences

object PlaybackSettings {
    private const val PREFS_NAME = "app_prefs"
    const val KEY_PLAYBACK_MODE = "playback_mode"
    const val MODE_AUTO_PLAY = "AUTO_PLAY"
    const val MODE_MANUAL = "MANUAL"

    const val KEY_EXCLUDED_PROVIDERS = "excluded_autoplay_providers"

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

    fun getAutoPlayStreamScore(context: Context, streamName: String): Int {
        var score = getStreamQualityScore(streamName)
        if (isProviderExcluded(context, streamName)) {
            score -= 10000
        }
        return score
    }
}
