package com.example.zubflix.sources

import android.content.Context
import android.util.Log
import com.example.zubflix.SourceManager
import com.example.zubflix.cloudstream.CloudStreamDexLoader
import com.example.zubflix.cloudstream.InstalledCloudStreamPlugin
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.example.zubflix.utils.TmdbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SingleCloudStreamPluginSource(
    val plugin: InstalledCloudStreamPlugin,
    private val context: Context
) : StreamingSource {

    override val name: String = "CS: ${plugin.name}"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val tmdbApi = "https://api.themoviedb.org/3"

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val categories = mutableListOf<StreamingCategory>()

        try {
            Log.d("CSPluginSource", "[GET_HOME] Starting home categories fetch for '${plugin.name}' (ID: ${plugin.id})")
            // Tier 1: Reflective direct execution of plugin's getMainPage()
            val reflectiveCategories = CloudStreamDexLoader.invokeReflectiveMainPage(context, plugin)
            if (!reflectiveCategories.isNullOrEmpty()) {
                Log.d("CSPluginSource", "[GET_HOME] Tier 1 Success: Fetched ${reflectiveCategories.size} categories reflectively for ${plugin.name}")
                return@withContext reflectiveCategories
            } else {
                Log.w("CSPluginSource", "[GET_HOME] Tier 1 Reflective execution returned no categories for ${plugin.name}. Falling back to Tier 2 HTML Scraper...")
            }

            // Tier 2: Multi-domain JSoup web scraper fallback
            val (detectedUrl, mainPageCategories) = CloudStreamDexLoader.getPluginMainPageInfo(context, plugin)
            val candidateDomains = mutableListOf<String>()
            if (detectedUrl.isNotBlank()) candidateDomains.add(detectedUrl)
            candidateDomains.addAll(CloudStreamDexLoader.getCandidateDomains(plugin.name.lowercase()))

            Log.d("CSPluginSource", "[GET_HOME] Tier 2 Scraper candidate domains for ${plugin.name}: ${candidateDomains.distinct()}")

            val categoriesToScrape = if (mainPageCategories.isNotEmpty()) {
                mainPageCategories
            } else {
                val lowerName = plugin.name.lowercase()
                if (lowerName.contains("anime")) {
                    listOf(
                        CloudStreamDexLoader.PluginMainPageCategory("Latest Anime", "/"),
                        CloudStreamDexLoader.PluginMainPageCategory("Popular Anime", "popular/"),
                        CloudStreamDexLoader.PluginMainPageCategory("Anime Movies", "movies/")
                    )
                } else if (lowerName.contains("drama") || lowerName.contains("asian")) {
                    listOf(
                        CloudStreamDexLoader.PluginMainPageCategory("Latest Releases", "/"),
                        CloudStreamDexLoader.PluginMainPageCategory("Korean Dramas", "korean-drama/"),
                        CloudStreamDexLoader.PluginMainPageCategory("Asian Movies", "movies/")
                    )
                } else {
                    listOf(
                        CloudStreamDexLoader.PluginMainPageCategory("Home", "/"),
                        CloudStreamDexLoader.PluginMainPageCategory("Latest Web Series", "web-series/"),
                        CloudStreamDexLoader.PluginMainPageCategory("Latest Movies", "movies/"),
                        CloudStreamDexLoader.PluginMainPageCategory("Anime", "genre/anime/")
                    )
                }
            }

            for (domain in candidateDomains.distinct()) {
                val cleanDomain = domain.trimEnd('/')
                Log.d("CSPluginSource", "[GET_HOME] Attempting domain scraping: $cleanDomain")
                val tasks = categoriesToScrape.map { cat ->
                    async {
                        val catUrl = when {
                            cat.pathOrUrl.startsWith("http") -> cat.pathOrUrl
                            cat.pathOrUrl.isBlank() || cat.pathOrUrl == "/" -> "$cleanDomain/"
                            cat.pathOrUrl.startsWith("/") -> "$cleanDomain${cat.pathOrUrl}"
                            else -> "$cleanDomain/${cat.pathOrUrl}"
                        }

                        val html = fetchHtml(catUrl, cleanDomain)
                        val items = parseItemsFromHtml(html, cleanDomain)
                        Log.d("CSPluginSource", "[GET_HOME] Category '${cat.title}' at '$catUrl' returned ${items.size} items (HTML size: ${html.length} chars)")
                        if (items.isNotEmpty()) {
                            StreamingCategory(
                                id = "cs_${cat.title}_${plugin.id}",
                                title = cat.title,
                                items = items
                            )
                        } else null
                    }
                }

                val resolved = tasks.mapNotNull { it.await() }
                if (resolved.isNotEmpty()) {
                    Log.d("CSPluginSource", "[GET_HOME] Tier 2 Success: Scraped ${resolved.size} categories from $cleanDomain")
                    categories.addAll(resolved)
                    break
                }
            }

            if (categories.isEmpty()) {
                Log.w("CSPluginSource", "[GET_HOME] No categories returned reflectively or from scraper for ${plugin.name}.")
            }
        } catch (e: Exception) {
            Log.e("CSPluginSource", "[GET_HOME] Error loading home categories for ${plugin.name}: ${e.message}", e)
        }

        categories
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val nativeItems = CloudStreamDexLoader.invokeReflectiveCategoryContent(context, plugin, categoryId, page)
        if (!nativeItems.isNullOrEmpty()) {
            return@withContext nativeItems
        }

        val (detectedUrl, mainPageCategories) = CloudStreamDexLoader.getPluginMainPageInfo(context, plugin)
        val candidateDomains = mutableListOf<String>()
        if (detectedUrl.isNotBlank()) candidateDomains.add(detectedUrl)
        candidateDomains.addAll(CloudStreamDexLoader.getCandidateDomains(plugin.name.lowercase()))

        val targetCat = mainPageCategories.find { categoryId.contains(it.title, ignoreCase = true) }
        val path = targetCat?.pathOrUrl ?: ""

        for (domain in candidateDomains.distinct()) {
            val cleanDomain = domain.trimEnd('/')
            val catUrl = when {
                path.startsWith("http") -> if (page > 1) "$path/page/$page/" else path
                path.isBlank() || path == "/" -> if (page > 1) "$cleanDomain/page/$page/" else "$cleanDomain/"
                path.startsWith("/") -> if (page > 1) "$cleanDomain$path/page/$page/" else "$cleanDomain$path"
                else -> if (page > 1) "$cleanDomain/$path/page/$page/" else "$cleanDomain/$path"
            }

            val html = fetchHtml(catUrl, cleanDomain)
            val items = parseItemsFromHtml(html, cleanDomain)
            if (items.isNotEmpty()) return@withContext items
        }

        emptyList()
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // Tier 1: Reflective invocation of search(query) on DEX plugin
        val reflectiveResults = CloudStreamDexLoader.invokeReflectiveSearch(context, plugin, query)
        if (!reflectiveResults.isNullOrEmpty()) {
            Log.d("CSPluginSource", "Successfully fetched ${reflectiveResults.size} search results reflectively for ${plugin.name}")
            return@withContext reflectiveResults
        }

        // Tier 2: JSoup web search fallback
        val (detectedUrl, _) = CloudStreamDexLoader.getPluginMainPageInfo(context, plugin)
        val candidateDomains = mutableListOf<String>()
        if (detectedUrl.isNotBlank()) candidateDomains.add(detectedUrl)
        candidateDomains.addAll(CloudStreamDexLoader.getCandidateDomains(plugin.name.lowercase()))

        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        for (domain in candidateDomains.distinct()) {
            val cleanDomain = domain.trimEnd('/')
            val searchUrl = "$cleanDomain/?s=$encoded"

            val html = fetchHtml(searchUrl, cleanDomain)
            val items = parseItemsFromHtml(html, cleanDomain)
            if (items.isNotEmpty()) return@withContext items
        }

        emptyList()
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            var itemUrl = id
            var itemTitle = ""
            var isSeries = false

            if (id.trim().startsWith("{")) {
                try {
                    val json = JSONObject(id)
                    itemUrl = json.optString("url", id)
                    itemTitle = json.optString("title", "")
                    isSeries = json.optBoolean("isSeries", false)
                } catch (_: Exception) {}
            }

            // Tier 1: Reflective invocation of load(url) on DEX plugin
            val reflectiveItem = CloudStreamDexLoader.invokeReflectiveLoad(context, plugin, itemUrl)
            if (reflectiveItem != null) {
                Log.d("CSPluginSource", "Successfully fetched reflective details for ${plugin.name}: ${reflectiveItem.title}")
                return@withContext reflectiveItem
            }

            if (itemUrl.startsWith("http://") || itemUrl.startsWith("https://")) {
                val (mainUrl, _) = CloudStreamDexLoader.getPluginMainPageInfo(context, plugin)
                val html = fetchHtml(itemUrl, mainUrl)
                val doc = Jsoup.parse(html, mainUrl)

                if (itemTitle.isBlank()) {
                    itemTitle = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()
                        ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                        ?: ""
                }
                itemTitle = cleanTitleText(itemTitle)

                var imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
                if (imageUrl.isBlank()) {
                    val imgElem = doc.selectFirst(".entry-content img, .post-thumbnail img, article img")
                    if (imgElem != null) {
                        imageUrl = imgElem.absUrl("src").ifBlank { imgElem.absUrl("data-src") }
                    }
                }

                var description = doc.selectFirst(".entry-content p, .post-description p, #synopsis p")?.text() ?: ""
                if (description.isBlank()) {
                    description = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
                }

                // Check TMDB details for backdrop / rating / release year
                val tmdbDetails = TmdbHelper.searchAndFetchDetails(context, itemTitle, year = null, isSeries = isSeries)
                val backdropUrl = tmdbDetails?.backdropPath?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w1280$it" }
                    ?: imageUrl
                val rating = tmdbDetails?.rating
                val year = tmdbDetails?.year
                val genres = tmdbDetails?.genres

                val streamPayload = JSONObject().apply {
                    put("url", itemUrl)
                    put("title", itemTitle)
                    put("isSeries", isSeries)
                    put("pluginId", plugin.id)
                }.toString()

                return@withContext StreamingItem(
                    id = itemUrl,
                    title = itemTitle.ifBlank { "Untitled" },
                    isSeries = isSeries,
                    imageUrl = imageUrl.ifBlank { tmdbDetails?.posterPath?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w500$it" } },
                    backdropUrl = backdropUrl,
                    description = description.ifBlank { tmdbDetails?.overview },
                    rating = rating,
                    year = year,
                    genres = genres,
                    streamUrl = streamPayload,
                    sourceName = name
                )
            }

            null
        } catch (e: Exception) {
            Log.e("CSPluginSource", "Error getting details for $id: ${e.message}")
            null
        }
    }

    suspend fun extractVideoLinksStreaming(
        data: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        onStreamFound: suspend (streams: Map<String, String>) -> Unit
    ) = withContext(Dispatchers.IO) {
        var queryTitle = data
        var isSeries = false
        var season: Int? = null
        var episode: Int? = null

        try {
            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                queryTitle = json.optString("title", data)
                isSeries = json.optBoolean("isSeries", false)
                if (json.has("season")) season = json.optInt("season")
                if (json.has("episode")) episode = json.optInt("episode")
            }
        } catch (_: Exception) {}

        onProgress(0, 1)

        val streamResults = CloudStreamDexLoader.searchAndFetchStreams(
            context = context,
            plugin = plugin,
            queryTitle = queryTitle,
            isSeries = isSeries,
            season = season,
            episode = episode
        )

        val streamMap = mutableMapOf<String, String>()
        for (res in streamResults) {
            val label = "[CS: ${plugin.name}] ${res.source} - ${res.qualityScore}p"
            streamMap[label] = res.url
        }

        onProgress(1, 1)
        if (streamMap.isNotEmpty()) {
            onStreamFound(streamMap)
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val reflectiveLinks = CloudStreamDexLoader.invokeReflectiveLoadLinks(context, plugin, data)
        if (!reflectiveLinks.isNullOrEmpty()) {
            Log.d("CSPluginSource", "Successfully extracted ${reflectiveLinks.size} links reflectively for ${plugin.name}")
            return@withContext reflectiveLinks
        }

        val map = mutableMapOf<String, String>()
        extractVideoLinksStreaming(data, onStreamFound = { map.putAll(it) })
        map
    }

    private fun parseItemsFromHtml(html: String, baseUrl: String): List<StreamingItem> {
        val doc = Jsoup.parse(html, baseUrl)
        val items = mutableListOf<StreamingItem>()
        val seenUrls = mutableSetOf<String>()

        val elements = doc.select("article, .post, .post-item, .latest-post, .movie-card, .entry-card, .item, ul.recent-posts li, .blog-post, div.result-item, .elementor-post, .box-item, .item-article, .movie-item, .poster-card, div.col-md-2, div.col-sm-3, div.col-xs-6, div.poster")
        val targetElements = if (elements.isNotEmpty()) elements else doc.select("a:has(img)")

        for (element in targetElements) {
            val linkElem = element.selectFirst("a[href]") ?: (if (element.tagName() == "a") element else null) ?: continue
            val itemUrl = linkElem.absUrl("href")
            if (itemUrl.isBlank() || seenUrls.contains(itemUrl) || itemUrl.contains("#") || itemUrl == baseUrl || itemUrl == "$baseUrl/") continue

            val imgElem = element.selectFirst("img") ?: continue
            var imageUrl = imgElem.absUrl("src")
            if (imageUrl.isBlank() || imageUrl.startsWith("data:image")) imageUrl = imgElem.absUrl("data-src")
            if (imageUrl.isBlank() || imageUrl.startsWith("data:image")) imageUrl = imgElem.absUrl("data-lazy-src")
            if (imageUrl.isBlank() || imageUrl.startsWith("data:image")) imageUrl = imgElem.absUrl("data-original")
            if (imageUrl.isBlank() || imageUrl.startsWith("data:image")) imageUrl = imgElem.absUrl("data-cfsrc")
            if (imageUrl.isBlank() || imageUrl.startsWith("data:image")) imageUrl = imgElem.absUrl("data-image")
            if (imageUrl.isBlank() || imageUrl.startsWith("data:image")) {
                val srcset = imgElem.attr("srcset").ifBlank { imgElem.attr("data-srcset") }
                if (srcset.isNotBlank()) {
                    imageUrl = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: ""
                }
            }
            if (imageUrl.startsWith("//")) {
                imageUrl = "https:$imageUrl"
            }

            var title = element.selectFirst("h1, h2, h3, h4, .entry-title, .title, p.title, .entry-header")?.text() ?: ""
            if (title.isBlank()) title = imgElem.attr("alt")
            if (title.isBlank()) title = imgElem.attr("title")
            if (title.isBlank()) title = linkElem.attr("title")
            if (title.isBlank()) title = linkElem.text()

            val rawTitle = title
            val cleanTitle = cleanTitleText(title)
            if (cleanTitle.isBlank() || imageUrl.isBlank()) continue

            seenUrls.add(itemUrl)

            val isSeries = rawTitle.contains("season", ignoreCase = true) ||
                           rawTitle.contains("series", ignoreCase = true) ||
                           rawTitle.contains("episode", ignoreCase = true) ||
                           rawTitle.contains("multi audio", ignoreCase = true) ||
                           rawTitle.contains("dual audio", ignoreCase = true) ||
                           rawTitle.contains("s0", ignoreCase = true) ||
                           rawTitle.contains("s1", ignoreCase = true)

            val streamPayload = JSONObject().apply {
                put("url", itemUrl)
                put("title", cleanTitle)
                put("rawTitle", rawTitle)
                put("isSeries", isSeries)
                put("pluginId", plugin.id)
            }.toString()

            items.add(
                StreamingItem(
                    id = itemUrl,
                    title = cleanTitle,
                    isSeries = isSeries,
                    imageUrl = imageUrl,
                    streamUrl = streamPayload,
                    sourceName = name
                )
            )
        }

        return items
    }

    private fun fetchHtml(url: String, referer: String): String {
        return try {
            val reqBuilder = Request.Builder().url(url)
            defaultHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            reqBuilder.addHeader("Referer", referer)

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: "" else ""
            }
        } catch (e: Exception) {
            Log.w("CSPluginSource", "Failed to fetch $url: ${e.message}")
            ""
        }
    }

    private fun cleanTitleText(text: String): String {
        return CloudStreamDexLoader.cleanMediaTitle(text)
    }
}
