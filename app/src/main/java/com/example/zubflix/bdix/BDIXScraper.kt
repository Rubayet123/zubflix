package com.example.zubflix.bdix

import android.content.Context
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * Native Local Scraper Orchestrator
 * Executes Kotlin local scrapers concurrently.
 */
object BDIXScraper {

    const val NAME = "Native Local Scraper Engine"

    data class MediaMeta(
        val name: String,
        val year: Int? = null
    )

    data class StreamResult(
        val source: String,
        val title: String,      // Quality / Stream name info
        val url: String,
        val qualityScore: Int = 0,
        val mediaTitle: String = "",
        val isHls: Boolean = false,
        val size: String? = null
    )

    suspend fun getStreams(
        type: String,           // "movie" or "series"
        name: String,
        imdbId: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        context: Context? = null
    ): List<StreamResult> = supervisorScope {
        if (context == null) return@supervisorScope emptyList()

        val meta = MediaMeta(name = name, year = year)

        // 1. Fetch enabled local Kotlin scrapers in priority order
        val activeLocalScrapers = LocalScraperManager.getOrderedEnabledScrapers(context)

        val tasks = mutableListOf<Deferred<List<StreamResult>>>()

        // 2. Execute local Kotlin scrapers concurrently with individual timeout
        for (scraper in activeLocalScrapers) {
            tasks.add(async {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(10000) {
                        scraper.getStreams(type, meta, season, episode)
                    } ?: emptyList()
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    emptyList()
                }
            })
        }

        val results = tasks.mapNotNull {
            try {
                it.await()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                emptyList()
            }
        }.flatten()
        results.sortedByDescending { it.qualityScore }
    }
}
