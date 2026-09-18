package com.example.zubflix.sources

import android.content.Context
import com.example.zubflix.SourceManager
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.example.zubflix.utils.TmdbHelper
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class CircleFtpSource : StreamingSource {
    override val name: String = "CircleFTP 🇧🇩"

    companion object {
        private const val TAG = "CircleFtpSource"

        // Endpoints supported by CircleFTP API
        private val API_HOSTS = listOf(
            "http://new.circleftp.net:5000",
            "http://15.1.1.50:5000",
            "http://172.16.50.4"
        )

        // Mapping domain links to direct BDIX internal IPs for fast, stable playback
        fun linkToIp(data: String?): String {
            if (data == null) return ""
            return when {
                "index.circleftp.net" in data -> data.replace("index.circleftp.net", "15.1.4.2")
                "index2.circleftp.net" in data -> data.replace("index2.circleftp.net", "15.1.4.5")
                "index1.circleftp.net" in data -> data.replace("index1.circleftp.net", "15.1.4.9")
                "ftp3.circleftp.net" in data -> data.replace("ftp3.circleftp.net", "15.1.4.7")
                "ftp4.circleftp.net" in data -> data.replace("ftp4.circleftp.net", "15.1.1.5")
                "ftp5.circleftp.net" in data -> data.replace("ftp5.circleftp.net", "15.1.1.15")
                "ftp6.circleftp.net" in data -> data.replace("ftp6.circleftp.net", "15.1.2.3")
                "ftp7.circleftp.net" in data -> data.replace("ftp7.circleftp.net", "15.1.4.8")
                "ftp8.circleftp.net" in data -> data.replace("ftp8.circleftp.net", "15.1.2.2")
                "ftp9.circleftp.net" in data -> data.replace("ftp9.circleftp.net", "15.1.2.12")
                "ftp10.circleftp.net" in data -> data.replace("ftp10.circleftp.net", "15.1.4.3")
                "ftp11.circleftp.net" in data -> data.replace("ftp11.circleftp.net", "15.1.2.6")
                "ftp12.circleftp.net" in data -> data.replace("ftp12.circleftp.net", "15.1.2.1")
                "ftp13.circleftp.net" in data -> data.replace("ftp13.circleftp.net", "15.1.1.18")
                "ftp15.circleftp.net" in data -> data.replace("ftp15.circleftp.net", "15.1.4.12")
                "ftp17.circleftp.net" in data -> data.replace("ftp17.circleftp.net", "15.1.3.8")
                else -> data
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()
    private val semaphore = Semaphore(4)

    // Complete mapping of categories
    private val categoriesToFetch = listOf(
        "80" to "Featured",
        "6" to "English Movies",
        "9" to "English & Foreign TV Series",
        "22" to "Dubbed TV Series",
        "2" to "Hindi Movies",
        "5" to "Hindi TV Series",
        "238" to "Indian TV Show",
        "7" to "English & Foreign Hindi Dubbed Movies",
        "8" to "Foreign Language Movies",
        "3" to "South Indian Dubbed Movies",
        "4" to "South Indian Movies",
        "1" to "Animation Movies",
        "21" to "Anime Series",
        "85" to "Documentary",
        "15" to "WWE"
    )

    private suspend fun fetchApiJson(endpoint: String): String? = withContext(Dispatchers.IO) {
        val cleanEndpoint = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        for (host in API_HOSTS) {
            val url = "$host$cleanEndpoint"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            return@withContext body
                        }
                    }
                }
            } catch (_: Exception) {
                // Try next host
            }
        }
        null
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val tasks = categoriesToFetch.map { (id, title) ->
            async {
                semaphore.withPermit {
                    try {
                        val json = fetchApiJson("/api/posts?categoryExact=$id&page=1&order=desc&limit=15")
                        if (json != null) {
                            val pageData = gson.fromJson(json, CirclePageData::class.java)
                            val items = pageData?.posts?.mapNotNull { mapPostToItem(it) }.orEmpty()
                            if (items.isNotEmpty()) {
                                StreamingCategory(id, title, items)
                            } else null
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        tasks.awaitAll().filterNotNull()
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val json = fetchApiJson("/api/posts?searchTerm=$encoded&order=desc")
            if (json != null) {
                val pageData = gson.fromJson(json, CirclePageData::class.java)
                pageData?.posts?.mapNotNull { mapPostToItem(it) }.orEmpty()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            val json = fetchApiJson("/api/posts/$id") ?: return@withContext null
            val detailPost = gson.fromJson(json, CirclePostDetail::class.java) ?: return@withContext null
            val item = mapDetailToItem(detailPost) ?: return@withContext null

            // TMDB Enhancement if available
            val context = SourceManager.getAllSources().firstOrNull()?.let {
                (it as? CachedSource)?.let { cs ->
                    try {
                        val contextField = cs.javaClass.getDeclaredField("context")
                        contextField.isAccessible = true
                        contextField.get(cs) as Context
                    } catch (_: Exception) { null }
                }
            }

            val tmdbDetails = if (context != null) TmdbHelper.searchAndFetchDetails(context, item.title, item.year, item.isSeries) else null
            if (tmdbDetails != null) {
                item.copy(
                    imageUrl = tmdbDetails.posterPath ?: item.imageUrl,
                    backdropUrl = tmdbDetails.backdropPath ?: item.backdropUrl,
                    description = tmdbDetails.overview ?: item.description,
                    rating = tmdbDetails.rating,
                    year = tmdbDetails.year ?: item.year,
                    cast = tmdbDetails.cast,
                    genres = tmdbDetails.genres ?: item.genres
                )
            } else {
                item
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val json = fetchApiJson("/api/posts?categoryExact=$categoryId&page=$page&order=desc&limit=20")
            if (json != null) {
                val pageData = gson.fromJson(json, CirclePageData::class.java)
                pageData?.posts?.mapNotNull { mapPostToItem(it) }.orEmpty()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolveImageUrl(imageName: String?): String {
        if (imageName.isNullOrBlank()) return ""
        if (imageName.startsWith("http://") || imageName.startsWith("https://")) return imageName
        return "http://new.circleftp.net:5000/uploads/$imageName"
    }

    private fun mapPostToItem(post: CirclePost): StreamingItem? {
        val title = post.name ?: post.title ?: return null
        val imageUrl = resolveImageUrl(post.imageSm ?: post.image)
        val isSeries = post.type == "series"

        return StreamingItem(
            id = post.id.toString(),
            title = title,
            imageUrl = imageUrl,
            description = null,
            isSeries = isSeries,
            streamUrl = null,
            seasons = null,
            quality = null,
            year = null,
            duration = null,
            rating = null,
            genres = null,
            cast = null
        )
    }

    private fun mapDetailToItem(post: CirclePostDetail): StreamingItem? {
        val title = post.name ?: post.title ?: return null
        val imageUrl = resolveImageUrl(post.image ?: post.imageSm)
        val isSeries = post.type == "series"
        val content = post.content

        var streamUrl: String? = null
        var seasons: List<StreamingSeason>? = null

        if (!isSeries) {
            if (content != null && content.isJsonPrimitive) {
                val rawUrl = content.asString
                streamUrl = linkToIp(rawUrl)
            }
        } else {
            if (content != null && content.isJsonArray) {
                try {
                    val type = object : TypeToken<List<CircleFtpSeason>>() {}.type
                    val circleFtpSeasons: List<CircleFtpSeason> = gson.fromJson(content, type)

                    seasons = circleFtpSeasons.map { ftpSeason ->
                        val sNum = Regex("(?i)\\b(?:season|s)\\s*0*(\\d+)\\b").find(ftpSeason.seasonName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        StreamingSeason(
                            title = ftpSeason.seasonName,
                            episodes = ftpSeason.episodes.map { ftpEpisode ->
                                StreamingEpisode(
                                    title = ftpEpisode.title,
                                    streamUrl = linkToIp(ftpEpisode.link)
                                )
                            },
                            seasonNumber = sNum
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return StreamingItem(
            id = post.id.toString(),
            title = title,
            imageUrl = imageUrl,
            description = post.metaData,
            isSeries = isSeries,
            streamUrl = streamUrl,
            seasons = seasons,
            quality = post.quality,
            year = post.year,
            duration = post.watchTime,
            rating = null,
            genres = null,
            cast = null
        )
    }

    // Models for JSON responses
    private data class CirclePageData(
        val posts: List<CirclePost>?
    )

    private data class CirclePost(
        val id: Int,
        val type: String?,
        val imageSm: String?,
        val image: String?,
        val title: String?,
        val name: String?
    )

    private data class CirclePostDetail(
        val id: Int,
        val type: String?,
        val imageSm: String?,
        val image: String?,
        val title: String?,
        val name: String?,
        val metaData: String?,
        val quality: String?,
        val year: String?,
        val watchTime: String?,
        val content: JsonElement?
    )

    private data class CircleFtpSeason(
        val seasonName: String,
        val episodes: List<CircleFtpEpisode>
    )

    private data class CircleFtpEpisode(
        val title: String,
        val link: String
    )
}

