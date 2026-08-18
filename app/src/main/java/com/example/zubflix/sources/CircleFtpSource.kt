package com.example.zubflix.sources

import com.example.netflix.network.RetrofitClient
import com.example.netflix.model.Post
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import android.content.Context
import com.example.zubflix.SourceManager
import com.example.zubflix.utils.TmdbHelper
import com.example.zubflix.sources.CachedSource

class CircleFtpSource : StreamingSource {
    override val name: String = "CircleFTP 🇧🇩"
    
    // Complete mapping of categories as requested
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

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        // Fetch all categories in parallel
        val tasks = categoriesToFetch.map { (id, title) ->
            async {
                try {
                    // Using categoryExact to match specific category ID
                    val response = RetrofitClient.instance.fetchPosts(categoryId = id, limit = 100)
                    if (response.isSuccessful && response.body() != null) {
                        val posts = response.body()!!.posts
                        val items = posts.mapNotNull { post -> mapPostToItem(post) }
                        
                        if (items.isNotEmpty()) {
                            StreamingCategory(id, title, items)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }
        // Wait for all requests to finish and filter out nulls
        tasks.awaitAll().filterNotNull()
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.instance.fetchPosts(searchTerm = query)
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.posts.mapNotNull { mapPostToItem(it) }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.instance.fetchPostDetails(id.toInt())
            if (response.isSuccessful && response.body() != null) {
                val item = mapPostToItem(response.body()!!) ?: return@withContext null
                
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
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.instance.fetchPosts(categoryId = categoryId, page = page, limit = 100)
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.posts.mapNotNull { mapPostToItem(it) }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun mapPostToItem(post: Post): StreamingItem? {
        val title = post.name ?: post.title ?: return null
        val image = RetrofitClient.IMAGE_BASE_URL + (post.imageSm ?: post.image ?: "")
        val desc = post.metaData
        
        // Fix smart cast issue by storing content in a local variable
        val content = post.content
        val isSeries = content?.isJsonArray == true
        
        var streamUrl: String? = null
        var seasons: List<StreamingSeason>? = null

        if (!isSeries && content?.isJsonPrimitive == true) {
             streamUrl = content.asString
        } else if (isSeries) {
            try {
                val type = object : TypeToken<List<CircleFtpSeason>>() {}.type
                val circleFtpSeasons: List<CircleFtpSeason> = Gson().fromJson(content, type)
                
                seasons = circleFtpSeasons.map { ftpSeason ->
                    val sNum = Regex("(?i)\\b(?:season|s)\\s*0*(\\d+)\\b").find(ftpSeason.seasonName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    StreamingSeason(
                        title = ftpSeason.seasonName,
                        episodes = ftpSeason.episodes.map { ftpEpisode ->
                            StreamingEpisode(
                                title = ftpEpisode.title,
                                streamUrl = ftpEpisode.link
                            )
                        },
                        seasonNumber = sNum
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return StreamingItem(
            id = post.id.toString(),
            title = title,
            imageUrl = image,
            description = desc,
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
    
    // Internal helper classes for JSON parsing
    private data class CircleFtpSeason(
        val seasonName: String,
        val episodes: List<CircleFtpEpisode>
    )
    
    private data class CircleFtpEpisode(
        val title: String,
        val link: String
    )
}
