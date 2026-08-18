package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.CircleFtpSource

internal object CircleFtpScraper : LocalScraper {
    override val id: String = "circleftp"
    override val name: String = "Circle FTP Scraper"
    override val description: String = "Circle BDIX FTP high speed media server scraper"

    private val delegateSource = CircleFtpSource()

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
                        source = "CircleFTP",
                        title = "${details.title} | BDIX FTP",
                        url = details.streamUrl,
                        qualityScore = 35,
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
                                        source = "CircleFTP",
                                        title = "${epItem.title} | BDIX FTP",
                                        url = epItem.streamUrl,
                                        qualityScore = 35,
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
