package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.MovieLinkBDSource

internal object MovieLinkBDScraper : LocalScraper {
    override val id: String = "movielinkbd"
    override val name: String = "MovieLinkBD Scraper"
    override val description: String = "High speed MovieLinkBD BDIX server scraper"
    
    private val delegateSource = MovieLinkBDSource()

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
                // Only allow fallback if item title matches and it's not a movie when series was requested
                (!isSeries || item.isSeries) && BDIXUtils.titlesMatch(item.title, query)
            } ?: return emptyList()

            var dataString = matchedItem.id
            if (isSeries) {
                val sNum = season ?: 1
                val eNum = episode ?: 1

                // Fetch details to strictly verify if the requested season & episode exist
                val details = delegateSource.getDetails(matchedItem.id) ?: return emptyList()
                val matchedSeason = details.seasons?.find { it.seasonNumber == sNum }
                    ?: if (season == null) details.seasons?.firstOrNull() else null

                if (matchedSeason == null) return emptyList()

                val matchedEp = matchedSeason.episodes.find { ep ->
                    val epNumFromTitle = Regex("(?i)\\b(?:Episode|Ep|E)\\s*0*(\\d+)\\b").find(ep.title)?.groupValues?.get(1)?.toIntOrNull()
                    val epNumFromPayload = if (ep.streamUrl.contains("|ep")) {
                        ep.streamUrl.substringAfterLast("|ep").toIntOrNull()
                    } else null
                    epNumFromTitle == eNum || epNumFromPayload == eNum || ep.title.equals("Episode $eNum", ignoreCase = true)
                } ?: if (eNum > 0 && eNum <= matchedSeason.episodes.size) {
                    val candidate = matchedSeason.episodes[eNum - 1]
                    val candEpNum = Regex("(?i)\\b(?:Episode|Ep|E)\\s*0*(\\d+)\\b").find(candidate.title)?.groupValues?.get(1)?.toIntOrNull()
                    val candPayloadNum = if (candidate.streamUrl.contains("|ep")) {
                        candidate.streamUrl.substringAfterLast("|ep").toIntOrNull()
                    } else null
                    if ((candEpNum == null || candEpNum == eNum) && (candPayloadNum == null || candPayloadNum == eNum)) candidate else null
                } else null

                if (matchedEp == null) {
                    return emptyList()
                }

                dataString = matchedEp.streamUrl
            }

            delegateSource.extractVideoLinksStreaming(dataString) { streams ->
                streams.forEach { (title, url) ->
                    results.add(
                        StreamResult(
                            source = "MovieLinkBD",
                            title = title,
                            url = url,
                            qualityScore = BDIXUtils.scoreQuality(title),
                            mediaTitle = matchedItem.title
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore extraction errors
        }

        return results
    }
}
