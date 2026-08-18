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
            val matchedItem = searchItems.firstOrNull { item ->
                item.isSeries == isSeries && BDIXUtils.titlesMatch(item.title, query)
            } ?: searchItems.firstOrNull { item ->
                item.isSeries == isSeries
            } ?: searchItems.firstOrNull { item ->
                BDIXUtils.titlesMatch(item.title, query)
            } ?: searchItems.first()

            val details = delegateSource.getDetails(matchedItem.id) ?: return emptyList()

            val targetStreamData: String = if (isSeries) {
                val sNum = season ?: 1
                val eNum = episode ?: 1
                val matchedSeason = details.seasons?.find { it.seasonNumber == sNum }
                    ?: if (season == null) details.seasons?.firstOrNull() else null

                if (matchedSeason == null) return emptyList()

                val matchedEp = matchedSeason.episodes.find {
                    it.title.contains("Episode $eNum", ignoreCase = true) || it.title.contains("E$eNum", ignoreCase = true)
                } ?: matchedSeason.episodes.getOrNull((eNum - 1).coerceAtLeast(0))

                matchedEp?.streamUrl ?: details.streamUrl ?: matchedItem.id
            } else {
                details.streamUrl ?: matchedItem.id
            }

            if (targetStreamData.isBlank()) return emptyList()

            val streamMap = delegateSource.extractVideoLinks(targetStreamData)
            streamMap.forEach { (title, url) ->
                results.add(
                    StreamResult(
                        source = "CastleTV",
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
