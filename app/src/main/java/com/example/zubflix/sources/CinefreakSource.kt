package com.example.zubflix.sources

import android.util.Base64
import android.util.Log
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class CinefreakSource : StreamingSource {
    override val name: String = "Cinefreak"
    private val mainUrl: String = "https://cinefreak.nl"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private val categories = listOf(
        "" to "Latest Releases",
        "hindi-movies" to "Hindi Movies",
        "hindi-dubbed-movies" to "Hindi Dubbed",
        "english-movies" to "English Movies",
        "dual-audio" to "Dual Audio",
        "web-series" to "Web Series",
        "korean" to "Korean & K-Drama",
        "bangla-movies" to "Bangla Movies",
        "animation" to "Animation",
        "horror" to "Horror"
    )

    private fun fetchHtml(url: String, referer: String = mainUrl): String {
        val reqBuilder = Request.Builder().url(url)
        defaultHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        reqBuilder.addHeader("Referer", referer)

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: "" else ""
            }
        } catch (e: Exception) {
            Log.w("Cinefreak", "Failed to fetch $url: ${e.message}")
            ""
        }
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("<[^>]+>"), "")
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractQuality(text: String): Int? {
        val lower = text.lowercase(Locale.US)
        return when {
            lower.contains("2160p") || lower.contains("4k") -> 2160
            lower.contains("1080p") -> 1080
            lower.contains("720p") -> 720
            lower.contains("480p") -> 480
            lower.contains("360p") -> 360
            else -> null
        }
    }

    private fun parseMovieCards(html: String, sectionName: String): List<StreamingItem> {
        val results = mutableListOf<StreamingItem>()
        val seenUrls = mutableSetOf<String>()

        // Match <a ... class="...movie-card..." ...>
        val cardPattern = Pattern.compile("<a\\b[^>]*class=[\"'][^\"']*movie-card[^\"']*[\"'][^>]*>", Pattern.CASE_INSENSITIVE)
        val matcher = cardPattern.matcher(html)

        while (matcher.find()) {
            val tag = matcher.group()
            val hrefMatch = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag)
            val href = if (hrefMatch.find()) hrefMatch.group(1) else continue

            if (!href.startsWith("http") && !href.startsWith("/")) continue
            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seenUrls.add(fullUrl)) continue

            val ariaMatch = Pattern.compile("aria-label=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag)
            var rawTitle = if (ariaMatch.find()) ariaMatch.group(1) else ""
            rawTitle = cleanText(rawTitle).removeSuffix(" details")

            if (rawTitle.isBlank()) {
                val endIdx = (matcher.start() + 2000).coerceAtMost(html.length)
                val snippet = html.substring(matcher.start(), endIdx)
                val imgAltMatch = Pattern.compile("<img\\b[^>]*alt=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(snippet)
                if (imgAltMatch.find()) {
                    rawTitle = cleanText(imgAltMatch.group(1))
                }
            }

            if (rawTitle.isBlank()) continue

            var posterUrl: String? = null
            val snippetEnd = (matcher.start() + 4000).coerceAtMost(html.length)
            val snippet = html.substring(matcher.start(), snippetEnd)
            val imgSrcMatch = Pattern.compile("<img\\b[^>]*src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(snippet)
            if (imgSrcMatch.find()) {
                val src = imgSrcMatch.group(1)
                posterUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                posterUrl = posterUrl.replace("/w185/", "/w500/")
            }

            val cleanTitle = rawTitle.split("(")[0].split("[").firstOrNull()?.trim() ?: rawTitle
            val isTv = Regex("season|series|episode|full-series-download|web-series|s0", RegexOption.IGNORE_CASE).containsMatchIn("$fullUrl $rawTitle")

            val qualityInt = extractQuality(rawTitle)
            val qualityLabel = when (qualityInt) {
                2160 -> "4K"
                1080 -> "1080p"
                720 -> "720p"
                480 -> "480p"
                else -> null
            }

            val itemId = fullUrl.replace(mainUrl, "").trim('/')

            results.add(
                StreamingItem(
                    id = itemId,
                    title = cleanTitle,
                    isSeries = isTv,
                    imageUrl = posterUrl,
                    quality = qualityLabel,
                    sourceName = name
                )
            )
        }

        return results
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val topCats = categories.take(6)
        val jobs = topCats.map { (path, title) ->
            async {
                val url = if (path.isBlank()) "$mainUrl/" else "$mainUrl/$path/"
                val html = fetchHtml(url)
                val items = parseMovieCards(html, title)
                if (items.isNotEmpty()) {
                    StreamingCategory(id = path.ifEmpty { "latest" }, title = title, items = items)
                } else null
            }
        }

        jobs.mapNotNull { it.await() }
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val cat = categories.find { it.first == categoryId || it.second.equals(categoryId, ignoreCase = true) }
        val path = cat?.first ?: categoryId

        val url = when {
            path.isBlank() || path == "latest" -> if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
            else -> if (page == 1) "$mainUrl/$path/" else "$mainUrl/$path/page/$page/"
        }

        val html = fetchHtml(url)
        parseMovieCards(html, categoryId)
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search-api.php?q=$encoded"

        val jsonStr = fetchHtml(searchUrl)
        if (jsonStr.isBlank()) return@withContext emptyList()

        val results = mutableListOf<StreamingItem>()
        try {
            val root = if (jsonStr.trim().startsWith("[")) JSONArray(jsonStr) else JSONObject(jsonStr).optJSONArray("results") ?: JSONArray()

            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i) ?: continue
                val rawTitle = item.optString("t").ifEmpty { item.optString("title") }
                val href = item.optString("l").ifEmpty { item.optString("href") }
                if (rawTitle.isBlank() || href.isBlank()) continue

                val cleanTitle = cleanText(rawTitle.split("(")[0].split("[").firstOrNull()?.trim() ?: rawTitle)
                val fullUrl = if (href.startsWith("http")) href else if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href/"

                val img = item.optString("i")
                val posterUrl = if (img.isNotBlank()) (if (img.startsWith("http")) img else "$mainUrl$img") else null

                val isTv = Regex("season|series|episode|s0|full-series-download|web-series", RegexOption.IGNORE_CASE).containsMatchIn("$fullUrl $rawTitle")
                val qualityInt = extractQuality(rawTitle)
                val qualityLabel = when (qualityInt) {
                    2160 -> "4K"
                    1080 -> "1080p"
                    720 -> "720p"
                    480 -> "480p"
                    else -> null
                }

                val itemId = fullUrl.replace(mainUrl, "").trim('/')

                results.add(
                    StreamingItem(
                        id = itemId,
                        title = cleanTitle,
                        isSeries = isTv,
                        imageUrl = posterUrl,
                        quality = qualityLabel,
                        sourceName = name
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("Cinefreak", "Search JSON parse error: ${e.message}")
        }

        results
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        val fullUrl = if (id.startsWith("http")) id else "$mainUrl/${id.trim('/')}/"
        val html = fetchHtml(fullUrl)

        if (html.isBlank()) return@withContext null

        val titleMatch = Pattern.compile("class=[\"'][^\"']*page-title[^\"']*[\"'][^>]*>([\\s\\S]*?)</h1", Pattern.CASE_INSENSITIVE).matcher(html)
        val rawTitle = if (titleMatch.find()) cleanText(titleMatch.group(1)) else "Unknown Title"
        val cleanTitle = rawTitle.split("(")[0].split("[").firstOrNull()?.trim() ?: rawTitle

        val yearMatch = Regex("\\((\\d{4})\\)").find(rawTitle)
        val year = yearMatch?.groupValues?.get(1)

        var posterUrl: String? = null
        val imgMatch = Pattern.compile("<img\\b[^>]*class=[\"'][^\"']*wp-post-image[^\"']*[\"'][^>]*src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (imgMatch.find()) {
            val src = imgMatch.group(1)
            posterUrl = if (src.startsWith("http")) src else "$mainUrl$src"
        }

        var overview: String? = null
        val storylineMatch = Pattern.compile("Storyline[\\s\\S]*?<p\\b[^>]*>([\\s\\S]*?)</p>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (storylineMatch.find()) {
            overview = cleanText(storylineMatch.group(1))
        }

        val genres = mutableListOf<String>()
        val genreBlock = Pattern.compile("class=[\"'][^\"']*sgeneros[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (genreBlock.find()) {
            val aMatcher = Pattern.compile("<a\\b[^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE).matcher(genreBlock.group(1))
            while (aMatcher.find()) {
                val g = cleanText(aMatcher.group(1))
                if (g.isNotBlank()) genres.add(g)
            }
        }

        val isTvSeries = html.contains("ep-card") || html.contains("season-number") || html.contains("episode-badge")

        val seasonsList = mutableListOf<StreamingSeason>()

        if (isTvSeries) {
            val epBlocks = html.split(Regex("<div\\b[^>]*class=[\"'][^\"']*ep-card[^\"']*[\"'][^>]*>", RegexOption.IGNORE_CASE))
            if (epBlocks.size > 1) {
                val seasonMap = mutableMapOf<Int, MutableList<StreamingEpisode>>()
                for (i in 1 until epBlocks.size) {
                    val block = epBlocks[i]

                    val seasonMatch = Regex("class=[\"'][^\"']*season-number[^\"']*[\"'][^>]*>\\s*S?0*(\\d+)", RegexOption.IGNORE_CASE).find(block)
                    val sNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    val epMatch = Regex("class=[\"'][^\"']*episode-badge[^\"']*[\"'][^>]*>\\s*Episode\\s*(\\d+)", RegexOption.IGNORE_CASE).find(block)
                    val eNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: i

                    val epTitleMatch = Regex("class=[\"'][^\"']*ep-title[^\"']*[\"'][^>]*>([\\s\\S]*?)</", RegexOption.IGNORE_CASE).find(block)
                    val epName = epTitleMatch?.groupValues?.get(1)?.let { cleanText(it) } ?: "Episode $eNum"

                    val genLinks = extractGenerateLinks(block)
                    val payload = JSONObject().apply {
                        put("url", fullUrl)
                        put("season", sNum)
                        put("episode", eNum)
                        val arr = JSONArray()
                        genLinks.forEach { arr.put(it) }
                        put("links", arr)
                    }.toString()

                    val episode = StreamingEpisode(
                        title = epName,
                        streamUrl = payload,
                        stillUrl = posterUrl,
                        overview = "Season $sNum Episode $eNum of $cleanTitle"
                    )

                    seasonMap.getOrPut(sNum) { mutableListOf() }.add(episode)
                }

                seasonMap.keys.sorted().forEach { sNum ->
                    seasonsList.add(
                        StreamingSeason(
                            title = "Season $sNum",
                            episodes = seasonMap[sNum] ?: emptyList(),
                            seasonNumber = sNum
                        )
                    )
                }
            }
        }

        var singleStreamUrl: String? = null
        if (seasonsList.isEmpty()) {
            val genLinks = extractGenerateLinks(html)
            val payload = JSONObject().apply {
                put("url", fullUrl)
                put("season", 1)
                put("episode", 1)
                val arr = JSONArray()
                genLinks.forEach { arr.put(it) }
                put("links", arr)
            }.toString()
            singleStreamUrl = payload
        }

        StreamingItem(
            id = id.trim('/'),
            title = cleanTitle,
            isSeries = isTvSeries,
            imageUrl = posterUrl,
            backdropUrl = posterUrl,
            description = overview,
            streamUrl = singleStreamUrl,
            seasons = if (seasonsList.isNotEmpty()) seasonsList else null,
            year = year,
            genres = genres.ifEmpty { null },
            sourceName = name
        )
    }

    private fun extractGenerateLinks(htmlBlock: String): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        val aMatcher = Pattern.compile("<a\\b[^>]*href=[\"']([^\"']*(?:generate\\.php|\\bhttps?://)[^\"']*)[\"'][^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE).matcher(htmlBlock)

        while (aMatcher.find()) {
            val href = aMatcher.group(1) ?: continue
            val text = cleanText(aMatcher.group(2) ?: "")
            if (href.contains("generate.php?id=") || (href.contains("http") && !href.contains("cinefreak.nl") && !href.contains("javascript"))) {
                val obj = JSONObject().apply {
                    put("href", href)
                    put("text", text)
                }
                list.add(obj)
            }
        }
        return list
    }

    suspend fun extractVideoLinksStreaming(
        data: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        onStreamFound: suspend (streams: Map<String, String>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val linksToResolve = mutableListOf<String>()
        var refererUrl = mainUrl

        try {
            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                refererUrl = json.optString("url", mainUrl)
                val linksArr = json.optJSONArray("links")
                if (linksArr != null) {
                    for (i in 0 until linksArr.length()) {
                        val item = linksArr.optJSONObject(i) ?: continue
                        val href = item.optString("href")
                        if (href.isNotBlank()) linksToResolve.add(href)
                    }
                }
            } else {
                refererUrl = if (data.startsWith("http")) data else "$mainUrl/${data.trim('/')}/"
                val html = fetchHtml(refererUrl)
                extractGenerateLinks(html).forEach { obj ->
                    val href = obj.optString("href")
                    if (href.isNotBlank()) linksToResolve.add(href)
                }
            }
        } catch (e: Exception) {
            Log.w("Cinefreak", "Error parsing episode payload: ${e.message}")
        }

        if (linksToResolve.isEmpty()) return@withContext

        val distinctLinks = linksToResolve.distinct()
        val total = distinctLinks.size
        onProgress(0, total)

        var done = 0
        val seenStreamUrls = mutableSetOf<String>()

        distinctLinks.forEach { link ->
            try {
                val streams = resolveLinkToStreams(link, refererUrl)
                val foundMap = mutableMapOf<String, String>()
                for (st in streams) {
                    if (seenStreamUrls.add(st.second)) {
                        foundMap[st.first] = st.second
                    }
                }
                if (foundMap.isNotEmpty()) {
                    onStreamFound(foundMap)
                }
            } catch (e: Exception) {
                // Ignore single link error
            }
            done++
            onProgress(done, total)
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()
        extractVideoLinksStreaming(data, onStreamFound = { results.putAll(it) })
        results
    }

    private fun resolveLinkToStreams(link: String, refererUrl: String): List<Pair<String, String>> {
        var targetUrl = link

        if (link.contains("generate.php?id=")) {
            val b64 = link.substringAfter("generate.php?id=").substringBefore("&").substringBefore("\"")
            if (b64.isBlank()) return emptyList()

            val decodedStr = try {
                val padded = b64 + "=".repeat((-b64.length) % 4)
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                return emptyList()
            }

            targetUrl = if (decodedStr.contains("newgo32")) {
                decodedStr.substringBefore("newgo32").trim()
            } else {
                decodedStr.trim()
            }
        }

        if (!targetUrl.startsWith("http")) return emptyList()

        val streams = mutableListOf<Pair<String, String>>()
        val targetHtml = fetchHtml(targetUrl, refererUrl)
        if (targetHtml.isBlank()) return emptyList()

        val domain = try {
            val urlObj = java.net.URL(targetUrl)
            "${urlObj.protocol}://${urlObj.host}"
        } catch (e: Exception) {
            ""
        }

        val headersJson = "{\"User-Agent\":\"Mozilla/5.0 (Windows NT 10.0; Win64; x64)\",\"Referer\":\"$refererUrl\"}"

        // 1. Direct video links in target page
        val directPattern = Pattern.compile("href=[\"']([^\"']*(?:cloudflarestorage|r2\\.dev|diskcdn|fastcdn|awscdn|\\.mkv|\\.mp4)[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
        val mDirect = directPattern.matcher(targetHtml)
        var directIdx = 1
        while (mDirect.find()) {
            val vidUrl = mDirect.group(1) ?: continue
            val q = extractQuality(vidUrl + " " + targetHtml) ?: 720
            val label = when (q) {
                2160 -> "4K"
                1080 -> "1080p"
                720 -> "720p"
                480 -> "480p"
                else -> "HD"
            }
            val streamName = "[Cinefreak] [$label] Direct Stream $directIdx"
            streams.add(streamName to "$vidUrl######$headersJson")
            directIdx++
        }

        // 2. Sub-paths (/d/, /gp/, /x/)
        val subPattern = Pattern.compile("href=[\"'](/d/[^\"']+|/gp/[^\"']+|/x/[^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val mSub = subPattern.matcher(targetHtml)
        val subUrls = mutableListOf<String>()

        while (mSub.find()) {
            val path = mSub.group(1) ?: continue
            if (domain.isNotBlank()) {
                subUrls.add("$domain$path")
            }
        }

        for (sUrl in subUrls.distinct()) {
            val sHtml = fetchHtml(sUrl, targetUrl)
            if (sHtml.isBlank()) continue

            val mSubDirect = directPattern.matcher(sHtml)
            var subDirectIdx = 1
            while (mSubDirect.find()) {
                val vidUrl = mSubDirect.group(1) ?: continue
                val q = extractQuality(vidUrl + " " + sHtml) ?: 720
                val label = when (q) {
                    2160 -> "4K"
                    1080 -> "1080p"
                    720 -> "720p"
                    480 -> "480p"
                    else -> "HD"
                }
                val streamName = "[Cinefreak] [$label] Fast Direct $subDirectIdx"
                streams.add(streamName to "$vidUrl######$headersJson")
                subDirectIdx++
            }

            // Embedded video link in player iframe/param
            val embedMatch = Pattern.compile("id=([^&\"']*(?:r2\\.dev|cloudflarestorage|diskcdn)[^&\"']*)", Pattern.CASE_INSENSITIVE).matcher(sHtml)
            var embedIdx = 1
            while (embedMatch.find()) {
                val rawUrl = embedMatch.group(1) ?: continue
                val decodedVidUrl = try { URLDecoder.decode(rawUrl, "UTF-8") } catch (e: Exception) { rawUrl }
                val q = extractQuality(decodedVidUrl) ?: 720
                val label = when (q) {
                    2160 -> "4K"
                    1080 -> "1080p"
                    720 -> "720p"
                    480 -> "480p"
                    else -> "HD"
                }
                val streamName = "[Cinefreak] [$label] Cloud Server $embedIdx"
                streams.add(streamName to "$decodedVidUrl######$headersJson")
                embedIdx++
            }
        }

        return streams
    }
}
