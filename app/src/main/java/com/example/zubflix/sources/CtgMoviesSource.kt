package com.example.zubflix.sources

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CtgMoviesSource : StreamingSource {
    override val name: String = "CTGMovies"

    companion object {
        private const val PRIMARY_API_BASE = "https://cockpit.103.109.92.178.nip.io/api/v1"
        private const val FALLBACK_API_BASE = "https://ctgmovies.com/api/v1"
        private const val MAIN_URL = "https://ctgmovies.com"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/html, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$MAIN_URL/",
        "Origin" to MAIN_URL
    )

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val categoriesDef = listOf(
            "/movies" to "Latest Movies",
            "/movies?language=english" to "English Movies",
            "/movies?language=hindi" to "Hindi Movies",
            "/movies?collection=6a0c75de4d24c52d35da61fa" to "South Indian Movies",
            "/movies?collection=6a0c75de4d24c52d35da61fc" to "Asian Movies",
            "/movies?collection=6a0cf22a21249bf80a046500" to "European Movies",
            "/tv" to "TV Shows",
            "/anime" to "Anime Series"
        )

        val tasks = categoriesDef.map { (endpoint, catTitle) ->
            async {
                try {
                    val items = fetchCategoryItems(endpoint, 1)
                    if (items.isNotEmpty()) {
                        StreamingCategory(id = endpoint, title = catTitle, items = items)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
        }

        tasks.awaitAll().filterNotNull()
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        fetchCategoryItems(categoryId, page)
    }

    private suspend fun fetchCategoryItems(endpointWithQuery: String, page: Int): List<StreamingItem> {
        val pageQuery = if (page > 1) mapOf("page" to page.toString()) else emptyMap()
        val pathAndQuery = splitEndpoint(endpointWithQuery)
        val combinedQuery = pathAndQuery.second + pageQuery

        val jsonStr = apiGet(pathAndQuery.first, combinedQuery)
        if (!jsonStr.isNullOrBlank()) {
            val items = parseJsonItems(jsonStr, pathAndQuery.first)
            if (items.isNotEmpty()) return items
        }

        // Fallback to HTML scraping
        return fetchCategoryHtml(endpointWithQuery, page)
    }

    private suspend fun fetchCategoryHtml(endpointWithQuery: String, page: Int): List<StreamingItem> {
        val fullUrl = if (page <= 1) {
            "$MAIN_URL$endpointWithQuery"
        } else {
            val sep = if (endpointWithQuery.contains("?")) "&" else "?"
            "$MAIN_URL$endpointWithQuery${sep}page=$page"
        }

        return try {
            val html = executeGetHtml(fullUrl)
            val doc = Jsoup.parse(html, fullUrl)
            parseHtmlMovieCards(doc)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val queryMap = mapOf("search" to query.trim())
        val tasks = listOf(
            async { apiGet("/movies", queryMap)?.let { parseJsonItems(it, "movies") } ?: emptyList() },
            async { apiGet("/tv", queryMap)?.let { parseJsonItems(it, "tv") } ?: emptyList() },
            async { apiGet("/anime", queryMap)?.let { parseJsonItems(it, "anime") } ?: emptyList() }
        )

        val combined = tasks.awaitAll().flatten().distinctBy { it.id }
        if (combined.isNotEmpty()) return@withContext combined

        // Fallback search via HTML
        val htmlUrl = "$MAIN_URL/movies?q=${URLEncoder.encode(query.trim(), "UTF-8")}"
        try {
            val html = executeGetHtml(htmlUrl)
            val doc = Jsoup.parse(html, htmlUrl)
            parseHtmlMovieCards(doc)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        val (kind, slug) = parseKindAndSlug(id)
        if (kind.isBlank() || slug.isBlank()) return@withContext null

        val detailJson = apiGet("/$kind/$slug")
        if (!detailJson.isNullOrBlank()) {
            val item = parseDetailJson(detailJson, kind, slug, id)
            if (item != null) return@withContext item
        }

        // Fallback to HTML details
        fetchDetailHtml(kind, slug, id)
    }

    private suspend fun fetchDetailHtml(kind: String, slug: String, originalId: String): StreamingItem? {
        val targetUrl = "$MAIN_URL/$kind/$slug"
        return try {
            val html = executeGetHtml(targetUrl)
            val doc = Jsoup.parse(html, targetUrl)

            val rawTitle = doc.selectFirst("h1, h2, .title, [class*='title']")?.text()?.trim()
                ?: doc.title().substringBefore("•").substringBefore("|").trim()

            val cleanTitle = cleanDisplayTitle(rawTitle)
            val year = yearFromDate(rawTitle)

            val poster = doc.select("img").firstOrNull { el ->
                val src = el.attr("data-src").ifEmpty { el.attr("src") }
                src.contains("tmdb.org", ignoreCase = true) || src.contains("poster", ignoreCase = true) || src.contains("uploads", ignoreCase = true)
            }?.let { resolvePosterUrl(it.attr("data-src").ifEmpty { it.attr("src") }) }

            val plot = doc.selectFirst("p, .description, [class*='plot'], [class*='story']")?.text()?.trim()
            val isSeries = kind == "tv" || kind == "anime" || html.contains("Episode", ignoreCase = true)

            StreamingItem(
                id = originalId,
                title = cleanTitle,
                isSeries = isSeries,
                imageUrl = poster,
                description = plot,
                streamUrl = if (!isSeries) "$kind/$slug" else null,
                year = year?.toString(),
                sourceName = name
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun extractVideoLinksStreaming(
        data: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        onStreamFound: suspend (streams: Map<String, String>) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val parts = data.split("|")
            val pathPart = parts[0]
            val epSpec = parts.getOrNull(1) // e.g. "s1e2" or "ep1"

            val (kind, slug) = parseKindAndSlug(pathPart)
            if (kind.isBlank() || slug.isBlank()) return@withContext

            val detailJson = apiGet("/$kind/$slug")
            val targetLinks = mutableListOf<JSONObject>()

            if (!detailJson.isNullOrBlank()) {
                val detailObj = JSONObject(detailJson)

                if (epSpec != null) {
                    val episodesArr = detailObj.optJSONArray("episodes") ?: JSONArray()
                    val targetSeason = extractSeasonFromSpec(epSpec) ?: 1
                    val targetEpisode = extractEpisodeFromSpec(epSpec) ?: 1

                    for (i in 0 until episodesArr.length()) {
                        val epObj = episodesArr.optJSONObject(i) ?: continue
                        val epSeason = optInt(epObj, "season_number") ?: 1
                        val epNum = optInt(epObj, "episode_number") ?: optInt(epObj, "absolute_number") ?: (i + 1)

                        if (epSeason == targetSeason && epNum == targetEpisode) {
                            val linksArr = epObj.optJSONArray("links") ?: JSONArray()
                            for (j in 0 until linksArr.length()) {
                                linksArr.optJSONObject(j)?.let { targetLinks.add(it) }
                            }
                            break
                        }
                    }
                } else {
                    val linksArr = detailObj.optJSONArray("links") ?: JSONArray()
                    for (j in 0 until linksArr.length()) {
                        linksArr.optJSONObject(j)?.let { targetLinks.add(it) }
                    }
                }
            }

            val totalCount = targetLinks.size.coerceAtLeast(1)
            onProgress(0, totalCount)

            val sortedLinks = targetLinks.sortedByDescending { linkObj ->
                val qStr = (optString(linkObj, "quality") ?: "") + " " + (optString(linkObj, "url") ?: "")
                when {
                    qStr.contains("2160p", ignoreCase = true) || qStr.contains("4k", ignoreCase = true) -> 2160
                    qStr.contains("1080p", ignoreCase = true) || qStr.contains("fhd", ignoreCase = true) -> 1080
                    qStr.contains("720p", ignoreCase = true) || qStr.contains("hd", ignoreCase = true) -> 720
                    qStr.contains("480p", ignoreCase = true) || qStr.contains("sd", ignoreCase = true) -> 480
                    else -> 0
                }
            }

            var doneCount = 0
            val seenUrls = mutableSetOf<String>()

            sortedLinks.forEachIndexed { idx, linkObj ->
                if (!linkObj.optBoolean("broken", false)) {
                    val rawUrl = optString(linkObj, "url")
                        ?: optString(linkObj, "file")
                        ?: optString(linkObj, "src")
                        ?: optString(linkObj, "link")

                    if (!rawUrl.isNullOrBlank()) {
                        val qualityStr = optString(linkObj, "quality") ?: ""
                        val qualityLabel = qualityFromUrl(rawUrl, qualityStr)
                        val lang = (optString(linkObj, "language") ?: "en").lowercase()

                        val langLabel = when {
                            lang.contains("hin") || qualityStr.lowercase().contains("hindi") -> "Hindi 🇮🇳"
                            lang.contains("ben") || lang.contains("bangla") -> "Bangla 🇧🇩"
                            else -> "English 🇺🇸"
                        }

                        val groupSource = optString(linkObj, "group_source")
                            ?: optString(linkObj, "source_display")
                            ?: "Server ${idx + 1}"

                        val cleanSource = cleanSourceName(groupSource)
                        val sizeFromObj = optString(linkObj, "size")
                            ?: optString(linkObj, "file_size")
                            ?: optString(linkObj, "filesize")

                        val itemResults = mutableMapOf<String, String>()
                        resolveCtgLink(rawUrl, qualityLabel, langLabel, cleanSource, sizeFromObj, itemResults, seenUrls)

                        if (itemResults.isNotEmpty()) {
                            onStreamFound(itemResults)
                        }
                    }
                }
                doneCount++
                onProgress(doneCount, totalCount)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()
        extractVideoLinksStreaming(
            data = data,
            onStreamFound = { streams ->
                results.putAll(streams)
            }
        )
        results
    }

    private suspend fun resolveCtgLink(
        rawUrl: String,
        qualityLabel: String,
        langLabel: String,
        cleanSource: String,
        sizeFromObj: String?,
        results: MutableMap<String, String>,
        seenUrls: MutableSet<String>
    ) {
        val resolvedUrl = resolveMediaUrl(rawUrl)
        if (resolvedUrl.isBlank() || !seenUrls.add(resolvedUrl)) return

        if (resolvedUrl.contains("/watch/") || resolvedUrl.contains("/file/") || resolvedUrl.contains("/getLink/") ||
            resolvedUrl.contains("/embed/") || resolvedUrl.contains("ctgmovies.com") || resolvedUrl.contains("nip.io")) {
            try {
                val reqBuilder = Request.Builder().url(resolvedUrl)
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                client.newCall(reqBuilder.build()).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    val html = response.body?.string() ?: ""

                    val doc = Jsoup.parse(html, finalUrl)

                    val videoSrc = doc.selectFirst("video source[src]")?.attr("src")
                        ?: doc.selectFirst("video[src]")?.attr("src")
                        ?: doc.selectFirst("iframe[src]")?.attr("src")

                    val sizeFromDoc = extractSizeFromText(doc.text()) ?: sizeFromObj

                    if (!videoSrc.isNullOrBlank()) {
                        val fullVideoSrc = resolveMediaUrl(videoSrc)
                        if (seenUrls.add(fullVideoSrc)) {
                            val finalSize = sizeFromDoc ?: getUrlContentLength(fullVideoSrc)
                            val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                            val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                            results["[CTGMovies] [$moniker] $cleanSource Stream ($qualityLabel$sizeAttr | $langLabel)"] = fullVideoSrc
                            return
                        }
                    }

                    var count = 1
                    doc.select("a[href]").forEach { a ->
                        val href = a.attr("href").trim()
                        val fullHref = resolveMediaUrl(href)
                        if (fullHref.startsWith("http") && !fullHref.contains("ctgmovies.com/watch") &&
                            !fullHref.contains("telegram") && !fullHref.contains("t.me") &&
                            !fullHref.contains("facebook") && !fullHref.contains("google.com")) {
                            if (seenUrls.add(fullHref)) {
                                val finalSize = extractSizeFromText(a.text()) ?: sizeFromDoc ?: getUrlContentLength(fullHref)
                                val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                                val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                                val label = if (count == 1) cleanSource else "$cleanSource Mirror $count"
                                results["[CTGMovies] [$moniker] $label ($qualityLabel$sizeAttr | $langLabel)"] = fullHref
                                count++
                            }
                        }
                    }

                    if (results.isEmpty() && finalUrl.startsWith("http") && seenUrls.add(finalUrl)) {
                        val finalSize = sizeFromDoc ?: getUrlContentLength(finalUrl)
                        val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                        val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                        results["[CTGMovies] [$moniker] $cleanSource Direct ($qualityLabel$sizeAttr | $langLabel)"] = finalUrl
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val finalSize = sizeFromObj ?: getUrlContentLength(resolvedUrl)
                val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                results["[CTGMovies] [$moniker] $cleanSource Link ($qualityLabel$sizeAttr | $langLabel)"] = resolvedUrl
            }
        } else {
            val finalSize = sizeFromObj ?: getUrlContentLength(resolvedUrl)
            val sizeAttr = if (finalSize != null) " | $finalSize" else ""
            val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
            results["[CTGMovies] [$moniker] $cleanSource Stream ($qualityLabel$sizeAttr | $langLabel)"] = resolvedUrl
        }
    }

    private fun extractSizeFromText(vararg texts: String?): String? {
        val sizeRegex = Regex("""(?i)\b([0-9.]+\s*(?:GB|MB|GiB|MiB))\b""")
        for (text in texts) {
            if (text.isNullOrBlank()) continue
            val match = sizeRegex.find(text)
            if (match != null) {
                return match.groupValues[1].uppercase()
            }
        }
        return null
    }

    private suspend fun getUrlContentLength(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val contentLength = response.header("Content-Length")?.toLongOrNull()
                    if (contentLength != null && contentLength > 100_000) {
                        return@withContext formatBytes(contentLength)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        null
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            else -> String.format(java.util.Locale.US, "%d KB", bytes / 1024)
        }
    }

    private fun parseDetailJson(jsonStr: String, kind: String, slug: String, originalId: String): StreamingItem? {
        return try {
            val obj = JSONObject(jsonStr)
            val rawTitle = optString(obj, "title")
                ?: optString(obj, "name")
                ?: optString(obj, "english_title")
                ?: slug

            val cleanTitle = cleanDisplayTitle(rawTitle)
            val plot = optString(obj, "overview")
                ?: optString(obj, "plot")
                ?: optString(obj, "description")
                ?: optString(obj, "storyline")

            val year = optInt(obj, "year")
                ?: yearFromDate(optString(obj, "release_date"))
                ?: yearFromDate(optString(obj, "first_air_date"))

            val posterRaw = optString(obj, "poster_path")
                ?: optString(obj, "poster")
                ?: optString(obj, "poster_url")
                ?: optString(obj, "image")
                ?: optString(obj, "backdrop_path")

            val poster = resolvePosterUrl(posterRaw)
            val backdropRaw = optString(obj, "backdrop_path") ?: optString(obj, "backdrop")
            val backdrop = resolvePosterUrl(backdropRaw) ?: poster

            val score = optString(obj, "rating")
                ?: optString(obj, "vote_average")
                ?: optString(obj, "imdb_rating")

            val genresList = mutableListOf<String>()
            obj.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val g = arr.opt(i)
                    if (g is JSONObject) {
                        optString(g, "name")?.let { genresList.add(it) }
                    } else if (g is String && g.isNotBlank()) {
                        genresList.add(g)
                    }
                }
            }

            val isSeries = kind == "tv" || kind == "anime" || obj.has("episodes")
            val seasons = mutableListOf<StreamingSeason>()

            if (isSeries) {
                val episodesArr = obj.optJSONArray("episodes") ?: JSONArray()
                val seasonMap = mutableMapOf<Int, MutableList<StreamingEpisode>>()

                for (i in 0 until episodesArr.length()) {
                    val epObj = episodesArr.optJSONObject(i) ?: continue
                    val epSeason = optInt(epObj, "season_number") ?: 1
                    val epNum = optInt(epObj, "episode_number") ?: optInt(epObj, "absolute_number") ?: (i + 1)
                    val epTitle = optString(epObj, "title") ?: optString(epObj, "name") ?: "Episode $epNum"
                    val epPlot = optString(epObj, "overview") ?: optString(epObj, "plot")
                    val epStill = resolvePosterUrl(optString(epObj, "still_path") ?: optString(epObj, "image"))

                    val episode = StreamingEpisode(
                        title = epTitle,
                        streamUrl = "$kind/$slug|s${epSeason}e${epNum}",
                        overview = epPlot ?: "Episode $epNum of $cleanTitle",
                        stillUrl = epStill ?: poster
                    )

                    seasonMap.getOrPut(epSeason) { mutableListOf() }.add(episode)
                }

                if (seasonMap.isEmpty() && episodesArr.length() > 0) {
                    val defaultEps = mutableListOf<StreamingEpisode>()
                    for (i in 0 until episodesArr.length()) {
                        val epNum = i + 1
                        defaultEps.add(
                            StreamingEpisode(
                                title = "Episode $epNum",
                                streamUrl = "$kind/$slug|s1e${epNum}",
                                overview = "Episode $epNum of $cleanTitle",
                                stillUrl = poster
                            )
                        )
                    }
                    seasonMap[1] = defaultEps
                }

                seasonMap.keys.sorted().forEach { sNum ->
                    seasons.add(
                        StreamingSeason(
                            title = "Season $sNum",
                            episodes = seasonMap[sNum] ?: emptyList(),
                            seasonNumber = sNum
                        )
                    )
                }
            }

            StreamingItem(
                id = originalId,
                title = cleanTitle,
                isSeries = isSeries,
                imageUrl = poster,
                description = plot,
                streamUrl = if (!isSeries) "$kind/$slug" else null,
                seasons = if (isSeries) seasons else null,
                year = year?.toString(),
                rating = score,
                genres = genresList.ifEmpty { null },
                sourceName = name
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseJsonItems(jsonStr: String, defaultKind: String): List<StreamingItem> {
        val list = mutableListOf<StreamingItem>()
        try {
            val jsonArray = try {
                JSONArray(jsonStr)
            } catch (e: Exception) {
                val jsonObj = JSONObject(jsonStr)
                jsonObj.optJSONArray("movies")
                    ?: jsonObj.optJSONArray("tv")
                    ?: jsonObj.optJSONArray("anime")
                    ?: jsonObj.optJSONArray("results")
                    ?: jsonObj.optJSONArray("data")
                    ?: JSONArray()
            }

            val cleanKind = defaultKind.trimStart('/').substringBefore("?").ifEmpty { "movies" }

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val rawTitle = optString(obj, "title")
                    ?: optString(obj, "name")
                    ?: optString(obj, "english_title")
                    ?: continue

                val slug = optString(obj, "slug")
                    ?: optString(obj, "id")
                    ?: optString(obj, "_id")
                    ?: continue

                val isAnime = obj.optBoolean("is_anime", false)
                val itemKind = if (cleanKind == "tv") "tv" else if (cleanKind == "anime" || isAnime) "anime" else "movies"
                val isSeries = itemKind == "tv" || itemKind == "anime"

                val posterRaw = optString(obj, "poster_path")
                    ?: optString(obj, "poster")
                    ?: optString(obj, "poster_url")
                    ?: optString(obj, "image")
                    ?: optString(obj, "backdrop_path")

                val poster = resolvePosterUrl(posterRaw)
                val year = optInt(obj, "year")
                    ?: yearFromDate(optString(obj, "release_date"))
                    ?: yearFromDate(optString(obj, "first_air_date"))

                val itemUrl = "$itemKind/$slug"

                list.add(
                    StreamingItem(
                        id = itemUrl,
                        title = cleanDisplayTitle(rawTitle),
                        isSeries = isSeries,
                        imageUrl = poster,
                        year = year?.toString(),
                        sourceName = name
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }

        return list
    }

    private fun parseHtmlMovieCards(doc: org.jsoup.nodes.Document): List<StreamingItem> {
        val results = mutableListOf<StreamingItem>()
        val cardElements = doc.select("a[href*='/movies/'], a[href*='/tv/'], a[href*='/anime/']")
        val seen = mutableSetOf<String>()

        cardElements.forEach { a ->
            var href = a.attr("href").trim()
            if (href.isBlank()) return@forEach
            if (href.startsWith("http")) {
                href = href.substringAfter("ctgmovies.com").ifEmpty { "/" }
            }
            href = href.trimStart('/')
            if (!seen.add(href)) return@forEach

            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            var poster = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src")
            poster = resolvePosterUrl(poster)

            val titleEl = a.parent()?.selectFirst(".title, h2, h3, [class*='title']")
            val rawTitle = titleEl?.text()?.trim()
                ?: a.attr("title").trim().ifEmpty { null }
                ?: a.text().trim().ifEmpty { null }
                ?: return@forEach

            if (rawTitle.length < 2 || rawTitle.equals("Watch Now", ignoreCase = true) || rawTitle.equals("Details", ignoreCase = true)) return@forEach

            val cleanTitle = cleanDisplayTitle(rawTitle)
            val isSeries = href.contains("tv/") || href.contains("anime/")

            results.add(
                StreamingItem(
                    id = href,
                    title = cleanTitle,
                    isSeries = isSeries,
                    imageUrl = poster,
                    sourceName = name
                )
            )
        }

        return results
    }

    private suspend fun apiGet(endpoint: String, queryParams: Map<String, String> = emptyMap()): String? {
        val path = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        val qStr = if (queryParams.isNotEmpty()) {
            "?" + queryParams.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
        } else ""

        val primaryUrl = "$PRIMARY_API_BASE$path$qStr"
        val fallbackUrl = "$FALLBACK_API_BASE$path$qStr"

        fun buildReq(url: String): Request {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            return builder.build()
        }

        try {
            client.newCall(buildReq(primaryUrl)).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (!body.isNullOrBlank()) return body
                }
            }
        } catch (e: Exception) {
            // Try fallback
        }

        try {
            client.newCall(buildReq(fallbackUrl)).execute().use { res ->
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

    private suspend fun executeGetHtml(url: String): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string() ?: ""
        }
    }

    private fun splitEndpoint(endpointWithQuery: String): Pair<String, Map<String, String>> {
        val parts = endpointWithQuery.split("?")
        val path = parts[0]
        if (parts.size <= 1) return Pair(path, emptyMap())

        val queryMap = mutableMapOf<String, String>()
        parts[1].split("&").forEach { pair ->
            val kv = pair.split("=")
            if (kv.isNotEmpty() && kv[0].isNotBlank()) {
                queryMap[kv[0]] = if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
            }
        }
        return Pair(path, queryMap)
    }

    private fun parseKindAndSlug(id: String): Pair<String, String> {
        val clean = id.replace(MAIN_URL, "").trim('/')
        val parts = clean.split("/")
        return if (parts.size >= 2) {
            Pair(parts[parts.size - 2], parts.last().substringBefore("|"))
        } else if (parts.size == 1) {
            Pair("movies", parts[0].substringBefore("|"))
        } else {
            Pair("", "")
        }
    }

    private fun extractSeasonFromSpec(spec: String): Int? {
        val match = Regex("""s(\d+)e(\d+)""", RegexOption.IGNORE_CASE).find(spec)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeFromSpec(spec: String): Int? {
        val match = Regex("""s(\d+)e(\d+)""", RegexOption.IGNORE_CASE).find(spec)
            ?: Regex("""ep(\d+)""", RegexOption.IGNORE_CASE).find(spec)
        return match?.groupValues?.lastOrNull()?.toIntOrNull()
    }

    private fun resolvePosterUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (path.startsWith("//")) return "https:$path"
        if (path.contains(".jpg") || path.contains(".jpeg") || path.contains(".png") || path.contains(".webp")) {
            if (path.startsWith("/")) return "https://image.tmdb.org/t/p/w500$path"
            return "https://image.tmdb.org/t/p/w500/$path"
        }
        return "$MAIN_URL/${path.trimStart('/')}"
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

    private fun qualityFromUrl(url: String, qualityStr: String = ""): String {
        val text = "$url $qualityStr"
        val match = Regex("""(2160p|1440p|1080p|720p|576p|540p|480p|360p|4k|uhd)""", RegexOption.IGNORE_CASE).find(text)
        if (match != null) {
            val q = match.groupValues[1].lowercase()
            if (q == "4k" || q == "uhd") return "2160p"
            return q
        }
        return "1080p"
    }

    private fun cleanDisplayTitle(title: String): String {
        if (title.isBlank()) return ""
        return title
            .replace(Regex("""\b(1080p|720p|480p|2160p|4k|web[- ]?dl|webrip|bluray|hdrip|x264|x265|hevc|10bit|dual[- ]?audio|hindi[- ]?dubbed|dubbed|esub)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
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
