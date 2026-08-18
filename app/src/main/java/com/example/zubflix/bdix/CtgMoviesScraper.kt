package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.bdix.BDIXUtils.encode
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal object CtgMoviesScraper : LocalScraper {
    override val id: String = "ctgmovies"
    override val name: String = "CTG Movies Scraper"
    override val description: String = "High-speed BDIX scraper for Movies, TV Shows, and Anime"
    const val SOURCE = "CTGMovies"

    private const val PRIMARY_API_BASE = "https://cockpit.103.109.92.178.nip.io/api/v1"
    private const val FALLBACK_API_BASE = "https://ctgmovies.com/api/v1"
    private const val MAIN_URL = "https://ctgmovies.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    data class CtgSearchItem(
        val title: String,
        val url: String,
        val kind: String, // "movies", "tv", "anime"
        val id: String,
        val type: String, // "movie", "tv", "anime"
        val year: Int?
    )

    override suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult> {
        val query = meta.name
        if (query.isBlank()) return emptyList()

        val searchType = if (type == "series") "tv" else type
        val searchResults = searchCtg(query, searchType)
        if (searchResults.isEmpty()) return emptyList()

        val bestMatch = findBestMatch(meta, searchResults, searchType) ?: return emptyList()

        return if (type == "movie") {
            getMovieStreams(bestMatch, meta)
        } else {
            getEpisodeStreams(bestMatch, meta, season ?: 1, episode ?: 1)
        }
    }

    private suspend fun searchCtg(query: String, type: String): List<CtgSearchItem> {
        val queryMap = mapOf("search" to query)
        val items = mutableListOf<CtgSearchItem>()

        if (type == "movie") {
            val moviesJson = apiGet("/movies", queryMap)
            items.addAll(parseSearchItems(moviesJson, "movies"))
            val animeJson = apiGet("/anime", queryMap)
            items.addAll(parseSearchItems(animeJson, "anime"))
        } else {
            val tvJson = apiGet("/tv", queryMap)
            items.addAll(parseSearchItems(tvJson, "tv"))
            val animeJson = apiGet("/anime", queryMap)
            items.addAll(parseSearchItems(animeJson, "anime"))
        }

        return items
    }

    private fun parseSearchItems(jsonStr: String?, defaultKind: String): List<CtgSearchItem> {
        if (jsonStr.isNullOrBlank()) return emptyList()

        val list = mutableListOf<CtgSearchItem>()
        try {
            val jsonArray = try {
                JSONArray(jsonStr)
            } catch (e: Exception) {
                val jsonObj = JSONObject(jsonStr)
                jsonObj.optJSONArray("movies")
                    ?: jsonObj.optJSONArray("results")
                    ?: jsonObj.optJSONArray("data")
                    ?: JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val rawTitle = optString(obj, "title")
                    ?: optString(obj, "name")
                    ?: optString(obj, "english_title")
                    ?: continue

                val id = optString(obj, "slug")
                    ?: optString(obj, "id")
                    ?: optString(obj, "_id")
                    ?: continue

                val isAnime = obj.optBoolean("is_anime", false)
                val year = optInt(obj, "year")
                    ?: yearFromDate(optString(obj, "release_date"))
                    ?: yearFromDate(optString(obj, "first_air_date"))

                val itemKind = if (defaultKind == "movies") "movies" else if (isAnime) "anime" else "tv"
                val itemType = if (itemKind == "movies") "movie" else if (isAnime) "anime" else "tv"
                val url = "$MAIN_URL/$itemKind/$id"

                list.add(
                    CtgSearchItem(
                        title = cleanDisplayTitle(rawTitle),
                        url = url,
                        kind = itemKind,
                        id = id,
                        type = itemType,
                        year = year
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }

        return list
    }

    private fun findBestMatch(meta: MediaMeta, items: List<CtgSearchItem>, targetType: String): CtgSearchItem? {
        if (items.isEmpty()) return null
        val targetTitleNorm = normalizedTitle(meta.name)
        val targetYear = meta.year

        var bestItem: CtgSearchItem? = null
        var maxScore = -1

        for (item in items) {
            val itemTitleNorm = normalizedTitle(item.title)
            var score = -1

            if (itemTitleNorm == targetTitleNorm) {
                score = 100
                if (targetYear != null && item.year == targetYear) score += 50
            } else if (itemTitleNorm.contains(targetTitleNorm) && targetTitleNorm.length >= 4) {
                score = 60
                if (targetYear != null && item.year == targetYear) score += 30
            } else if (targetTitleNorm.contains(itemTitleNorm) && itemTitleNorm.length >= 4) {
                score = 40
            }

            if ((targetType == "series" || targetType == "tv") && (item.type == "tv" || item.type == "anime")) {
                score += 10
            } else if (targetType == "movie" && (item.type == "movie" || item.type == "anime")) {
                score += 10
            }

            if (score > maxScore) {
                maxScore = score
                bestItem = item
            }
        }

        return if (maxScore >= 30) bestItem else null
    }

    private suspend fun getMovieStreams(item: CtgSearchItem, meta: MediaMeta): List<StreamResult> {
        val detailJson = apiGet("/${item.kind}/${item.id}") ?: return emptyList()
        val results = mutableListOf<StreamResult>()

        try {
            val detailObj = JSONObject(detailJson)
            val linksArr = detailObj.optJSONArray("links") ?: JSONArray()
            results.addAll(parseLinks(linksArr, item.title))
        } catch (e: Exception) {
            // Ignore
        }

        return results
    }

    private suspend fun getEpisodeStreams(item: CtgSearchItem, meta: MediaMeta, season: Int, episode: Int): List<StreamResult> {
        val detailJson = apiGet("/${item.kind}/${item.id}") ?: return emptyList()
        val results = mutableListOf<StreamResult>()

        try {
            val detailObj = JSONObject(detailJson)
            val episodesArr = detailObj.optJSONArray("episodes") ?: JSONArray()

            for (i in 0 until episodesArr.length()) {
                val epObj = episodesArr.optJSONObject(i) ?: continue
                val epSeason = optInt(epObj, "season_number") ?: optInt(epObj, "season") ?: 1
                val epNum = optInt(epObj, "episode_number") ?: optInt(epObj, "absolute_number") ?: optInt(epObj, "episode") ?: optInt(epObj, "ep") ?: (i + 1)

                if (epSeason == season && epNum == episode) {
                    val linksArr = epObj.optJSONArray("links") ?: JSONArray()
                    results.addAll(parseLinks(linksArr, item.title))
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return results
    }

    private fun parseLinks(linksArr: JSONArray, mediaTitle: String): List<StreamResult> {
        val results = mutableListOf<StreamResult>()
        val seenUrls = mutableSetOf<String>()

        for (i in 0 until linksArr.length()) {
            val linkObj = linksArr.optJSONObject(i) ?: continue
            if (linkObj.optBoolean("broken", false)) continue

            val rawUrl = optString(linkObj, "url")
                ?: optString(linkObj, "file")
                ?: optString(linkObj, "src")
                ?: optString(linkObj, "link")
                ?: continue

            val resolvedUrl = resolveMediaUrl(rawUrl)
            if (resolvedUrl.isBlank() || !seenUrls.add(resolvedUrl)) continue

            val qualityStr = optString(linkObj, "quality") ?: ""
            val quality = qualityFromUrl(resolvedUrl, qualityStr)
            val lang = (optString(linkObj, "language") ?: "en").lowercase()

            val langLabel = when {
                lang.contains("hin") || qualityStr.lowercase().contains("hindi") -> "Hindi 🇮🇳"
                lang.contains("ben") || lang.contains("bangla") -> "Bangla 🇧🇩"
                else -> "English 🇺🇸"
            }

            val groupSource = optString(linkObj, "group_source")
                ?: optString(linkObj, "source_display")
                ?: "Server ${i + 1}"

            val cleanSource = cleanSourceName(groupSource)
            val score = qualityScore(quality)
            val streamTitle = "$quality | $langLabel | $cleanSource"

            results.add(
                StreamResult(
                    source = SOURCE,
                    title = streamTitle,
                    url = resolvedUrl,
                    qualityScore = score,
                    mediaTitle = mediaTitle
                )
            )
        }

        return results
    }

    private suspend fun apiGet(endpoint: String, queryParams: Map<String, String> = emptyMap()): String? {
        val path = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        val qStr = if (queryParams.isNotEmpty()) {
            "?" + queryParams.entries.joinToString("&") { "${it.key.encode()}=${it.value.encode()}" }
        } else ""

        val primaryUrl = "$PRIMARY_API_BASE$path$qStr"
        val fallbackUrl = "$FALLBACK_API_BASE$path$qStr"

        fun buildReq(url: String): Request {
            return Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Accept-Language", "en")
                .header("Referer", "$MAIN_URL/")
                .header("Origin", MAIN_URL)
                .build()
        }

        try {
            BDIXUtils.client.newCall(buildReq(primaryUrl)).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (!body.isNullOrBlank()) return body
                }
            }
        } catch (e: Exception) {
            // Ignore & try fallback
        }

        try {
            BDIXUtils.client.newCall(buildReq(fallbackUrl)).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (!body.isNullOrBlank()) return body
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return null
    }

    private fun resolveMediaUrl(url: String): String {
        if (url.isBlank()) return ""
        var u = url.trim()
        if (u.startsWith("//")) u = "http:$u"
        if (u.contains("ctgfun.com", ignoreCase = true) || u.contains("103.109.92.", ignoreCase = true)) {
            u = u.replace("https://", "http://", ignoreCase = true)
        }
        if (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) return u
        if (u.startsWith("/")) return "$MAIN_URL$u"
        return u
    }

    private fun qualityFromUrl(url: String, qualityStr: String): String {
        val text = "$url $qualityStr"
        val match = Regex("""(2160p|1440p|1080p|720p|576p|540p|480p|360p|4k|uhd)""", RegexOption.IGNORE_CASE).find(text)
        if (match != null) {
            val q = match.groupValues[1].lowercase()
            if (q == "4k" || q == "uhd") return "2160p"
            return q
        }
        return "1080p"
    }

    private fun qualityScore(quality: String): Int {
        val q = quality.lowercase()
        return when {
            q.contains("2160p") || q.contains("4k") -> 40
            q.contains("1080p") -> 30
            q.contains("720p") -> 20
            q.contains("576p") || q.contains("480p") -> 15
            else -> 10
        }
    }

    private fun cleanDisplayTitle(title: String): String {
        if (title.isBlank()) return ""
        return title
            .replace(Regex("""\b(1080p|720p|480p|2160p|4k|web[- ]?dl|webrip|bluray|hdrip|x264|x265|hevc|10bit|dual[- ]?audio|hindi[- ]?dubbed|dubbed|esub)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizedTitle(title: String): String {
        return cleanDisplayTitle(title).lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
    }

    private fun cleanSourceName(sourceName: String): String {
        if (sourceName.isBlank()) return ""
        var cleaned = sourceName.replace("auto:", "").replace(":", " ").replace("-", " ").trim()
        val regex = Regex("""^server\s*([a-z0-9]+)$""", RegexOption.IGNORE_CASE)
        val match = regex.find(cleaned)
        if (match != null) {
            cleaned = "Server " + match.groupValues[1].uppercase()
        }
        return cleaned
    }

    private fun optString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val str = obj.optString(key, "").trim()
        return if (str.isBlank() || str.equals("null", ignoreCase = true)) null else str
    }

    private fun optInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val valObj = obj.opt(key)
        if (valObj is Int) return valObj
        if (valObj is Number) return valObj.toInt()
        return valObj?.toString()?.toIntOrNull()
    }

    private fun yearFromDate(dateStr: String?): Int? {
        if (dateStr.isNullOrBlank()) return null
        return Regex("""\d{4}""").find(dateStr)?.value?.toIntOrNull()
    }
}
