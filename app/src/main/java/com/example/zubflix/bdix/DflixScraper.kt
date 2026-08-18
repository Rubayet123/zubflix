package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.bdix.BDIXUtils.encode
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object DflixScraper : LocalScraper {
    override val id: String = "dflix"
    override val name: String = "DFLIX Scraper"
    override val description: String = "DFLIX Discovery FTP media server scraper"

    const val SOURCE = "DFLIX"
    private const val BASE = "https://movies.discoveryftp.net"

    override suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult> {
        val searchResults = search(meta.name, type)
        val matchingResults = searchResults.filter { item ->
            val titleMatches = BDIXUtils.titlesMatch(item.title, meta.name)
            val itemYear = BDIXUtils.extractYear(item.details + " " + item.title)
            val yearOk = if (meta.year != null && itemYear != null) {
                kotlin.math.abs(meta.year - itemYear) <= 2
            } else true

            titleMatches && yearOk
        }.sortedByDescending { score(it, meta) }

        if (matchingResults.isEmpty()) return emptyList()

        val allStreams = mutableListOf<StreamResult>()
        for (item in matchingResults) {
            val streams = if (type == "movie") {
                getMovieStreams(item.url, item.title, item.details)
            } else {
                getSeriesStreams(item.url, season ?: 1, episode ?: 1, item.title, item.details)
            }
            allStreams.addAll(streams)
        }

        return allStreams.distinctBy { it.url }
    }

    private suspend fun search(query: String, type: String): List<SearchItem> {
        val searchType = if (type == "movie") "m" else "s"
        val body = "term=${query.encode()}&types=$searchType".toRequestBody()

        return try {
            val request = Request.Builder()
                .url("$BASE/search")
                .post(body)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            BDIXUtils.client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                parseSearchResults(res.body?.string() ?: "")
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun parseSearchResults(html: String): List<SearchItem> {
        val list = mutableListOf<SearchItem>()
        val regex = Regex("""<a href="(/[ms]/view/\d+)"[^>]*>[\s\S]*?<div class="searchtitle"[^>]*>([^<]+)</div>[\s\S]*?<div class="searchdetails"[^>]*>([\s\S]*?)</div>""")
        regex.findAll(html).forEach {
            list.add(SearchItem(it.groupValues[2].trim(), it.groupValues[3].trim(), BASE + it.groupValues[1]))
        }
        return list
    }

    private suspend fun getMovieStreams(url: String, scrapedTitle: String, details: String): List<StreamResult> {
        val html = fetchHtml(url)
        val detailQuality = Regex("Quality:\\s*([^<\\n]+)", RegexOption.IGNORE_CASE).find(details)?.groupValues?.getOrNull(1)?.trim()?.replace(Regex("\\s+"), " ")
        return extractLinks(html).map { link ->
            val qualityLabel = detailQuality ?: BDIXUtils.extractQuality(link)
            val qualityScore = BDIXUtils.scoreQuality("${detailQuality ?: ""} $link")
            StreamResult(SOURCE, qualityLabel, link, qualityScore, scrapedTitle)
        }
    }

    private suspend fun getSeriesStreams(url: String, season: Int, episode: Int, scrapedTitle: String, details: String): List<StreamResult> {
        val html = fetchHtml(url)
        val detailQuality = Regex("Quality:\\s*([^<\\n]+)", RegexOption.IGNORE_CASE).find(details)?.groupValues?.getOrNull(1)?.trim()?.replace(Regex("\\s+"), " ")
        val s = season
        val e = episode
        val patterns = listOf(
            Regex("S0?$s\\D*E0?$e\\b", RegexOption.IGNORE_CASE),
            Regex("\\b0?$s[xX]0?$e\\b", RegexOption.IGNORE_CASE),
            Regex("Season\\s*0?$s.*Episode\\s*0?$e\\b", RegexOption.IGNORE_CASE),
            Regex("\\bE0?$e\\b", RegexOption.IGNORE_CASE),
            Regex("\\bEp\\s*0?$e\\b", RegexOption.IGNORE_CASE)
        )
        return extractLinks(html)
            .filter { link ->
                val linkSeason = Regex("(?i)\\bS0*(\\d+)E0*\\d+\\b").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(?i)\\bSeason\\s*0*(\\d+)\\b").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (linkSeason != null && linkSeason != s) {
                    return@filter false
                }
                patterns.any { regex -> regex.containsMatchIn(link) }
            }
            .map { link ->
                val qualityLabel = detailQuality ?: BDIXUtils.extractQuality(link)
                val qualityScore = BDIXUtils.scoreQuality("${detailQuality ?: ""} $link")
                StreamResult(SOURCE, qualityLabel, link, qualityScore, scrapedTitle)
            }
    }

    private suspend fun fetchHtml(url: String): String {
        return try {
            BDIXUtils.client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() ?: "" }
        } catch (e: Exception) { "" }
    }

    private fun extractLinks(html: String): List<String> {
        val links = mutableListOf<String>()
        Regex("""href="(https?://p?cdn.*?\.(mkv|mp4))"""").findAll(html).forEach {
            links.add(it.groupValues[1])
        }
        return links.distinct()
    }

    data class SearchItem(val title: String, val details: String, val url: String)

    private fun score(it: SearchItem, meta: MediaMeta): Int {
        var score = 10
        BDIXUtils.extractYear(it.details + it.title)?.let { y ->
            score += if (y == meta.year) 15 else if (kotlin.math.abs(y - (meta.year ?: 0)) == 1) 8 else -5
        }
        return score
    }
}
