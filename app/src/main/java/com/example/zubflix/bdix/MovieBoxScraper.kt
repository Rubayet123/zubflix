package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.MovieBoxWebSource

internal object MovieBoxScraper : LocalScraper {
    override val id: String = "moviebox"
    override val name: String = "MovieBox Scraper"
    override val description: String = "MovieBox Web scraper for high quality video streams"

    private val delegateSource = MovieBoxWebSource()

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

            val dataString = org.json.JSONObject().apply {
                put("subjectId", matchedItem.id)
                put("detailPath", matchedItem.id)
                if (type == "series" || type == "tv") {
                    put("season", season ?: 1)
                    put("episode", episode ?: 1)
                }
            }.toString()

            delegateSource.extractVideoLinksStreaming(dataString) { streams ->
                streams.forEach { (title, url) ->
                    results.add(
                        StreamResult(
                            source = "MovieBox",
                            title = title,
                            url = url,
                            qualityScore = BDIXUtils.scoreQuality(title),
                            mediaTitle = matchedItem.title
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
