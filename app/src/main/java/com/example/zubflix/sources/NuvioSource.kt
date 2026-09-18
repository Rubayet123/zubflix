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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import com.example.zubflix.SourceManager
import com.example.zubflix.util.FilterSettings
import android.content.Context

class NuvioSource(private val context: Context? = null) : StreamingSource {
    override val name: String = "Nuvio"
    override val hasBackdropSupport: Boolean = true
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val addonClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    // TMDB API configuration
    private val apiKey = "68e094699525b18a70bab2f86b1fa706"
    private val tmdbAPI = "https://api.themoviedb.org/3"
    
    // Genre mapping cache
    private val genreMap = mutableMapOf<Int, String>()
    
    // Popular languages mapping: ISO 639-1 Code to Name
    private val languages = listOf(
        "en" to "English",
        "hi" to "Hindi",
        "bn" to "Bengali",
        "es" to "Spanish",
        "fr" to "French",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "tr" to "Turkish",
        "de" to "German",
        "it" to "Italy/Italian",
        "ru" to "Russian",
        "ta" to "Tamil",
        "te" to "Telugu",
        "ml" to "Malayalam",
        "ur" to "Urdu",
        "ar" to "Arabic",
        "pt" to "Portuguese"
    )

    // Popular regions mapping: Code to Name (Curated 14 cards)
    private val regions = listOf(
        "US" to "USA",
        "GB" to "UK",
        "KR" to "South Korea",
        "JP" to "Japan",
        "BD" to "Bangladesh",
        "IN" to "India",
        "TR" to "Turkey",
        "AU" to "Australia",
        "FR" to "France",
        "DE" to "Germany",
        "ES" to "Spain",
        "AR" to "Argentina",
        "BR" to "Brazil",
        "MX" to "Mexico"
    )

    // Curated 14 popular genre cards for Home Screen
    private val homeGenres = listOf(
        "28" to "Action",
        "12" to "Adventure",
        "16" to "Animation",
        "35" to "Comedy",
        "80" to "Crime",
        "99" to "Documentary",
        "18" to "Drama",
        "10751" to "Family",
        "14" to "Fantasy",
        "27" to "Horror",
        "9648" to "Mystery",
        "10749" to "Romance",
        "878" to "Science Fiction",
        "53" to "Thriller"
    )

    private val STREMIO_CATALOG_BASE_URL = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibSxwbXAscGNwLGhsdSxjdHMsbWdsLGNydSxqaHMsemVlLGRwZSxtYmksc29ueWxpdixiYmM6OjoxNzg1Njg0MjQzMzkwOjA6MDpCRA%3D%3D"
    private val stremioMetaCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    private data class StremioMetaParsed(
        val item: StreamingItem,
        val genres: List<String>,
        val country: String,
        val imdbRating: Double,
        val year: Int,
        val popularity: Double
    )

