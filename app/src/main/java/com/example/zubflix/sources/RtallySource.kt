package com.example.zubflix.sources

import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import android.content.Context

class RtallySource(private val context: Context? = null) : StreamingSource {
    override val name: String = "Rtally"
    
    private val mainUrl = "https://www.rtally.shop"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    )

    private suspend fun executeGet(url: String): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string() ?: ""
        }
    }

    private fun parseMovieCards(doc: org.jsoup.nodes.Document, base: String): List<StreamingItem> {
        val results = mutableListOf<StreamingItem>()
        val cards = doc.select("section.md\\:col-span-3 div.grid a[href], div.grid a[href]")
        cards.forEach { card ->
            val href = card.attr("href") ?: return@forEach
            if (!href.contains("/post/")) return@forEach
            
            val absoluteUrl = if (href.startsWith("http")) href else "$base${if (href.startsWith("/")) "" else "/"}$href"
            
            val title = card.select("h4").text().trim()
            if (title.isBlank()) return@forEach
            
            var posterUrl = card.select("img").attr("src")
            if (posterUrl.isNullOrEmpty()) {
                val styleAttr = card.select("div[style*=background-image]").attr("style") ?: ""
                if (styleAttr.contains("url(")) {
                    posterUrl = styleAttr.substringAfter("url(").substringBefore(")").substringBefore("?").replace("'", "").replace("\"", "").trim()
                }
            }
            if (posterUrl.isNotBlank() && !posterUrl.startsWith("http")) {
                posterUrl = "$base${if (posterUrl.startsWith("/")) "" else "/"}$posterUrl"
            }
            
            val type = card.select("h5.border").text().trim()
            val titleLower = title.lowercase(java.util.Locale.ROOT)
            val isSeries = type.contains("series", ignoreCase = true) || 
                           type.contains("show", ignoreCase = true) || 
                           titleLower.contains("season") || 
                           titleLower.contains("episode")
            
            results.add(
                StreamingItem(
                    id = absoluteUrl,
                    title = title,
                    isSeries = isSeries,
                    imageUrl = posterUrl.takeIf { it.isNotBlank() },
                    sourceName = name
                )
            )
        }
        return results.distinctBy { it.id }
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val categoriesToFetch = listOf(
            "/categories/trending" to "Trending",
            "/categories/featured" to "Featured",
            "/categories/hollywood" to "Hollywood",
            "/categories/bengali" to "Bangla",
            "/categories/bollywood" to "Bollywood",
            "/categories/tv-shows" to "Tv Shows",
            "/categories/korean" to "Korean",
            "/categories/anime" to "Anime"
        )
        
        val tasks = categoriesToFetch.map { (path, catTitle) ->
            async {
                try {
                    val url = "$mainUrl$path?page=1"
                    val html = executeGet(url)
                    val doc = Jsoup.parse(html, mainUrl)
                    val items = parseMovieCards(doc, mainUrl)
                    if (items.isNotEmpty()) {
                        StreamingCategory(id = path, title = catTitle, items = items)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
        }
        
        tasks.awaitAll().filterNotNull()
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val url = "$mainUrl$categoryId?page=$page"
            val html = executeGet(url)
            val doc = Jsoup.parse(html, mainUrl)
            parseMovieCards(doc, mainUrl)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val url = "$mainUrl/search/${URLEncoder.encode(query.trim(), "UTF-8")}"
            val html = executeGet(url)
            val doc = Jsoup.parse(html, mainUrl)
            parseMovieCards(doc, mainUrl)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            val html = executeGet(id)
            val doc = Jsoup.parse(html, id)

            var title = doc.select(".font-serif").firstOrNull()?.text()?.trim() ?: ""
            if (title.isEmpty()) {
                title = doc.select("h1, h2, title").firstOrNull()?.text()?.trim() ?: ""
            }
            if (title.isEmpty()) {
                title = "Unknown Title"
            }

            var image = doc.selectFirst("div.w-\\[200px\\] img, .w-\\[200px\\] img, img.object-cover")?.attr("src")
            if (image.isNullOrEmpty()) {
                val styleAttr = doc.select("div[style*=background-image]").first()?.attr("style")
                image = styleAttr?.substringAfter("url(")?.substringBefore(")")?.substringBefore("?")?.replace("'", "")?.replace("\"", "")?.trim()
            }
            if (image != null && image.isNotBlank() && !image.startsWith("http")) {
                image = "$mainUrl${if (image.startsWith("/")) "" else "/"}$image"
            }

            val plot = doc.selectFirst("p.text-sm:nth-child(2), p.text-sm, .text-sm p")?.text()?.trim()

            val infoSpans = doc.select("div.infoDiv span")
            var yearStr: String? = null
            for (i in 0 until infoSpans.size) {
                val text = infoSpans[i].text()
                if (text.contains("year", ignoreCase = true) || text.contains("released", ignoreCase = true)) {
                    yearStr = infoSpans.getOrNull(i + 1)?.text()?.trim()
                    break
                }
            }
            if (yearStr == null) {
                yearStr = doc.select("div.infoDiv:nth-child(7) > span:nth-child(2)").text().trim().takeIf { it.isNotBlank() }
            }

            val episodeElements = doc.select("ul.flex > li")
            val isSeries = episodeElements.isNotEmpty()

            if (isSeries) {
                val scriptHtml = doc.select("script").joinToString { it.html() }.replace("\\", "")
                val linkList = mutableListOf<String>()
                
                doc.select("div.justify-center:nth-child(2) > a").forEach { anchor ->
                    val link = anchor.attr("href") ?: ""
                    when {
                        link.contains("filemoon") -> {
                            extractFileMoonUrls(scriptHtml)?.split(",")?.forEachIndexed { index, epId ->
                                val cleanId = epId.trim()
                                if (cleanId.isNotEmpty()) {
                                    if (index < linkList.size) {
                                        linkList[index] += "https://filemoon.sx/e/$cleanId ; "
                                    } else {
                                        linkList.add("https://filemoon.sx/e/$cleanId ; ")
                                    }
                                }
                            }
                        }
                        link.contains("vidhideplus") -> {
                            extractVidhideplus(scriptHtml)?.split(",")?.forEachIndexed { index, epId ->
                                val cleanId = epId.trim()
                                if (cleanId.isNotEmpty()) {
                                    if (index < linkList.size) {
                                        linkList[index] += "https://vidhideplus.com/v/$cleanId ; "
                                    } else {
                                        linkList.add("https://vidhideplus.com/v/$cleanId ; ")
                                    }
                                }
                            }
                        }
                        link.contains("wish") || link.contains("playerwish") -> {
                            extractStreamwishUrls(scriptHtml)?.split(",")?.forEachIndexed { index, epId ->
                                val cleanId = epId.trim()
                                if (cleanId.isNotEmpty()) {
                                    if (index < linkList.size) {
                                        linkList[index] += "https://playerwish.com/e/$cleanId ; "
                                    } else {
                                        linkList.add("https://playerwish.com/e/$cleanId ; ")
                                    }
                                }
                            }
                        }
                    }
                }

                val episodes = mutableListOf<StreamingEpisode>()
                val totalEpisodes = maxOf(episodeElements.size, linkList.size)
                for (index in 0 until totalEpisodes) {
                    val epTitle = episodeElements.getOrNull(index)?.text()?.trim() ?: "Episode ${index + 1}"
                    val streamData = linkList.getOrNull(index) ?: ""
                    episodes.add(
                        StreamingEpisode(
                            title = epTitle,
                            streamUrl = "$id|ep$index|$streamData",
                            overview = "$epTitle of $title",
                            stillUrl = image
                        )
                    )
                }

                val seasons = listOf(
                    StreamingSeason(
                        title = "Season 1",
                        episodes = episodes,
                        seasonNumber = 1
                    )
                )

                StreamingItem(
                    id = id,
                    title = title,
                    isSeries = true,
                    imageUrl = image,
                    description = plot,
                    seasons = seasons,
                    year = yearStr,
                    sourceName = name
                )
            } else {
                var links = ""
                doc.select("div.justify-center:nth-child(2) > a").forEach { anchor ->
                    val url = anchor.attr("href") ?: ""
                    if (url.isNotEmpty()) {
                        val embedUrl = when {
                            url.contains("filemoon") -> url.replace("/download/", "/e/")
                            url.contains("vidhideplus") -> url.replace("/download/", "/v/")
                            url.contains("vidhidepre") -> url.replace("/d/", "/v/")
                            url.contains("playerwish") -> url.replace("/d/", "/e/")
                            else -> url
                        }
                        links += "$embedUrl ; "
                    }
                }

                StreamingItem(
                    id = id,
                    title = title,
                    isSeries = false,
                    imageUrl = image,
                    description = plot,
                    streamUrl = if (links.isNotEmpty()) "$id|movie|$links" else null,
                    year = yearStr,
                    sourceName = name
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            val rawLinks = parts.lastOrNull() ?: ""
            val linksList = rawLinks.split(" ; ").map { it.trim() }.filter { it.isNotEmpty() }
            
            if (linksList.isEmpty()) {
                onProgress(0, 1)
                onProgress(1, 1)
                return@withContext
            }

            val total = linksList.size
            var done = 0
            onProgress(0, total)

            val resolved = mutableMapOf<String, String>()
            linksList.forEach { link ->
                val label = when {
                    link.contains("filemoon") -> "Filemoon"
                    link.contains("vidhide") -> "Vidhide"
                    link.contains("playerwish") || link.contains("wish") -> "PlayerWish"
                    else -> "Rtally Stream"
                }

                val directUrl = extractDirectVideoUrl(link)
                if (directUrl != null) {
                    val domain = android.net.Uri.parse(link).host ?: ""
                    val playerHeadersMap = mapOf(
                        "Referer" to "https://$domain/",
                        "Origin" to "https://$domain",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    )
                    val headersJson = com.google.gson.Gson().toJson(playerHeadersMap)
                    resolved[label] = "$directUrl######$headersJson"
                } else {
                    resolved[label] = link
                }

                done++
                onProgress(done, total)
            }
            if (resolved.isNotEmpty()) {
                onStreamFound(resolved)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val resolved = mutableMapOf<String, String>()
        try {
            val parts = data.split("|")
            val rawLinks = parts.lastOrNull() ?: ""
            val linksList = rawLinks.split(" ; ").map { it.trim() }.filter { it.isNotEmpty() }
            linksList.forEach { link ->
                val label = when {
                    link.contains("filemoon") -> "Filemoon"
                    link.contains("vidhide") -> "Vidhide"
                    link.contains("playerwish") || link.contains("wish") -> "PlayerWish"
                    else -> "Rtally Stream"
                }

                val directUrl = extractDirectVideoUrl(link)
                if (directUrl != null) {
                    val domain = android.net.Uri.parse(link).host ?: ""
                    val playerHeadersMap = mapOf(
                        "Referer" to "https://$domain/",
                        "Origin" to "https://$domain",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    )
                    val headersJson = com.google.gson.Gson().toJson(playerHeadersMap)
                    resolved[label] = "$directUrl######$headersJson"
                } else {
                    resolved[label] = link
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        resolved
    }

    private suspend fun executeGetWithHeaders(url: String, customHeaders: Map<String, String>): String = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder().url(url)
            customHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) ""
                else response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun extractDirectVideoUrl(embedUrl: String): String? {
        try {
            val domain = android.net.Uri.parse(embedUrl).host ?: ""
            val customHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "Referer" to "https://www.rtally.shop/",
                "Origin" to "https://www.rtally.shop"
            )
            val html = executeGetWithHeaders(embedUrl, customHeaders)
            if (html.isEmpty()) return null

            val m3u8Regex = Regex("""(https?://[^\s'"]+\.m3u8[^\s'"]*)""")
            val firstMatch = m3u8Regex.find(html)?.value
            if (firstMatch != null) return firstMatch

            val packedPattern = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[rd]\s*\)\s*\{\s*(.*?)\s*\}\s*\(\s*(.*?)\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val packedMatches = packedPattern.findAll(html)
            for (match in packedMatches) {
                val unpacked = unpack(match.value)
                if (unpacked != null) {
                    val matchUrl = m3u8Regex.find(unpacked)?.value
                    if (matchUrl != null) {
                        return matchUrl
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun unpack(packedCode: String): String? {
        try {
            val openParen = packedCode.lastIndexOf('}')
            if (openParen == -1) return null
            val argsStr = packedCode.substring(openParen + 1).trim()
            if (!argsStr.startsWith("(") || !argsStr.endsWith(")")) return null

            val innerArgs = argsStr.substring(1, argsStr.length - 1).trim()
            val parsed = parsePackerArgs(innerArgs) ?: return null

            val p = unescapeString(parsed.p)
            val radix = parsed.a
            val c = parsed.c
            val k = parsed.k

            val wordRegex = Regex("""\b\w+\b""")
            val unpacked = p.replace(wordRegex) { matchResult ->
                val word = matchResult.value
                val index = base62ToInt(word, radix)
                if (index >= 0 && index < k.size && k[index].isNotEmpty()) {
                    k[index]
                } else {
                    word
                }
            }
            return unpacked
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private class PackerArgs(val p: String, val a: Int, val c: Int, val k: List<String>)

    private fun parsePackerArgs(argsStr: String): PackerArgs? {
        try {
            val args = mutableListOf<String>()
            var current = java.lang.StringBuilder()
            var inQuote = false
            var quoteChar = ' '
            var escaped = false
            var i = 0
            while (i < argsStr.length) {
                val c = argsStr[i]
                if (escaped) {
                    current.append(c)
                    escaped = false
                } else if (c == '\\') {
                    current.append(c)
                    escaped = true
                } else if (inQuote) {
                    if (c == quoteChar) {
                        inQuote = false
                    } else {
                        current.append(c)
                    }
                } else {
                    if (c == '\'' || c == '"') {
                        inQuote = true
                        quoteChar = c
                    } else if (c == ',') {
                        args.add(current.toString().trim())
                        current = java.lang.StringBuilder()
                    } else {
                        current.append(c)
                    }
                }
                i++
            }
            args.add(current.toString().trim())

            if (args.size < 4) return null

            val p = args[0]
            val a = args[1].toIntOrNull() ?: return null
            val c = args[2].toIntOrNull() ?: return null

            var kStr = args[3]
            if (kStr.endsWith(".split('|')") || kStr.endsWith(".split(\"|\")")) {
                kStr = kStr.substringBefore(".split")
            }
            if ((kStr.startsWith("'") && kStr.endsWith("'")) || (kStr.startsWith("\"") && kStr.endsWith("\""))) {
                kStr = kStr.substring(1, kStr.length - 1)
            }
            val k = kStr.split("|")
            return PackerArgs(p, a, c, k)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun base62ToInt(s: String, radix: Int): Int {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var result = 0
        for (char in s) {
            val index = chars.indexOf(char)
            if (index == -1 || index >= radix) return -1
            result = result * radix + index
        }
        return result
    }

    private fun unescapeString(s: String): String {
        val sb = java.lang.StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private val fileMoonRegex = Regex("\"multiLinksDl\":\\s*\"([^\"]+)\"")
    private fun extractFileMoonUrls(text: String): String? {
        val fileMoonMatch = fileMoonRegex.find(text)
        return fileMoonMatch?.groupValues?.getOrNull(1)
    }

    private val streamwishMultiUrlRegex = Regex("\"streamwishMultiUrl\":\\s*\"([^\"]+)\"")
    private fun extractStreamwishUrls(text: String): String? {
        val streamwishMultiUrlMatch = streamwishMultiUrlRegex.find(text)
        return streamwishMultiUrlMatch?.groupValues?.getOrNull(1)
    }

    private val vidhideplusRegex = Regex("\"multiLinksSl\":\\s*\"([^\"]+)\"")
    private fun extractVidhideplus(text: String): String? {
        val vidhideplusMatch = vidhideplusRegex.find(text)
        return vidhideplusMatch?.groupValues?.getOrNull(1)
    }
}
