package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.DhakaFlixSource

internal object DhakaFlixScraper : LocalScraper {
    override val id: String = "dhakaflix"
    override val name: String = "DhakaFlix Scraper"
    override val description: String = "DhakaFlix BDIX FTP & HTTP media server scraper"

    private val delegateSource = DhakaFlixSource()

    override suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult> {
        val results = mutableListOf<StreamResult>()
        val query = meta.name
        if (query.isBlank()) return emptyList()

        try {
            val searchItems = delegateSource.search(query)
            val isSeries = (type == "series" || type == "tv")
            val matchedItem = searchItems.firstOrNull { item ->
                item.isSeries == isSeries && BDIXUtils.titlesMatch(item.title, query)
            } ?: searchItems.firstOrNull { item ->
                BDIXUtils.titlesMatch(item.title, query)
            } ?: return emptyList()

            val details = delegateSource.getDetails(matchedItem.id) ?: return emptyList()
            if (!isSeries && !details.streamUrl.isNullOrBlank()) {
                results.add(
                    StreamResult(
                        source = "DhakaFlix",
                        title = "${details.title} | 1080p | BDIX",
                        url = details.streamUrl,
                        qualityScore = 30,
                        mediaTitle = details.title
                    )
                )
            }

            details.seasons?.forEach { seasonItem ->
                var sNum = if (seasonItem.seasonNumber > 0) seasonItem.seasonNumber else 0
                if (sNum == 0) {
                    val parsed = Regex("(?i)\\b(?:season|s)\\s*0*(\\d+)\\b").find(seasonItem.title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (parsed != null) sNum = parsed
                }

                val seasonMatches = when {
                    season == null -> true
                    sNum > 0 -> sNum == season
                    else -> details.seasons?.size == 1
                }

                if (seasonMatches) {
                    seasonItem.episodes.forEachIndexed { epIndex, epItem ->
                        val targetUrlOrTitle = "${epItem.title} ${epItem.streamUrl}"
                        val sInTitle = Regex("(?i)\\bS0*(\\d+)E0*\\d+\\b").find(targetUrlOrTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("(?i)\\bSeason\\s*0*(\\d+)\\b").find(targetUrlOrTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (season != null && sInTitle != null && sInTitle != season) {
                            return@forEachIndexed
                        }

                        var epNum = epIndex + 1
                        val matchSxE = Regex("(?i)\\bS\\d+E0*(\\d+)\\b").find(epItem.title)
                        val matchEp = Regex("(?i)\\b(?:ep|episode|e)\\s*0*(\\d+)\\b").find(epItem.title)
                        val parsedEp = (matchSxE ?: matchEp)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (parsedEp != null) {
                            epNum = parsedEp
                        }

                        if (episode == null || epNum == episode) {
                            if (epItem.streamUrl.isNotBlank()) {
                                results.add(
                                    StreamResult(
                                        source = "DhakaFlix",
                                        title = "${epItem.title} | 1080p | BDIX",
                                        url = epItem.streamUrl,
                                        qualityScore = 30,
                                        mediaTitle = matchedItem.title
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return results
    }
}
