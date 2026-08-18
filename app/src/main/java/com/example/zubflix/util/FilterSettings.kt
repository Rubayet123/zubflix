package com.example.zubflix.util

import android.content.Context
import android.content.SharedPreferences

object FilterSettings {
    private const val PREFS_NAME = "filter_settings"

    private const val KEY_LARGE_MARKET_VOTE_COUNT = "large_market_vote_count"
    private const val KEY_MEDIUM_MARKET_VOTE_COUNT = "medium_market_vote_count"
    private const val KEY_SMALL_MARKET_VOTE_COUNT = "small_market_vote_count"
    private const val KEY_GENERAL_MIN_VOTE_COUNT = "general_min_vote_count"
    const val DEFAULT_LARGE_MARKET_VOTE_COUNT = 100
    const val DEFAULT_MEDIUM_MARKET_VOTE_COUNT = 20
    const val DEFAULT_SMALL_MARKET_VOTE_COUNT = 0
    const val DEFAULT_GENERAL_MIN_VOTE_COUNT = 50

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getGeneralMinVoteCount(context: Context?): Int {
        if (context == null) return DEFAULT_GENERAL_MIN_VOTE_COUNT
        return getPrefs(context).getInt(KEY_GENERAL_MIN_VOTE_COUNT, DEFAULT_GENERAL_MIN_VOTE_COUNT)
    }

    fun setGeneralMinVoteCount(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_GENERAL_MIN_VOTE_COUNT, count).apply()
    }

    fun getLargeMarketVoteCount(context: Context?): Int {
        if (context == null) return DEFAULT_LARGE_MARKET_VOTE_COUNT
        return getPrefs(context).getInt(KEY_LARGE_MARKET_VOTE_COUNT, DEFAULT_LARGE_MARKET_VOTE_COUNT)
    }

    fun setLargeMarketVoteCount(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_LARGE_MARKET_VOTE_COUNT, count).apply()
    }

    fun getMediumMarketVoteCount(context: Context?): Int {
        if (context == null) return DEFAULT_MEDIUM_MARKET_VOTE_COUNT
        return getPrefs(context).getInt(KEY_MEDIUM_MARKET_VOTE_COUNT, DEFAULT_MEDIUM_MARKET_VOTE_COUNT)
    }

    fun setMediumMarketVoteCount(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_MEDIUM_MARKET_VOTE_COUNT, count).apply()
    }

    fun getSmallMarketVoteCount(context: Context?): Int {
        if (context == null) return DEFAULT_SMALL_MARKET_VOTE_COUNT
        return getPrefs(context).getInt(KEY_SMALL_MARKET_VOTE_COUNT, DEFAULT_SMALL_MARKET_VOTE_COUNT)
    }

    fun setSmallMarketVoteCount(context: Context, count: Int) {
        getPrefs(context).edit().putInt(KEY_SMALL_MARKET_VOTE_COUNT, count).apply()
    }

    fun resetToDefaults(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun getVoteCountThresholdForTier(context: Context?, voteTier: String): Int {
        return when (voteTier.lowercase()) {
            "large" -> getLargeMarketVoteCount(context)
            "medium" -> getMediumMarketVoteCount(context)
            "small" -> getSmallMarketVoteCount(context)
            else -> 0
        }
    }

    fun getVoteCountThresholdForRegion(context: Context?, regionCode: String): Int {
        if (regionCode.isEmpty() || regionCode == "all") {
            return getGeneralMinVoteCount(context)
        }
        val regionItem = com.example.zubflix.model.FilterConfigRepository.findRegion(regionCode, context)
        return if (regionItem != null && regionItem.code != "all") {
            getVoteCountThresholdForTier(context, regionItem.voteTier)
        } else {
            getGeneralMinVoteCount(context)
        }
    }
}
