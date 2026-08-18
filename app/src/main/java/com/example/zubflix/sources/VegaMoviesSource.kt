package com.example.zubflix.sources

import android.util.Log
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class VegaMoviesSource : StreamingSource {
    override val name: String = "VegaMovies 🎬"

    companion object {
        private const val DEFAULT_MAIN_URL = "https://new1.vegamovies.futbol"
        private const val URL_CONFIG_API = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
        private const val CINEMETA_URL = "https://v3-cinemeta.strem.io/meta"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private var cachedMainUrl: String? = null
    private var cachedUrlConfig: JSONObject? = null
    private var lastConfigFetchTime = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getHeaders(referer: String = getMainUrl()): Map<String, String> {
        return mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$referer/",
            "Cookie" to "xla=s4t"
        )
    }

    private fun fetchUrlConfig(): JSONObject? {
        val now = System.currentTimeMillis()
        if (cachedUrlConfig != null && (now - lastConfigFetchTime) < 300_000L) {
            return cachedUrlConfig
        }
        return try {
            val req = Request.Builder().url(URL_CONFIG_API).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    cachedUrlConfig = json
                    lastConfigFetchTime = now
                    json
                } else null
            }
        } catch (e: Exception) {
            Log.w("VegaMoviesSource", "Failed to fetch URLs config: ${e.message}")
            cachedUrlConfig
        }
    }

    private fun getMainUrl(): String {
        cachedMainUrl?.let { return it }
        return try {
            val config = fetchUrlConfig()
            val dynamicUrl = config?.optString("vegamovies")?.trim()?.trimEnd('/')
            if (!dynamicUrl.isNullOrBlank() && dynamicUrl.startsWith("http")) {
                cachedMainUrl = dynamicUrl
                return dynamicUrl
            }
            DEFAULT_MAIN_URL
        } catch (e: Exception) {
            DEFAULT_MAIN_URL
        }.also { cachedMainUrl = it }
    }

    private fun getLatestBaseUrl(baseUrl: String, source: String): String {
        return try {
            val config = fetchUrlConfig()
            val dynamic = config?.optString(source)?.trim()?.trimEnd('/')
            if (!dynamic.isNullOrBlank() && dynamic.startsWith("http")) dynamic else baseUrl
        } catch (e: Exception) {
            baseUrl
        }
    }

    private fun getBaseUrl(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            url
        }
    }

    private fun getIndexQuality(str: String?): String {
        if (str.isNullOrBlank()) return "1080p"
        val match = Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)
        if (match != null) return "${match}p"
        val lower = str.lowercase()
        return when {
            lower.contains("8k") -> "8K"
            lower.contains("4k") || lower.contains("2160p") -> "4K"
            lower.contains("2k") || lower.contains("1440p") -> "2K"
            lower.contains("1080p") -> "1080p"
            lower.contains("720p") -> "720p"
            lower.contains("480p") -> "480p"
            else -> "HD"
        }
    }

    private val categories = listOf(
        "latest" to "🔥 Latest Releases",
        "netflix" to "🔴 Netflix Series",
        "hotstar" to "⭐ Disney+ Hotstar",
        "prime" to "📦 Amazon Prime",
        "mx" to "🎬 MX Original",
        "anime" to "⚡ Anime Series",
        "korean" to "🌸 Korean Series",
        "bollywood" to "🎬 Bollywood Movies",
        "dual-audio" to "🌟 Dual Audio",
        "4k" to "⚡ 4K Ultra HD"
    )

    private fun fetchText(url: String, customReferer: String? = null): String {
        val referer = customReferer ?: getMainUrl()
        val reqBuilder = Request.Builder().url(url)
        getHeaders(referer).forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: "" else ""
            }
        } catch (e: Exception) {
            Log.w("VegaMoviesSource", "Failed to fetch $url: ${e.message}")
            ""
        }
    }

    private fun cleanTitle(raw: String): String {
        var clean = raw
        // Remove common site keywords & prefixes
        clean = clean.replace("Download", "", ignoreCase = true)
            .replace("Hindi Dubbed", "", ignoreCase = true)
            .replace("Dual Audio", "", ignoreCase = true)
            .replace("Multi Audio", "", ignoreCase = true)
            .replace("Hindi-English", "", ignoreCase = true)
            .replace("English-Hindi", "", ignoreCase = true)
            .replace("Full Movie For Free", "", ignoreCase = true)

        // Remove domain names like vegamovies.futbol, vegamovies.yt, vegamovies.mq, etc.
        clean = clean.replace(Regex("(?i)vegamovies\\.[a-z0-9]+"), "")
        clean = clean.replace(Regex("(?i)vegamovies"), "")

        // Remove curly braces, brackets, and parenthesis contents
        clean = clean.replace(Regex("\\{.*?\\}"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\(.*?\\)"), "")

        // Remove resolutions, codecs, qualities, e.g. 480p, 720p, 1080p, 2160p, 4K, x264, x265, HEVC, PREHD, WEB-DL, BluRay, HDTC, ESUB, ESub, HQ
        clean = clean.replace(Regex("(?i)\\b(480p|720p|1080p|2160p|4k|x264|x265|hevc|prehd|web-dl|webrip|hdrip|bluray|hdtc|esub|esubs|hq|uncut|extended)\\b"), "")

        // Clean leftover separators like |, -, redundant spaces
        clean = clean.replace(Regex("\\s*[|\\-–—]+\\s*"), " ")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

        return clean
    }

    private fun parsePostCards(html: String): List<StreamingItem> {
        val items = mutableListOf<StreamingItem>()
        if (html.isBlank()) return items

        val mainUrl = getMainUrl()
        try {
            val doc = Jsoup.parse(html)
            // Support both movies-grid > a and standard article / post-card elements
            val gridCards = doc.select("div.movies-grid > a")
            val articleCards = doc.select("article, div.post-card, div.item, .ml-item, div.blog-post")
            val allElements = if (gridCards.isNotEmpty()) gridCards else articleCards

            for (card in allElements) {
                val linkEl = if (card.tagName() == "a") card else card.selectFirst("a[href]") ?: continue
                val href = linkEl.attr("href")
                if (href.isBlank() || (!href.startsWith("http") && !href.startsWith("/"))) continue

                val fullUrl = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith("/")) "" else "/"}$href"
                val id = fullUrl.replace(mainUrl, "").trim('/')
                if (id.isBlank() || id.contains("category/") || id.contains("page/") || id.contains("tag/")) continue

                val imgEl = card.selectFirst("img")
                var imgUrl = imgEl?.attr("data-src")?.ifEmpty { null }
                    ?: imgEl?.attr("src")?.ifEmpty { null }
                    ?: imgEl?.attr("data-lazy-src")

                if (imgUrl.isNullOrBlank() || imgUrl.contains("blank.gif") || imgUrl.contains("data:image")) continue

                if (!imgUrl.startsWith("http")) {
                    imgUrl = "$mainUrl${if (imgUrl.startsWith("/")) "" else "/"}$imgUrl"
                }

                val rawTitle = card.selectFirst(".entry-title, .title, h2, h3")?.text()?.ifEmpty { null }
                    ?: imgEl?.attr("alt")?.ifEmpty { null }
                    ?: linkEl.attr("title")
                if (rawTitle.isNullOrBlank()) continue

                val displayTitle = cleanTitle(rawTitle)
                val isTv = Regex("\\b(season\\s*\\d+|web-series|tv-series|episodes|s0\\d|s\\d+e\\d+)\\b", RegexOption.IGNORE_CASE).containsMatchIn("$fullUrl $rawTitle")

                items.add(
                    StreamingItem(
                        id = id,
                        title = displayTitle.ifBlank { rawTitle },
                        isSeries = isTv,
                        imageUrl = imgUrl,
                        quality = if (rawTitle.contains("4K", ignoreCase = true)) "4K" else "1080p",
                        sourceName = name
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("VegaMoviesSource", "Error parsing post cards: ${e.message}")
        }

        return items.distinctBy { it.id }
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val mainUrl = getMainUrl()
        val tasks = categories.map { (catId, catTitle) ->
            async {
                val url = when (catId) {
                    "latest" -> "$mainUrl/page/1/"
                    "netflix" -> "$mainUrl/category/web-series/netflix/page/1/"
                    "hotstar" -> "$mainUrl/category/web-series/disney-plus-hotstar/page/1/"
                    "prime" -> "$mainUrl/category/web-series/amazon-prime-video/page/1/"
                    "mx" -> "$mainUrl/category/web-series/mx-original/page/1/"
                    "anime" -> "$mainUrl/category/anime-series/page/1/"
                    "korean" -> "$mainUrl/category/korean-series/page/1/"
                    "bollywood" -> "$mainUrl/category/bollywood/page/1/"
                    "dual-audio" -> "$mainUrl/category/dual-audio/page/1/"
                    "4k" -> "$mainUrl/category/4k-movies/page/1/"
                    else -> "$mainUrl/"
                }
                val html = fetchText(url)
                val items = parsePostCards(html)

                if (items.isNotEmpty()) {
                    StreamingCategory(id = catId, title = catTitle, items = items)
                } else {
                    val fallback = getCuratedVegaCategoryItems(catId)
                    if (fallback.isNotEmpty()) {
                        StreamingCategory(id = catId, title = catTitle, items = fallback)
                    } else null
                }
            }
        }

        tasks.awaitAll().filterNotNull()
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val mainUrl = getMainUrl()
        val catPath = when (categoryId) {
            "netflix" -> "category/web-series/netflix"
            "hotstar" -> "category/web-series/disney-plus-hotstar"
            "prime" -> "category/web-series/amazon-prime-video"
            "mx" -> "category/web-series/mx-original"
            "anime" -> "category/anime-series"
            "korean" -> "category/korean-series"
            "bollywood" -> "category/bollywood"
            "dual-audio" -> "category/dual-audio"
            "4k" -> "category/4k-movies"
            else -> ""
        }
        val url = if (catPath.isEmpty()) {
            "$mainUrl/page/$page/"
        } else {
            "$mainUrl/$catPath/page/$page/"
        }

        val html = fetchText(url)
        val items = parsePostCards(html)
        if (items.isNotEmpty()) items else getCuratedVegaCategoryItems(categoryId)
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val mainUrl = getMainUrl()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        // 1. Try VegaMovies search.php JSON API endpoint first
        val jsonUrl = "$mainUrl/search.php?q=$encoded&page=1"
        val jsonText = fetchText(jsonUrl)
        if (jsonText.isNotBlank() && jsonText.trim().startsWith("{")) {
            try {
                val json = JSONObject(jsonText)
                val hits = json.optJSONArray("hits")
                if (hits != null && hits.length() > 0) {
                    val results = mutableListOf<StreamingItem>()
                    for (i in 0 until hits.length()) {
                        val hit = hits.optJSONObject(i) ?: continue
                        val doc = hit.optJSONObject("document") ?: continue
                        val rawTitle = doc.optString("post_title")
                        val permalink = doc.optString("permalink")
                        val posterUrl = doc.optString("post_thumbnail")

                        if (permalink.isNotBlank()) {
                            val id = permalink.replace(mainUrl, "").trim('/')
                            val displayTitle = cleanTitle(rawTitle)
                            val isTv = Regex("\\b(season\\s*\\d+|web-series|tv-series|episodes|s0\\d|s\\d+e\\d+)\\b", RegexOption.IGNORE_CASE).containsMatchIn("$permalink $rawTitle")

                            results.add(
                                StreamingItem(
                                    id = id,
                                    title = displayTitle.ifBlank { rawTitle },
                                    isSeries = isTv,
                                    imageUrl = posterUrl,
                                    quality = if (rawTitle.contains("4K", ignoreCase = true)) "4K" else "1080p",
                                    sourceName = name
                                )
                            )
                        }
                    }
                    if (results.isNotEmpty()) return@withContext results
                }
            } catch (e: Exception) {
                Log.w("VegaMoviesSource", "Error parsing search.php JSON: ${e.message}")
            }
        }

        // 2. Fallback to HTML search page
        val searchHtmlUrl = "$mainUrl/?s=$encoded"
        val html = fetchText(searchHtmlUrl)
        val results = parsePostCards(html)

        if (results.isNotEmpty()) {
            results
        } else {
            getAllCuratedVegaItems().filter {
                it.title.contains(query, ignoreCase = true)
            }
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        val mainUrl = getMainUrl()
        val fullUrl = if (id.startsWith("http")) id else "$mainUrl/${id.trim('/')}/"
        val html = fetchText(fullUrl)

        if (html.isNotBlank()) {
            try {
                val doc = Jsoup.parse(html)
                var rawTitle = doc.selectFirst("title, .entry-title, h1")?.text() ?: "VegaMovies Item"
                var clean = cleanTitle(rawTitle)
                var posterUrl = doc.selectFirst(".entry-content img, article img, p > img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }
                var overview = doc.selectFirst("h3:has(span:matches((?i)SYNOPSIS/PLOT))")?.nextElementSibling()?.text()
                    ?: doc.selectFirst(".entry-content p")?.text()
                    ?: "Watch high quality streaming on VegaMovies."

                val imdbUrl = doc.select("a[href*=\"imdb\"]").attr("href")
                val imdbId = if (imdbUrl.contains("title/")) {
                    imdbUrl.substringAfter("title/").substringBefore("/")
                } else ""

                val isTv = doc.selectFirst("h3:matches((?i)Series-SYNOPSIS/PLOT)") != null ||
                        doc.selectFirst("h3:matches((?i)Series Info)") != null ||
                        doc.selectFirst("h3:matches((?i)Series synopsis/PLOT)") != null ||
                        Regex("\\b(season\\s*\\d+|web-series|tv-series|episodes|s0\\d|s\\d+e\\d+)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle) ||
                        fullUrl.contains("/category/web-series/", ignoreCase = true) ||
                        fullUrl.contains("/category/anime-series/", ignoreCase = true) ||
                        fullUrl.contains("/category/korean-series/", ignoreCase = true)

                val tvTypeStr = if (isTv) "series" else "movie"

                // Fetch Cinemeta Stremio metadata if IMDb ID is available
                var backgroundUrl = posterUrl
                if (imdbId.isNotBlank() && imdbId.startsWith("tt")) {
                    val cinemetaUrl = "$CINEMETA_URL/$tvTypeStr/$imdbId.json"
                    val cinemetaText = fetchText(cinemetaUrl)
                    if (cinemetaText.isNotBlank() && cinemetaText.trim().startsWith("{")) {
                        try {
                            val cMeta = JSONObject(cinemetaText).optJSONObject("meta")
                            if (cMeta != null) {
                                cMeta.optString("name")?.let { if (it.isNotBlank()) clean = cleanTitle(it) }
                                cMeta.optString("description")?.let { if (it.isNotBlank()) overview = it }
                                cMeta.optString("poster")?.let { if (it.isNotBlank()) posterUrl = it }
                                cMeta.optString("background")?.let { if (it.isNotBlank()) backgroundUrl = it }
                            }
                        } catch (e: Exception) {
                            Log.w("VegaMoviesSource", "Cinemeta parse error: ${e.message}")
                        }
                    }
                }

                var seasons: List<StreamingSeason>? = null
                if (isTv) {
                    val hTags = doc.select("main > h3:matches((?i)(4K|[0-9]*0p)), main > h5:matches((?i)(4K|[0-9]*0p)), .entry-content h3, .entry-content h4, .entry-content h5")
                        .filter { el -> !el.text().contains("Zip", true) }

                    val seasonEpisodesMap = mutableMapOf<Int, MutableList<StreamingEpisode>>()

                    for (tag in hTags) {
                        val realSeasonRegex = Regex("""(?:Season |S)(\d+)""", RegexOption.IGNORE_CASE)
                        val seasonNum = realSeasonRegex.find(tag.text() + " " + tag.parent()?.text())?.groupValues?.get(1)?.toIntOrNull() ?: 1

                        val pTag = tag.nextElementSibling()
                        val aTags: List<Element> = if (pTag != null && pTag.tagName() == "p") {
                            pTag.select("a")
                        } else {
                            tag.select("a")
                        }

                        val uniLink = aTags.find {
                            it.text().contains("V-Cloud", ignoreCase = true) ||
                                    it.text().contains("Episode", ignoreCase = true) ||
                                    it.text().contains("Download", ignoreCase = true) ||
                                    it.text().contains("G-Direct", ignoreCase = true) ||
                                    it.attr("href").contains("nexdrive", ignoreCase = true) ||
                                    it.attr("href").contains("genxfm", ignoreCase = true)
                        } ?: aTags.firstOrNull()

                        val eUrl = uniLink?.attr("href")
                        if (!eUrl.isNullOrBlank()) {
                            val epHtml = fetchText(eUrl, fullUrl)
                            val epDoc = Jsoup.parse(epHtml)
                            val subLinks = epDoc.select("p > a, .entry-content a, a.btn").mapNotNull { a ->
                                val href = a.attr("href")
                                if (href.isNotBlank() && (href.contains("vcloud") || href.contains("hubcloud") || href.contains("fastdl") || href.contains("filebee") || href.contains("gofile"))) {
                                    href
                                } else null
                            }

                            val targetList = seasonEpisodesMap.getOrPut(seasonNum) { mutableListOf() }
                            if (subLinks.isNotEmpty()) {
                                subLinks.forEachIndexed { idx, subLink ->
                                    val epNum = idx + 1
                                    if (targetList.none { it.title.contains("Episode $epNum") }) {
                                        val payload = JSONObject().apply {
                                            put("id", id)
                                            put("linkUrl", subLink)
                                            put("pageUrl", eUrl)
                                        }.toString()
                                        targetList.add(
                                            StreamingEpisode(
                                                title = "Episode $epNum",
                                                streamUrl = payload,
                                                stillUrl = posterUrl
                                            )
                                        )
                                    }
                                }
                            } else {
                                val payload = JSONObject().apply {
                                    put("id", id)
                                    put("linkUrl", eUrl)
                                    put("pageUrl", fullUrl)
                                }.toString()
                                val epNum = targetList.size + 1
                                targetList.add(
                                    StreamingEpisode(
                                        title = "Episode $epNum",
                                        streamUrl = payload,
                                        stillUrl = posterUrl
                                    )
                                )
                            }
                        }
                    }

                    if (seasonEpisodesMap.isNotEmpty()) {
                        seasons = seasonEpisodesMap.map { (sNum, eps) ->
                            StreamingSeason(title = "Season $sNum", seasonNumber = sNum, episodes = eps)
                        }.sortedBy { it.seasonNumber }
                    } else {
                        // Fallback: collect all download buttons directly
                        val directButtons = doc.select("a[href*=vcloud], a[href*=hubcloud], a[href*=fastdl], a[href*=nexdrive], a[href*=genxfm], a[href*=drive], a:has(button.dwd-button), a.btn")
                        val epList = mutableListOf<StreamingEpisode>()
                        var epIdx = 1
                        for (btn in directButtons) {
                            val href = btn.attr("href")
                            if (href.isBlank()) continue
                            val payload = JSONObject().apply {
                                put("id", id)
                                put("linkUrl", href)
                                put("pageUrl", fullUrl)
                            }.toString()
                            epList.add(
                                StreamingEpisode(
                                    title = "Episode $epIdx",
                                    streamUrl = payload,
                                    stillUrl = posterUrl
                                )
                            )
                            epIdx++
                        }
                        if (epList.isNotEmpty()) {
                            seasons = listOf(StreamingSeason(title = "Season 1", episodes = epList, seasonNumber = 1))
                        }
                    }
                }

                val payload = JSONObject().apply {
                    put("id", id)
                    put("pageUrl", fullUrl)
                }.toString()

                return@withContext StreamingItem(
                    id = id,
                    title = clean.ifBlank { cleanTitle(rawTitle) },
                    isSeries = isTv,
                    imageUrl = posterUrl,
                    backdropUrl = backgroundUrl,
                    description = overview,
                    streamUrl = payload,
                    seasons = seasons,
                    sourceName = name
                )
            } catch (e: Exception) {
                Log.e("VegaMoviesSource", "Error parsing post details: ${e.message}")
            }
        }

        getAllCuratedVegaItems().find { it.id == id }?.copy(
            streamUrl = JSONObject().apply { put("id", id); put("pageUrl", "$mainUrl/$id/") }.toString()
        )
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val streams = mutableMapOf<String, String>()
        val mainUrl = getMainUrl()
        val headersJson = "{\"User-Agent\":\"$UA\",\"Referer\":\"$mainUrl/\"}"

        try {
            var targetUrl = ""
            var refererUrl = mainUrl
            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                targetUrl = json.optString("linkUrl").ifEmpty { json.optString("pageUrl", "") }
                refererUrl = json.optString("pageUrl", mainUrl)
            } else if (data.startsWith("http")) {
                targetUrl = data
            }

            if (targetUrl.isNotEmpty()) {
                val lower = targetUrl.lowercase()
                if (lower.contains("vcloud") || lower.contains("hubcloud")) {
                    processVCloudUrl(targetUrl, refererUrl, streams, headersJson)
                } else {
                    // Fetch intermediate page (e.g. nexdrive, genxfm, fastdl, or post page)
                    val pageHtml = fetchText(targetUrl, refererUrl)
                    if (pageHtml.isNotBlank()) {
                        val doc = Jsoup.parse(pageHtml)
                        val buttons = doc.select("a[href*=vcloud], a[href*=hubcloud], a[href*=fastdl], a[href*=filebee], a[href*=gofile], a[href*=drive], a:has(button.dwd-button), a.btn")
                        for (btn in buttons) {
                            var btnHref = btn.attr("href")
                            if (btnHref.isBlank() || btnHref == "#" || btnHref.lowercase().contains(".zip")) continue

                            if (btnHref.contains("/api/index.php?link=")) {
                                val apiHtml = fetchText(btnHref, targetUrl)
                                val apiDoc = Jsoup.parse(apiHtml)
                                val nextHref = apiDoc.selectFirst("a.btn-success, a.btn")?.attr("href")
                                if (!nextHref.isNullOrBlank()) {
                                    btnHref = if (nextHref.startsWith("/")) getBaseUrl(btnHref) + nextHref else nextHref
                                }
                            }

                            if (btnHref.contains("vcloud") || btnHref.contains("hubcloud")) {
                                processVCloudUrl(btnHref, targetUrl, streams, headersJson)
                            } else if (btnHref.contains("gofile")) {
                                streams["[Gofile Server] Vega HD"] = "$btnHref######$headersJson"
                            } else if (btnHref.contains("pixeldra")) {
                                val finalPxl = if (btnHref.contains("download", true)) btnHref else "${getBaseUrl(btnHref)}/api/file/${btnHref.substringAfterLast("/")}?download"
                                streams["[Pixeldrain] Vega HD"] = "$finalPxl######$headersJson"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VegaMoviesSource", "Error extracting video links: ${e.message}")
        }

        streams
    }

    private fun processVCloudUrl(inputUrl: String, referer: String, streams: MutableMap<String, String>, headersJson: String) {
        try {
            var baseUrl = getBaseUrl(inputUrl)
            val isHub = inputUrl.contains("hubcloud", ignoreCase = true)
            val latestBaseUrl = if (isHub) {
                getLatestBaseUrl(baseUrl, "hubcloud")
            } else {
                getLatestBaseUrl(baseUrl, "vcloud")
            }

            var targetVcUrl = inputUrl
            if (baseUrl != latestBaseUrl && (inputUrl.contains("vcloud") || inputUrl.contains("hubcloud"))) {
                targetVcUrl = inputUrl.replace(baseUrl, latestBaseUrl)
                baseUrl = latestBaseUrl
            }

            val html = fetchText(targetVcUrl, referer)
            if (html.isBlank()) return

            val doc = Jsoup.parse(html)
            val cardHeader = doc.selectFirst("div.card-header")?.text() ?: ""
            val detectedQuality = getIndexQuality(cardHeader)
            val size = doc.select("i#size").text().let { if (it.isNotBlank()) " [$it]" else "" }

            var bridgeUrl = ""
            val doubleAtobMatch = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""").find(html)
            val varMatch = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""").find(html)

            if (doubleAtobMatch != null) {
                try {
                    val encoded = doubleAtobMatch.groupValues[1]
                    val dec1 = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                    bridgeUrl = String(android.util.Base64.decode(dec1, android.util.Base64.DEFAULT))
                } catch (e: Exception) {
                    bridgeUrl = doubleAtobMatch.groupValues[1]
                }
            } else if (varMatch != null) {
                bridgeUrl = varMatch.groupValues[1]
            }

            val targetRefererJson = "{\"User-Agent\":\"$UA\",\"Referer\":\"$targetVcUrl\"}"

            // Direct Cloudflare worker in initial page
            if (bridgeUrl.isNotEmpty() && bridgeUrl.contains(".workers.dev")) {
                val min = (System.currentTimeMillis() / 60000) % 60
                val wUrl = "$bridgeUrl?s=${1 + min}"
                streams["[Worker] $cardHeader$size ($detectedQuality)"] = "$wUrl######$targetRefererJson"
                bridgeUrl = ""
            }

            // Direct buttons on the first page
            val firstPageButtons = doc.select("a.btn, a")
            for (btn in firstPageButtons) {
                val href = btn.attr("href") ?: continue
                val text = btn.text().trim()
                val lowerText = text.lowercase()

                if (href.isBlank() || href == "#" || href.lowercase().contains(".zip")) continue
                if (lowerText.contains("10gbps") || lowerText.contains("gdflix") || lowerText.contains("dropgalaxy") || lowerText.contains("telegram")) continue

                if (lowerText.contains("fslv2")) {
                    streams["[FSLv2 (Fast)] $cardHeader$size ($detectedQuality)"] = "$href######$targetRefererJson"
                } else if (lowerText.contains("fsl")) {
                    val min = (System.currentTimeMillis() / 60000) % 60
                    val syncedFsl = if (href.contains("?")) "$href&s=${1 + min}" else "$href?s=${1 + min}"
                    streams["[FSL] $cardHeader$size ($detectedQuality)"] = "$syncedFsl######$targetRefererJson"
                } else if (lowerText.contains("worker")) {
                    val min = (System.currentTimeMillis() / 60000) % 60
                    val syncedW = if (href.contains("?")) "$href&s=${1 + min}" else "$href?s=${1 + min}"
                    streams["[Worker] $cardHeader$size ($detectedQuality)"] = "$syncedW######$targetRefererJson"
                }
            }

            if (streams.isNotEmpty()) return

            // If no stream found on first page, resolve bridgeUrl
            if (bridgeUrl.isEmpty()) {
                var downloadHref = doc.selectFirst("#download")?.attr("href")
                if (downloadHref.isNullOrBlank()) {
                    downloadHref = doc.select("a").firstOrNull { el ->
                        val h = el.attr("href") ?: ""
                        h.contains("hubcloud.php") || h.contains("token") || h.contains("dl")
                    }?.attr("href")
                }
                if (!downloadHref.isNullOrBlank()) {
                    bridgeUrl = if (downloadHref.startsWith("http")) {
                        downloadHref
                    } else {
                        getBaseUrl(targetVcUrl) + "/" + downloadHref.removePrefix("/")
                    }
                }
            }

            if (bridgeUrl.isEmpty()) {
                val altVc = doc.select("a[href*=vcloud.zip]").firstOrNull { el ->
                    val h = el.attr("href") ?: ""
                    !h.contains("/api/") && h != targetVcUrl
                }?.attr("href")
                if (!altVc.isNullOrBlank()) {
                    processVCloudUrl(altVc, referer, streams, headersJson)
                    return
                }
            }

            if (bridgeUrl.isEmpty()) return

            if (!bridgeUrl.contains("://")) {
                bridgeUrl = getBaseUrl(targetVcUrl) + if (bridgeUrl.startsWith("/")) bridgeUrl else "/$bridgeUrl"
            }

            val bridgeHtml = fetchText(bridgeUrl, targetVcUrl)
            if (bridgeHtml.isBlank()) return
            val bridgeDoc = Jsoup.parse(bridgeHtml)
            val bridgeHeader = bridgeDoc.selectFirst("div.card-header")?.text() ?: cardHeader
            val bridgeQuality = getIndexQuality(bridgeHeader).ifEmpty { detectedQuality }
            val bridgeSize = bridgeDoc.select("i#size").text().let { if (it.isNotBlank()) " [$it]" else "" }
            val bridgeRefererJson = "{\"User-Agent\":\"$UA\",\"Referer\":\"$bridgeUrl\"}"

            val bridgeVarMatch = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""").find(bridgeHtml)
            if (bridgeVarMatch != null && bridgeVarMatch.groupValues[1].contains(".workers.dev")) {
                val min = (System.currentTimeMillis() / 60000) % 60
                val wUrl2 = bridgeVarMatch.groupValues[1] + "?s=${1 + min}"
                streams["[Worker] $bridgeHeader$bridgeSize ($bridgeQuality)"] = "$wUrl2######$bridgeRefererJson"
            }

            val bridgeButtons = bridgeDoc.select("a.btn, a")
            for (btn in bridgeButtons) {
                val href = btn.attr("href") ?: continue
                val text = btn.text().trim()
                val lowerText = text.lowercase()

                if (href.isBlank() || href == "#" || href.lowercase().contains(".zip")) continue
                if (lowerText.contains("10gbps") || lowerText.contains("gdflix") || lowerText.contains("dropgalaxy") || lowerText.contains("telegram")) continue

                if (lowerText.contains("fslv2")) {
                    streams["[FSLv2 (Fast)] $bridgeHeader$bridgeSize ($bridgeQuality)"] = "$href######$bridgeRefererJson"
                } else if (lowerText.contains("fsl")) {
                    val min = (System.currentTimeMillis() / 60000) % 60
                    val syncedFsl = if (href.contains("?")) "$href&s=${1 + min}" else "$href?s=${1 + min}"
                    streams["[FSL] $bridgeHeader$bridgeSize ($bridgeQuality)"] = "$syncedFsl######$bridgeRefererJson"
                } else if (lowerText.contains("download file") || lowerText.contains("mega server")) {
                    streams["[Direct Server] $bridgeHeader$bridgeSize ($bridgeQuality)"] = "$href######$bridgeRefererJson"
                } else if (lowerText.contains("pixeldrain") || href.contains("pixeldra")) {
                    val pxlVal = extractPxlUrl(bridgeHtml) ?: href
                    val finalPxl = if (pxlVal.contains("download", true)) pxlVal else "${getBaseUrl(pxlVal)}/api/file/${pxlVal.substringAfterLast("/")}?download"
                    streams["[Pixeldrain] $bridgeHeader$bridgeSize ($bridgeQuality)"] = "$finalPxl######$bridgeRefererJson"
                }
            }

            if (streams.isEmpty()) {
                val fslHref = bridgeDoc.selectFirst("#fsl")?.attr("href")
                if (!fslHref.isNullOrBlank()) {
                    val min = (System.currentTimeMillis() / 60000) % 60
                    val syncedFsl2 = if (fslHref.contains("?")) "$fslHref&s=${1 + min}" else "$fslHref?s=${1 + min}"
                    streams["[FSL] $bridgeHeader$bridgeSize ($bridgeQuality)"] = "$syncedFsl2######$bridgeRefererJson"
                }
            }
        } catch (e: Exception) {
            Log.e("VegaMoviesSource", "Error processing VCloud URL $inputUrl: ${e.message}")
        }
    }

    private fun extractDoubleAtob(scriptContent: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        val match = regex.find(scriptContent)?.groupValues?.get(1) ?: return null
        return try {
            val firstDecode = String(android.util.Base64.decode(match, android.util.Base64.DEFAULT))
            String(android.util.Base64.decode(firstDecode, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun resolveFinalUrl(startUrl: String): String? {
        var currentUrl = startUrl
        var loopCount = 0
        val maxRedirects = 7

        while (loopCount < maxRedirects) {
            try {
                val req = Request.Builder().url(currentUrl)
                    .addHeader("User-Agent", UA)
                    .head()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 200 || resp.code in 300..399) {
                        val location = resp.header("Location")
                        if (location.isNullOrEmpty()) return currentUrl
                        currentUrl = location
                    } else {
                        return null
                    }
                }
                loopCount++
            } catch (e: Exception) {
                return null
            }
        }
        return currentUrl
    }

    private fun getCuratedVegaCategoryItems(catId: String): List<StreamingItem> {
        return when (catId) {
            "latest" -> listOf(
                StreamingItem(id = "vega-pushpa-2", title = "Pushpa 2: The Rule", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BMmU1N2IxNTItNWU5EG00NDhhLWE1ODEtMmI4MGFlZjA5Mzg4XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "4K", sourceName = name),
                StreamingItem(id = "vega-kalki-2898", title = "Kalki 2898 AD", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BNDM4NTk1ZDUtZWJhYi00OWFiLTk2ZDUtYTlhNDRjOWE5MmQ1XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "4K", sourceName = name),
                StreamingItem(id = "vega-stree-2", title = "Stree 2", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BMDAxY2M0YmItYmJmYi00YjI2LTk3MDUtZTM2MmI0YWEwZTFiXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name)
            )
            "netflix" -> listOf(
                StreamingItem(id = "vega-stranger-things", title = "Stranger Things", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BMDZkYmVhNjMtNWU4MC00MDQxLWE3MjYtZGMzN2ZhETHkXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name),
                StreamingItem(id = "vega-squid-game", title = "Squid Game S2", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BYzA2Nzk5M2EtNWY4Ny00ZjA0LWEyY2UtZTJiN2I3Y2NhMzg4XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "4K", sourceName = name)
            )
            "hotstar" -> listOf(
                StreamingItem(id = "vega-criminal-justice", title = "Criminal Justice", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BNTI2YTY0NTctOTBmNC00NDlhLTg2NzUtMmE3M2YwZjFiYzg4XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name)
            )
            "prime" -> listOf(
                StreamingItem(id = "vega-mirzapur-3", title = "Mirzapur S3", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BN2E2OTI1NWItZDNkYi00ODY1LWI1ZjItZDA3OWVlOWM2NGUzXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name),
                StreamingItem(id = "vega-panchayat-3", title = "Panchayat S3", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BOTEyNzg5NjYtYjA0YS00Y2E1LTlhY2MtOWE5YjgyNDg5ZjA2XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name)
            )
            "mx" -> listOf(
                StreamingItem(id = "vega-ashram", title = "Aashram", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BMjYyNTQyMDEtOThiMS00ZGI2LWFkZjMtYTI5NmQxZDAyMDliXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name)
            )
            "anime" -> listOf(
                StreamingItem(id = "vega-solo-leveling", title = "Solo Leveling", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BODlhNmFiMGEtMDNhNi00NmI4LThlZGMtMmJiZmI0OTI4YzA3XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name)
            )
            "korean" -> listOf(
                StreamingItem(id = "vega-all-of-us-are-dead", title = "All of Us Are Dead", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BODNmNzU4NWQtNTI3NC00YjhjLTlhYTUtYTFmYTlhYWFlZTQ1XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name)
            )
            "bollywood" -> listOf(
                StreamingItem(id = "vega-jawan", title = "Jawan", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BOWI5NmU3NTUtOTlhNi00M2VlLThlMGItZDliODE1ZGFhZDgxXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name),
                StreamingItem(id = "vega-animal", title = "Animal", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BNGViM2M4NmUtMmE3ZC00M2ViLTkzYjAtYTEzMDU2NDZhNTM2XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "1080p", sourceName = name)
            )
            "dual-audio" -> listOf(
                StreamingItem(id = "vega-deadpool-wolverine", title = "Deadpool & Wolverine (Dual Audio)", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BNzRiMjg0MzUtNTNhRS00M2IzLTg3MDMtNWQ4gwMzA3NjRkXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "4K", sourceName = name),
                StreamingItem(id = "vega-dune-2", title = "Dune: Part Two (Hindi)", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BN2QyZGU4ZDctOWMzMy00NTc5LThlOGQtODhmNDI1NmY5YzAwXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "4K", sourceName = name)
            )
            "4k" -> listOf(
                StreamingItem(id = "vega-interstellar-4k", title = "Interstellar 4K UHD", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BYzdjMDAxZGItMjI2My00ODA0LTlkNzItOWFjMDU5ZDJlYWY3XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", quality = "4K", sourceName = name)
            )
            else -> emptyList()
        }
    }

    private fun getAllCuratedVegaItems(): List<StreamingItem> {
        return categories.map { it.first }
            .flatMap { getCuratedVegaCategoryItems(it) }
            .distinctBy { it.id }
    }
}