    private suspend fun fetchStremioCatalog(
        catalogId: String,
        type: String,
        genre: String = "all",
        country: String = "all",
        sort: String = "popularity",
        page: Int = 1
    ): List<StreamingItem> = withContext(Dispatchers.IO) {
        val parsedList = mutableListOf<StremioMetaParsed>()
        try {
            val catalogsToFetch = if (catalogId == "all") {
                listOf("nfx", "amp", "dnp", "atp", "hbm")
            } else {
                listOf(catalogId)
            }

            val typesToFetch = if (type == "movie") {
                listOf("movie")
            } else if (type == "tv" || type == "series") {
                listOf("series")
            } else {
                listOf("movie", "series")
            }

            for (cid in catalogsToFetch) {
                for (t in typesToFetch) {
                    val skip = if (page > 1) "/skip=${(page - 1) * 25}" else ""
                    val url = "$STREMIO_CATALOG_BASE_URL/catalog/$t/$cid$skip.json"
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string()
                            if (!bodyStr.isNullOrEmpty()) {
                                val json = JSONObject(bodyStr)
                                val metas = json.optJSONArray("metas") ?: JSONArray()
                                for (i in 0 until metas.length()) {
                                    val obj = metas.getJSONObject(i)
                                    val id = obj.optString("id")
                                    val name = obj.optString("name")
                                    val poster = obj.optString("poster")
                                    val itemType = obj.optString("type")
                                    val isSeries = itemType == "series" || itemType == "tv"
                                    
                                    val itemCountry = obj.optString("country", "")
                                    val imdbRating = obj.optString("imdbRating", "").toDoubleOrNull() ?: 0.0
                                    val yearStr = obj.optString("year", obj.optString("releaseInfo", "")).take(4)
                                    val year = yearStr.toIntOrNull() ?: 0
                                    val pop = obj.optDouble("popularity", 0.0)

                                    val genresArr = obj.optJSONArray("genres") ?: obj.optJSONArray("genre")
                                    val genreList = mutableListOf<String>()
                                    if (genresArr != null) {
                                        for (g in 0 until genresArr.length()) {
                                            genreList.add(genresArr.optString(g))
                                        }
                                    }

                                    if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
                                        stremioMetaCache[id] = obj
                                        val streamingItem = StreamingItem(
                                            id = id,
                                            title = name,
                                            imageUrl = poster,
                                            isSeries = isSeries,
                                            isCategory = false,
                                            sourceName = name
                                        )
                                        parsedList.add(
                                            StremioMetaParsed(
                                                item = streamingItem,
                                                genres = genreList,
                                                country = itemCountry,
                                                imdbRating = imdbRating,
                                                year = year,
                                                popularity = pop
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Nuvio", "Error fetching Stremio catalog $catalogId ($type): ${e.message}")
        }

        var filtered = parsedList.distinctBy { it.item.id }
        if (genre != "all" && genre.isNotBlank()) {
            filtered = filtered.filter { meta ->
                meta.genres.any { it.contains(genre, ignoreCase = true) }
            }
        }
        if (country != "all" && country.isNotBlank()) {
            filtered = filtered.filter { meta ->
                meta.country.contains(country, ignoreCase = true)
            }
        }

        val sorted = when (sort) {
            "rating", "imdb", "vote_average.desc" -> filtered.sortedByDescending { it.imdbRating }
            "latest", "year", "primary_release_date.desc", "first_air_date.desc" -> filtered.sortedByDescending { it.year }
            else -> filtered.sortedByDescending { it.popularity }
        }

        sorted.map { it.item }
    }

    private suspend fun fetchStremioAddonCatalog(baseUrl: String, type: String, catalogId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<StreamingItem>()
        try {
            val skip = if (page > 1) "/skip=${(page - 1) * 25}" else ""
            val url = "$baseUrl/catalog/$type/$catalogId$skip.json"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrEmpty()) {
                        val json = JSONObject(bodyStr)
                        val metas = json.optJSONArray("metas") ?: JSONArray()
                        for (i in 0 until metas.length()) {
                            val obj = metas.getJSONObject(i)
                            val id = obj.optString("id")
                            val name = obj.optString("name")
                            val poster = obj.optString("poster")
                            val itemType = obj.optString("type")
                            val isSeries = itemType == "series" || itemType == "tv"
                            if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
                                stremioMetaCache[id] = obj
                                val encodedBaseUrl = java.net.URLEncoder.encode(baseUrl, "UTF-8")
                                val r = obj.optString("imdbRating", "").ifEmpty { obj.optString("rating", "") }
                                val rating = if (r.isNotBlank() && r != "0" && r != "0.0") {
                                    val d = r.toDoubleOrNull()
                                    if (d != null && d > 0) String.format(java.util.Locale.US, "%.1f", d) else r
                                } else null
                                list.add(
                                    StreamingItem(
                                        id = "stremio_addon_item:$encodedBaseUrl:$itemType:$id",
                                        title = name,
                                        imageUrl = poster,
                                        rating = rating,
                                        isSeries = isSeries,
                                        isCategory = false,
                                        sourceName = name
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Nuvio", "Error fetching Stremio addon catalog: ${e.message}")
        }
        list.distinctBy { it.id }
    }

    private val categories = listOf(
        "/trending/movie/day" to "Trending Movies",
        "/trending/tv/day" to "Trending TV Shows",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows",
        "/tv/airing_today?region=US" to "Airing Today"
    )

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val tasks = categories.map { (path, title) ->
            async {
                try {
                    val items = fetchCategory(path, 1)
                    if (items.isNotEmpty()) {
                        StreamingCategory(path, title, items)
                    } else null
                } catch (e: Exception) {
                    Log.e("Nuvio", "Failed to load category: $title", e)
                    null
                }
            }
        }
        val categoryList = tasks.awaitAll().filterNotNull().toMutableList()

        // Fetch installed Stremio catalog addons (e.g., Bharat Binge) and add their catalogs to homescreen
        context?.let { ctx ->
            try {
                val installedAddons = com.example.zubflix.stremio.StremioAddonManager.getInstalledAddons(ctx).filter { it.isEnabled }
                val catalogAddons = installedAddons.filter { it.resources.contains("catalog") || it.manifestUrl.contains("bharat-binge", ignoreCase = true) }
                for (addon in catalogAddons) {
                    if (addon.manifestUrl.startsWith("internal://")) continue
                    try {
                        val req = Request.Builder().url(addon.manifestUrl).build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val jsonStr = resp.body?.string()
                                if (!jsonStr.isNullOrEmpty()) {
                                    val jsonObj = JSONObject(jsonStr)
                                    val catalogs = jsonObj.optJSONArray("catalogs") ?: JSONArray()
                                    val baseUrl = addon.manifestUrl.substringBeforeLast("/manifest.json")
                                    for (c in 0 until catalogs.length()) {
                                        val catObj = catalogs.getJSONObject(c)
                                        val catId = catObj.optString("id")
                                        val catType = catObj.optString("type", "movie")
                                        val catName = catObj.optString("name", "Catalog")
                                        if (catId.isNotBlank()) {
                                            val encodedBaseUrl = java.net.URLEncoder.encode(baseUrl, "UTF-8")
                                            val categoryRoute = "stremio_addon:$encodedBaseUrl:$catType:$catId"
                                            val items = fetchStremioAddonCatalog(baseUrl, catType, catId, 1)
                                            if (items.isNotEmpty()) {
                                                val typeLabel = if (catType.equals("series", ignoreCase = true) || catType.equals("tv", ignoreCase = true)) "Series" else "Movie"
                                                val displayTitle = if (catName.contains("movie", ignoreCase = true) || catName.contains("series", ignoreCase = true) || catName.contains("tv", ignoreCase = true)) {
                                                    catName
                                                } else {
                                                    "$catName - $typeLabel"
                                                }
                                                categoryList.add(StreamingCategory(categoryRoute, displayTitle, items))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Nuvio", "Error fetching addon catalog for ${addon.name}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("Nuvio", "Error loading installed catalog addons", e)
            }
        }

        // Add Networks Category before Genre
        val networkItems = com.example.zubflix.model.FilterConfigRepository.getNetworks(context)
            .filter { it.id != "all" }
            .map { net ->
                StreamingItem(
                    id = "network:${net.id}",
                    title = net.name,
                    imageUrl = net.logoUrl ?: "",
                    isSeries = false,
                    isCategory = true
                )
            }
        categoryList.add(StreamingCategory("network_browse", "Networks & Studios", networkItems, hideViewMore = true))
        
        // Add Genre Category at the bottom
        val genreItems = homeGenres.map { (id, name) ->
            StreamingItem(
                id = "genre:$id",
                title = name,
                imageUrl = "",
                isSeries = false,
                isCategory = true
            )
        }
        categoryList.add(StreamingCategory("genre_browse", "Genre", genreItems, hideViewMore = true))

        // Add Region Category at the bottom
        val regionItems = regions.map { (code, name) ->
            StreamingItem(
                id = "region:$code",
                title = name,
                imageUrl = "",
                isSeries = false,
                isCategory = true
            )
        }
        categoryList.add(StreamingCategory("region_browse", "Region", regionItems, hideViewMore = true))
        
        categoryList
    }
    
    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = 
        withContext(Dispatchers.IO) {
            if (categoryId.startsWith("stremio_addon:")) {
                val parts = categoryId.split(":")
                val encodedBaseUrl = parts.getOrNull(1) ?: ""
                val baseUrl = try { java.net.URLDecoder.decode(encodedBaseUrl, "UTF-8") } catch(e: Exception) { encodedBaseUrl }
                val type = parts.getOrNull(2) ?: "movie"
                val catalogId = parts.getOrNull(3) ?: ""
                if (baseUrl.isNotBlank() && catalogId.isNotBlank()) {
                    fetchStremioAddonCatalog(baseUrl, type, catalogId, page)
                } else emptyList()
            } else if (categoryId.startsWith("filter:") || categoryId.startsWith("genre:") || categoryId.startsWith("region:") || categoryId.startsWith("network:")) {
                val filterState = if (categoryId.startsWith("filter:")) {
                    val parts = categoryId.split(":")
                    com.example.zubflix.model.FilterState(
                        type = parts.getOrNull(1) ?: "movie",
                        sortId = parts.getOrNull(2) ?: "popularity.desc",
                        genreId = parts.getOrNull(3) ?: "all",
                        networkId = parts.getOrNull(4) ?: "all",
                        regionCode = parts.getOrNull(5) ?: "all"
                    )
                } else {
                    val parts = categoryId.split(":")
                    val prefix = parts.getOrNull(0) ?: ""
                    val baseId = parts.getOrNull(1) ?: ""
                    val type = parts.getOrNull(2) ?: "movie"
                    val sortBy = parts.getOrNull(3) ?: "popularity.desc"
                    com.example.zubflix.model.FilterState(
                        type = type,
                        sortId = sortBy,
                        genreId = if (prefix == "genre") baseId else "all",
                        networkId = if (prefix == "network") baseId else "all",
                        regionCode = if (prefix == "region") baseId else "all"
                    )
                }

                if (filterState.type == "all") {
                    val movieState = filterState.copy(type = "movie")
                    val tvState = filterState.copy(type = "tv")
                    val movieTask = async {
                        val path = com.example.zubflix.model.TMDBQueryBuilder.buildQueryPath(movieState, context)
                        if (path != null) fetchCategory(path, page) else emptyList()
                    }
                    val tvTask = async {
                        val path = com.example.zubflix.model.TMDBQueryBuilder.buildQueryPath(tvState, context)
                        if (path != null) fetchCategory(path, page) else emptyList()
                    }
                    (movieTask.await() + tvTask.await()).distinctBy { it.id }
                } else {
                    val queryPath = com.example.zubflix.model.TMDBQueryBuilder.buildQueryPath(filterState, context)
                    if (queryPath != null) {
                        fetchCategory(queryPath, page).distinctBy { it.id }
                    } else {
                        emptyList()
                    }
                }
            } else if (categoryId.startsWith("language:")) {
                val parts = categoryId.split(":")
                val langCode = parts.getOrNull(1) ?: ""
                val type = parts.getOrNull(2) ?: "movie"
                val sortBy = parts.getOrNull(3) ?: "popularity.desc"
                val langRegion = when (langCode.lowercase()) {
                    "en" -> "US"
                    "hi" -> "IN"
                    "bn" -> "BD"
                    "ko" -> "KR"
                    "ja" -> "JP"
                    "zh" -> "CN"
                    "es" -> "ES"
                    "fr" -> "FR"
                    "de" -> "DE"
                    "it" -> "IT"
                    "pt" -> "BR"
                    "tr" -> "TR"
                    "th" -> "TH"
                    else -> langCode
                }
                val voteThreshold = FilterSettings.getVoteCountThresholdForRegion(context, langRegion)
                val extraSort = if (voteThreshold > 0) "&vote_count.gte=$voteThreshold" else ""
                val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

                if (type == "movie") {
                    val path = "/discover/movie?with_original_language=$langCode&sort_by=$sortBy$extraSort&primary_release_date.lte=$todayStr"
                    fetchCategory(path, page).distinctBy { it.id }
                } else if (type == "tv") {
                    val path = "/discover/tv?with_original_language=$langCode&sort_by=$sortBy$extraSort&first_air_date.lte=$todayStr"
                    fetchCategory(path, page).distinctBy { it.id }
                } else {
                    val movieTask = async { fetchCategory("/discover/movie?with_original_language=$langCode&sort_by=$sortBy$extraSort&primary_release_date.lte=$todayStr", page) }
                    val tvTask = async { fetchCategory("/discover/tv?with_original_language=$langCode&sort_by=$sortBy$extraSort&first_air_date.lte=$todayStr", page) }
                    (movieTask.await() + tvTask.await()).distinctBy { it.id }
                }
            } else if (categoryId.startsWith("/discover/movie") || categoryId.startsWith("/discover/tv")) {
                fetchCategory(categoryId, page).distinctBy { it.id }
            } else if (categoryId.contains("with_watch_providers=") || categoryId.contains("with_networks=")) {
                val providerId = if (categoryId.contains("with_watch_providers=")) {
                    categoryId.substringAfter("with_watch_providers=").substringBefore("&")
                } else {
                    categoryId.substringAfter("with_networks=").substringBefore("&")
                }
                val watchRegion = "US"
                
                val voteThreshold = FilterSettings.getVoteCountThresholdForRegion(context, "all")
                val extraSort = if (voteThreshold > 0) "&vote_count.gte=$voteThreshold" else ""
                
                val movieTask = async { fetchCategory("/discover/movie?with_watch_providers=$providerId&watch_region=$watchRegion$extraSort", page) }
                val tvTask = async { fetchCategory("/discover/tv?with_watch_providers=$providerId&watch_region=$watchRegion$extraSort", page) }
                
                val movies = movieTask.await()
                val tv = tvTask.await()
                
                (movies + tv).distinctBy { it.id }
            } else if (categoryId.contains("with_origin_country=")) {
                val countryCode = categoryId.substringAfter("with_origin_country=").substringBefore("&")
                
                val voteThreshold = FilterSettings.getVoteCountThresholdForRegion(context, countryCode)
                val extraSort = if (voteThreshold > 0) "&vote_count.gte=$voteThreshold" else ""
                
                val movieTask = async { fetchCategory("/discover/movie?with_origin_country=$countryCode$extraSort", page) }
                val tvTask = async { fetchCategory("/discover/tv?with_origin_country=$countryCode$extraSort", page) }
                
                val movies = movieTask.await()
                val tv = tvTask.await()
                
                (movies + tv)
                    .distinctBy { it.id }
            } else {
                fetchCategory(categoryId, page).distinctBy { it.id }
            }
        }
    
    private fun fetchCategory(path: String, page: Int): List<StreamingItem> {
        return try {
            val apiKey = SourceManager.getTmdbApiKey(com.example.zubflix.SourceManager.getAllSources().firstOrNull()?.let { (it as? com.example.zubflix.sources.CachedSource)?.let { cs -> cs.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(cs) as android.content.Context } } ?: return emptyList())
            val connector = if (path.contains("?")) "&" else "?"
            val url = "$tmdbAPI$path${connector}api_key=$apiKey&include_adult=false&page=$page"
            Log.d("Nuvio", "Fetching category URL: $url")
            
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e("Nuvio", "API Error: ${response.code} - $responseBody")
                return emptyList()
            }
            
            val json = JSONObject(responseBody)
            val results = json.optJSONArray("results") ?: return emptyList()
            
            (0 until results.length()).mapNotNull { i ->
                val item = results.getJSONObject(i)
                val id = item.optInt("id")
                if (id <= 0) return@mapNotNull null
                
                val title = item.optString("title").ifEmpty { item.optString("name") }
                if (title.isNullOrBlank()) return@mapNotNull null

                val posterPath = item.optString("poster_path")
                val backdropPath = item.optString("backdrop_path")
                val backdropUrl = if (!backdropPath.isNullOrBlank() && backdropPath != "null") {
                    "https://image.tmdb.org/t/p/w1280$backdropPath"
                } else null

                val voteAverage = item.optDouble("vote_average", 0.0)
                val ratingStr = if (voteAverage > 0) String.format(java.util.Locale.US, "%.1f", voteAverage) else null

                val overview = item.optString("overview").ifBlank { null }
                val releaseDate = item.optString("release_date").ifEmpty { item.optString("first_air_date") }
                val releaseYear = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null

                val rawMediaType = item.optString("media_type").lowercase()
                val mediaType = if (rawMediaType.isNotEmpty() && rawMediaType != "null") {
                    if (rawMediaType != "movie" && rawMediaType != "tv") return@mapNotNull null
                    rawMediaType
                } else {
                    if (path.contains("/movie")) "movie" else "tv"
                }
                
                StreamingItem(
                    id = "$mediaType:$id",
                    title = title,
                    imageUrl = if (!posterPath.isNullOrBlank() && posterPath != "null") "https://image.tmdb.org/t/p/w500$posterPath" else "",
                    backdropUrl = backdropUrl,
                    description = overview,
                    rating = ratingStr,
                    year = releaseYear,
                    isSeries = mediaType == "tv"
                )
            }
        } catch (e: Exception) {
            Log.e("Nuvio", "Error fetching path: $path", e)
            emptyList()
        }
    }

    private suspend fun fetchGenres(): Map<Int, String> = withContext(Dispatchers.IO) {
        if (genreMap.isNotEmpty()) return@withContext genreMap

        val movieTask = async { fetchGenresFromPath("/genre/movie/list") }
        val tvTask = async { fetchGenresFromPath("/genre/tv/list") }
        
        genreMap.putAll(movieTask.await())
        genreMap.putAll(tvTask.await())
        
        genreMap
    }

    private fun fetchGenresFromPath(path: String): Map<Int, String> {
        return try {
            val url = "$tmdbAPI$path?api_key=$apiKey"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")
            val genres = json.optJSONArray("genres") ?: return emptyMap()
            
            val map = mutableMapOf<Int, String>()
            (0 until genres.length()).forEach { i ->
                val genre = genres.getJSONObject(i)
                map[genre.getInt("id")] = genre.getString("name")
            }
            map
        } catch (e: Exception) {
            Log.e("Nuvio", "Failed to fetch genres from $path", e)
            emptyMap()
        }
    }
    
    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val url = "$tmdbAPI/search/multi?api_key=$apiKey&include_adult=false&query=$query"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")
            
            val results = json.optJSONArray("results") ?: return@withContext emptyList()
            
            (0 until results.length()).mapNotNull { i ->
                val item = results.getJSONObject(i)
                val id = item.optInt("id")
                val title = item.optString("title").ifEmpty { item.optString("name") }
                val posterPath = item.optString("poster_path")
                val mediaType = item.optString("media_type")
                
                if (title.isNotEmpty() && (mediaType == "movie" || mediaType == "tv")) {
                    val releaseDate = item.optString("release_date").ifEmpty { item.optString("first_air_date") }
                    val releaseYear = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                    StreamingItem(
                        id = "$mediaType:$id",
                        title = title,
                        imageUrl = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else "",
                        year = releaseYear,
                        isSeries = mediaType == "tv"
                    )
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        var stremioId = ""
        var type = "movie"
        var baseUrl = ""
        var isStremioAddonId = false

        if (id.startsWith("stremio_addon_item:")) {
            try {
                isStremioAddonId = true
                val parts = id.split(":")
                val encodedBaseUrl = parts.getOrNull(1) ?: ""
                baseUrl = try { java.net.URLDecoder.decode(encodedBaseUrl, "UTF-8") } catch(e: Exception) { encodedBaseUrl }
                type = parts.getOrNull(2) ?: "movie"
                stremioId = parts.getOrNull(3) ?: ""
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (id.startsWith("tt")) {
            stremioId = id
            type = "movie"
        } else if (id.contains(":")) {
            val parts = id.split(":")
            val first = parts[0]
            val second = parts[1]
            if (first == "movie" || first == "tv" || first == "series") {
                type = if (first == "series") "tv" else first
                stremioId = second
            } else if (first == "imdb") {
                stremioId = second
                type = "movie"
            }
        }

        var meta: JSONObject? = null
        if (stremioId.isNotBlank()) {
            val cached = stremioMetaCache[stremioId]
            if (cached != null && (cached.has("description") || cached.has("videos") || cached.has("genres"))) {
                meta = cached
            }
        }

        if (meta == null && isStremioAddonId && baseUrl.isNotBlank() && stremioId.isNotBlank()) {
            try {
                val metaUrl = "$baseUrl/meta/$type/$stremioId.json"
                val req = Request.Builder().url(metaUrl).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val jsonObj = JSONObject(body)
                            meta = jsonObj.optJSONObject("meta")
                            if (meta != null) {
                                stremioMetaCache[stremioId] = meta!!
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Nuvio", "Error fetching stremio addon meta from $baseUrl", e)
            }
        }

        if (meta != null) {
            val title = meta!!.optString("name")
            val poster = meta!!.optString("poster")
            val background = meta!!.optString("background")
            val description = meta!!.optString("description")
            val releaseInfo = meta!!.optString("releaseInfo").ifEmpty { meta!!.optString("year") }
            val year = releaseInfo.substringBefore("-").takeIf { it.isNotEmpty() } ?: "2023"
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")

            val genresArr = meta!!.optJSONArray("genres") ?: meta!!.optJSONArray("genre")
            val genresList = if (genresArr != null) {
                (0 until genresArr.length()).map { genresArr.getString(it) }
            } else null

            val castArr = meta!!.optJSONArray("cast")
            val castList = if (castArr != null) {
                (0 until minOf(castArr.length(), 10)).map { castArr.getString(it) }
            } else null

            val metaType = meta!!.optString("type", type)
            val isSeries = metaType == "series" || metaType == "tv"
            val seasonsList = if (isSeries) {
                val videosArr = meta!!.optJSONArray("videos")
                val seasonsMap = mutableMapOf<Int, MutableList<StreamingEpisode>>()
                if (videosArr != null && videosArr.length() > 1) {
                    for (v in 0 until videosArr.length()) {
                        val vObj = videosArr.getJSONObject(v)
                        val seasonNum = vObj.optInt("season", 1)
                        val episodeNum = vObj.optInt("episode", 1)
                        val epTitle = vObj.optString("title").ifEmpty { "Episode $episodeNum" }
                        val epThumbnail = vObj.optString("thumbnail", poster)

                        val episodes = seasonsMap.getOrPut(seasonNum) { mutableListOf() }
                        episodes.add(
                            StreamingEpisode(
                                title = "S${seasonNum}E${episodeNum} - $epTitle",
                                streamUrl = "nuvio_resolve/series:$stremioId:$seasonNum:$episodeNum:$encodedTitle:$year",
                                stillUrl = epThumbnail,
                                overview = ""
                            )
                        )
                    }
                    seasonsMap.map { (sNum, eps) ->
                        StreamingSeason(
                            title = "Season $sNum",
                            episodes = eps,
                            seasonNumber = sNum
                        )
                    }.sortedBy { it.seasonNumber }
                } else {
                    // Try fetching complete season and episode breakdown from TMDB
                    val tmdbSeasons = context?.let { ctx ->
                        com.example.zubflix.utils.TmdbHelper.fetchTvSeasonsAndEpisodes(
                            ctx,
                            stremioId.takeIf { it.startsWith("tt") },
                            title,
                            year
                        )
                    }

                    if (!tmdbSeasons.isNullOrEmpty()) {
                        tmdbSeasons.map { tmdbSeason ->
                            StreamingSeason(
                                title = tmdbSeason.name,
                                seasonNumber = tmdbSeason.seasonNumber,
                                episodes = tmdbSeason.episodes.map { ep ->
                                    StreamingEpisode(
                                        title = "S${ep.seasonNumber}E${ep.episodeNumber} - ${ep.title}",
                                        streamUrl = "nuvio_resolve/series:$stremioId:${ep.seasonNumber}:${ep.episodeNumber}:$encodedTitle:$year",
                                        stillUrl = ep.stillPath ?: poster,
                                        overview = ep.overview ?: ""
                                    )
                                }
                            )
                        }.sortedBy { it.seasonNumber }
                    } else if (videosArr != null && videosArr.length() == 1) {
                        val vObj = videosArr.getJSONObject(0)
                        val seasonNum = vObj.optInt("season", 1)
                        val episodeNum = vObj.optInt("episode", 1)
                        val epTitle = vObj.optString("title").ifEmpty { "Episode $episodeNum" }
                        val epThumbnail = vObj.optString("thumbnail", poster)

                        listOf(
                            StreamingSeason(
                                title = "Season $seasonNum",
                                seasonNumber = seasonNum,
                                episodes = listOf(
                                    StreamingEpisode(
                                        title = "S${seasonNum}E${episodeNum} - $epTitle",
                                        streamUrl = "nuvio_resolve/series:$stremioId:$seasonNum:$episodeNum:$encodedTitle:$year",
                                        stillUrl = epThumbnail,
                                        overview = ""
                                    )
                                )
                            )
                        )
                    } else {
                        val episodes = mutableListOf<StreamingEpisode>()
                        episodes.add(
                            StreamingEpisode(
                                title = "Episode 1",
                                streamUrl = "nuvio_resolve/series:$stremioId:1:1:$encodedTitle:$year",
                                stillUrl = poster,
                                overview = ""
                            )
                        )
                        listOf(
                            StreamingSeason(
                                title = "Season 1",
                                seasonNumber = 1,
                                episodes = episodes
                            )
                        )
                    }
                }
            } else null

            val resolveUrl = if (isSeries) {
                "nuvio_resolve/series:$stremioId:::$encodedTitle:$year"
            } else {
                "nuvio_resolve/movie:$stremioId::$encodedTitle:$year"
            }

            return@withContext StreamingItem(
                id = id,
                title = title,
                imageUrl = poster,
                backdropUrl = background,
                description = description,
                isSeries = isSeries,
                seasons = seasonsList,
                genres = genresList,
                cast = castList,
                year = year,
                streamUrl = resolveUrl,
                videoSources = mapOf("Stremio Addon" to resolveUrl)
            )
        }

        try {
            val context = this@NuvioSource.context ?: return@withContext null

            val apiKey = SourceManager.getTmdbApiKey(context)
            val useBackdrops = SourceManager.isTmdbBackdropsEnabled(context)
            val usePlot = SourceManager.isTmdbPlotEnabled(context)
            val useCast = SourceManager.isTmdbCastEnabled(context)
            val useRatings = SourceManager.isTmdbRatingsEnabled(context)
            val useGenres = SourceManager.isTmdbGenresEnabled(context)

            var mediaType = ""
            var tmdbId = ""
            var imdbId = ""

            if (id.startsWith("tt")) {
                imdbId = id
            } else if (id.startsWith("tmdb_")) {
                tmdbId = id.removePrefix("tmdb_")
            } else if (id.contains(":")) {
                val parts = id.split(":")
                val first = parts[0]
                val second = parts.getOrNull(1) ?: ""
                val third = parts.getOrNull(2)
                if (first == "movie" || first == "tv" || first == "series") {
                    mediaType = if (first == "series") "tv" else first
                    if (second.startsWith("tt")) {
                        imdbId = second
                    } else {
                        tmdbId = second
                    }
                } else if (first == "tmdb") {
                    if (third != null && third.isNotBlank()) {
                        mediaType = if (second == "series") "tv" else second
                        tmdbId = third
                    } else {
                        tmdbId = second
                    }
                } else if (first == "imdb") {
                    imdbId = second
                }
            } else {
                if (id.all { it.isDigit() }) {
                    tmdbId = id
                }
            }

            if (imdbId.isNotBlank() && tmdbId.isBlank()) {
                try {
                    val findUrl = "$tmdbAPI/find/$imdbId?api_key=$apiKey&external_source=imdb_id"
                    val req = Request.Builder().url(findUrl).build()
                    client.newCall(req).execute().use { res ->
                        if (res.isSuccessful) {
                            val body = res.body?.string()
                            if (!body.isNullOrEmpty()) {
                                val json = JSONObject(body)
                                val movieResults = json.optJSONArray("movie_results")
                                val tvResults = json.optJSONArray("tv_results")
                                if (movieResults != null && movieResults.length() > 0) {
                                    val movie = movieResults.getJSONObject(0)
                                    tmdbId = movie.optString("id")
                                    mediaType = "movie"
                                } else if (tvResults != null && tvResults.length() > 0) {
                                    val tv = tvResults.getJSONObject(0)
                                    tmdbId = tv.optString("id")
                                    mediaType = "tv"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Nuvio", "Error finding TMDB ID for IMDb ID: $imdbId", e)
                }
            }

            if (tmdbId.isBlank()) {
                val name = if (stremioId.isNotBlank()) stremioId else id
                val isSeries = type == "series" || type == "tv"
                val resolveUrl = if (isSeries) {
                    "nuvio_resolve/series:$stremioId:::$name:2023"
                } else {
                    "nuvio_resolve/movie:$stremioId::$name:2023"
                }
                return@withContext StreamingItem(
                    id = id,
                    title = name,
                    imageUrl = "",
                    isSeries = isSeries,
                    year = "2023",
                    streamUrl = resolveUrl,
                    videoSources = mapOf("Stremio Addon" to resolveUrl)
                )
            }

            if (mediaType.isBlank()) {
                mediaType = "movie"
            }

            val url = "$tmdbAPI/$mediaType/$tmdbId?api_key=$apiKey&append_to_response=external_ids,credits"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "")

            val title = json.optString("title").ifEmpty { json.optString("name") }

            val posterPath = if (useBackdrops) json.optString("poster_path") else ""
            val overview = if (usePlot) json.optString("overview") else ""
            val rating = if (useRatings) "${String.format("%.1f", json.optDouble("vote_average"))}" else null

            val genresList = if (useGenres) {
                val gArr = json.optJSONArray("genres")
                if (gArr != null && gArr.length() > 0) {
                    (0 until gArr.length()).map { gArr.getJSONObject(it).getString("name") }
                } else null
            } else null

            val castList = if (useCast) {
                val credits = json.optJSONObject("credits")
                val cArr = credits?.optJSONArray("cast")
                if (cArr != null) {
                    (0 until minOf(cArr.length(), 10)).map { cArr.getJSONObject(it).getString("name") }
                } else null
            } else null

            val releaseDate = if (mediaType == "movie") json.optString("release_date") else json.optString("first_air_date")
            val year = releaseDate.substringBefore("-").takeIf { it.isNotEmpty() } ?: "0"
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            val rawImdb = json.optString("imdb_id", "")
            if (imdbId.isBlank()) {
                imdbId = if (rawImdb.isNotEmpty() && rawImdb != "null") {
                    rawImdb
                } else {
                    json.optJSONObject("external_ids")?.optString("imdb_id") ?: ""
                }.trim()
            }

            val seasonsList = if (mediaType == "tv") {
                val seasonsArr = json.optJSONArray("seasons")
                if (seasonsArr != null) {
                    val list = mutableListOf<StreamingSeason>()
                    for (i in 0 until seasonsArr.length()) {
                        val sObj = seasonsArr.getJSONObject(i)
                        val sNum = sObj.optInt("season_number")
                        if (sNum <= 0) continue
                        val sName = sObj.optString("name").ifEmpty { "Season $sNum" }
                        list.add(StreamingSeason(title = sName, episodes = emptyList(), seasonNumber = sNum))
                    }
                    list.sortBy { it.seasonNumber }

                    if (list.isNotEmpty()) {
                        val firstSeason = list.first()
                        try {
                            val firstEpisodes = getSeasonEpisodes(id, firstSeason.seasonNumber)
                            list[0] = firstSeason.copy(episodes = firstEpisodes)
                        } catch (e: Exception) {
                            Log.e("Nuvio", "Error eagerly fetching season episodes", e)
                        }
                    }
                    list
                } else null
            } else null

            val resolveId = if (imdbId.isNotBlank()) imdbId else tmdbId
            StreamingItem(
                id = id,
                title = title,
                imageUrl = if (posterPath.isNotEmpty() && posterPath != "null") "https://image.tmdb.org/t/p/original$posterPath" else "",
                description = overview,
                isSeries = mediaType == "tv",
                seasons = seasonsList,
                rating = rating,
                genres = genresList,
                cast = castList,
                year = if (year != "0") year else null,
                streamUrl = if (mediaType == "movie") "nuvio_resolve/movie:$tmdbId:$resolveId:$encodedTitle:$year" else null,
                videoSources = if (mediaType == "movie") mapOf("Nuvio" to "nuvio_resolve/movie:$tmdbId:$resolveId:$encodedTitle:$year") else null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun getSeasonEpisodes(itemId: String, seasonNumber: Int): List<StreamingEpisode> = withContext(Dispatchers.IO) {
        try {
            var stremioIdCandidate = itemId
            if (itemId.startsWith("stremio_addon_item:")) {
                val parts = itemId.split(":")
                stremioIdCandidate = parts.getOrNull(3) ?: ""
            }

            val meta = stremioMetaCache[stremioIdCandidate]
            if (meta != null) {
                val videosArr = meta.optJSONArray("videos")
                if (videosArr != null && videosArr.length() > 0) {
                    val epList = mutableListOf<StreamingEpisode>()
                    val poster = meta.optString("poster")
                    val releaseInfo = meta.optString("releaseInfo").ifEmpty { meta.optString("year") }
                    val year = releaseInfo.substringBefore("-").takeIf { it.isNotEmpty() } ?: "2023"
                    val title = meta.optString("name")
                    val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")

                    for (v in 0 until videosArr.length()) {
                        val vObj = videosArr.getJSONObject(v)
                        val sNum = vObj.optInt("season", 1)
                        if (sNum == seasonNumber) {
                            val episodeNum = vObj.optInt("episode", 1)
                            val epTitle = vObj.optString("title").ifEmpty { "Episode $episodeNum" }
                            val epThumbnail = vObj.optString("thumbnail", poster)
                            epList.add(
                                StreamingEpisode(
                                    title = "S${sNum}E${episodeNum} - $epTitle",
                                    streamUrl = "nuvio_resolve/series:$stremioIdCandidate:$sNum:$episodeNum:$encodedTitle:$year",
                                    stillUrl = epThumbnail,
                                    overview = ""
                                )
                            )
                        }
                    }
                    if (epList.isNotEmpty()) {
                        return@withContext epList
                    }
                }
            }

            var mediaType = ""
            var tmdbId = ""
            var imdbId = ""

            if (itemId.startsWith("tt")) {
                imdbId = itemId
                mediaType = "tv"
            } else if (itemId.startsWith("tmdb_")) {
                val clean = itemId.removePrefix("tmdb_")
                if (clean.startsWith("tv_") || clean.startsWith("series_")) {
                    tmdbId = clean.substringAfter("_")
                    mediaType = "tv"
                } else if (clean.startsWith("movie_")) {
                    tmdbId = clean.substringAfter("_")
                    mediaType = "movie"
                } else {
                    tmdbId = clean
                    mediaType = "tv"
                }
            } else if (itemId.contains(":")) {
                val parts = itemId.split(":")
                val first = parts[0]
                val second = parts.getOrNull(1) ?: ""
                val third = parts.getOrNull(2)
                if (first == "movie" || first == "tv" || first == "series") {
                    mediaType = if (first == "series") "tv" else first
                    if (second.startsWith("tt")) {
                        imdbId = second
                    } else {
                        tmdbId = second
                    }
                } else if (first == "tmdb") {
                    if (third != null && third.isNotBlank()) {
                        mediaType = if (second == "series") "tv" else second
                        tmdbId = third
                    } else {
                        tmdbId = second
                        mediaType = "tv"
                    }
                } else if (first == "imdb") {
                    imdbId = second
                    mediaType = "tv"
                }
            } else if (itemId.all { it.isDigit() }) {
                tmdbId = itemId
                mediaType = "tv"
            }

            val context = this@NuvioSource.context ?: return@withContext emptyList()
            val apiKey = SourceManager.getTmdbApiKey(context)
            val useBackdrops = SourceManager.isTmdbBackdropsEnabled(context)

            if (imdbId.isNotBlank() && tmdbId.isBlank()) {
                try {
                    val findUrl = "$tmdbAPI/find/$imdbId?api_key=$apiKey&external_source=imdb_id"
                    val req = Request.Builder().url(findUrl).build()
                    client.newCall(req).execute().use { res ->
                        if (res.isSuccessful) {
                            val body = res.body?.string()
                            if (!body.isNullOrEmpty()) {
                                val json = JSONObject(body)
                                val tvResults = json.optJSONArray("tv_results")
                                if (tvResults != null && tvResults.length() > 0) {
                                    val tv = tvResults.getJSONObject(0)
                                    tmdbId = tv.optString("id")
                                    mediaType = "tv"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Nuvio", "Error finding TMDB ID in getSeasonEpisodes", e)
                }
            }

            if (tmdbId.isBlank() || mediaType != "tv") return@withContext emptyList()

            val url = "$tmdbAPI/tv/$tmdbId?api_key=$apiKey&append_to_response=external_ids"
            val req = Request.Builder().url(url).build()
            val res = client.newCall(req).execute()
            val json = JSONObject(res.body?.string() ?: "")
            if (imdbId.isBlank()) {
                imdbId = json.optJSONObject("external_ids")?.optString("imdb_id") ?: ""
            }
            val seriesTitle = json.optString("name")
            val releaseDate = json.optString("first_air_date")
            val seriesYear = releaseDate.substringBefore("-").takeIf { it.isNotEmpty() } ?: "0"
            val encodedTitle = java.net.URLEncoder.encode(seriesTitle, "UTF-8")

            val sUrl = "$tmdbAPI/tv/$tmdbId/season/$seasonNumber?api_key=$apiKey"
            val sReq = Request.Builder().url(sUrl).build()
            val sRes = client.newCall(sReq).execute()
            val sJson = JSONObject(sRes.body?.string() ?: "")
            val epsJson = sJson.optJSONArray("episodes") ?: return@withContext emptyList()

            (0 until epsJson.length()).map { k ->
                val ep = epsJson.getJSONObject(k)
                val epNum = ep.optInt("episode_number")
                val epName = ep.optString("name")
                val epOverview = ep.optString("overview")
                val epAirDate = ep.optString("air_date").takeIf { it.isNotEmpty() }
                val epVoteAverage = if (ep.has("vote_average") && ep.optDouble("vote_average") > 0) ep.optDouble("vote_average") else null
                val epRuntime = if (ep.has("runtime") && ep.optInt("runtime") > 0) ep.optInt("runtime") else null
                StreamingEpisode(
                    title = "S${seasonNumber}E${epNum} - $epName",
                    streamUrl = "nuvio_resolve/series:$tmdbId:$imdbId:$seasonNumber:$epNum:$encodedTitle:$seriesYear",
                    stillUrl = if (useBackdrops) ep.optString("still_path")?.let { "https://image.tmdb.org/t/p/w500$it" } else null,
                    overview = epOverview,
                    airDate = epAirDate,
                    voteAverage = epVoteAverage,
                    runtime = epRuntime
                )
            }
        } catch (e: Exception) {
            Log.e("Nuvio", "Error fetching season $seasonNumber", e)
            emptyList()
        }
    }
    
    private fun formatStreamDisplayName(addonName: String, rawName: String, rawTitle: String): String {
        val lines = rawTitle.split("\n").filter { it.isNotBlank() }
        
        var torrentName = if (lines.isNotEmpty()) {
            lines[0].trim()
        } else {
            ""
        }
        
        if (torrentName.isBlank()) {
            torrentName = rawName.trim()
        }

        // Clean up the torrent name
        torrentName = torrentName
            .replace(Regex("(?<!\\b\\d)\\.(?!\\d\\b)"), " ") // replace dots with spaces unless it's a decimal number like 5.1
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val metadataList = mutableListOf<String>()
        val fullTextToAnalyze = "$rawName $rawTitle"
        
        // Resolution badge detection
        val resolution = when {
            fullTextToAnalyze.contains("4k", ignoreCase = true) || fullTextToAnalyze.contains("2160p", ignoreCase = true) || fullTextToAnalyze.contains("uhd", ignoreCase = true) -> "4K"
            fullTextToAnalyze.contains("1080p", ignoreCase = true) || fullTextToAnalyze.contains("fhd", ignoreCase = true) -> "1080p"
            fullTextToAnalyze.contains("720p", ignoreCase = true) || fullTextToAnalyze.contains("hd", ignoreCase = true) -> "720p"
            fullTextToAnalyze.contains("480p", ignoreCase = true) || fullTextToAnalyze.contains("sd", ignoreCase = true) -> "480p"
            else -> null
        }
        resolution?.let { metadataList.add(it) }
        
        // Audio codecs / Channels detection
        val audio = when {
            fullTextToAnalyze.contains("atmos", ignoreCase = true) -> "Atmos"
            fullTextToAnalyze.contains("ddp5.1", ignoreCase = true) || fullTextToAnalyze.contains("dd5.1", ignoreCase = true) || fullTextToAnalyze.contains("dd 5.1", ignoreCase = true) || fullTextToAnalyze.contains("5.1", ignoreCase = true) -> "DD 5.1"
            fullTextToAnalyze.contains("dts-hd", ignoreCase = true) || fullTextToAnalyze.contains("dts hd", ignoreCase = true) -> "DTS-HD"
            fullTextToAnalyze.contains("dts", ignoreCase = true) -> "DTS"
            fullTextToAnalyze.contains("aac", ignoreCase = true) -> "AAC"
            else -> null
        }
        audio?.let { metadataList.add(it) }

        // Quality / Release Source
        val quality = when {
            fullTextToAnalyze.contains("bluray", ignoreCase = true) || fullTextToAnalyze.contains("bdrip", ignoreCase = true) -> "BluRay"
            fullTextToAnalyze.contains("webrip", ignoreCase = true) || fullTextToAnalyze.contains("web-dl", ignoreCase = true) || fullTextToAnalyze.contains("webdl", ignoreCase = true) -> "WEBRip"
            fullTextToAnalyze.contains("hdr10+", ignoreCase = true) || fullTextToAnalyze.contains("hdr10", ignoreCase = true) -> "HDR10"
            fullTextToAnalyze.contains("hdr", ignoreCase = true) -> "HDR"
            fullTextToAnalyze.contains("dolby vision", ignoreCase = true) || fullTextToAnalyze.contains("dv", ignoreCase = true) -> "DV"
            fullTextToAnalyze.contains("hdtv", ignoreCase = true) -> "HDTV"
            fullTextToAnalyze.contains("cam", ignoreCase = true) || fullTextToAnalyze.contains("tc", ignoreCase = true) || fullTextToAnalyze.contains("ts", ignoreCase = true) -> "CAM"
            else -> null
        }
        quality?.let { metadataList.add(it) }

        // Codecs
        val codec = when {
            fullTextToAnalyze.contains("hevc", ignoreCase = true) || fullTextToAnalyze.contains("x265", ignoreCase = true) || fullTextToAnalyze.contains("h265", ignoreCase = true) -> "HEVC"
            fullTextToAnalyze.contains("x264", ignoreCase = true) || fullTextToAnalyze.contains("h264", ignoreCase = true) -> "x264"
            else -> null
        }
        codec?.let { metadataList.add(it) }

        // File size detection
        val sizeRegex = Regex("(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:GB|MB)\\b")
        val match = sizeRegex.find(rawTitle)
        val fileSize = match?.value?.uppercase()
        fileSize?.let { metadataList.add(it) }
        
        // Language detection
        val langMap = mapOf("Português" to "PT", "Inglês" to "EN", "Espanhol" to "ES")
        val detectedLanguages = mutableListOf<String>()
        rawTitle.split("\n").forEach { line ->
            if (line.contains("🌎") || line.contains("Português", ignoreCase = true) || line.contains("Inglês", ignoreCase = true)) {
                langMap.forEach { (key, value) ->
                    if (line.contains(key, ignoreCase = true)) {
                        detectedLanguages.add(value)
                    }
                }
            }
        }
        val langString = if (detectedLanguages.isNotEmpty()) {
            val languages = detectedLanguages.distinct().joinToString("/")
            com.example.zubflix.util.DebugLogger.d("Nuvio", "Detected languages for $torrentName: $languages")
            " [$languages]"
        } else {
            ""
        }

        // Release group or server host moniker (e.g. [RD+], [Torrent])
        val hostMoniker = if (rawName.isNotBlank()) {
            rawName.trim().replace(Regex("[\\[\\]]"), "")
        } else {
            ""
        }
        
        val monikerText = if (hostMoniker.isNotEmpty()) " [$hostMoniker]" else ""

        val metadataString = if (metadataList.isNotEmpty()) {
            " (" + metadataList.distinct().joinToString(" | ") + ")"
        } else {
            ""
        }

        return "[$addonName]$monikerText $torrentName$langString$metadataString"
    }

    private suspend fun fetchStreamsFromUrl(url: String, defaultName: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<String, String>>()
        if (url.contains("127.0.0.1") || url.contains("localhost")) {
            return@withContext emptyList()
        }
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build()
            
            val response = addonClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                Log.e("Nuvio", "Failed to fetch streams from $url: ${response.code}. Response: $responseBody")
                return@withContext emptyList()
            }
            
            val json = JSONObject(responseBody)
            val streams = json.optJSONArray("streams") ?: run {
                Log.e("Nuvio", "No streams found in response: $responseBody")
                return@withContext emptyList()
            }
            
            for (i in 0 until streams.length()) {
                val stream = streams.getJSONObject(i)
                val streamUrl = stream.optString("url")
                
                if (streamUrl.isNotEmpty() && streamUrl != "null") {
                    val rawName = stream.optString("name", "")
                    var rawTitle = stream.optString("title", "")
                    if (rawTitle.isEmpty()) {
                        rawTitle = stream.optString("description", "")
                    }
                    
                    val displayName = formatStreamDisplayName(defaultName, rawName, rawTitle)
                    
                    // Check if there are subtitles
                    val subtitles = stream.optJSONArray("subtitles")
                    var subUrl = ""
                    if (subtitles != null && subtitles.length() > 0) {
                        if (subtitles.length() == 1) {
                            subUrl = subtitles.getJSONObject(0).optString("url", "")
                        } else {
                            subUrl = subtitles.toString()
                        }
                    } else {
                        subUrl = stream.optString("subtitle", stream.optString("sub_url", ""))
                    }

                    // Extract behaviorHints headers if any
                    var headersJson = ""
                    try {
                        val bh = stream.optJSONObject("behaviorHints")
                        val headersMap = mutableMapOf<String, String>()
                        
                        // Try proxyHeaders.request (standard for some addons)
                        val proxyHeaders = bh?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                        if (proxyHeaders != null) {
                            val keys = proxyHeaders.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                headersMap[key] = proxyHeaders.optString(key)
                            }
                        }
                        
                        // Try direct headers (some addons use this)
                        val httpHeaders = bh?.optJSONObject("headers")
                        if (httpHeaders != null) {
                            val keys = httpHeaders.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                headersMap[key] = httpHeaders.optString(key)
                            }
                        }
                        
                        if (headersMap.isNotEmpty()) {
                            headersJson = com.google.gson.Gson().toJson(headersMap)
                        }
                    } catch (e: Exception) {}

                    val finalUrl = if (subUrl.isNotEmpty() || headersJson.isNotEmpty()) {
                        "$streamUrl###$subUrl###$headersJson"
                    } else {
                        streamUrl
                    }
                    
                    results.add(Pair(displayName, finalUrl))
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w("Nuvio", "Timeout fetching streams from $url: ${e.message}")
        } catch (e: java.io.IOException) {
            Log.w("Nuvio", "Network error fetching streams from $url: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("Nuvio", "Error fetching streams from $url: ${e.message}")
        }
        results
    }
    

    private data class NuvioResolveParams(
        val type: String,
        val tmdbId: String,
        val imdbId: String,
        val primaryId: String,
        val contentTitle: String,
        val contentYear: Int?,
        val season: Int?,
        val episode: Int?
    )

    private fun parseNuvioResolveData(data: String): NuvioResolveParams? {
        val cleanData = data.removePrefix("nuvio_resolve/").trim()
        val parts = cleanData.split(":")
        if (parts.isEmpty()) return null
        val rawType = parts[0]
        val type = if (rawType == "tv") "series" else rawType
        if (type != "movie" && type != "series") return null

        var tmdbId = ""
        var imdbId = ""
        var season: Int? = null
        var episode: Int? = null
        var contentTitle = ""
        var contentYear: Int? = null

        if (type == "movie") {
            if (parts.size >= 5) {
                tmdbId = parts[1]
                imdbId = parts[2]
                contentTitle = try { java.net.URLDecoder.decode(parts[3], "UTF-8") } catch(e: Exception) { parts[3] }
                contentYear = parts[4].toIntOrNull()
            } else if (parts.size >= 4) {
                if (parts[1].startsWith("tt")) {
                    imdbId = parts[1]
                } else {
                    tmdbId = parts[1]
                }
                contentTitle = try { java.net.URLDecoder.decode(parts[2], "UTF-8") } catch(e: Exception) { parts[2] }
                contentYear = parts[3].toIntOrNull()
            } else if (parts.size >= 2) {
                if (parts[1].startsWith("tt")) imdbId = parts[1] else tmdbId = parts[1]
            }
        } else { // series
            if (parts.size >= 7) {
                tmdbId = parts[1]
                imdbId = parts[2]
                season = parts[3].toIntOrNull()
                episode = parts[4].toIntOrNull()
                contentTitle = try { java.net.URLDecoder.decode(parts[5], "UTF-8") } catch(e: Exception) { parts[5] }
                contentYear = parts[6].toIntOrNull()
            } else if (parts.size >= 6) {
                if (parts[1].startsWith("tt")) {
                    imdbId = parts[1]
                } else {
                    tmdbId = parts[1]
                }
                season = parts[2].toIntOrNull()
                episode = parts[3].toIntOrNull()
                contentTitle = try { java.net.URLDecoder.decode(parts[4], "UTF-8") } catch(e: Exception) { parts[4] }
                contentYear = parts[5].toIntOrNull()
            } else if (parts.size >= 4) {
                if (parts[1].startsWith("tt")) imdbId = parts[1] else tmdbId = parts[1]
                season = parts[2].toIntOrNull()
                episode = parts[3].toIntOrNull()
            }
        }

        val primaryId = if (tmdbId.isNotBlank()) tmdbId else if (imdbId.isNotBlank()) imdbId else contentTitle

        return NuvioResolveParams(
            type = type,
            tmdbId = tmdbId,
            imdbId = imdbId,
            primaryId = primaryId,
            contentTitle = contentTitle,
            contentYear = contentYear,
            season = season,
            episode = episode
        )
    }

    private fun resolveImdbIdIfNeeded(tmdbId: String, type: String, currentImdbId: String): String {
        if (currentImdbId.startsWith("tt")) return currentImdbId
        if (tmdbId.isBlank()) return currentImdbId
        return try {
            val tmdbMediaType = if (type == "series" || type == "tv") "tv" else "movie"
            val apiKeyToUse = context?.let { SourceManager.getTmdbApiKey(it) } ?: apiKey
            val extUrl = "$tmdbAPI/$tmdbMediaType/$tmdbId/external_ids?api_key=$apiKeyToUse"
            val req = Request.Builder().url(extUrl).build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val bodyStr = res.body?.string()
                    if (!bodyStr.isNullOrEmpty()) {
                        val json = JSONObject(bodyStr)
                        val foundImdb = json.optString("imdb_id", "").trim()
                        if (foundImdb.startsWith("tt")) return foundImdb
                    }
                }
            }
            currentImdbId
        } catch (e: Exception) {
            currentImdbId
        }
    }

    suspend fun extractVideoLinksStreaming(data: String, onProgress: suspend (done: Int, total: Int) -> Unit, onStreamFound: suspend (streams: Map<String, String>) -> Unit) = withContext(Dispatchers.IO) {
        try {
            Log.d("NuvioSource", "📥 Received extractVideoLinksStreaming call for data='$data'")
            val params = parseNuvioResolveData(data)
            if (params == null) {
                Log.e("NuvioSource", "❌ parseNuvioResolveData failed for input: '$data'")
                return@withContext
            }
            val type = params.type
            val primaryId = params.primaryId
            val rawImdbId = params.imdbId
            val tmdbId = params.tmdbId
            val contentTitle = params.contentTitle
            val contentYear = params.contentYear
            val season = params.season
            val episode = params.episode

            val resolvedImdbId = resolveImdbIdIfNeeded(tmdbId, type, rawImdbId)
            val imdbId = if (resolvedImdbId.startsWith("tt")) resolvedImdbId else rawImdbId

            Log.d("NuvioSource", "🚀 Starting extractVideoLinksStreaming: title='$contentTitle', tmdbId='$tmdbId', imdbId='$imdbId', type='$type', S=$season, E=$episode")

            val stremioId = if (imdbId.startsWith("tt")) imdbId else if (tmdbId.isNotBlank()) tmdbId else primaryId

            val addonEndpoints = mutableListOf<Pair<String, String>>()
            var bdixEnabled = true
            var activeLocalScrapers = emptyList<com.example.zubflix.bdix.LocalScraper>()
            var activeCloudStreamPlugins = emptyList<com.example.zubflix.cloudstream.InstalledCloudStreamPlugin>()
            context?.let { ctx ->
                try {
                    if (bdixEnabled) {
                        activeLocalScrapers = com.example.zubflix.bdix.LocalScraperManager.getOrderedEnabledScrapers(ctx)
                    }
                    activeCloudStreamPlugins = com.example.zubflix.cloudstream.CloudStreamManager.getActiveScrapers(ctx)
                    Log.d("NuvioSource", "Active local scrapers: ${activeLocalScrapers.size}, CloudStream plugins: ${activeCloudStreamPlugins.size}")
                    val addons = com.example.zubflix.stremio.StremioAddonManager.getInstalledAddons(ctx).filter { it.isEnabled }
                    val streamAddons = addons.filter { 
                        it.resources.contains("stream") || it.manifestUrl.contains("cncverse", ignoreCase = true) || it.manifestUrl.contains("pengu", ignoreCase = true) || it.manifestUrl.contains("stream", ignoreCase = true) || it.name.contains("stream", ignoreCase = true) || it.name.contains("bridge", ignoreCase = true)
                    }
                    for (addon in streamAddons) {
                        if (addon.manifestUrl.startsWith("internal://")) continue
                        val baseUrl = addon.manifestUrl.substringBeforeLast("/manifest.json")
                        val addonEndpoint = if (type == "movie") {
                            if (stremioId.isNotBlank()) "$baseUrl/stream/movie/$stremioId.json" else null
                        } else {
                            if (stremioId.isNotBlank() && season != null && episode != null) {
                                "$baseUrl/stream/series/$stremioId:$season:$episode.json"
                            } else null
                        }
                        if (addonEndpoint != null) addonEndpoints.add(Pair(addonEndpoint, addon.name))
                    }
                } catch (e: Exception) {
                    Log.e("NuvioSource", "Error checking plugins/addons: ${e.message}", e)
                }
            }

            val totalSources = addonEndpoints.size + activeCloudStreamPlugins.size + (if (bdixEnabled) activeLocalScrapers.size else 0)
            var doneSources = 0
            onProgress(doneSources, totalSources)
            val uniqueNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            suspend fun processStreams(streams: List<Pair<String, String>>) {
                if (streams.isNotEmpty()) {
                    val batch = LinkedHashMap<String, String>()
                    for ((name, url) in streams) {
                        var uniqueName = name
                        var duplicateCount = 1
                        while (!uniqueNames.add(uniqueName)) {
                            uniqueName = "$name ($duplicateCount)"
                            duplicateCount++
                        }
                        batch[uniqueName] = url
                    }
                    onStreamFound(batch)
                }
            }

            kotlinx.coroutines.supervisorScope {
                val scraperSemaphore = kotlinx.coroutines.sync.Semaphore(4)
                val deferredList = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                if (bdixEnabled && context != null) {
                    for (scraper in activeLocalScrapers) {
                        deferredList.add(async<Unit> {
                            scraperSemaphore.withPermit {
                                try {
                                    val queryTitle = if (contentTitle.isNotBlank()) contentTitle else primaryId
                                    val meta = com.example.zubflix.bdix.BDIXScraper.MediaMeta(
                                        name = queryTitle,
                                        year = contentYear
                                    )
                                    Log.d("Nuvio", "Executing local scraper ${scraper.name} (${scraper.id}) for $queryTitle")
                                    val scraperStreams = kotlinx.coroutines.withTimeoutOrNull(20000) {
                                        scraper.getStreams(type, meta, season, episode)
                                    } ?: emptyList()
                                    val validStreams = scraperStreams.filter { item ->
                                        val itemTitle = item.mediaTitle.ifBlank { item.title }
                                        itemTitle.isBlank() || com.example.zubflix.bdix.BDIXUtils.titlesMatch(itemTitle, queryTitle)
                                    }
                                    processStreams(validStreams.map {
                                        Pair(formatLocalStreamDisplayName(it, "[Local Scraper]"), it.url)
                                    })
                                } catch (t: Throwable) {
                                    if (t is kotlinx.coroutines.CancellationException) throw t
                                    Log.e("Nuvio", "Error executing local scraper ${scraper.id}: ${t.message}")
                                } finally {
                                    synchronized(this@NuvioSource) { doneSources++ }
                                    onProgress(doneSources, totalSources)
                                }
                            }
                        })
                    }
                }
                if (context != null) {
                    for (csPlugin in activeCloudStreamPlugins) {
                        deferredList.add(async<Unit> {
                            scraperSemaphore.withPermit {
                                try {
                                    Log.d("Nuvio", "Executing CloudStream plugin ${csPlugin.name} (${csPlugin.id})")
                                    val csStreams = kotlinx.coroutines.withTimeoutOrNull(15000) {
                                        com.example.zubflix.cloudstream.CloudStreamDexLoader.searchAndFetchStreams(
                                            context = context,
                                            plugin = csPlugin,
                                            queryTitle = if (contentTitle.isNotBlank()) contentTitle else primaryId,
                                            isSeries = (type == "tv" || type == "series"),
                                            season = season,
                                            episode = episode
                                        )
                                    } ?: emptyList()
                                    processStreams(csStreams.map {
                                        Pair(formatLocalStreamDisplayName(it, "[CloudStream Extension]"), it.url)
                                    })
                                } catch (t: Throwable) {
                                    if (t is kotlinx.coroutines.CancellationException) throw t
                                    Log.e("Nuvio", "Error executing CloudStream plugin ${csPlugin.id}: ${t.message}")
                                } finally {
                                    synchronized(this@NuvioSource) { doneSources++ }
                                    onProgress(doneSources, totalSources)
                                }
                            }
                        })
                    }
                }
                for ((endpoint, name) in addonEndpoints) {
                    deferredList.add(async<Unit> {
                        try {
                            val streams = kotlinx.coroutines.withTimeoutOrNull(20000) { fetchStreamsFromUrl(endpoint, name) } ?: emptyList()
                            processStreams(streams)
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) throw t
                            Log.e("Nuvio", "Error fetching addon stream $name ($endpoint): ${t.message}")
                        } finally {
                            synchronized(this@NuvioSource) { doneSources++ }
                            onProgress(doneSources, totalSources)
                        }
                    })
                }
                deferredList.forEach {
                    try {
                        it.await()
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.e("Nuvio", "Deferred await error: ${t.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("Nuvio", "Error extracting video links streaming", e)
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val videoLinks = LinkedHashMap<String, String>() // Maintain insertion order
        try {
            val params = parseNuvioResolveData(data) ?: return@withContext emptyMap()
            val type = params.type
            val primaryId = params.primaryId
            val rawImdbId = params.imdbId
            val tmdbId = params.tmdbId
            val contentTitle = params.contentTitle
            val contentYear = params.contentYear
            val season = params.season
            val episode = params.episode

            val resolvedImdbId = resolveImdbIdIfNeeded(tmdbId, type, rawImdbId)
            val imdbId = if (resolvedImdbId.startsWith("tt")) resolvedImdbId else rawImdbId

            val stremioId = if (imdbId.startsWith("tt")) imdbId else if (tmdbId.isNotBlank()) tmdbId else primaryId
            
            // Collect endpoints from installed Stremio stream addons
            val addonEndpoints = mutableListOf<Pair<String, String>>()
            var bdixEnabled = true
            context?.let { ctx ->
                try {
                    val addons = com.example.zubflix.stremio.StremioAddonManager.getInstalledAddons(ctx).filter { it.isEnabled }
                    val streamAddons = addons.filter { 
                        it.resources.contains("stream") || it.manifestUrl.contains("cncverse", ignoreCase = true) || it.manifestUrl.contains("pengu", ignoreCase = true) || it.manifestUrl.contains("stream", ignoreCase = true) || it.name.contains("stream", ignoreCase = true) || it.name.contains("bridge", ignoreCase = true)
                    }
                    for (addon in streamAddons) {
                        if (addon.manifestUrl.startsWith("internal://")) continue
                        val baseUrl = addon.manifestUrl.substringBeforeLast("/manifest.json")
                        val addonEndpoint = if (type == "movie") {
                            if (stremioId.isNotBlank()) "$baseUrl/stream/movie/$stremioId.json" else null
                        } else {
                            if (stremioId.isNotBlank() && season != null && episode != null) {
                                "$baseUrl/stream/series/$stremioId:$season:$episode.json"
                            } else null
                        }
                        if (addonEndpoint != null) addonEndpoints.add(Pair(addonEndpoint, addon.name))
                    }
                } catch (e: Exception) {
                    Log.e("Nuvio", "Error loading Stremio stream addons", e)
                }
            }
            
            // Parallel fetches
            val allFetches = coroutineScope {
                val deferredList = mutableListOf<kotlinx.coroutines.Deferred<List<Pair<String, String>>>>()
                
                if (bdixEnabled) {
                    deferredList.add(async {
                        val queryTitle = if (contentTitle.isNotBlank()) contentTitle else primaryId
                        Log.d("Nuvio", "Fetching from Nuvio JS Plugins: $queryTitle ($primaryId)")
                        val bdixStreams = com.example.zubflix.bdix.BDIXScraper.getStreams(type, queryTitle, primaryId, contentYear, season, episode, context)
                        bdixStreams.map {
                            Pair(formatLocalStreamDisplayName(it, "[Local Scraper]"), it.url)
                        }
                    })
                }

                context?.let { ctx ->
                    val activeCSPlugins = com.example.zubflix.cloudstream.CloudStreamManager.getActiveScrapers(ctx)
                    for (csPlugin in activeCSPlugins) {
                        deferredList.add(async {
                            val csStreams = com.example.zubflix.cloudstream.CloudStreamDexLoader.searchAndFetchStreams(
                                context = ctx,
                                plugin = csPlugin,
                                queryTitle = if (contentTitle.isNotBlank()) contentTitle else primaryId,
                                isSeries = (type == "tv" || type == "series"),
                                season = season,
                                episode = episode
                            )
                            csStreams.map {
                                Pair(formatLocalStreamDisplayName(it, "[CloudStream Extension]"), it.url)
                            }
                        })
                    }
                }
                
                // Addon fetches
                for ((endpoint, name) in addonEndpoints) {
                    deferredList.add(async { 
                        Log.d("Nuvio", "Fetching from $name at $endpoint")
                        withTimeoutOrNull(20000) {
                            fetchStreamsFromUrl(endpoint, name)
                        } ?: emptyList()
                    })
                }
                
                deferredList.awaitAll().flatten()
            }
            Log.d("Nuvio", "Total streams fetched: ${allFetches.size}")
            
            // Separate Vixsrc streams from others to prioritize them
            val vixsrcStreams = mutableListOf<Pair<String, String>>()
            val otherStreams = mutableListOf<Pair<String, String>>()
            
            for ((displayName, finalUrl) in allFetches) {
                if (displayName.contains("Vixsrc", ignoreCase = true) || 
                    finalUrl.contains("vixsrc", ignoreCase = true)) {
                    vixsrcStreams.add(Pair(displayName, finalUrl))
                } else {
                    otherStreams.add(Pair(displayName, finalUrl))
                }
            }
            
            // Add Vixsrc streams first (they'll be the default), then others
            var duplicateCount = 1
            vixsrcStreams.forEach { (name, url) -> 
                var uniqueName = name
                while (videoLinks.containsKey(uniqueName)) {
                    uniqueName = "$name ($duplicateCount)"
                    duplicateCount++
                }
                videoLinks[uniqueName] = url 
            }
            otherStreams.forEach { (name, url) -> 
                var uniqueName = name
                while (videoLinks.containsKey(uniqueName)) {
                    uniqueName = "$name ($duplicateCount)"
                    duplicateCount++
                }
                videoLinks[uniqueName] = url 
            }
            
            Log.d("Nuvio", "Total streams found: ${videoLinks.size} (Vixsrc: ${vixsrcStreams.size}, Others: ${otherStreams.size})")
        } catch (e: Exception) {
            Log.e("Nuvio", "Error extracting video links", e)
        }
        
        return@withContext videoLinks
    }

    private fun formatLocalStreamDisplayName(
        result: com.example.zubflix.bdix.BDIXScraper.StreamResult,
        prefix: String = "[Local Scraper]"
    ): String {
        val filename = parseFilenameFromUrl(result.url)
        val sizeStr = result.size
            ?: extractSizeFromText(result.title)
            ?: extractSizeFromText(result.mediaTitle)
            ?: extractSizeFromText(filename ?: "")

        val qualityStr = com.example.zubflix.bdix.BDIXUtils.extractQuality(
            if (result.title.isNotBlank()) result.title else (filename ?: "")
        ).let { if (it == "SD") "" else it }

        val cleanPrefix = prefix.removePrefix("[").removeSuffix("]").trim()
        val cleanSource = result.source.removePrefix("[").removeSuffix("]").trim()

        val mediaTitle = result.mediaTitle.trim()
        val fileTitle = when {
            !filename.isNullOrBlank() && !filename.equals(mediaTitle, ignoreCase = true) -> filename.trim()
            result.title.isNotBlank() && !result.title.equals(mediaTitle, ignoreCase = true) -> result.title.trim()
            else -> null
        }

        val titleBlock = if (mediaTitle.isNotBlank() && !fileTitle.isNullOrBlank()) {
            "$mediaTitle\n$fileTitle"
        } else {
            mediaTitle.ifBlank { fileTitle ?: "Stream" }
        }

        val attrs = mutableListOf<String>()
        if (qualityStr.isNotBlank()) attrs.add(qualityStr)
        if (!sizeStr.isNullOrBlank()) attrs.add(sizeStr)

        val attrSuffix = if (attrs.isNotEmpty()) " (${attrs.joinToString(" | ")})" else ""

        return "[$cleanPrefix] [$cleanSource] $titleBlock$attrSuffix"
    }

    private fun parseFilenameFromUrl(url: String): String? {
        return try {
            val cleanUrl = url.substringBefore("?").substringBefore("#")
            val rawSegment = cleanUrl.substringAfterLast("/")
            if (rawSegment.isNotBlank() && rawSegment.contains(".")) {
                java.net.URLDecoder.decode(rawSegment, "UTF-8")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractSizeFromText(text: String): String? {
        if (text.isBlank()) return null
        val match = Regex("(?i)\\b(\\d+(?:\\.\\d+)?\\s*(?:GB|MB|GiB|MiB))\\b").find(text)
        return match?.groupValues?.getOrNull(1)
    }
}
