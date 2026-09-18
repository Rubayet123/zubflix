package com.example.zubflix.sources

import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MovieBoxWebSource : StreamingSource {
    override val name: String = "MovieBoxWeb"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var bearerToken: String? = null
    private val apiBase = "https://h5-api.aoneroom.com/wefeed-h5api-bff"

    private fun getHeaders(token: String?): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
            .add("Referer", "https://moviebox.ph/")
            .add("Origin", "https://moviebox.ph")
            .add("X-Client-Info", """{"timezone":"Asia/Dhaka"}""")
            .add("X-Request-Lang", "en")
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
        if (!token.isNullOrBlank()) {
            builder.add("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private suspend fun getBearerToken(): String? = withContext(Dispatchers.IO) {
        if (bearerToken != null) return@withContext bearerToken

        try {
            val request = Request.Builder()
                .url("$apiBase/home?host=moviebox.ph")
                .headers(getHeaders(null))
                .build()

            client.newCall(request).execute().use { response ->
                val xUser = response.header("x-user")
                if (!xUser.isNullOrBlank()) {
                    try {
                        val json = JSONObject(xUser)
                        bearerToken = json.optString("token").takeIf { it.isNotBlank() }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (bearerToken.isNullOrBlank()) {
                    val setCookie = response.header("set-cookie") ?: ""
                    val match = Regex("""token=([^;]+)""").find(setCookie)
                    if (match != null) {
                        bearerToken = match.groupValues[1]
                    }
                }
                val bodyStr = response.body?.string() ?: ""
                if (bearerToken.isNullOrBlank() && bodyStr.isNotBlank()) {
                    try {
                        val json = JSONObject(bodyStr)
                        val dataObj = json.optJSONObject("data")
                        val tok = dataObj?.optJSONObject("user")?.optString("token")
                            ?: dataObj?.optString("token")
                            ?: json.optJSONObject("user")?.optString("token")
                        if (!tok.isNullOrBlank()) {
                            bearerToken = tok
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                bearerToken
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieBoxWeb", "Error fetching bearer token", e)
            null
        }
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val categories = mutableListOf<StreamingCategory>()
        val token = getBearerToken()

        try {
            val request = Request.Builder()
                .url("$apiBase/home?host=moviebox.ph")
                .headers(getHeaders(token))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data")
                val operatingList = dataObj?.optJSONArray("operatingList")

                if (operatingList != null) {
                    for (i in 0 until operatingList.length()) {
                        val op = operatingList.optJSONObject(i) ?: continue
                        val opType = op.optString("type")
                        val title = op.optString("title", "Featured")

                        val titleLower = title.lowercase()
                        if (titleLower.contains("bet+") ||
                            titleLower.contains("bl story") ||
                            titleLower.contains("black shows") ||
                            titleLower.contains("short tv")) {
                            continue
                        }

                        val items = mutableListOf<StreamingItem>()

                        if (opType == "BANNER") {
                            val bannerObj = op.optJSONObject("banner")
                            val bannerItems = bannerObj?.optJSONArray("items") ?: org.json.JSONArray()
                            for (j in 0 until bannerItems.length()) {
                                val item = bannerItems.optJSONObject(j) ?: continue
                                val name = item.optString("title").ifEmpty {
                                    item.optJSONObject("subject")?.optString("title") ?: ""
                                }
                                if (name.isBlank() || name.contains("Communities", ignoreCase = true)) continue

                                val subject = item.optJSONObject("subject")
                                val subjectId = subject?.optString("subjectId") ?: item.optString("subjectId")
                                val slug = item.optString("detailPath").ifEmpty {
                                    subject?.optString("detailPath") ?: ""
                                }
                                val posterUrl = item.optJSONObject("image")?.optString("url")?.ifEmpty {
                                    subject?.optJSONObject("cover")?.optString("url")
                                } ?: subject?.optJSONObject("cover")?.optString("url")
                                val rating = subject?.optString("imdbRatingValue")
                                val isSeries = subject?.optInt("subjectType") == 2

                                if (slug.isNotBlank() || subjectId.isNotBlank()) {
                                    val itemId = if (subjectId.isNotBlank() && slug.isNotBlank()) "$subjectId|$slug" else slug.ifEmpty { subjectId }
                                    items.add(
                                        StreamingItem(
                                            id = itemId,
                                            title = name,
                                            isSeries = isSeries,
                                            imageUrl = posterUrl,
                                            rating = rating,
                                            sourceName = this@MovieBoxWebSource.name
                                        )
                                    )
                                }
                            }
                        } else if (opType in listOf("SUBJECTS_MOVIE", "SUBJECTS_TV", "SUBJECTS_ANIMATION") || op.has("subjects")) {
                            val subjects = op.optJSONArray("subjects") ?: org.json.JSONArray()
                            for (j in 0 until subjects.length()) {
                                val sub = subjects.optJSONObject(j) ?: continue
                                val name = sub.optString("title")
                                if (name.isBlank()) continue

                                val subjectId = sub.optString("subjectId")
                                val slug = sub.optString("detailPath")
                                val posterUrl = sub.optJSONObject("cover")?.optString("url")
                                val rating = sub.optString("imdbRatingValue")
                                val year = sub.optString("releaseDate")?.take(4)
                                val isSeries = sub.optInt("subjectType") == 2 || opType == "SUBJECTS_TV" || opType == "SUBJECTS_ANIMATION"

                                val itemId = if (subjectId.isNotBlank() && slug.isNotBlank()) "$subjectId|$slug" else slug.ifEmpty { subjectId }
                                items.add(
                                    StreamingItem(
                                        id = itemId,
                                        title = name,
                                        isSeries = isSeries,
                                        imageUrl = posterUrl,
                                        rating = rating,
                                        year = year,
                                        sourceName = this@MovieBoxWebSource.name
                                    )
                                )
                            }
                        }

                        if (items.isNotEmpty()) {
                            val catId = when (opType) {
                                "SUBJECTS_MOVIE" -> "moviebox_movies"
                                "SUBJECTS_TV" -> "moviebox_tv"
                                "SUBJECTS_ANIMATION" -> "moviebox_animation"
                                else -> "moviebox_$title"
                            }
                            categories.add(StreamingCategory(id = catId, title = title, items = items))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieBoxWeb", "Error fetching home categories", e)
        }

        if (categories.isEmpty()) {
            val movies = fetchCategoryData(tabId = 2, page = 1)
            if (movies.isNotEmpty()) {
                categories.add(StreamingCategory(id = "moviebox_movies", title = "Movies", items = movies))
            }
            val tvSeries = fetchCategoryData(tabId = 5, page = 1)
            if (tvSeries.isNotEmpty()) {
                categories.add(StreamingCategory(id = "moviebox_tv", title = "TV Series", items = tvSeries))
            }
            val animation = fetchCategoryData(tabId = 8, page = 1)
            if (animation.isNotEmpty()) {
                categories.add(StreamingCategory(id = "moviebox_animation", title = "Animation", items = animation))
            }
        }

        categories
    }

    private suspend fun fetchCategoryData(tabId: Int, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<StreamingItem>()
        val token = getBearerToken()
        try {
            val url = "$apiBase/subject/filter"
            val payload = JSONObject().apply {
                put("tabId", tabId)
                put("filter", JSONObject().apply {
                    put("sort", "RECOMMEND")
                    put("genre", "ALL")
                    put("country", "ALL")
                    put("year", "ALL")
                    put("language", "ALL")
                })
                put("page", page)
                put("perPage", 24)
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .headers(getHeaders(token))
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data") ?: JSONObject()
                val rawItems = dataObj.optJSONArray("items") ?: dataObj.optJSONArray("subjects") ?: org.json.JSONArray()

                for (i in 0 until rawItems.length()) {
                    val sub = rawItems.optJSONObject(i) ?: continue
                    val name = sub.optString("title")
                    if (name.isBlank()) continue

                    val subjectId = sub.optString("subjectId")
                    val slug = sub.optString("detailPath")
                    val posterUrl = sub.optJSONObject("cover")?.optString("url")
                    val rating = sub.optString("imdbRatingValue")
                    val year = sub.optString("releaseDate")?.take(4)
                    val isSeries = sub.optInt("subjectType") == 2

                    val itemId = if (subjectId.isNotBlank() && slug.isNotBlank()) "$subjectId|$slug" else slug.ifEmpty { subjectId }
                    items.add(
                        StreamingItem(
                            id = itemId,
                            title = name,
                            isSeries = isSeries,
                            imageUrl = posterUrl,
                            rating = rating,
                            year = year,
                            sourceName = this@MovieBoxWebSource.name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieBoxWeb", "Error fetching category data for tabId $tabId", e)
        }
        items
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val tabId = when {
            categoryId.contains("tv", ignoreCase = true) -> 5
            categoryId.contains("animation", ignoreCase = true) -> 8
            else -> 2
        }
        fetchCategoryData(tabId, page)
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val items = mutableListOf<StreamingItem>()
        val token = getBearerToken()
        try {
            val url = "$apiBase/subject/search"
            val payload = JSONObject().apply {
                put("keyword", query.trim())
                put("page", 1)
                put("perPage", 20)
            }

            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .headers(getHeaders(token))
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                val dataObj = json.optJSONObject("data") ?: JSONObject()
                val rawItems = dataObj.optJSONArray("items") ?: dataObj.optJSONArray("list") ?: org.json.JSONArray()

                for (i in 0 until rawItems.length()) {
                    val item = rawItems.optJSONObject(i) ?: continue
                    val sub = item.optJSONObject("subject") ?: item
                    val name = sub.optString("title")
                    if (name.isBlank()) continue

                    val subjectId = sub.optString("subjectId")
                    val slug = sub.optString("detailPath")
                    val posterUrl = sub.optJSONObject("cover")?.optString("url")
                    val rating = sub.optString("imdbRatingValue")
                    val year = sub.optString("releaseDate")?.take(4)
                    val isSeries = sub.optInt("subjectType") == 2

                    val itemId = if (subjectId.isNotBlank() && slug.isNotBlank()) "$subjectId|$slug" else slug.ifEmpty { subjectId }
                    items.add(
                        StreamingItem(
                            id = itemId,
                            title = name,
                            isSeries = isSeries,
                            imageUrl = posterUrl,
                            rating = rating,
                            year = year,
                            sourceName = this@MovieBoxWebSource.name
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieBoxWeb", "Error searching MovieBox", e)
        }
        items
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        val token = getBearerToken() ?: ""
        val parts = id.split("|")
        val idSubjectId = if (parts.size >= 2) parts[0] else ""
        val detailPath = if (parts.size >= 2) parts[1] else parts[0]

        val candidates = mutableListOf<String>()
        if (detailPath.isNotBlank()) {
            candidates.add("$apiBase/detail?detailPath=${URLEncoder.encode(detailPath, "UTF-8")}")
        }
        if (idSubjectId.isNotBlank() && idSubjectId != detailPath) {
            candidates.add("$apiBase/detail?detailPath=${URLEncoder.encode(idSubjectId, "UTF-8")}")
        }
        val targetSubjectId = if (idSubjectId.isNotBlank()) idSubjectId else detailPath
        if (targetSubjectId.isNotBlank()) {
            candidates.add("https://api3.aoneroom.com/wefeed-mobile-bff/subject-api/get?subjectId=$targetSubjectId")
        }

        for (url in candidates) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .headers(getHeaders(token))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val dataObj = json.optJSONObject("data") ?: return@use

                    val subject = dataObj.optJSONObject("subject")
                        ?: if (dataObj.has("title") || dataObj.has("subjectId") || dataObj.has("subjectType")) dataObj else null

                    if (subject == null) return@use

                    val realSubjectId = subject.optString("subjectId").ifEmpty { idSubjectId }
                    val realDetailPath = subject.optString("detailPath").ifEmpty { detailPath }
                    val title = subject.optString("title").ifEmpty { "Movie" }
                    val subjectType = subject.optInt("subjectType", 1)
                    val isSeries = subjectType == 2 || subjectType == 7
                    val posterUrl = subject.optJSONObject("cover")?.optString("url")
                        ?: subject.optString("coverUrl")
                        ?: subject.optJSONObject("image")?.optString("url")
                    val description = subject.optString("description")
                        .ifEmpty { subject.optString("overview") }
                    val year = subject.optString("releaseDate").take(4).ifEmpty { null }
                    val rating = subject.optString("imdbRatingValue").ifEmpty { null }

                    val genresStr = subject.optString("genre")
                    val genres = if (genresStr.isNotBlank()) genresStr.split(",").map { it.trim() } else null

                    val starsArr = subject.optJSONArray("stars") ?: subject.optJSONArray("staffList")
                    val cast = mutableListOf<String>()
                    if (starsArr != null) {
                        for (i in 0 until starsArr.length()) {
                            val star = starsArr.optJSONObject(i)
                            val starName = star?.optString("name")
                            if (!starName.isNullOrBlank()) cast.add(starName)
                        }
                    }

                    val seasonsList = mutableListOf<StreamingSeason>()

                    if (isSeries) {
                        val resource = dataObj.optJSONObject("resource")
                        val seasonsArr = resource?.optJSONArray("seasons")
                            ?: dataObj.optJSONArray("seasons")
                            ?: subject.optJSONArray("seasons")

                        if (seasonsArr != null && seasonsArr.length() > 0) {
                            for (sIdx in 0 until seasonsArr.length()) {
                                val seasonObj = seasonsArr.optJSONObject(sIdx) ?: continue
                                val seasonNum = seasonObj.optInt("se", sIdx + 1)
                                val allEpStr = seasonObj.optString("allEp")
                                val maxEp = seasonObj.optInt("maxEp", 1)

                                val epNumbers = if (allEpStr.isNotBlank()) {
                                    allEpStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                                } else {
                                    (1..maxEp).toList()
                                }

                                val epList = mutableListOf<StreamingEpisode>()
                                for (epNum in epNumbers) {
                                    epList.add(
                                        StreamingEpisode(
                                            title = "S${seasonNum}E${epNum}",
                                            streamUrl = "$realSubjectId|$realDetailPath|$seasonNum|$epNum",
                                            stillUrl = posterUrl,
                                            overview = "Episode $epNum"
                                        )
                                    )
                                }

                                seasonsList.add(
                                    StreamingSeason(
                                        title = "Season $seasonNum",
                                        episodes = epList,
                                        seasonNumber = seasonNum
                                    )
                                )
                            }
                        } else {
                            seasonsList.add(
                                StreamingSeason(
                                    title = "Season 1",
                                    episodes = listOf(
                                        StreamingEpisode(
                                            title = "S1E1",
                                            streamUrl = "$realSubjectId|$realDetailPath|1|1",
                                            stillUrl = posterUrl,
                                            overview = "Episode 1"
                                        )
                                    ),
                                    seasonNumber = 1
                                )
                            )
                        }
                    }

                    return@withContext StreamingItem(
                        id = "$realSubjectId|$realDetailPath",
                        title = title,
                        isSeries = isSeries,
                        imageUrl = posterUrl,
                        description = description,
                        streamUrl = if (!isSeries) "$realSubjectId|$realDetailPath" else null,
                        seasons = if (isSeries) seasonsList else null,
                        year = year,
                        rating = rating,
                        genres = genres,
                        cast = if (cast.isNotEmpty()) cast else null,
                        sourceName = this@MovieBoxWebSource.name
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieBoxWeb", "Error fetching details candidate url $url", e)
            }
        }
        null
    }

    private suspend fun getMovieBoxBaseUrl(): String = withContext(Dispatchers.IO) {
        val configEndpoints = listOf("https://themoviebox.org", "https://m2box.org")
        for (endpoint in configEndpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .build()
                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        if (body.trim().startsWith("{")) {
                            val json = JSONObject(body)
                            val urlObj = json.optJSONObject("movieBoxWeb")
                            val url = urlObj?.optString("url")?.trim()?.removeSuffix("/")
                            if (!url.isNullOrBlank()) {
                                return@withContext if (!url.startsWith("http")) "https://$url" else url
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieBoxWeb", "Error fetching dynamic baseUrl from $endpoint", e)
            }
        }
        return@withContext "https://m2box.org"
    }

    private fun getQuality(resolutions: String?): String? {
        if (resolutions.isNullOrBlank()) return null
        val values = resolutions.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in listOf(360, 480, 720, 1080, 2160) }
        val quality = values.maxOrNull()
        return if (quality != null) "${quality}p" else null
    }

    private fun getStreamType(format: String?): String {
        val normalized = format?.uppercase()
        return when (normalized) {
            "HLS", "M3U8" -> "m3u8"
            "DASH" -> "mpd"
            else -> "mp4"
        }
    }

    private suspend fun getCaptions(
        baseUrl: String,
        subjectId: String,
        detailPath: String,
        streamId: String,
        streamFormat: String,
        referer: String,
        requestHeaders: okhttp3.Headers
    ): String {
        if (streamId.isBlank() || streamFormat.isBlank()) return ""
        try {
            val encodedPath = URLEncoder.encode(detailPath, "UTF-8")
            val encodedId = URLEncoder.encode(subjectId, "UTF-8")
            val encodedFormat = URLEncoder.encode(streamFormat, "UTF-8")
            val capStreamId = URLEncoder.encode(streamId, "UTF-8")
            val capUrl = "$baseUrl/wefeed-h5api-bff/subject/caption?format=$encodedFormat&id=$capStreamId&subjectId=$encodedId&detailPath=$encodedPath"

            val req = Request.Builder()
                .url(capUrl)
                .headers(requestHeaders)
                .header("Referer", referer)
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return ""
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val captions = json.optJSONObject("data")?.optJSONArray("captions") ?: return ""

                var selectedUrl = ""
                for (i in 0 until captions.length()) {
                    val cap = captions.optJSONObject(i) ?: continue
                    val url = cap.optString("url")
                    val lan = cap.optString("lan").ifEmpty { cap.optString("lanName") }
                    if (url.isNotBlank()) {
                        if (lan.contains("en", ignoreCase = true) || selectedUrl.isBlank()) {
                            selectedUrl = url
                            if (lan.contains("en", ignoreCase = true)) break
                        }
                    }
                }
                return selectedUrl
            }
        } catch (e: Exception) {
            return ""
        }
    }

    suspend fun extractVideoLinksStreaming(
        data: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        onStreamFound: suspend (streams: Map<String, String>) -> Unit
    ) = withContext(Dispatchers.IO) {
        var subjectId = ""
        var detailPath = ""
        var season: Int? = null
        var episode: Int? = null

        if (data.trim().startsWith("{")) {
            try {
                val json = JSONObject(data)
                subjectId = json.optString("subjectId")
                detailPath = json.optString("detailPath")
                season = json.optInt("season", 0).takeIf { it > 0 }
                episode = json.optInt("episode", 0).takeIf { it > 0 }
            } catch (e: Exception) {}
        }

        if (subjectId.contains("|")) {
            val parts = subjectId.split("|")
            subjectId = parts.getOrNull(0) ?: ""
            if (detailPath.isBlank() || detailPath.contains("|")) {
                detailPath = parts.getOrNull(1) ?: ""
            }
        }
        if (detailPath.contains("|")) {
            val parts = detailPath.split("|")
            if (subjectId.isBlank() || !subjectId.all { it.isDigit() }) {
                subjectId = parts.getOrNull(0) ?: ""
            }
            detailPath = parts.getOrNull(1) ?: ""
        }

        if (subjectId.isBlank() && detailPath.isBlank()) {
            val parts = data.split("|")
            subjectId = parts.getOrNull(0) ?: ""
            detailPath = parts.getOrNull(1) ?: if (!data.all { it.isDigit() }) data else ""
            season = parts.getOrNull(2)?.toIntOrNull()
            episode = parts.getOrNull(3)?.toIntOrNull()
        }

        if (detailPath.isBlank() && subjectId.isNotBlank() && !subjectId.all { it.isDigit() }) {
            detailPath = subjectId
            subjectId = ""
        }

        if (subjectId.isBlank() && detailPath.isNotBlank()) {
            try {
                val details = getDetails(detailPath)
                if (details != null) {
                    val p = details.id.split("|")
                    if (p.size >= 2) {
                        subjectId = p[0]
                        if (detailPath.isBlank()) detailPath = p[1]
                    } else if (p.isNotEmpty()) {
                        subjectId = p[0]
                    }
                }
            } catch (e: Exception) {}
        }

        // Final sanitation: subjectId must be strictly numeric for Go backend
        if (subjectId.isNotBlank() && !subjectId.all { it.isDigit() }) {
            val digitsOnly = subjectId.filter { it.isDigit() }
            if (digitsOnly.length >= 10) {
                subjectId = digitsOnly
            }
        }

        val dynamicBaseUrl = getMovieBoxBaseUrl()
        val baseUrls = listOf(
            dynamicBaseUrl,
            "https://m2box.org",
            "https://netfilm.world",
            "https://moviebox.ph",
            "https://h5-api.aoneroom.com"
        ).distinct()

        val cleanDetailPath = detailPath.removePrefix("/").removePrefix("movies/").removePrefix("movie/")
        val detailPathCandidates = listOf(
            cleanDetailPath,
            detailPath
        ).distinct().filter { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }

        val results = mutableMapOf<String, String>()

        val requestHeaders = okhttp3.Headers.Builder()
            .add("Accept", "application/json")
            .add("x-client-info", """{"timezone":"Asia/Colombo"}""")
            .add("x-source", "")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            .build()

        val seEpCandidates = if (season != null && episode != null && season > 0 && episode > 0) {
            listOf(Pair(season, episode), Pair<Int?, Int?>(null, null))
        } else {
            listOf(Pair<Int?, Int?>(null, null))
        }

        outerLoop@ for (baseUrl in baseUrls) {
            for (candPath in detailPathCandidates) {
                for (seEp in seEpCandidates) {
                    val s = seEp.first
                    val e = seEp.second

                    val watchParams = StringBuilder()
                        .append("id=").append(URLEncoder.encode(subjectId, "UTF-8"))
                        .append("&type=").append(URLEncoder.encode("/movie/detail", "UTF-8"))
                        .append("&detailSe=").append(if (s != null && s > 0) s.toString() else "")
                        .append("&detailEp=").append(if (e != null && e > 0) e.toString() else "")
                        .append("&lang=en")
                        .toString()

                    val referer = "$baseUrl/movies/$candPath?$watchParams"

                    val playParams = StringBuilder()
                        .append("subjectId=").append(URLEncoder.encode(subjectId, "UTF-8"))
                        .append("&detailPath=").append(URLEncoder.encode(candPath, "UTF-8"))

                    if (s != null && e != null && s > 0 && e > 0) {
                        playParams.append("&se=").append(s).append("&ep=").append(e)
                    }

                    val playUrl = "$baseUrl/wefeed-h5api-bff/subject/play?$playParams"

                try {
                    val req = Request.Builder()
                        .url(playUrl)
                        .headers(requestHeaders)
                        .header("Referer", referer)
                        .build()

                    client.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val code = json.optInt("code", -1)
                        val playData = json.optJSONObject("data") ?: return@use

                        if (code != 0 || !playData.optBoolean("hasResource", false)) {
                            return@use
                        }

                        val availableSources = mutableListOf<JSONObject>()
                        listOf("streams", "hls", "dash").forEach { key ->
                            val arr = playData.optJSONArray(key)
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val obj = arr.optJSONObject(i) ?: continue
                                    val url = obj.optString("url")
                                    val isVip = obj.optBoolean("vipLocked", false)
                                    if (url.isNotBlank() && !isVip) {
                                        availableSources.add(obj)
                                    }
                                }
                            }
                        }

                        if (availableSources.isEmpty()) return@use

                        onProgress(0, availableSources.size)

                        for (i in 0 until availableSources.size) {
                            val source = availableSources[i]
                            val rawUrl = source.optString("url")
                            val streamId = source.optString("id")
                            val signCookie = source.optString("signCookie")
                            val format = source.optString("format", "")
                            val resolutions = source.optString("resolutions", "")
                            val quality = getQuality(resolutions) ?: source.optString("quality", "HD")

                            val playableUrl = rawUrl
                            val streamType = getStreamType(format)

                            val subUrl = getCaptions(
                                baseUrl,
                                subjectId,
                                candPath,
                                streamId,
                                format,
                                referer,
                                requestHeaders
                            )

                            val playerHeadersMap = mutableMapOf(
                                "Referer" to referer,
                                "Origin" to baseUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                                "x-client-info" to """{"timezone":"Asia/Colombo"}"""
                            )
                            if (signCookie.isNotBlank()) {
                                playerHeadersMap["Cookie"] = signCookie
                            }
                            val headersJson = com.google.gson.Gson().toJson(playerHeadersMap)

                            val finalStreamUrl = if (subUrl.isNotBlank() || headersJson.isNotBlank()) {
                                "$playableUrl###$subUrl###$headersJson"
                            } else {
                                playableUrl
                            }

                            val serverLabel = "[MovieBox] [$quality] ($streamType) Server ${i + 1}"
                            results[serverLabel] = finalStreamUrl

                            onProgress(i + 1, availableSources.size)
                        }

                        if (results.isNotEmpty()) {
                            onStreamFound(results)
                            break@outerLoop
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MovieBoxWeb", "Error trying playUrl $playUrl", e)
                }
            }
        }
    }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, String>()
        extractVideoLinksStreaming(data, onStreamFound = { map.putAll(it) })
        map
    }

    private fun resolveDashManifestFromPolicy(signCookie: String): String? {
        if (signCookie.isBlank()) return null
        for (part in signCookie.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("CloudFront-Policy=")) {
                val raw = trimmed.substring("CloudFront-Policy=".length).trim()
                val norm = raw.replace("-", "+").replace("_", "/").replace("~", "/")
                val padded = norm + "=".repeat((4 - norm.length % 4) % 4)
                try {
                    val bytes = try {
                        android.util.Base64.decode(padded, android.util.Base64.URL_SAFE)
                    } catch (_: Exception) {
                        android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
                    }
                    val decoded = String(bytes, Charsets.UTF_8)
                    val json = JSONObject(decoded)
                    val stmtArr = json.optJSONArray("Statement")
                    val stmt = stmtArr?.optJSONObject(0)
                    val resource = stmt?.optString("Resource") ?: ""
                    var baseResource = resource.trimEnd('*', '/')
                    if (baseResource.isNotBlank() && baseResource.startsWith("http")) {
                        if (baseResource.endsWith(".mpd")) return baseResource
                        if (baseResource.contains(".")) {
                            baseResource = baseResource.substringBeforeLast("/")
                        }
                        return "$baseResource/index.mpd"
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }
}
