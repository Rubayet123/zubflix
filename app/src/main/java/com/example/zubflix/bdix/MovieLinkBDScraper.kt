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
                BDIXUtils.titlesMatch(item.title, query)
            } ?: return emptyList()

            var dataString = matchedItem.id
            if (type == "series" || type == "tv") {
                val ep = episode ?: 1
                dataString = "$dataString|ep$ep"
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
