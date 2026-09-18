package com.example.zubflix.sources

import android.util.Log
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class HDGharTvSource : StreamingSource {
    override val name: String = "HDGharTV 📺"

    companion object {
        private const val BASE_URL = "https://hdghartv.cc"
        private const val API_BASE = "https://hdghartv.cc/api"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        private const val HEADERS_JSON = "{\"User-Agent\":\"$UA\",\"Referer\":\"$BASE_URL/\"}"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun fetchJson(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", UA)
            .addHeader("Referer", "$BASE_URL/")
            .addHeader("Accept", "application/json, text/plain, */*")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string()?.trim()
                    if (bodyStr != null && (bodyStr.startsWith("{") || bodyStr.startsWith("["))) {
                        bodyStr
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.w("HDGharTvSource", "Failed to fetch JSON from $url: ${e.message}")
            null
        }
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val categories = mutableListOf<StreamingCategory>()

        try {
            // Fetch dynamic homepage sections configured on HDGharTV
            val sectionsJson = fetchJson("$API_BASE/views/homepage/sections")
            if (!sectionsJson.isNullOrBlank()) {
                val rootObj = JSONObject(sectionsJson)
                val sectionsArray = rootObj.optJSONArray("data")
                if (sectionsArray != null) {
                    for (i in 0 until sectionsArray.length()) {
                        val sectionObj = sectionsArray.getJSONObject(i)
                        val title = sectionObj.optString("title", "Featured")
                        val sectionType = sectionObj.optString("sectionType", "")
                        val categoryName = sectionObj.optString("category", "")
                        val genreName = sectionObj.optString("genre", "")
                        val itemsArray = sectionObj.optJSONArray("items") ?: JSONArray()

                        val items = mutableListOf<StreamingItem>()
                        for (j in 0 until itemsArray.length()) {
                            val itemObj = itemsArray.getJSONObject(j)
                            val sectionForcedType = when (sectionType) {
                                "series" -> "series"
                                "movies" -> "movie"
                                else -> null
                            }
                            parseItemFromJson(itemObj, forcedType = sectionForcedType)?.let { items.add(it) }
                        }

                        if (items.isNotEmpty()) {
                            val catId = when {
                                categoryName.isNotBlank() -> "category:$categoryName"
                                genreName.isNotBlank() -> "genre:$genreName"
                                sectionType.isNotBlank() -> sectionType
                                else -> "sec_$i"
                            }
                            categories.add(StreamingCategory(id = catId, title = title, items = items))
                        }
                    }
                }
            }

            // Fallback / Supplementary categories if homepage sections returned few items
            if (categories.isEmpty()) {
                val moviesJson = fetchJson("$API_BASE/movies/public?page=1&limit=20")
                if (!moviesJson.isNullOrBlank()) {
                    val moviesArray = JSONObject(moviesJson).optJSONArray("data")
                    val movieItems = mutableListOf<StreamingItem>()
                    if (moviesArray != null) {
                        for (i in 0 until moviesArray.length()) {
                            parseItemFromJson(moviesArray.getJSONObject(i), forcedType = "movie")?.let { movieItems.add(it) }
                        }
                    }
                    if (movieItems.isNotEmpty()) {
                        categories.add(StreamingCategory(id = "movies", title = "🎬 Latest Movies", items = movieItems))
                    }
                }

                val seriesJson = fetchJson("$API_BASE/series/public?page=1&limit=20")
                if (!seriesJson.isNullOrBlank()) {
                    val seriesArray = JSONObject(seriesJson).optJSONArray("data")
                    val seriesItems = mutableListOf<StreamingItem>()
                    if (seriesArray != null) {
                        for (i in 0 until seriesArray.length()) {
                            parseItemFromJson(seriesArray.getJSONObject(i), forcedType = "series")?.let { seriesItems.add(it) }
                        }
                    }
                    if (seriesItems.isNotEmpty()) {
                        categories.add(StreamingCategory(id = "series", title = "📺 TV Shows & Web Series", items = seriesItems))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDGharTvSource", "Error loading home categories: ${e.message}", e)
        }

        categories
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<StreamingItem>()
        try {
            val url = when {
                categoryId.startsWith("category:") -> {
                    val cat = categoryId.removePrefix("category:")
                    "$API_BASE/movies/public?region=${URLEncoder.encode(cat, "UTF-8")}&page=$page&limit=20"
                }
                categoryId.startsWith("genre:") -> {
                    val genre = categoryId.removePrefix("genre:")
                    "$API_BASE/movies/public?genre=${URLEncoder.encode(genre, "UTF-8")}&page=$page&limit=20"
                }
                categoryId == "movies" -> "$API_BASE/movies/public?page=$page&limit=20"
                categoryId == "series" -> "$API_BASE/series/public?page=$page&limit=20"
                categoryId == "trending" || categoryId == "trending_now" -> "$API_BASE/views/trending/all?page=$page&limit=20"
                categoryId == "pinned" -> "$API_BASE/views/pinned?page=$page&limit=20"
                categoryId == "most_watched" -> "$API_BASE/views/trending?type=all&days=30&page=$page&limit=20"
                else -> "$API_BASE/movies/public?page=$page&limit=20"
            }

            val jsonStr = fetchJson(url)
            if (!jsonStr.isNullOrBlank()) {
                val rootObj = JSONObject(jsonStr)
                val dataArray = rootObj.optJSONArray("data")
                if (dataArray != null) {
                    val forcedType = if (categoryId == "series") "series" else null
                    for (i in 0 until dataArray.length()) {
                        parseItemFromJson(dataArray.getJSONObject(i), forcedType = forcedType)?.let { items.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDGharTvSource", "Error loading category content for $categoryId (page $page): ${e.message}", e)
        }
        items
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StreamingItem>()
        if (query.isBlank()) return@withContext results

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$API_BASE/search?q=$encodedQuery&type=all&page=1"
            val jsonStr = fetchJson(searchUrl)
            if (!jsonStr.isNullOrBlank()) {
                val rootObj = JSONObject(jsonStr)

                val moviesArray = rootObj.optJSONArray("movies")
                if (moviesArray != null) {
                    for (i in 0 until moviesArray.length()) {
                        parseItemFromJson(moviesArray.getJSONObject(i), forcedType = "movie")?.let { results.add(it) }
                    }
                }

                val seriesArray = rootObj.optJSONArray("series")
                if (seriesArray != null) {
                    for (i in 0 until seriesArray.length()) {
                        parseItemFromJson(seriesArray.getJSONObject(i), forcedType = "series")?.let { results.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDGharTvSource", "Error during search for '$query': ${e.message}", e)
        }

        results
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            var isSeries = id.startsWith("series:")
            val rawId = id.substringAfter(":")

            var detailUrl = if (isSeries) {
                "$API_BASE/series/public/$rawId"
            } else {
                "$API_BASE/movies/public/$rawId"
            }

            var jsonStr = fetchJson(detailUrl)
            // If primary type fetch fails or returns error JSON, automatically attempt the alternate endpoint
            if (jsonStr.isNullOrBlank() || jsonStr.contains("Movie not found") || jsonStr.contains("Series not found") || !jsonStr.contains("_id")) {
                val alternateUrl = if (isSeries) {
                    "$API_BASE/movies/public/$rawId"
                } else {
                    "$API_BASE/series/public/$rawId"
                }
                val altJson = fetchJson(alternateUrl)
                if (!altJson.isNullOrBlank() && altJson.contains("_id") && !altJson.contains("not found")) {
                    jsonStr = altJson
                    isSeries = !isSeries
                }
            }

            if (jsonStr.isNullOrBlank()) return@withContext null
            val obj = JSONObject(jsonStr)
            if (!obj.has("_id") && !obj.has("title") && !obj.has("name")) return@withContext null

            val title = obj.optString("title").ifEmpty { obj.optString("originalTitle", "Unknown Title") }
            val overview = obj.optString("overview", "")
            val posterPath = obj.optString("posterPath", "")
            val backdropPath = obj.optString("backdropPath", "")
            val releaseDate = obj.optString("releaseDate").ifEmpty { obj.optString("firstAirDate", "") }
            val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
            val runtime = obj.optInt("runtime", 0).let { if (it > 0) "$it min" else null }
            val voteAverage = obj.optDouble("voteAverage", 0.0).let { if (it > 0.0) String.format(Locale.US, "%.1f/10", it) else null }

            val genresList = mutableListOf<String>()
            val genresArr = obj.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    val gObj = genresArr.optJSONObject(i)
                    val gName = gObj?.optString("name") ?: genresArr.optString(i)
                    if (gName.isNotBlank()) genresList.add(gName)
                }
            }

            val castList = mutableListOf<String>()
            val castArr = obj.optJSONArray("cast")
            if (castArr != null) {
                for (i in 0 until castArr.length()) {
                    val cObj = castArr.optJSONObject(i)
                    val cName = cObj?.optString("name")
                    if (!cName.isNullOrBlank()) castList.add(cName)
                }
            }

            var seasonsList: List<StreamingSeason>? = null
            val videoSourcesMap = mutableMapOf<String, String>()
            var primaryStreamUrl: String? = null

            if (isSeries) {
                val seasonsArr = obj.optJSONArray("seasons")
                if (seasonsArr != null && seasonsArr.length() > 0) {
                    val parsedSeasons = mutableListOf<StreamingSeason>()
                    for (sIdx in 0 until seasonsArr.length()) {
                        val seasonObj = seasonsArr.getJSONObject(sIdx)
                        val seasonNumber = seasonObj.optInt("seasonNumber", sIdx + 1)
                        val seasonName = seasonObj.optString("name", "Season $seasonNumber")
                        val episodesArr = seasonObj.optJSONArray("episodes") ?: JSONArray()

                        val episodes = mutableListOf<StreamingEpisode>()
                        for (eIdx in 0 until episodesArr.length()) {
                            val epObj = episodesArr.getJSONObject(eIdx)
                            val epNumber = epObj.optInt("episodeNumber", eIdx + 1)
                            val epName = epObj.optString("name", "Episode $epNumber")
                            val epStill = epObj.optString("stillPath", posterPath)
                            val epOverview = epObj.optString("overview", "")
                            val epAirDate = epObj.optString("airDate", "")
                            val epRuntime = epObj.optInt("runtime", 0).takeIf { it > 0 }
                            val epVote = epObj.optDouble("voteAverage", 0.0).takeIf { it > 0 }

                            val epStreamingLinks = epObj.optJSONArray("streamingLinks")
                            val epPayload = JSONObject().apply {
                                put("id", rawId)
                                put("type", "series")
                                put("title", title)
                                put("year", year ?: "")
                                put("seasonNumber", seasonNumber)
                                put("episodeNumber", epNumber)
                                put("streamingLinks", epStreamingLinks ?: JSONArray())
                            }.toString()

                            episodes.add(
                                StreamingEpisode(
                                    title = "S${seasonNumber}E${epNumber} - $epName",
                                    streamUrl = epPayload,
                                    stillUrl = epStill,
                                    overview = epOverview,
                                    airDate = epAirDate,
                                    voteAverage = epVote,
                                    runtime = epRuntime
                                )
                            )
                        }

                        if (episodes.isNotEmpty()) {
                            parsedSeasons.add(
                                StreamingSeason(
                                    title = seasonName,
                                    seasonNumber = seasonNumber,
                                    episodes = episodes
                                )
                            )
                        }
                    }
                    if (parsedSeasons.isNotEmpty()) {
                        seasonsList = parsedSeasons
                    }
                }
            } else {
                // Movie: Parse streaming links
                val streamingLinks = obj.optJSONArray("streamingLinks")
                if (streamingLinks != null && streamingLinks.length() > 0) {
                    val formattedStreams = formatStreamingLinks(streamingLinks, title, year ?: "", isSeries = false)
                    videoSourcesMap.putAll(formattedStreams)
                    primaryStreamUrl = formattedStreams.values.firstOrNull()
                }
            }

            StreamingItem(
                id = id,
                title = title,
                isSeries = isSeries,
                sourceName = name,
                imageUrl = posterPath.ifEmpty { null },
                backdropUrl = backdropPath.ifEmpty { null },
                description = overview.ifEmpty { null },
                streamUrl = primaryStreamUrl,
                videoSources = if (videoSourcesMap.isNotEmpty()) videoSourcesMap else null,
                seasons = seasonsList,
                year = year,
                duration = runtime,
                rating = voteAverage,
                genres = if (genresList.isNotEmpty()) genresList else null,
                cast = if (castList.isNotEmpty()) castList else null
            )
        } catch (e: Exception) {
            Log.e("HDGharTvSource", "Error parsing details for $id: ${e.message}", e)
            null
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val streams = mutableMapOf<String, String>()

        try {
            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                val title = json.optString("title", "Video")
                val year = json.optString("year", "")
                val isSeries = json.optString("type") == "series"
                val sNum = json.optInt("seasonNumber", 1)
                val eNum = json.optInt("episodeNumber", 1)
                val linksArr = json.optJSONArray("streamingLinks")

                if (linksArr != null && linksArr.length() > 0) {
                    val formatted = formatStreamingLinks(linksArr, title, year, isSeries, sNum, eNum)
                    streams.putAll(formatted)
                }
            } else if (data.startsWith("http")) {
                streams["[HDGharTV] Stream 1080p"] = "$data######$HEADERS_JSON"
            }
        } catch (e: Exception) {
            Log.e("HDGharTvSource", "Error extracting video links: ${e.message}", e)
        }

        if (streams.isEmpty() && data.isNotBlank() && data.startsWith("http")) {
            streams["[HDGharTV] Direct Stream"] = "$data######$HEADERS_JSON"
        }

        streams
    }

    /**
     * Decodes and formats stream links according to the ranking, quality, audio, and codec logic
     */
    private fun formatStreamingLinks(
        linksArr: JSONArray,
        title: String,
        year: String,
        isSeries: Boolean,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1
    ): Map<String, String> {
        val streamEntries = mutableListOf<StreamEntry>()

        for (i in 0 until linksArr.length()) {
            val linkObj = linksArr.optJSONObject(i) ?: continue
            val rawUrl = linkObj.optString("url")
            if (rawUrl.isBlank()) continue

            val qualityStr = linkObj.optString("quality", "1080p")
            val linkName = linkObj.optString("name", "")
            val language = linkObj.optString("language", "")
            val streamType = linkObj.optString("type", "hls")

            val combinedSearch = "$qualityStr $linkName $language $rawUrl".lowercase(Locale.US)

            val is4k = combinedSearch.contains("2160p") || combinedSearch.contains("4k")
            val is1080p = combinedSearch.contains("1080p")
            val is720p = combinedSearch.contains("720p")

            val (qualityLabel, qualityEmoji, rank) = when {
                is4k -> Triple("2160p", "💎", 3)
                is1080p -> Triple("1080p", "🔥", 2)
                is720p -> Triple("720p", "🎬", 1)
                else -> Triple(qualityStr.ifEmpty { "HD" }, "⚡", 0)
            }

            var audioTag = "Dual-Audio 🌐"
            if ((combinedSearch.contains("hindi") || combinedSearch.contains("hin") || combinedSearch.contains("🇮🇳")) &&
                !combinedSearch.contains("multi") && !combinedSearch.contains("dual")
            ) {
                audioTag = "Hindi 🇮🇳"
            } else if (combinedSearch.contains("english") || combinedSearch.contains("eng")) {
                audioTag = "English 🇬🇧"
            }

            val isHls = rawUrl.contains(".m3u8") || streamType.equals("hls", ignoreCase = true)
            val container = if (isHls) "HLS" else if (combinedSearch.contains("mp4")) "MP4" else "MKV"
            val videoCodec = if (combinedSearch.contains("hevc") || combinedSearch.contains("x265") || combinedSearch.contains("h265")) "x.265" else "x.264"
            val streamDelivery = if (isHls) "HLS" else "Direct"
            val audioCodec = when {
                combinedSearch.contains("ddp") || combinedSearch.contains("dd+") || combinedSearch.contains("eac3") || combinedSearch.contains("dolby") -> "E-AC3"
                combinedSearch.contains("ac3") -> "AC3"
                else -> "AAC"
            }

            val serverLabel = if (isSeries) {
                "[$qualityEmoji $qualityLabel] S${seasonNumber}E${episodeNumber} • $audioTag • $videoCodec $audioCodec"
            } else {
                "[$qualityEmoji $qualityLabel] $audioTag • $container $videoCodec ($streamDelivery)"
            }

            val finalPlayUrl = "$rawUrl######$HEADERS_JSON"
            streamEntries.add(StreamEntry(rank = rank, label = serverLabel, playUrl = finalPlayUrl))
        }

        // Sort descending by resolution rank (4K -> 1080p -> 720p -> 480p)
        streamEntries.sortByDescending { it.rank }

        val map = linkedMapOf<String, String>()
        streamEntries.forEach { entry ->
            var key = entry.label
            var counter = 2
            while (map.containsKey(key)) {
                key = "${entry.label} ($counter)"
                counter++
            }
            map[key] = entry.playUrl
        }
        return map
    }

    private data class StreamEntry(
        val rank: Int,
        val label: String,
        val playUrl: String
    )

    private fun parseItemFromJson(json: JSONObject, forcedType: String? = null): StreamingItem? {
        val rawId = json.optString("sourceId").ifEmpty { json.optString("_id") }
        if (rawId.isBlank()) return null

        val detectedType = when {
            forcedType != null -> forcedType
            json.has("type") && json.optString("type").isNotBlank() -> json.optString("type")
            json.has("contentType") && json.optString("contentType").isNotBlank() -> json.optString("contentType")
            json.has("numberOfSeasons") || json.has("firstAirDate") -> "series"
            json.has("runtime") || json.has("releaseDate") -> "movie"
            else -> "movie"
        }
        val isSeries = detectedType.equals("series", ignoreCase = true)
        val itemType = if (isSeries) "series" else "movie"

        val id = "$itemType:$rawId"
        val title = json.optString("title").ifEmpty { json.optString("originalTitle", "Untitled") }
        val posterPath = json.optString("posterPath")
        val backdropPath = json.optString("backdropPath")
        val overview = json.optString("overview")
        val releaseDate = json.optString("releaseDate").ifEmpty { json.optString("firstAirDate", "") }
        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
        val voteAvg = json.optDouble("voteAverage", 0.0).let { if (it > 0.0) String.format(Locale.US, "%.1f", it) else null }

        val genresList = mutableListOf<String>()
        val gName = json.optString("genre", "")
        if (gName.isNotBlank()) genresList.add(gName)
        val genresArr = json.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val gObj = genresArr.optJSONObject(i)
                val name = gObj?.optString("name") ?: genresArr.optString(i)
                if (name.isNotBlank() && !genresList.contains(name)) genresList.add(name)
            }
        }

        return StreamingItem(
            id = id,
            title = title,
            isSeries = isSeries,
            sourceName = name,
            imageUrl = posterPath.ifEmpty { null },
            backdropUrl = backdropPath.ifEmpty { null },
            description = overview.ifEmpty { null },
            year = year,
            rating = voteAvg,
            genres = if (genresList.isNotEmpty()) genresList else null
        )
    }
}
