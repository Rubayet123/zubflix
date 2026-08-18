package com.example.zubflix.sources

import android.content.Context
import android.util.Log
import com.example.zubflix.SourceManager
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.example.zubflix.utils.TmdbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class DiscoveryFtpSource : StreamingSource {
    override val name: String = "DiscoveryFTP 🇧🇩"
    private val mainUrl = "https://movies.discoveryftp.net"

    private val client = createUnsafeOkHttpClient()

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        } catch (e: Exception) {
            return OkHttpClient.Builder().build()
        }
    }

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
        "Connection" to "keep-alive"
    )

    private val categoriesToFetch = listOf(
        "English Movies" to "$mainUrl/m/category/English",
        "Animation Movies" to "$mainUrl/m/category/Animation",
        "Hindi Movies" to "$mainUrl/m/category/Hindi",
        "Tamil Movies" to "$mainUrl/m/category/Tamil",
        "Bangla Movies" to "$mainUrl/m/category/Bangla",
        "Other Movies" to "$mainUrl/m/category/Others",
        "Series Animation" to "$mainUrl/s/category/Animation",
        "Series Bangla" to "$mainUrl/s/category/Bangla",
        "Series Hindi" to "$mainUrl/s/category/Hindi",
        "Series South" to "$mainUrl/s/category/South",
        "Series Foreign" to "$mainUrl/s/category/Foreign",
        "Series Dubbed" to "$mainUrl/s/category/Dubbed"
    )

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        Log.d("DiscoveryFtpSource", "=== STARTING DISCOVERY FTP HOME FETCH ===")
        
        coroutineScope {
            val tasks = categoriesToFetch.map { (title: String, url: String) ->
                async {
                    try {
                        val html = get(url)
                        if (html.isNotBlank()) {
                            val doc = Jsoup.parse(html)
                            val items = parseGrid(doc)
                            if (items.isNotEmpty()) {
                                Log.d("DiscoveryFtpSource", "Category '$title' parsed ${items.size} items")
                                return@async StreamingCategory(url, title, items)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("DiscoveryFtpSource", "Error fetching category $title", e)
                    }
                    null
                }
            }
            
            val results = tasks.awaitAll().filterNotNull()
            Log.d("DiscoveryFtpSource", "=== HOME FETCH COMPLETE. TOTAL: ${results.size} ===")
            results
        }
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/m/find/$encodedQuery"
            val html = get(url)
            if (html.isNotBlank()) {
                val doc = Jsoup.parse(html)
                parseGrid(doc)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        coroutineScope {
            try {
                val url = if (id.startsWith("http")) id else "$mainUrl${if (id.startsWith("/")) "" else "/"}$id"
                val html = get(url)
                if (html.isBlank()) return@coroutineScope null
                val doc = Jsoup.parse(html)

                val titleEl = doc.select("h3").firstOrNull() 
                    ?: doc.select(".details h3, .movie-details h3, .ftitle, .searchtitle").firstOrNull()
                
                var title = titleEl?.text()?.trim() ?: doc.title().replace("DFLIX - ", "").trim()
                var year: String? = null

                // Handle ((Year)) in series titles
                if (title.contains("((") && title.contains("))")) {
                    val match = Regex("\\(\\(\\s*(\\d{4})\\s*\\)\\)").find(title)
                    year = match?.groupValues?.get(1)
                    title = title.replace(Regex("\\(\\(\\s*\\d{4}\\s*\\)\\)"), "").trim()
                }

                val description = doc.select("div.description, p, .movie-details, .fdetails.mt-1").lastOrNull()?.text()?.trim() ?: ""
                val poster = doc.select("div.poster img, .movie-poster img, .img-fluid").firstOrNull()?.attr("src") ?: ""
                val imageUrl = if (poster.startsWith("http")) poster else "$mainUrl$poster"
                
                val tableRows = doc.select("table.table-striped > tbody > tr")
                
                val isSeriesUrl = id.contains("/s/", ignoreCase = true)
                
                if (isSeriesUrl) {
                    val seasons = mutableListOf<StreamingSeason>()
                    val episodes = mutableListOf<StreamingEpisode>()
                    
                    // The series page structure has divs with card-like content containing h5 (title) and anchor (download link)
                    val cardElements = doc.select("div[style*='background-color: #000000a3']")
                    
                    cardElements.forEach { card ->
                        val h5El = card.select("h5").firstOrNull()
                        val epTitle = h5El?.text()?.trim() ?: "Episode"
                        val linkEl = h5El?.select("a[href]")?.firstOrNull()
                        val epUrl = linkEl?.attr("href")
                        
                        if (epUrl != null) {
                            episodes.add(StreamingEpisode(epTitle, epUrl))
                        }
                    }
                    
                    if (episodes.isNotEmpty()) {
                        seasons.add(StreamingSeason("Season 1", episodes))
                    }
                    
                    // TMDB Enhancement
                    val context = SourceManager.getAllSources().firstOrNull()?.let { 
                        (it as? com.example.zubflix.sources.CachedSource)?.let { cs -> 
                            try {
                                val contextField = cs.javaClass.getDeclaredField("context")
                                contextField.isAccessible = true
                                contextField.get(cs) as Context
                            } catch (e: Exception) { null }
                        } 
                    }
                    
                    val tmdbDetails = if (context != null) TmdbHelper.searchAndFetchDetails(context, title, year, true) else null
                    
                    StreamingItem(
                        id = id,
                        title = title,
                        imageUrl = tmdbDetails?.posterPath ?: imageUrl,
                        backdropUrl = tmdbDetails?.backdropPath,
                        description = tmdbDetails?.overview ?: description,
                        rating = tmdbDetails?.rating,
                        year = tmdbDetails?.year ?: year,
                        cast = tmdbDetails?.cast,
                        genres = tmdbDetails?.genres,
                        isSeries = true,
                        seasons = seasons
                    )
                } else {
                    // Movie
                    // Try to find direct video file link first
                    var streamBtn = doc.select("a.btn[href~=(?i)\\.(mkv|mp4|avi)]").firstOrNull()
                    
                    // Fallback to Download buttons if not found
                    if (streamBtn == null) {
                        streamBtn = doc.select("a.btn").firstOrNull { 
                            it.text().contains("Download", true) 
                        }
                    }
                    
                    // Fallback to Stream buttons if still not found
                    if (streamBtn == null) {
                        streamBtn = doc.select("a.btn").firstOrNull { 
                            it.text().contains("Stream", true) 
                        }
                    }
                    
                    var streamUrl = streamBtn?.attr("href")
                    
                    if (streamUrl != null) {
                        if (streamUrl.startsWith("intent:")) {
                            // Extract from intent:URL#Intent...
                            streamUrl = streamUrl.substringAfter("intent:").substringBefore("#Intent")
                        } else if (!streamUrl.startsWith("http")) {
                            streamUrl = "$mainUrl$streamUrl"
                        }
                    }
                    
                    // TMDB Enhancement
                    val context = SourceManager.getAllSources().firstOrNull()?.let { 
                        (it as? CachedSource)?.let { cs -> 
                            try {
                                val contextField = cs.javaClass.getDeclaredField("context")
                                contextField.isAccessible = true
                                contextField.get(cs) as Context
                            } catch (e: Exception) { null }
                        } 
                    }
                    
                    val tmdbDetails = if (context != null) TmdbHelper.searchAndFetchDetails(context, title, year, false) else null

                    StreamingItem(
                        id = id,
                        title = title,
                        imageUrl = tmdbDetails?.posterPath ?: imageUrl,
                        backdropUrl = tmdbDetails?.backdropPath,
                        description = tmdbDetails?.overview ?: description,
                        rating = tmdbDetails?.rating,
                        year = tmdbDetails?.year ?: year,
                        cast = tmdbDetails?.cast,
                        genres = tmdbDetails?.genres,
                        isSeries = false,
                        streamUrl = streamUrl
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseEpisodesFromDoc(doc: org.jsoup.nodes.Document, seasonTitle: String): List<StreamingEpisode> {
        val episodes = mutableListOf<StreamingEpisode>()
        // Look for direct CDN links
        val episodeLinks = doc.select("a[href*='.mkv'], a[href*='.mp4'], a[href*='.webm']")
        
        episodeLinks.forEach { el ->
            val href = el.attr("href")
            val text = el.text().trim()
            val parent = el.parent()
            val parentText = parent?.text() ?: ""
            
            // On DiscoveryFTP, the link text is often just "500.15 MB"
            // The actual title "S1 | EP 2" is usually in the parent header or adjacent text.
            val combinedText = "$parentText ${theSiblingText(el)}"
            
            // Regex to find Episode number
            val eMatch = Regex("E(?:pisode|P)?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(combinedText)
            val eNum = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: (episodes.size + 1)
            
            // 1. Try to find a clean descriptor like "S1 | EP 2"
            val descriptorMatch = Regex("(S\\d+\\s*\\|\\s*EP\\s*\\d+)", RegexOption.IGNORE_CASE).find(combinedText)
            var finalTitle = descriptorMatch?.groupValues?.get(1)?.uppercase()
            
            // 2. Fallback to extracting clean name if descriptor not found
            if (finalTitle == null) {
                // Remove common file info/bytes from the text to get a cleaner title
                val cleanParentText = parentText.replace(Regex("\\d+(\\.\\d+)?\\s*(MB|GB)", RegexOption.IGNORE_CASE), "").replace("|", "").trim()
                if (cleanParentText.length > 3) {
                    finalTitle = cleanParentText
                }
            }

            episodes.add(StreamingEpisode(
                title = finalTitle ?: "$seasonTitle | EP $eNum",
                streamUrl = href
            ))
        }
        return episodes.distinctBy { it.streamUrl }.sortedBy { ep ->
            Regex("EP\\s*(\\d+)", RegexOption.IGNORE_CASE).find(ep.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }

    private fun theSiblingText(el: org.jsoup.nodes.Element): String {
        return el.previousElementSibling()?.text() ?: ""
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val url = if (page > 1) {
            val base = categoryId.removeSuffix("/")
            if (base.contains("?")) {
                if (base.contains("page=")) {
                    base.replace(Regex("page=\\d+"), "page=$page")
                } else {
                    "$base&page=$page"
                }
            } else {
                "$base/$page"
            }
        } else {
            categoryId
        }
        
        Log.d("DiscoveryFtpSource", "Fetching Category Content: $url (Page $page)")
        val html = get(url)
        if (html.isBlank()) return@withContext emptyList()
        val doc = Jsoup.parse(html)
        parseGrid(doc)
    }

    private fun parseGrid(doc: org.jsoup.nodes.Document): List<StreamingItem> {
        val items = mutableListOf<StreamingItem>()
        
        // Match all potential card/item containers
        val cards = doc.select(".moviegrid .card, .row .card, div.card, .moviesearchiteam")
        
        cards.forEach { card ->
            try {
                // 1. Link Extraction (Very Robust)
                val linkEl = card.select("a[href*='/view/']").firstOrNull() 
                    ?: card.select("a.cfocus").firstOrNull()
                    ?: card.select("a").firstOrNull { it.hasAttr("href") }
                
                var link = linkEl?.attr("href") ?: ""
                if (link.isEmpty()) return@forEach // Skip if no link
                if (!link.startsWith("http")) link = "$mainUrl${if (link.startsWith("/")) "" else "/"}$link"

                // 2. Title Extraction
                val titleEl = card.select(".details h3, h3, .searchtitle, .ftitle").firstOrNull()
                val title = titleEl?.text()?.trim() ?: ""
                if (title.isEmpty()) return@forEach

                // 3. Image Extraction
                val imgEl = card.select("img").firstOrNull()
                val image = imgEl?.attr("src") ?: imgEl?.attr("data-src") ?: ""
                
                // 4. Metadata (Year/Quality/Rating)
                val detailsText = card.select(".searchdetails, .feedback").text()
                val spans = card.select("span.movie_details_span, span.movie_details_span_end")
                
                var year: String? = null
                var quality: String? = null
                var rating: String? = null

                // Try Regex on raw details text first (for .moviesearchiteam)
                if (detailsText.isNotEmpty()) {
                    year = Regex("Year\\s*:\\s*(\\d{4})").find(detailsText)?.groupValues?.get(1)
                    quality = Regex("Quality\\s*:\\s*([^|\\n]+)").find(detailsText)?.groupValues?.get(1)?.trim()
                }

                // Fallback to spans (for .card)
                spans.forEach { span ->
                    val text = span.text().trim()
                    if (text.matches(Regex("\\d{4}"))) {
                        year = text
                    } else if (text.contains("-") && text.length >= 10) { // e.g. 2026-02-13
                        year = text.split("-")[0]
                    } else if (text.contains("1080P", true) || text.contains("4K", true) || text.contains("WEB-DL", true)) {
                        quality = text
                    }
                }

                // Special handling for rating in search results
                val ratingEl = card.select("span[style*=\"float: right\"]").firstOrNull()
                if (ratingEl != null) {
                    val rText = ratingEl.text().trim()
                    if (rText.isNotEmpty()) rating = rText
                }

                items.add(StreamingItem(
                    id = link,
                    title = title,
                    isSeries = link.contains("/s/"),
                    imageUrl = if (image.startsWith("http")) image else "$mainUrl${if (image.startsWith("/")) "" else "/"}$image",
                    year = year,
                    quality = quality,
                    rating = rating,
                    streamUrl = null
                ))
            } catch (e: Exception) { }
        }

        // 2. Try Series-Only Format (col-xl-4 columns)
        val seriesColumns = doc.select("div.col-xl-4.col-md-4.col-sm-6.mt-3.pe-3")
        seriesColumns.forEach { col ->
            try {
                val linkEl = col.select("a.text-light[href^=\"/s/view/\"], a[href*='/view/']").firstOrNull()
                val imgEl = col.select("img").firstOrNull()
                val titleEl = col.select(".ftitle, h3").firstOrNull()
                
                var title = titleEl?.text()?.trim() ?: ""
                var link = linkEl?.attr("href") ?: ""
                val image = imgEl?.attr("src") ?: ""
                var year: String? = null

                if (title.contains("((") && title.contains("))")) {
                    val match = Regex("\\(\\(\\s*(\\d{4})\\s*\\)\\)").find(title)
                    year = match?.groupValues?.get(1)
                    title = title.replace(Regex("\\(\\(\\s*\\d{4}\\s*\\)\\)"), "").trim()
                }

                if (title.isNotEmpty() && link.isNotEmpty()) {
                    if (!link.startsWith("http")) link = "$mainUrl${if (link.startsWith("/")) "" else "/"}$link"

                    items.add(StreamingItem(
                        id = link,
                        title = title,
                        isSeries = true,
                        imageUrl = if (image.startsWith("http")) image else "$mainUrl${if (image.startsWith("/")) "" else "/"}$image",
                        year = year,
                        quality = null,
                        streamUrl = null
                    ))
                }
            } catch (e: Exception) { }
        }

        return items.distinctBy { it.id }
    }

    private fun get(url: String): String {
        try {
            val requestBuilder = Request.Builder().url(url)
            baseHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            val request = requestBuilder.build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return response.body?.string() ?: ""
                }
                if (url.startsWith("https")) return get(url.replace("https", "http"))
                return ""
            }
        } catch (e: Exception) {
            if (url.startsWith("https")) return get(url.replace("https", "http"))
            return ""
        }
    }
}
