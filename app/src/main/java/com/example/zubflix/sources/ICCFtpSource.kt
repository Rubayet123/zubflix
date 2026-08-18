package com.example.zubflix.sources

import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import android.content.Context
import com.example.zubflix.SourceManager
import com.example.zubflix.utils.TmdbHelper
import com.example.zubflix.sources.CachedSource
import com.example.zubflix.sources.InMemoryCookieJar

class ICCFtpSource : StreamingSource {
    override val name: String = "ICC FTP 🇧🇩"
    // Removed trailing slash to avoid double slash issues when appending paths starting with /
    private val mainUrl = "http://10.16.100.244"
    private val sessionToken = "83408fdbf5821f154e708b55c2df1f8056a8052315ee92cb17047e51bf8fae2f"
    private val sessionUrl = "$mainUrl/dashboard.php?session=$sessionToken"
    private var sessionPrimed = false
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private suspend fun ensureSession() {
        if (sessionPrimed) return
        try {
            val request = Request.Builder()
                .url(sessionUrl)
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

    private val categoriesMap = mapOf(
        "/dashboard.php?session=$sessionToken&category=0" to "Latest",
        "/dashboard.php?session=$sessionToken&category=19" to "English Movies",
        "/dashboard.php?session=$sessionToken&category=36" to "English Series",
        "/dashboard.php?session=$sessionToken&category=43" to "Dual Audio",
        "/dashboard.php?session=$sessionToken&category=2" to "Hindi Movies",
        "/dashboard.php?session=$sessionToken&category=37" to "Hindi Series",
        "/dashboard.php?session=$sessionToken&category=32" to "South Movies (Dubbed)",
        "/dashboard.php?session=$sessionToken&category=73" to "South Movies",
        "/dashboard.php?session=$sessionToken&category=64" to "Foreign Movies",
        "/dashboard.php?session=$sessionToken&category=75" to "Korean Movies",
        "/dashboard.php?session=$sessionToken&category=80" to "Japanese Movies",
        "/dashboard.php?session=$sessionToken&category=33" to "Animated",
        "/dashboard.php?session=$sessionToken&category=41" to "Documentary",
        "/dashboard.php?session=$sessionToken&category=59" to "Bangla Movies",
        "/dashboard.php?session=$sessionToken&category=60" to "Bangla Movies Kolkata",
        "/dashboard.php?session=$sessionToken&category=34" to "Bangla Drama"
    )

    private val client = OkHttpClient.Builder()
        .cookieJar(InMemoryCookieJar())
        .build()

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        ensureSession()
        val resultList = mutableListOf<StreamingCategory>()
        for ((path, title) in categoriesMap) {
            try {
                val url = "$mainUrl$path"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val response = client.newCall(request).execute()
                // Use property access for body
                val doc = Jsoup.parse(response.body?.string() ?: "")

                val elements = doc.select("div.post-wrapper > a")
                val items = elements.mapNotNull { element ->
                    val name = element.select("img").attr("alt")
                    val link = element.attr("href")
                    val fullLink = if (link.startsWith("http")) link else "$mainUrl/$link"
                    val image = element.select("img").attr("src")
                    val fullImage = if (image.startsWith("http")) image else "$mainUrl/$image"

                    if (name.isNotEmpty() && link.isNotEmpty()) {
                        StreamingItem(id = fullLink, title = name, imageUrl = fullImage, isSeries = false)
                    } else null
                }
                if (items.isNotEmpty()) {
                    resultList.add(StreamingCategory(path, title, items))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        resultList
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        ensureSession()
        try {
            val formBody = FormBody.Builder().add("cSearch", query).build()
            val request = Request.Builder()
                .url("$mainUrl/command.php")
                .post(formBody)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$mainUrl/")
                .build()

            val response = client.newCall(request).execute()
            val jsonString = response.body?.string() ?: return@withContext emptyList()

            val type = object : TypeToken<List<SearchJsonItem>>() {}.type
            val searchResults: List<SearchJsonItem> = Gson().fromJson(jsonString, type)

            searchResults.mapNotNull { mapJsonItemToStreamingItem(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        ensureSession()
        try {
            val request = Request.Builder()
                .url(id)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .build()
            val response = client.newCall(request).execute()
            val doc = Jsoup.parse(response.body?.string() ?: "")

            val tableRows = doc.select(".table > tbody > tr")
            var title = ""
            var year = ""
            var genre = ""
            var plot = ""
            var duration = ""
            
            for (row in tableRows) {
                val label = row.select("td:nth-child(1)").text().trim().lowercase()
                val value = row.select("td:nth-child(2)").text().trim()
                when {
                    "name" in label || "title" in label -> title = value
                    "year" in label -> year = value
                    "genre" in label -> genre = value
                    "storyline" in label || "plot" in label || "description" in label -> plot = value
                    "duration" in label || "runtime" in label -> duration = value
                }
            }
            
            if (title.isBlank()) {
                title = doc.select(".table > tbody > tr:nth-child(1)").text().trim()
            }
            if (year.isBlank()) {
                year = doc.select(".table > tbody > tr:nth-child(2) > td:nth-child(2)").text().trim()
            }
            if (genre.isBlank()) {
                genre = doc.select(".table > tbody > tr:nth-child(5) > td:nth-child(2)").text().trim()
            }
            if (plot.isBlank()) {
                plot = doc.select(".table > tbody > tr:nth-child(12) > td:nth-child(2)").text().trim()
            }
            if (duration.isBlank()) {
                duration = doc.select(".table > tbody > tr:nth-child(4) > td:nth-child(2)").text().trim()
            }

            val imageElement = doc.selectFirst(".col-md-4 > img")
            var image = imageElement?.attr("src") ?: ""
            if (image.isNotEmpty() && !image.startsWith("http")) {
                val cleanImg = if (image.startsWith("/")) image else "/$image"
                image = "$mainUrl$cleanImg"
            }

            val downloadEpisode = doc.select(".btn-group > ul > li")

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
            
            var cleanTitle = title.replace(Regex("\\(.*?\\)"), "").trim()
            cleanTitle = cleanTitle.replace(Regex("(?i)season\\s*\\d+"), "").trim()
            cleanTitle = cleanTitle.replace(Regex("(?i)completed"), "").trim()
            cleanTitle = cleanTitle.replace(Regex("\\s+"), " ").trim()
            val tmdbDetails = if (context != null) TmdbHelper.searchAndFetchDetails(context, cleanTitle, year, downloadEpisode.isNotEmpty()) else null

            if (downloadEpisode.isEmpty()) {
                var link = doc.selectFirst("a.btn")?.attr("href") ?: ""
                if (link.isNotEmpty() && !link.startsWith("http")) {
                    link = if (link.startsWith("/")) "$mainUrl$link" else "$mainUrl/$link"
                }
                StreamingItem(
                    id = id, 
                    title = title, 
                    imageUrl = tmdbDetails?.posterPath ?: image, 
                    backdropUrl = tmdbDetails?.backdropPath,
                    description = tmdbDetails?.overview ?: plot, 
                    isSeries = false, 
                    streamUrl = link, 
                    year = tmdbDetails?.year ?: year, 
                    duration = duration, 
                    rating = tmdbDetails?.rating,
                    cast = tmdbDetails?.cast,
                    genres = tmdbDetails?.genres ?: genre.split(",").map { it.trim() }
                )
            } else {
                val episodes = downloadEpisode.map {
                    var link = it.select("a").attr("href")
                    if (link.isNotEmpty() && !link.startsWith("http")) {
                        link = if (link.startsWith("/")) "$mainUrl$link" else "$mainUrl/$link"
                    }
                    val name = it.select("a").text()
                    val span = it.select("span").text()
                    val cleanName = name.replace(span, "").trim()
                    StreamingEpisode(title = cleanName, streamUrl = link)
                }
                val season = StreamingSeason("Season 1", episodes)
                StreamingItem(
                    id = id, 
                    title = title, 
                    imageUrl = tmdbDetails?.posterPath ?: image, 
                    backdropUrl = tmdbDetails?.backdropPath,
                    description = tmdbDetails?.overview ?: plot, 
                    isSeries = true, 
                    seasons = listOf(season), 
                    year = tmdbDetails?.year ?: year, 
                    duration = duration, 
                    rating = tmdbDetails?.rating,
                    cast = tmdbDetails?.cast,
                    genres = tmdbDetails?.genres ?: genre.split(",").map { it.trim() }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        ensureSession()
        try {
            // For page 1, we scrape the initial page, which also populates our cookie jar with PHPSESSID
            if (page == 1) {
                 val url = "$mainUrl$categoryId"
                 val request = Request.Builder().url(url).build()
                 val response = client.newCall(request).execute()
                 val doc = Jsoup.parse(response.body?.string() ?: "")
                 val elements = doc.select("div.post-wrapper > a")
                 return@withContext elements.mapNotNull { element ->
                    val name = element.select("img").attr("alt")
                    val link = element.attr("href")
                    val fullLink = if (link.startsWith("http")) link else "$mainUrl/$link"
                    val image = element.select("img").attr("src")
                    val fullImage = if (image.startsWith("http")) image else "$mainUrl/$image"

                    if (name.isNotEmpty()) {
                        StreamingItem(id = fullLink, title = name, imageUrl = fullImage, isSeries = false)
                    } else null
                }
            }

            // For subsequent pages, the server returns HTML snippet, not JSON.
            // We use the same headers and cookies as before.
            val catId = categoryId.substringAfter("category=").substringBefore("&")
            val formBody = FormBody.Builder()
                .add("cpage", page.toString())
                .add("cCat", catId)
                .build()

            val request = Request.Builder()
                .url("$mainUrl/command.php") // Removed double slash risk by cleaning mainUrl
                .post(formBody)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$mainUrl$categoryId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .header("Origin", mainUrl) // mainUrl now has no trailing slash, matching curl
                .build()

            val response = client.newCall(request).execute()
            val htmlString = response.body?.string() ?: return@withContext emptyList()

            // Parse the HTML snippet (which contains <div class="post ..."> elements)
            val doc = Jsoup.parseBodyFragment(htmlString)
            val elements = doc.select("div.post-wrapper > a") // Reuse the same selector logic

            return@withContext elements.mapNotNull { element ->
                val name = element.select("img").attr("alt")
                val link = element.attr("href")
                val fullLink = if (link.startsWith("http")) link else "$mainUrl/$link"
                val image = element.select("img").attr("src")
                val fullImage = if (image.startsWith("http")) image else "$mainUrl/$image"

                if (name.isNotEmpty() && link.isNotEmpty()) {
                    StreamingItem(id = fullLink, title = name, imageUrl = fullImage, isSeries = false)
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun mapJsonItemToStreamingItem(item: SearchJsonItem): StreamingItem? {
        if (item.name == null || item.id == null) return null
        val fullUrl = "$mainUrl/player.php?session=$sessionToken&play=${item.id}"
        val fullImage = "$mainUrl/files/${item.image}"
        val isSeries = item.type?.contains("tv", ignoreCase = true) == true || item.type?.contains("series", ignoreCase = true) == true
        return StreamingItem(id = fullUrl, title = item.name, imageUrl = fullImage, isSeries = isSeries)
    }

    data class SearchJsonItem(val id: String?, val image: String?, val name: String?, val type: String?)
}
