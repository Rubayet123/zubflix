package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.sources.InMemoryCookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object IccFtpScraper : LocalScraper {
    override val id: String = "iccftp"
    override val name: String = "ICC FTP Scraper"
    override val description: String = "ICC FTP BDIX high speed media server scraper"

    const val SOURCE = "ICC FTP"
    private const val BASE = "http://10.16.100.244"
    private const val SESSION_TOKEN = "83408fdbf5821f154e708b55c2df1f8056a8052315ee92cb17047e51bf8fae2f"
    private const val SESSION_URL = "$BASE/dashboard.php?session=$SESSION_TOKEN"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private var sessionPrimed = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(InMemoryCookieJar())
        .build()

    private suspend fun ensureSession() {
        if (sessionPrimed) return
        try {
            val request = Request.Builder()
                .url(SESSION_URL)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    sessionPrimed = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult> {
        return try {
            ensureSession()
            val searchResults = searchServer(meta.name)
            if (searchResults.isEmpty()) return emptyList()

            val streams = mutableListOf<StreamResult>()
            
            val requestedSeries = (type == "series" || type == "tv")
            val filteredResults = searchResults.filter { item ->
                val isSeriesItem = item.type?.contains("tv", ignoreCase = true) == true ||
                                   item.type?.contains("series", ignoreCase = true) == true ||
                                   item.type?.contains("show", ignoreCase = true) == true ||
                                   item.type?.contains("season", ignoreCase = true) == true ||
                                   item.type?.contains("drama", ignoreCase = true) == true ||
                                   item.type?.contains("anime", ignoreCase = true) == true
                if (!item.type.isNullOrBlank()) {
                    if (isSeriesItem != requestedSeries) return@filter false
                }
                
                item.name?.let { BDIXUtils.titlesMatch(it, meta.name) } == true
            }

            filteredResults.forEach { item ->
                val id = item.id ?: return@forEach
                val itemName = item.name ?: ""
                extractStreams(id).forEach { (link, pageTitle) ->
                    if (shouldInclude(link, type, meta, season, episode)) {
                        val finalTitle = if (pageTitle.isNotEmpty()) pageTitle else itemName
                        streams.add(StreamResult(SOURCE, BDIXUtils.extractQuality(link), link, BDIXUtils.scoreQuality(link), finalTitle))
                    }
                }
            }
            streams
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun searchServer(query: String): List<SearchJsonItem> {
        val formBody = FormBody.Builder().add("cSearch", query).build()
        val request = Request.Builder()
            .url("$BASE/command.php")
            .post(formBody)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE/")
            .build()

        return try {
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val jsonString = res.body?.string() ?: return emptyList()
                val json = org.json.JSONArray(jsonString)
                val list = mutableListOf<SearchJsonItem>()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    list.add(SearchJsonItem(
                        id = obj.optString("id", null),
                        image = obj.optString("image", null),
                        name = obj.optString("name", null),
                        type = obj.optString("type", null)
                    ))
                }
                list
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun extractStreams(id: String): List<Pair<String, String>> {
        val req = Request.Builder()
            .url("$BASE/player.php?session=$SESSION_TOKEN&play=$id")
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                val html = res.body?.string() ?: ""
                val pageTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.trim()
                    ?.replace(" - ICC", "", ignoreCase = true)
                    ?.replace(" - Player", "", ignoreCase = true)
                    ?.trim() ?: ""
                
                Regex("""<source\s+src='([^']+)'""").findAll(html)
                    .map { it.groupValues[1] }
                    .filter { !it.matches(Regex(".*\\.(rar|zip|iso|txt|srt)$", RegexOption.IGNORE_CASE)) }
                    .map { link ->
                        val fileName = BDIXUtils.getNameFromPath(link)
                        val isGenericPageTitle = pageTitle.isBlank() || 
                                                pageTitle.lowercase().let { 
                                                    it == "icc ftp" || it == "icc ftp server" || it == "player" || it == "icc player" || it.contains("ftp server")
                                                }
                        val t = if (fileName.isNotEmpty() && !fileName.lowercase().matches(Regex(".*(player|index|stream|video|play|source).*"))) {
                            fileName
                        } else if (!isGenericPageTitle) {
                            pageTitle
                        } else {
                            ""
                        }
                        Pair(link, t)
                    }
                    .toList()
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun shouldInclude(url: String, type: String, meta: MediaMeta, season: Int?, episode: Int?): Boolean {
        val isSeries = (type == "series" || type == "tv")
        if (!isSeries && meta.year != null) {
            val urlYear = Regex("(19|20)\\d{2}").find(url)?.value?.toIntOrNull()
            if (urlYear != null) {
                return kotlin.math.abs(urlYear - meta.year) <= 1
            }
        }
        if (isSeries && season != null && episode != null) {
            val s = season
            val e = episode
            val urlSeason = Regex("(?i)\\bS0*(\\d+)E0*\\d+\\b").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(?i)\\bSeason\\s*0*(\\d+)\\b").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (urlSeason != null && urlSeason != s) {
                return false
            }
            val patterns = listOf(
                Regex("S0?$s\\D*E0?$e\\b", RegexOption.IGNORE_CASE),
                Regex("\\b0?$s[xX]0?$e\\b", RegexOption.IGNORE_CASE),
                Regex("Season\\s*0?$s.*Episode\\s*0?$e\\b", RegexOption.IGNORE_CASE),
                Regex("\\bE0?$e\\b", RegexOption.IGNORE_CASE),
                Regex("\\bEp\\s*0?$e\\b", RegexOption.IGNORE_CASE)
            )
            return patterns.any { it.containsMatchIn(url) }
        }
        return true
    }

    private data class SearchJsonItem(val id: String?, val image: String?, val name: String?, val type: String?)
}
