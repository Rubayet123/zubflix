package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.CinefreakSource

internal object CinefreakScraper : LocalScraper {
    override val id: String = "cinefreak"
    override val name: String = "Cinefreak Scraper"
    override val description: String = "Cinefreak web scraper for movies, dual audio, and web series"

    private val delegateSource = CinefreakSource()

    private fun itemMatchesSeason(text: String, season: Int): Boolean {
        val sNum = Regex("(?i)\\b(?:season|s)\\s*0*(\\d+)\\b").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)season-0*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return sNum == season
    }

    override suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult> {
        val results = mutableListOf<StreamResult>()
        val query = meta.name
        if (query.isBlank()) return emptyList()

        try {
            val searchItems = delegateSource.search(query)
            if (searchItems.isEmpty()) return emptyList()

            val isSeries = (type == "series" || type == "tv")
            val matchedItem = if (isSeries && season != null) {
                searchItems.firstOrNull { item ->
                    item.isSeries == isSeries && BDIXUtils.titlesMatch(item.title, query) && itemMatchesSeason("${item.title} ${item.id}", season)
                } ?: searchItems.firstOrNull { item ->
                    item.isSeries == isSeries && BDIXUtils.titlesMatch(item.title, query)
                } ?: searchItems.firstOrNull { item ->
                    BDIXUtils.titlesMatch(item.title, query)
                } ?: return emptyList()
            } else {
                searchItems.firstOrNull { item ->
                    item.isSeries == isSeries && BDIXUtils.titlesMatch(item.title, query)
                } ?: searchItems.firstOrNull { item ->
                    BDIXUtils.titlesMatch(item.title, query)
                } ?: return emptyList()
            }

            val details = delegateSource.getDetails(matchedItem.id) ?: return emptyList()

            val targetStreamData: String = if (isSeries) {
                val sNum = season ?: 1
                val eNum = episode ?: 1
                val matchedSeason = details.seasons?.find { it.seasonNumber == sNum }
                    ?: if (season == null) details.seasons?.firstOrNull() else null

                if (matchedSeason == null) return emptyList()

                val matchedEp = matchedSeason.episodes.find {
                    Regex("(?i)\\b(?:Episode|Ep|E)\\s*0*$eNum\\b").containsMatchIn(it.title) ||
                        (it.streamUrl != null && it.streamUrl.contains("\"episode\":$eNum"))
                } ?: if (eNum > 0 && eNum <= matchedSeason.episodes.size) {
                    matchedSeason.episodes.getOrNull(eNum - 1)
                } else null

                if (matchedEp == null || matchedEp.streamUrl.isNullOrBlank()) {
                    return emptyList()
                }

                matchedEp.streamUrl
            } else {
                details.streamUrl ?: matchedItem.id
            }

            delegateSource.extractVideoLinksStreaming(targetStreamData) { streams ->
                streams.forEach { (title, url) ->
                    val combined = "$title $url"
                    val sInUrl = Regex("(?i)\\bS0*(\\d+)E0*\\d+\\b").find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("(?i)\\bSeason\\s*0*(\\d+)\\b").find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (season != null && sInUrl != null && sInUrl != season) {
                        return@forEach
                    }
                    results.add(
                        StreamResult(
                            source = "Cinefreak",
                            title = title,
                            url = url,
                            qualityScore = BDIXUtils.scoreQuality(title),
                            mediaTitle = details.title
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return results
    }
}

