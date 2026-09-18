package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.MovieBoxAppSource

internal object MovieBoxAppScraper : LocalScraper {
    override val id: String = "moviebox_app"
    override val name: String = "MovieBox App Scraper"
    override val description: String = "MovieBox Mobile BFF API scraper for native streams"

    private val delegateSource = MovieBoxAppSource()

    override suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult> {
        val results = mutableListOf<StreamResult>()
        val query = meta.name
        if (query.isBlank()) return emptyList()

        try {
            val searchItems = delegateSource.search(query)
            val isSeries = (type == "series" || type == "tv")
            val matchedItem = searchItems
                .map { item ->
                    val score = BDIXUtils.titleMatchScore(item.title, query)
                    item to score
                }
                .filter { (item, score) ->
                    score >= 0.70 && (!isSeries || item.isSeries)
                }
                .maxByOrNull { (item, score) ->
                    var rank = score
                    if (item.isSeries == isSeries) rank += 1.0
                    rank
                }?.first
                ?: searchItems.firstOrNull { item ->
                    item.isSeries == isSeries && BDIXUtils.titlesMatch(item.title, query)
                }
                ?: searchItems.firstOrNull { item ->
                    BDIXUtils.titlesMatch(item.title, query)
                }
                ?: return emptyList()

            // Extract real subjectId if matchedItem.id is a compound key (e.g. "subjectId|detailPath")
            val idParts = matchedItem.id.split("|")
            val subjectId = idParts.firstOrNull { it.all { ch -> ch.isDigit() } } ?: idParts.firstOrNull() ?: matchedItem.id
            val detailPath = idParts.getOrNull(1) ?: matchedItem.id

            val dataString = org.json.JSONObject().apply {
                put("subjectId", subjectId)
                put("detailPath", detailPath)
                if (type == "series" || type == "tv") {
                    put("season", season ?: 1)
                    put("episode", episode ?: 1)
                }
            }.toString()

            delegateSource.extractVideoLinksStreaming(dataString) { streams ->
                streams.forEach { (title, url) ->
                    results.add(
                        StreamResult(
                            source = "MovieBoxApp",
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
