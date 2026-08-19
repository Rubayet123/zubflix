package com.example.zubflix.sources

import android.content.Context
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.example.zubflix.utils.TmdbHelper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class DhakaFlixSource(private val context: Context? = null) : StreamingSource {
    override val name: String = "DhakaFlix 🇧🇩"
    private val mainUrl = "http://172.16.50.14"
    private val serverName = "DHAKA-FLIX-14"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val currentYear = 2025
    private val semaphore = Semaphore(3)

    private val categoriesMap = mapOf(
        // Server 14 - Movies & Series
        "http://172.16.50.14/DHAKA-FLIX-14/English%20Movies%20%281080p%29/%28$currentYear%29%201080p/" to "English Movies ($currentYear)",
        "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/%28$currentYear%29/" to "Hindi Movies ($currentYear)",
        "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/%28$currentYear%29/" to "Hindi Dubbed South ($currentYear)",
        "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/South%20Movies/$currentYear/" to "South Movies ($currentYear)",
        "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies%20%281080p%29/" to "Animation Movies (1080p)",
        "http://172.16.50.14/DHAKA-FLIX-14/IMDb%20Top-250%20Movies/" to "IMDb Top-250 Movies",
        "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/" to "Korean TV & WEB Series",

        // Server 12 - Alphabetical TV/WEB Series
        "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%98%85%20%200%20%20%E2%80%94%20%209/" to "TV Series (0 - 9)",
        "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20L/" to "TV Series (A - L)",
        "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A6%20%20M%20%20%E2%80%94%20%20R/" to "TV Series (M - R)",
        "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/TV%20Series%20%E2%99%A6%20%20S%20%20%E2%80%94%20%20Z/" to "TV Series (S - Z)",

        // Server 9 - Anime & Cartoon
        "http://172.16.50.9/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/" to "Anime & Cartoon Series"
    )

    private val fullRowCategories = listOf(
        "http://172.16.50.14/DHAKA-FLIX-14/English%20Movies%20%281080p%29/%28$currentYear%29%201080p/",
        "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/%28$currentYear%29/",
        "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies%20%281080p%29/",
        "http://172.16.50.9/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/"
    )

    private fun getBaseFromUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            "http://172.16.50.14"
        }
    }

    private fun buildUrl(path: String, baseUrlOverride: String? = null, isEncoded: Boolean = false): String {
        if (path.startsWith("http")) return path
        val parent = baseUrlOverride ?: mainUrl
        return try {
            val baseUri = java.net.URI(parent.trimEnd('/') + "/")
            val resolvedUri = baseUri.resolve(path.removePrefix("/"))
            resolvedUri.toString()
        } catch (e: Exception) {
            val host = baseUrlOverride ?: mainUrl
            host.trimEnd('/') + "/" + path.removePrefix("/")
        }
    }

    private fun decodeSafe(text: String): String {
        return try {
            URLDecoder.decode(text, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            text
        }
    }

    private fun nameFromUrl(href: String): String {
        val decoded = decodeSafe(href)
        return decoded.trimEnd('/').substringAfterLast('/')
    }

    private fun cleanTitle(rawName: String): String {
        val decoded = decodeSafe(rawName).trimEnd('/')
        val lastSegment = decoded.substringAfterLast('/')

        return lastSegment
            // 1. Remove Prefixes like "001. ", "01. ", "1. ", "001 - "
            .replace(Regex("""^\d{1,3}[\.\-]\s*"""), "")
            // 2. Remove parenthetical blocks containing year or TV Series info
            .replace(Regex("""\s*[\(\[][^)]*?\d{4}.*?[\)\]]"""), "")
            .replace(Regex("""\s*[\(\[][^)]*?(1080p|720p|480p|WEBRip|WEB-DL|BluRay|HDTV).*?[\)\]]"""), "")
            // 3. Strip quality tags and resolution specifiers
            .replace(Regex("""(?i)\b(1080p|720p|480p|2160p|4k|hdr|web-?dl|webrip|hdrip|bluray|brrip|dvdrip|x264|x265|hevc|aac|dual\s*audio|esub|msub|hindi\s*dubbed|tv\s*series)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractYear(rawName: String): String? {
        val decoded = decodeSafe(rawName)
        val match = Regex("""\b(19\d\d|20\d\d)\b""").find(decoded)
        return match?.value
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val categories = categoriesMap.map { (path, title) ->
            async {
                val isAllItemsCategory = fullRowCategories.contains(path)
                val items = if (isAllItemsCategory) getCategoryContent(path, -1).reversed() else getCategoryContent(path, 1)
                StreamingCategory(path, title, items, hideViewMore = isAllItemsCategory)
            }
        }.awaitAll().toMutableList()

        categories.filter { it.items.isNotEmpty() }
    }

    override fun invalidateCache() {
        // Clear caches if applicable
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        val serverEndpoints = listOf(
            "http://172.16.50.14/DHAKA-FLIX-14/",
            "http://172.16.50.12/DHAKA-FLIX-12/",
            "http://172.16.50.7/DHAKA-FLIX-7/",
            "http://172.16.50.9/DHAKA-FLIX-9/"
        )

        try {
            kotlinx.coroutines.coroutineScope {
                val allRawResults = serverEndpoints.map { endpointUrl ->
                    async {
                        try {
                            val host = getBaseFromUrl(endpointUrl)
                            val server = endpointUrl.split("/").filter { it.startsWith("DHAKA-FLIX-") }.firstOrNull() ?: "DHAKA-FLIX-14"

                            val jsonPayload = """{"action":"get","search":{"href":"/$server/","pattern":"$query","ignorecase":true}}"""
                            val body = jsonPayload.toRequestBody("application/json".toMediaType())
                            val request = Request.Builder()
                                .url(endpointUrl)
                                .post(body)
                                .build()

                            val response = client.newCall(request).execute()
                            val jsonString = response.body?.string() ?: return@async emptyList<Triple<String, String, String>>()
                            val searchResult = gson.fromJson(jsonString, SearchResult::class.java)

                            searchResult.search
                                .filter { item ->
                                    // Ignore root server directories or empty paths
                                    val trimmed = item.href.trim('/')
                                    trimmed.isNotBlank() && trimmed != server && !trimmed.equals("$server/English Movies", ignoreCase = true) && !trimmed.equals("$server/TV-WEB-Series", ignoreCase = true)
                                }
                                .map { item ->
                                    val fullUrl = buildUrl(item.href, baseUrlOverride = host, isEncoded = true)
                                    Triple(fullUrl, item.href, host)
                                }
                        } catch (e: Exception) {
                            emptyList<Triple<String, String, String>>()
                        }
                    }
                }.awaitAll().flatten()

                val items: List<StreamingItem> = allRawResults.take(12).map { (fullUrl, href, itemHost) ->
                    async<StreamingItem?> {
                        semaphore.withPermit {
                            val rawName = nameFromUrl(href)
                            val clean = cleanTitle(rawName)
                            if (clean.isBlank()) return@async null

                            val year = extractYear(rawName)
                            var isSeries = href.contains("Series", ignoreCase = true) || href.contains("TV", ignoreCase = true)
                            var isCategory = href.endsWith("/")

                            // Peeking inside folder for local image & video items check (memory-capped)
                            var localImageUrl: String? = null
                            try {
                                val imgRequest = Request.Builder().url(fullUrl).build()
                                client.newCall(imgRequest).execute().use { imgResponse ->
                                    val responseBody = imgResponse.peekBody(128 * 1024).string()
                                    if (responseBody.isNotBlank()) {
                                        val imgDoc = Jsoup.parse(responseBody)

                                        val allImages = imgDoc.select("td.fb-n > a[href~=(?i)\\.(png|jpe?g)]").map { it.attr("href") }
                                        val posterPath = allImages.find { img ->
                                            val lower = img.lowercase()
                                            lower.contains("poster") || lower.contains("folder") || lower.contains("a11") || lower.contains("a_al_")
                                        } ?: allImages.firstOrNull()

                                        localImageUrl = posterPath?.let { buildUrl(it, baseUrlOverride = itemHost, isEncoded = true) }

                                        val tableItems = imgDoc.select("tbody > tr:gt(1)")
                                        val hasVideo = tableItems.select("td.fb-n > a[href~=(?i)\\.(mkv|mp4|avi)]").isNotEmpty()

                                        if (hasVideo || (isSeries && tableItems.isNotEmpty())) {
                                            isCategory = false
                                        }
                                    }
                                }
                            } catch (e: Throwable) { }

                            // TMDB Details Enrichment
                            val tmdbDetails = if (context != null && clean.isNotBlank()) {
                                try {
                                    TmdbHelper.searchAndFetchDetails(context, clean, year, isSeries)
                                } catch (e: Exception) { null }
                            } else null

                            val displayTitle = clean
                            val posterUrl = tmdbDetails?.posterPath ?: localImageUrl
                            val backdropUrl = tmdbDetails?.backdropPath

                            StreamingItem(
                                id = fullUrl,
                                title = displayTitle,
                                imageUrl = posterUrl,
                                backdropUrl = backdropUrl,
                                description = tmdbDetails?.overview,
                                rating = tmdbDetails?.rating,
                                year = tmdbDetails?.year ?: year,
                                isSeries = isSeries,
                                isCategory = isCategory
                            )
                        }
                    }
                }.awaitAll().filterNotNull()

                items
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(categoryId)
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val doc = Jsoup.parse(response.body?.string() ?: "")

            val allElements = doc.select("tbody > tr:gt(1)")

            val isAllItems = page == -1
            val pageSize = 20
            val startIndex = if (isAllItems) 0 else (page - 1) * pageSize
            val endIndex = if (isAllItems) allElements.size else (startIndex + pageSize).coerceAtMost(allElements.size)

            if (!isAllItems && startIndex >= allElements.size) return@withContext emptyList()

            val pageElements = allElements.subList(startIndex, endIndex)

            kotlinx.coroutines.coroutineScope {
                pageElements.map { row ->
                    async<StreamingItem?> {
                        val folderHtml = row.select("td.fb-n > a").firstOrNull() ?: return@async null
                        semaphore.withPermit {
                            val rawText = decodeSafe(folderHtml.text())
                            if (rawText.equals("Parent directory", ignoreCase = true) || rawText.equals("..", ignoreCase = true)) {
                                return@async null
                            }

                            val clean = cleanTitle(rawText)
                            if (clean.isBlank()) return@async null

                            val year = extractYear(rawText)
                            val href = folderHtml.attr("href")
                            val itemHost = getBaseFromUrl(categoryId)
                            val fullUrl = buildUrl(href, baseUrlOverride = itemHost, isEncoded = true)
                            val isSeries = categoryId.contains("Series", ignoreCase = true) || href.contains("Series", ignoreCase = true)
                            var isCategory = row.select("td.fb-i > img").firstOrNull()?.attr("alt") == "folder"

                            var localImageUrl: String? = null
                            try {
                                val imgRequest = Request.Builder().url(fullUrl).build()
                                client.newCall(imgRequest).execute().use { imgResponse ->
                                    val responseBody = imgResponse.body?.string() ?: ""
                                    val imgDoc = Jsoup.parse(responseBody)

                                    val allImages = imgDoc.select("td.fb-n > a[href~=(?i)\\.(png|jpe?g)]").map { it.attr("href") }
                                    val posterPath = allImages.find { img ->
                                        val lower = img.lowercase()
                                        lower.contains("poster") || lower.contains("folder") || lower.contains("a11") || lower.contains("a_al_")
                                    } ?: allImages.firstOrNull()

                                    localImageUrl = posterPath?.let { buildUrl(it, baseUrlOverride = itemHost, isEncoded = true) }

                                    val tableItems = imgDoc.select("tbody > tr:gt(1)")
                                    val hasVideo = tableItems.select("td.fb-n > a[href~=(?i)\\.(mkv|mp4|avi)]").isNotEmpty()
                                    if (hasVideo || (isSeries && tableItems.isNotEmpty())) {
                                        isCategory = false
                                    }
                                }
                            } catch (e: Exception) { }

                            val tmdbDetails = if (context != null && clean.isNotBlank()) {
                                try {
                                    TmdbHelper.searchAndFetchDetails(context, clean, year, isSeries)
                                } catch (e: Exception) { null }
                            } else null

                            val displayTitle = clean
                            val posterUrl = tmdbDetails?.posterPath ?: localImageUrl
                            val backdropUrl = tmdbDetails?.backdropPath

                            StreamingItem(
                                id = fullUrl,
                                title = displayTitle,
                                imageUrl = posterUrl,
                                backdropUrl = backdropUrl,
                                description = tmdbDetails?.overview,
                                rating = tmdbDetails?.rating,
                                year = tmdbDetails?.year ?: year,
                                isSeries = isSeries,
                                isCategory = isCategory
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(id).build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null
            response.close()
            val doc = Jsoup.parse(responseBody)

            val currentHost = getBaseFromUrl(id)
            val allImages = doc.select("td.fb-n > a[href~=(?i)\\.(png|jpe?g)]").map { it.attr("href") }
            val posterPath = allImages.find { img ->
                val lower = img.lowercase()
                lower.contains("poster") || lower.contains("folder") || lower.contains("a11") || lower.contains("a_al_")
            } ?: allImages.firstOrNull()

            val localImageUrl = posterPath?.let { buildUrl(it, baseUrlOverride = currentHost, isEncoded = true) }

            val rawName = nameFromUrl(id)
            val clean = cleanTitle(rawName)
            val year = extractYear(rawName)

            val tableRows = doc.select("tbody > tr:gt(1)")

            val videoFile = tableRows.select("td.fb-n > a[href~=(?i)\\.(mkv|mp4|avi)]").firstOrNull()
            val isSeriesUrl = id.contains("Series", ignoreCase = true) || id.contains("TV", ignoreCase = true)

            val isSeries = isSeriesUrl || (videoFile == null && tableRows.size > 1)

            val tmdbDetails = if (context != null && clean.isNotBlank()) {
                try {
                    TmdbHelper.searchAndFetchDetails(context, clean, year, isSeries)
                } catch (e: Exception) { null }
            } else null

            val displayTitle = clean.ifBlank { rawName }
            val posterUrl = tmdbDetails?.posterPath ?: localImageUrl
            val backdropUrl = tmdbDetails?.backdropPath

            if (isSeries) {
                val seasons = mutableListOf<StreamingSeason>()
                val directEpisodes = mutableListOf<StreamingEpisode>()
                var seasonNum = 0

                tableRows.forEach { row ->
                    val aHtml = row.selectFirst("td.fb-n > a")
                    val rawLink = aHtml?.attr("href") ?: ""
                    val link = buildUrl(rawLink, baseUrlOverride = currentHost, isEncoded = true)
                    val isFolder = row.selectFirst("td.fb-i > img")?.attr("alt") == "folder"

                    if (isFolder) {
                        seasonNum++
                        val seasonEpisodes = extractEpisodesFromFolder(link)
                        if (seasonEpisodes.isNotEmpty()) {
                            seasons.add(StreamingSeason("Season $seasonNum", seasonEpisodes, seasonNum))
                        }
                    } else if (rawLink.contains(Regex("(?i)\\.(mkv|mp4|avi)"))) {
                        val epName = cleanTitle(aHtml?.text() ?: "Episode")
                        directEpisodes.add(StreamingEpisode(epName, link))
                    }
                }

                if (directEpisodes.isNotEmpty() && seasons.isEmpty()) {
                    seasons.add(StreamingSeason("Season 1", directEpisodes, 1))
                }

                StreamingItem(
                    id = id,
                    title = displayTitle,
                    imageUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    description = tmdbDetails?.overview,
                    rating = tmdbDetails?.rating,
                    year = tmdbDetails?.year ?: year,
                    isSeries = true,
                    seasons = seasons
                )
            } else {
                val streamUrl = videoFile?.attr("href")?.let { buildUrl(it, baseUrlOverride = currentHost, isEncoded = true) } ?: id

                StreamingItem(
                    id = id,
                    title = displayTitle,
                    imageUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    description = tmdbDetails?.overview,
                    rating = tmdbDetails?.rating,
                    year = tmdbDetails?.year ?: year,
                    isSeries = false,
                    streamUrl = streamUrl
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun extractEpisodesFromFolder(url: String?): List<StreamingEpisode> {
        if (url == null) return emptyList()
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val doc = Jsoup.parse(response.body?.string() ?: "")

            doc.select("tbody > tr:gt(1) > td.fb-n > a[href~=(?i)\\.(mkv|mp4|avi)]").map {
                val rawName = decodeSafe(it.text())
                val cleanName = cleanTitle(rawName)
                val rawLink = it.attr("href")
                val currentHost = getBaseFromUrl(url)
                val link = buildUrl(rawLink, baseUrlOverride = currentHost, isEncoded = true)
                StreamingEpisode(cleanName.ifBlank { rawName }, link)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class SearchResult(val search: List<SearchItem>)
    data class SearchItem(val href: String, val size: Long?)
}
