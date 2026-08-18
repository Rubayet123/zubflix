package com.example.zubflix.sources

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MovieBlastSource(private val context: Context? = null) : StreamingSource {
    override val name: String = "MovieBlast"

    private val TOKEN = "jdvhhjv255vghhgdhvfch2565656jhdcghfdf"
    private val HMAC_SECRET = "GJ8reydarI7Jqat9rvbAJKNQ9gY4DoEQF2H5nfuI1gi"
    private val BASE_URL = "https://app.cloud-mb.xyz"
    private val MAX_RETRIES = 2

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val PLAYBACK_HEADERS = mapOf(
        "Accept-Encoding" to "identity",
        "Connection" to "Keep-Alive",
        "Icy-MetaData" to "1",
        "Referer" to "MovieBlast",
        "User-Agent" to "MovieBlast",
        "x-request-x" to "com.movieblast"
    )

    private val PLAYBACK_HEADERS_TV = mapOf(
        "Accept-Encoding" to "identity",
        "Connection" to "Keep-Alive",
        "Referer" to "MovieBlast",
        "User-Agent" to "MovieBlast",
        "x-request-x" to "com.movieblast"
    )

    private suspend fun executeGet(path: String): String = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else "$BASE_URL/$path"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "okhttp/5.0.0-alpha.6")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
                response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            Log.e("MovieBlast", "HTTP request failed for $url", e)
            ""
        }
    }

    private fun isSeries(type: String?, contentType: String?): Boolean {
        val t = type?.lowercase() ?: ""
        val c = contentType?.lowercase() ?: ""
        return t in listOf("series", "serie", "tv", "show") || c == "series"
    }

    private fun httpsify(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val s = url.trim()
        return if (s.startsWith("http")) s else "https://$s"
    }

    private fun extractUrlPath(urlStr: String): String {
        return try {
            val uri = java.net.URI(urlStr)
            val path = uri.rawPath
            if (path.isNullOrEmpty()) "/" else path
        } catch (e: Exception) {
            val m = java.util.regex.Pattern.compile("^https?://[^/]+(/[^?#]*)?").matcher(urlStr)
            if (m.find()) {
                m.group(1) ?: "/"
            } else {
                "/"
            }
        }
    }

    private fun hmacSha256(key: String, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun generateSignedUrl(rawUrl: String): String {
        try {
            val url = httpsify(rawUrl)
            if (url.isBlank()) return ""
            val path = extractUrlPath(url)
            val ts = (System.currentTimeMillis() / 1000).toString()
            val sig = hmacSha256(HMAC_SECRET, path + ts)
            val b64 = Base64.encodeToString(sig, Base64.NO_WRAP)
            val encoded = URLEncoder.encode(b64, "UTF-8")
            val sep = if (url.contains("?")) "&" else "?"
            return "$url${sep}verify=$ts-$encoded"
        } catch (e: Exception) {
            Log.e("MovieBlast", "Error signing URL", e)
            return httpsify(rawUrl)
        }
    }

    private fun extractQuality(server: String, url: String): Int? {
        val text = "${url.lowercase()} ${server.lowercase()}"
        return when {
            "2160" in text || "4k" in text -> 2160
            "1440" in text -> 1440
            "1080" in text || "fullhd" in text -> 1080
            "720" in text || "hd" in text -> 720
            "480" in text -> 480
            "360" in text -> 360
            else -> {
                val m = java.util.regex.Pattern.compile("[_\\-.]?(\\d{3,4})p?[_\\-.]?").matcher(text)
                if (m.find()) {
                    m.group(1)?.toIntOrNull()
                } else {
                    null
                }
            }
        }
    }

    private fun createSourceLabel(server: String?, lang: String?, qualityInt: Int?, streamUrl: String?): String {
        val parts = mutableListOf<String>()
        if (!server.isNullOrBlank()) parts.add(server)
        if (!lang.isNullOrBlank()) parts.add(lang)
        if (qualityInt != null) {
            if (qualityInt == 2160) parts.add("4K") else parts.add("${qualityInt}p")
        }
        val u = (streamUrl ?: "").lowercase()
        if ("h265" in u || "hevc" in u) parts.add("HEVC")
        else if ("h264" in u || "avc" in u) parts.add("H264")
        if ("hdr" in u || "10bit" in u) parts.add("HDR")
        return if (parts.isEmpty()) "MovieBlast" else parts.joinToString(" • ")
    }

    private fun mapToStreamingItem(raw: JSONObject): StreamingItem? {
        val id = raw.optString("id")
        if (id.isBlank()) return null

        val title = raw.optString("name").ifBlank { raw.optString("title") }.ifBlank { "Unknown" }
        val posterPath = raw.optString("poster_path").ifBlank { null }

        val isSeries = isSeries(raw.optString("type"), raw.optString("content_type"))

        val apiPath = if (isSeries) {
            "api/series/show/$id/$TOKEN"
        } else {
            "api/media/detail/$id/$TOKEN"
        }

        val voteAverage = raw.optDouble("vote_average", -1.0)
        val rating = if (voteAverage > 0) String.format("%.1f/10", voteAverage) else null

        return StreamingItem(
            id = "$BASE_URL/$apiPath",
            title = title,
            isSeries = isSeries,
            sourceName = name,
            imageUrl = posterPath,
            rating = rating
        )
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = coroutineScope {
        val sections = listOf(
            "Trending" to "api/genres/trending/all/$TOKEN",
            "Latest" to "api/genres/pinned/all/$TOKEN",
            "Recently Added" to "api/genres/new/all/$TOKEN",
            "Popular • Movies" to "api/genres/popularmovies/all/$TOKEN",
            "Popular • Series" to "api/genres/popularseries/all/$TOKEN",
            "Latest • Series" to "api/media/seriesEpisodesAll/$TOKEN",
            "Recommended" to "api/genres/recommended/all/$TOKEN",
            "New HD Releases" to "api/genres/media/names/New%20HD%20Released/$TOKEN"
        )

        val deferreds = sections.map { (title, path) ->
            async {
                try {
                    val items = getCategoryContent(path, 1)
                    if (items.isNotEmpty()) {
                        StreamingCategory(path, title, items)
                    } else null
                } catch (e: Exception) {
                    Log.e("MovieBlast", "Error fetching home section $title", e)
                    null
                }
            }
        }
        deferreds.awaitAll().filterNotNull()
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        try {
            val url = if (categoryId.startsWith("http")) categoryId else "$BASE_URL/$categoryId"
            val sep = if (url.contains("?")) "&" else "?"
            val finalUrl = "$url${sep}page=$page"
            val jsonStr = executeGet(finalUrl)
            if (jsonStr.isNotBlank()) {
                val obj = JSONObject(jsonStr)
                val dataArray = obj.optJSONArray("data") ?: JSONArray()
                val items = mutableListOf<StreamingItem>()
                for (i in 0 until dataArray.length()) {
                    val raw = dataArray.optJSONObject(i) ?: continue
                    val item = mapToStreamingItem(raw)
                    if (item != null) {
                        items.add(item)
                    }
                }
                items
            } else emptyList()
        } catch (e: Exception) {
            Log.e("MovieBlast", "Error fetching category items for $categoryId", e)
            emptyList()
        }
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        try {
            val jsonStr = executeGet("api/search/$encoded/$TOKEN")
            if (jsonStr.isBlank()) return@withContext emptyList()

            val results = mutableListOf<StreamingItem>()
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("search") ?: root.optJSONArray("data") ?: JSONArray()

            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val mapped = mapToStreamingItem(item)
                if (mapped != null) {
                    results.add(mapped)
                }
            }
            results
        } catch (e: Exception) {
            Log.e("MovieBlast", "Search failed", e)
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            val jsonStr = executeGet(id)
            if (jsonStr.isBlank()) return@withContext null

            val json = JSONObject(jsonStr)
            val title = json.optString("name").ifBlank { json.optString("title") }.ifBlank { "Unknown" }
            val posterPath = json.optString("poster_path").ifBlank { null }
            val posterUrl = if (!posterPath.isNullOrBlank() && !posterPath.startsWith("http")) {
                posterPath
            } else {
                posterPath
            }
            val backdropPath = json.optString("backdrop_path_tv")
                .ifBlank { json.optString("backdrop_path") }
                .ifBlank { posterPath }
            val backdropUrl = backdropPath.takeIf { !it.isNullOrBlank() }

            val releaseDate = json.optString("first_air_date").ifBlank { json.optString("release_date") }
            val year = releaseDate.split("-").firstOrNull() ?: ""

            val scoreVal = json.optDouble("vote_average", -1.0)
            val rating = if (scoreVal > 0.0) String.format("%.1f/10", scoreVal) else null

            val genresList = mutableListOf<String>()
            val genresArray = json.optJSONArray("genres")
            if (genresArray != null) {
                for (i in 0 until genresArray.length()) {
                    val gObj = genresArray.optJSONObject(i)
                    if (gObj != null) {
                        val genreName = gObj.optString("name")
                        if (genreName.isNotBlank()) genresList.add(genreName)
                    } else {
                        val genreName = genresArray.optString(i)
                        if (genreName.isNotBlank()) genresList.add(genreName)
                    }
                }
            }

            val castList = mutableListOf<String>()
            val castersArray = json.optJSONArray("casterslist")
            if (castersArray != null) {
                for (i in 0 until castersArray.length()) {
                    val c = castersArray.optJSONObject(i) ?: continue
                    val actorName = c.optString("original_name")
                    if (actorName.isNotBlank()) {
                        castList.add(actorName)
                    }
                }
            }

            val seasonsArray = json.optJSONArray("seasons")
            val isTvSeries = seasonsArray != null && seasonsArray.length() > 0

            val seasonsList = mutableListOf<StreamingSeason>()
            if (isTvSeries) {
                for (s in 0 until seasonsArray.length()) {
                    val seasonObj = seasonsArray.optJSONObject(s) ?: continue
                    val seasonNum = seasonObj.optInt("season_number", 1)
                    val episodesArray = seasonObj.optJSONArray("episodes") ?: JSONArray()
                    val episodesList = mutableListOf<StreamingEpisode>()
                    for (e in 0 until episodesArray.length()) {
                        val epObj = episodesArray.optJSONObject(e) ?: continue
                        val epNum = epObj.optInt("episode_number", 1)
                        val epName = epObj.optString("name").ifBlank { "Episode $epNum" }
                        val epOverview = epObj.optString("overview")
                        val epThumbnail = epObj.optString("still_path_tv").ifBlank { epObj.optString("still_path") }.ifBlank { posterUrl }

                        val epVideos = epObj.optJSONArray("videos") ?: JSONArray()
                        val payload = JSONObject().apply {
                            put("type", "episode")
                            put("url", id)
                            put("season", seasonNum)
                            put("episode", epNum)
                            put("videos", epVideos)
                        }.toString()

                        episodesList.add(
                            StreamingEpisode(
                                title = epName,
                                streamUrl = payload,
                                stillUrl = epThumbnail,
                                overview = epOverview
                            )
                        )
                    }
                    seasonsList.add(
                        StreamingSeason(
                            title = "Season $seasonNum",
                            episodes = episodesList,
                            seasonNumber = seasonNum
                        )
                    )
                }
            }

            StreamingItem(
                id = id,
                title = title,
                isSeries = isTvSeries,
                sourceName = name,
                imageUrl = posterUrl,
                description = json.optString("overview").takeIf { it.isNotBlank() },
                rating = rating,
                year = year,
                genres = genresList.takeIf { it.isNotEmpty() },
                cast = castList.takeIf { it.isNotEmpty() },
                seasons = seasonsList.takeIf { isTvSeries },
                streamUrl = if (!isTvSeries) id else null
            )
        } catch (e: Exception) {
            Log.e("MovieBlast", "Error parsing details", e)
            null
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, String>()
        val seenUrls = mutableSetOf<String>()

        var videosArray = JSONArray()
        var isTv = false
        var seasonNum = 1
        var episodeNum = 1
        var originalId = data

        if (data.trim().startsWith("{")) {
            try {
                val json = JSONObject(data)
                videosArray = json.optJSONArray("videos") ?: JSONArray()
                isTv = json.optString("type") == "episode"
                seasonNum = json.optInt("season", 1)
                episodeNum = json.optInt("episode", 1)
                originalId = json.optString("url")
            } catch (e: Exception) {
                Log.e("MovieBlast", "Error parsing episode payload JSON", e)
            }
        }

        if (videosArray.length() == 0) {
            try {
                val jsonStr = executeGet(originalId)
                if (jsonStr.isNotBlank()) {
                    val root = JSONObject(jsonStr)
                    val seasons = root.optJSONArray("seasons")
                    if (seasons != null && seasons.length() > 0) {
                        isTv = true
                        for (s in 0 until seasons.length()) {
                            val seasonObj = seasons.optJSONObject(s) ?: continue
                            val sNum = seasonObj.optInt("season_number", 1)
                            if (sNum == seasonNum) {
                                val eps = seasonObj.optJSONArray("episodes") ?: JSONArray()
                                for (e in 0 until eps.length()) {
                                    val epObj = eps.optJSONObject(e) ?: continue
                                    val eNum = epObj.optInt("episode_number", 1)
                                    if (eNum == episodeNum) {
                                        videosArray = epObj.optJSONArray("videos") ?: JSONArray()
                                        break
                                    }
                                }
                                break
                            }
                        }
                    } else {
                        videosArray = root.optJSONArray("videos") ?: JSONArray()
                    }
                }
            } catch (e: Exception) {
                Log.e("MovieBlast", "Error fetching/parsing details URL for streams", e)
            }
        }

        if (videosArray.length() == 0) return@withContext emptyMap()

        val baseHeaders = if (isTv) PLAYBACK_HEADERS_TV else PLAYBACK_HEADERS

        // Resolve streams in parallel
        val deferreds = (0 until videosArray.length()).map { i ->
            async {
                val videoObj = videosArray.optJSONObject(i) ?: return@async null
                val rawLink = videoObj.optString("link").trim()
                if (rawLink.isBlank()) return@async null

                val server = videoObj.optString("server", "MovieBlast").ifBlank { "MovieBlast" }
                val lang = videoObj.optString("lang")
                val qualityInt = extractQuality(server, rawLink)
                val label = createSourceLabel(server, lang, qualityInt, rawLink)

                var verifiedUrl: String? = null

                for (attempt in 0..MAX_RETRIES) {
                    val signedUrl = generateSignedUrl(rawLink)
                    if (signedUrl.isBlank()) break

                    try {
                        Log.d("MovieBlast", "Verifying signed URL: $signedUrl")
                        val reqBuilder = Request.Builder()
                            .url(signedUrl)
                            .addHeader("Range", "bytes=0-0")
                            .get()
                        baseHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

                        client.newCall(reqBuilder.build()).execute().use { resp ->
                            Log.d("MovieBlast", "Verification resp code: ${resp.code} for $signedUrl")
                            if (resp.isSuccessful || resp.code == 206 || resp.code in 300..308) {
                                verifiedUrl = resp.request.url.toString()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MovieBlast", "Verification failed on attempt $attempt for $rawLink", e)
                    }
                    if (verifiedUrl != null) break
                }

                val finalUrl = verifiedUrl ?: generateSignedUrl(rawLink)
                if (finalUrl.isNotBlank()) {
                    val headersJson = com.google.gson.Gson().toJson(baseHeaders)
                    val streamValue = "$finalUrl######$headersJson"
                    Pair(label, streamValue)
                } else null
            }
        }

        deferreds.awaitAll().filterNotNull().forEach { (label, streamValue) ->
            val urlPart = streamValue.substringBefore("###")
            if (seenUrls.add(urlPart)) {
                results[label] = streamValue
            }
        }

        results
    }
}
