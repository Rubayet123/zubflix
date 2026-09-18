package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.CastleTvSource

internal object CastleTvScraper : LocalScraper {
    override val id: String = "castletv"
    override val name: String = "CastleTV Scraper"
    override val description: String = "CastleTV scraper for movies, Indian cinema, and TV series"

    private val delegateSource = CastleTvSource()

    override suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult> {
        val results = mutableListOf<StreamResult>()
        val query = meta.name
        if (query.isBlank()) return emptyList()

        try {
            val searchItems = delegateSource.search(query)
            if (searchItems.isEmpty()) return emptyList()

            val isSeries = (type == "series" || type == "tv")
            // Rank candidates by match quality: exact series match with highest title match score
            val matchedItem = searchItems
                .map { item -> item to BDIXUtils.titleMatchScore(item.title, query) }
                .filter { (item, score) ->
                    score >= 0.70 && (!isSeries || item.isSeries)
                }
                .maxByOrNull { (item, score) ->
                    var rank = score
                    if (item.isSeries == isSeries) rank += 1.0
                    rank
                }?.first ?: return emptyList()

            val details = delegateSource.getDetails(matchedItem.id) ?: return emptyList()

            val targetStreamData: String = if (isSeries) {
                val sNum = season ?: 1
                val eNum = episode ?: 1
                val matchedSeason = details.seasons?.find { it.seasonNumber == sNum }
                    ?: if (season == null) details.seasons?.firstOrNull() else null

                if (matchedSeason == null) return emptyList()

                val matchedEp = matchedSeason.episodes.find { ep ->
                    val epNumFromTitle = Regex("(?i)\\b(?:Episode|Ep|E)\\s*0*(\\d+)\\b").find(ep.title)?.groupValues?.get(1)?.toIntOrNull()
                    epNumFromTitle == eNum || ep.title.equals("Episode $eNum", ignoreCase = true)
                } ?: if (eNum > 0 && eNum <= matchedSeason.episodes.size) {
                    val candidate = matchedSeason.episodes[eNum - 1]
                    val candEpNum = Regex("(?i)\\b(?:Episode|Ep|E)\\s*0*(\\d+)\\b").find(candidate.title)?.groupValues?.get(1)?.toIntOrNull()
                    if (candEpNum == null || candEpNum == eNum) candidate else null
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
                val isPreview = title.contains("preview", ignoreCase = true) || url.contains("preview", ignoreCase = true)
                val cleanTitle = if (isPreview && !title.contains("Preview", ignoreCase = true)) "$title [Preview ⚠️]" else title
                results.add(
                    StreamResult(
                        source = "CastleTV",
                        title = cleanTitle,
                        url = url,
                        qualityScore = if (isPreview) -50000 else BDIXUtils.scoreQuality(title),
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
