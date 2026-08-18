package com.example.zubflix.bdix

import android.content.Context

object LocalScraperManager {

    private const val PREFS_NAME = "local_scrapers_prefs"
    private const val KEY_ORDER = "local_scrapers_order"
    private const val KEY_ENABLED = "local_scrapers_enabled"

    // Default registered scrapers list
    private val ALL_SCRAPERS: List<LocalScraper> = listOf(
        CtgMoviesScraper,
        MovieLinkBDScraper,
        MovieBoxScraper,
        CinefreakScraper,
        CastleTvScraper,
        HDGharTvScraper,
        MovieBlastScraper(),
        DhakaFlixScraper,
        CircleFtpScraper,
        IccFtpScraper,
        DflixScraper,
        VegaMoviesScraper
    )

    data class LocalScraperInfo(
        val scraper: LocalScraper,
        val enabled: Boolean,
        val priority: Int // 1-based rank
    )

    private fun getSavedOrder(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedStr = prefs.getString(KEY_ORDER, null)
        if (savedStr.isNullOrBlank()) {
            return ALL_SCRAPERS.map { it.id }
        }
        val savedList = savedStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val missing = ALL_SCRAPERS.map { it.id }.filter { !savedList.contains(it) }
        return savedList + missing
    }

    private fun saveOrderList(context: Context, orderList: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ORDER, orderList.joinToString(",")).apply()
    }

    fun getEnabledIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultSet = ALL_SCRAPERS.map { it.id }.toSet()
        val saved = prefs.getStringSet(KEY_ENABLED, null)
        return saved?.toSet() ?: defaultSet
    }

    fun setScraperEnabled(context: Context, id: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabledSet = getEnabledIds(context).toMutableSet()
        if (enabled) {
            enabledSet.add(id)
        } else {
            enabledSet.remove(id)
        }
        prefs.edit().putStringSet(KEY_ENABLED, enabledSet).apply()
    }

    fun getAllScrapersInfo(context: Context): List<LocalScraperInfo> {
        val order = getSavedOrder(context)
        val enabledIds = getEnabledIds(context)
        val scraperMap = ALL_SCRAPERS.associateBy { it.id }

        val result = mutableListOf<LocalScraperInfo>()
        var rank = 1
        for (id in order) {
            val scraper = scraperMap[id] ?: continue
            result.add(
                LocalScraperInfo(
                    scraper = scraper,
                    enabled = enabledIds.contains(id),
                    priority = rank++
                )
            )
        }
        return result
    }

    fun getOrderedEnabledScrapers(context: Context): List<LocalScraper> {
        return getAllScrapersInfo(context)
            .filter { it.enabled }
            .map { it.scraper }
    }

    fun moveUp(context: Context, id: String) {
        val currentOrder = getSavedOrder(context).toMutableList()
        val index = currentOrder.indexOf(id)
        if (index > 0) {
            val temp = currentOrder[index]
            currentOrder[index] = currentOrder[index - 1]
            currentOrder[index - 1] = temp
            saveOrderList(context, currentOrder)
        }
    }

    fun moveDown(context: Context, id: String) {
        val currentOrder = getSavedOrder(context).toMutableList()
        val index = currentOrder.indexOf(id)
        if (index >= 0 && index < currentOrder.size - 1) {
            val temp = currentOrder[index]
            currentOrder[index] = currentOrder[index + 1]
            currentOrder[index + 1] = temp
            saveOrderList(context, currentOrder)
        }
    }
}
