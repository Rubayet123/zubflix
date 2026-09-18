package com.example.zubflix.bdix

import android.content.Context
import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.MovieBlastSource

internal class MovieBlastScraper(context: Context? = null) : LocalScraper {
    override val id: String = "movieblast"
    override val name: String = "MovieBlast Scraper"
    override val description: String = "MovieBlast scraper for movies, dubbed content, and series"

    private val delegateSource = MovieBlastSource(context)

    override suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult> {
        val results = mutableListOf<StreamResult>()
        val query = meta.name
        if (query.isBlank()) return emptyList()

        try {
            val searchItems = delegateSource.search(query)
            if (searchItems.isEmpty()) return emptyList()

            val isSeries = (type == "series" || type == "tv")
            val matchedItem = searchItems.firstOrNull { item ->
                item.isSeries == isSeries && BDIXUtils.titlesMatch(item.title, query)
            } ?: searchItems.firstOrNull { item ->
                (!isSeries || item.isSeries) && BDIXUtils.titlesMatch(item.title, query)
            } ?: return emptyList()

            val details = delegateSource.getDetails(matchedItem.id) ?: return emptyList()

            val targetStreamData: String = if (isSeries) {
                val sNum = season ?: 1
                val eNum = episode ?: 1
                val matchedSeason = details.seasons?.find { it.seasonNumber == sNum }
                    ?: if (season == null) details.seasons?.firstOrNull() else null

                if (matchedSeason == null) return emptyList()

                val matchedEp = matchedSeason.episodes.find { ep ->
                    val epNumFromTitle = Regex("(?i)\\b(?:Episode|Ep|E)\\s*0*(\\d+)\\b").find(ep.title)?.groupValues?.get(1)?.toIntOrNull()
                    val epNumFromPayload = if (ep.streamUrl != null && ep.streamUrl.contains("\"episode\":")) {
                        Regex("\"episode\"\\s*:\\s*(\\d+)").find(ep.streamUrl)?.groupValues?.get(1)?.toIntOrNull()
                    } else null
                    epNumFromTitle == eNum || epNumFromPayload == eNum || ep.title.equals("Episode $eNum", ignoreCase = true)
                } ?: if (eNum > 0 && eNum <= matchedSeason.episodes.size) {
                    val candidate = matchedSeason.episodes[eNum - 1]
                    val candEpNum = Regex("(?i)\\b(?:Episode|Ep|E)\\s*0*(\\d+)\\b").find(candidate.title)?.groupValues?.get(1)?.toIntOrNull()
                    val candPayloadNum = if (candidate.streamUrl != null && candidate.streamUrl.contains("\"episode\":")) {
                        Regex("\"episode\"\\s*:\\s*(\\d+)").find(candidate.streamUrl)?.groupValues?.get(1)?.toIntOrNull()
                    } else null
                    if ((candEpNum == null || candEpNum == eNum) && (candPayloadNum == null || candPayloadNum == eNum)) candidate else null
                } else null

                if (matchedEp == null || matchedEp.streamUrl.isNullOrBlank()) {
                    return emptyList()
                }

                matchedEp.streamUrl
            } else {
                details.streamUrl ?: matchedItem.id
            }

            if (targetStreamData.isBlank()) return emptyList()

            val streamMap = delegateSource.extractVideoLinks(targetStreamData)
            streamMap.forEach { (title, url) ->
                results.add(
                    StreamResult(
                        source = "MovieBlast",
                        title = title,
                        url = url,
                        qualityScore = BDIXUtils.scoreQuality(title),
                        mediaTitle = details.title
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore failure
        }

        return results
    }
}
