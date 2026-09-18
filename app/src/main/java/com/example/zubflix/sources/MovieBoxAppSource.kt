package com.example.zubflix.sources

import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MovieBoxAppSource : StreamingSource {
    override val name: String = "MovieBoxApp"
    override val hasBackdropSupport: Boolean = true

    companion object {
        private const val SECRET_KEY_DEFAULT = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
        private const val SECRET_KEY_ALT = "Xqn2nnO41/L92o1iuXhSLHTbXvY4Z5ZZ62m8mSLA"
        private val BFF_HOSTS = listOf(
            "https://api3.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api6.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api6sg.aoneroom.com",
            "https://api.inmoviebox.com"
        )
    }

    private val secretBytesDefault by lazy {
        android.util.Base64.decode(SECRET_KEY_DEFAULT, android.util.Base64.DEFAULT)
    }

    private val secretBytesAlt by lazy {
        android.util.Base64.decode(SECRET_KEY_ALT, android.util.Base64.DEFAULT)
    }

    private var mobileBffToken: String? = null

    private fun md5Hex(input: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(input).joinToString("") { "%02x".format(it) }
    }

    private fun buildBffHeaders(
        url: String,
        method: String,
        bodyBytes: ByteArray?,
        token: String?,
        useAltKey: Boolean = false
    ): okhttp3.Headers {
        val ts = System.currentTimeMillis()
        val reversedTs = ts.toString().reversed()
        val clientToken = "$ts,${md5Hex(reversedTs.toByteArray(Charsets.UTF_8))}"

        val uri = android.net.Uri.parse(url)
        val path = uri.path ?: ""
        val queryKeys = uri.queryParameterNames.sorted()
        val sortedQuery = if (queryKeys.isNotEmpty()) {
            queryKeys.joinToString("&") { k ->
                "${android.net.Uri.encode(k)}=${android.net.Uri.encode(uri.getQueryParameter(k))}"
            }
        } else ""
        val canonicalUrl = if (sortedQuery.isNotEmpty()) "$path?$sortedQuery" else path

        val bodyLength = if (bodyBytes != null && bodyBytes.isNotEmpty()) bodyBytes.size.toString() else ""
        val bodyHash = if (bodyBytes != null && bodyBytes.isNotEmpty()) {
            val chunk = if (bodyBytes.size > 102400) bodyBytes.copyOf(102400) else bodyBytes
            md5Hex(chunk)
        } else ""

        val contentType = if (method.equals("POST", ignoreCase = true)) "application/json; charset=utf-8" else "application/json"
        val canonical = "$method\napplication/json\n$contentType\n$bodyLength\n$ts\n$bodyHash\n$canonicalUrl"
        val mac = javax.crypto.Mac.getInstance("HmacMD5")
        val sBytes = if (useAltKey) secretBytesAlt else secretBytesDefault
        mac.init(javax.crypto.spec.SecretKeySpec(sBytes, "HmacMD5"))
        val sigB64 = android.util.Base64.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
        val signature = "$ts|2|$sigB64"

        val clientInfo = """{"package_name":"com.community.oneroom","version_name":"4.0.01.0813.03","version_code":50020120,"os":"android","os_version":"12","install_ch":"ps","device_id":"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4","install_store":"ps","gaid":"00000000-0000-0000-0000-000000000000","brand":"Redmi","model":"2201117TY","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Kolkata","sp_code":"40401","X-Play-Mode":"2"}"""

        val builder = okhttp3.Headers.Builder()
            .add("User-Agent", "com.community.oneroom/50020120 (Linux; U; Android 12; en_US; 2201117TY; Build/S1B.220414.015; Cronet/135.0.7012.3)")
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
            .add("Connection", "keep-alive")
            .add("x-client-token", clientToken)
            .add("x-tr-signature", signature)
            .add("x-client-info", clientInfo)
            .add("x-client-status", "0")
            .add("x-forwarded-for", "103.241.10.15")

        if (!token.isNullOrBlank()) {
            builder.add("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private suspend fun getMobileBffToken(): String? = withContext(Dispatchers.IO) {
        if (!mobileBffToken.isNullOrBlank()) return@withContext mobileBffToken
        for (host in BFF_HOSTS) {
            try {
                val url = "$host/wefeed-mobile-bff/user-api/visitor-login"
                val bodyBytes = "{}".toByteArray(Charsets.UTF_8)
                val reqBody = bodyBytes.toRequestBody("application/json".toMediaType())
                val headers = buildBffHeaders(url, "POST", bodyBytes, null)
                val request = Request.Builder().url(url).headers(headers).post(reqBody).build()
                client.newCall(request).execute().use { resp ->
                    val xUser = resp.header("x-user")
                    if (!xUser.isNullOrBlank()) {
                        try {
                            val tok = JSONObject(xUser).optString("token")
                            if (tok.isNotBlank()) {
                                mobileBffToken = tok
                                return@withContext tok
                            }
                        } catch (_: Exception) {}
                    }
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val dataObj = json.optJSONObject("data")
                        val tok = dataObj?.optString("token") ?: json.optString("token")
                        if (!tok.isNullOrBlank()) {
                            mobileBffToken = tok
                            return@withContext tok
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        null
    }

    private fun isDeprecationNoticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d")
                || lower.contains("9a0461bc39da389663bf3dbb17091d3f")
                || lower.contains("/notice.mp4")
                || lower.contains("notice")
                || lower.contains("hakunaymatata.com")
                || (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
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
                    if (baseResource.isNotBlank() && (baseResource.startsWith("http://") || baseResource.startsWith("https://"))) {
                        return "$baseResource/index.mpd"
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
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
                                            sourceName = this@MovieBoxAppSource.name
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
                                        sourceName = this@MovieBoxAppSource.name
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
                            sourceName = this@MovieBoxAppSource.name
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

    private fun parseBffSubject(sub: JSONObject?, items: MutableList<StreamingItem>) {
        if (sub == null) return
        val name = sub.optString("title")
        if (name.isBlank()) return
        val subjectId = sub.optString("subjectId")
        val slug = sub.optString("detailPath")
        val posterUrl = sub.optJSONObject("cover")?.optString("url")
        val rating = sub.optString("imdbRatingValue")
        val year = sub.optString("releaseDate").take(4).ifEmpty { null }
        val isSeries = sub.optInt("subjectType") == 2

        val itemId = if (subjectId.isNotBlank() && slug.isNotBlank()) "$subjectId|$slug" else slug.ifEmpty { subjectId }
        if (itemId.isNotBlank()) {
            items.add(
                StreamingItem(
                    id = itemId,
                    title = name,
                    isSeries = isSeries,
                    imageUrl = posterUrl,
                    rating = rating,
                    year = year,
                    sourceName = this@MovieBoxAppSource.name
                )
            )
        }
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val items = mutableListOf<StreamingItem>()

        // 1. Try Mobile BFF Search API first
        try {
            val token = getMobileBffToken()
            val searchBody = JSONObject().apply {
                put("keyword", query.trim())
                put("page", 1)
                put("perPage", 20)
                put("subjectType", 0)
            }.toString().toByteArray(Charsets.UTF_8)

            for (host in BFF_HOSTS) {
                try {
                    val url = "$host/wefeed-mobile-bff/subject-api/search/v2"
                    val headers = buildBffHeaders(url, "POST", searchBody, token)
                    val req = Request.Builder().url(url).headers(headers)
                        .post(searchBody.toRequestBody("application/json".toMediaType())).build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            val json = JSONObject(body)
                            val dataObj = json.optJSONObject("data")
                            val resultsArr = dataObj?.optJSONArray("results")
                            if (resultsArr != null && resultsArr.length() > 0) {
                                for (i in 0 until resultsArr.length()) {
                                    val rObj = resultsArr.optJSONObject(i) ?: continue
                                    val subjects = rObj.optJSONArray("subjects")
                                    if (subjects != null && subjects.length() > 0) {
                                        for (j in 0 until subjects.length()) {
                                            parseBffSubject(subjects.optJSONObject(j), items)
                                        }
                                    } else if (rObj.has("subjectId")) {
                                        parseBffSubject(rObj, items)
                                    }
                                }
                                if (items.isNotEmpty()) {
                                    return@withContext items
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieBoxApp", "Mobile BFF search failed, falling back to web", e)
        }

        // 2. Fallback to Web Search API
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
                            sourceName = this@MovieBoxAppSource.name
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
                        sourceName = this@MovieBoxAppSource.name
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

        if (subjectId.isBlank() && detailPath.isBlank()) {
            val parts = data.split("|")
            subjectId = parts.getOrNull(0) ?: ""
            detailPath = parts.getOrNull(1) ?: if (!data.all { it.isDigit() }) data else ""
            season = parts.getOrNull(2)?.toIntOrNull()
            episode = parts.getOrNull(3)?.toIntOrNull()
        }

        if (detailPath.isBlank() && subjectId.isNotBlank() && !subjectId.all { it.isDigit() }) {
            detailPath = subjectId
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

        val seenStreamUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        fun cleanUrlForDedup(raw: String): String {
            return raw.substringBefore("###").substringBefore("?").trim()
        }

        coroutineScope {
            // Native Mobile BFF Play-Info API
            if (subjectId.isNotBlank()) {
                val token = getMobileBffToken()
                val se = if (season != null && season > 0) season else 0
                val ep = if (episode != null && episode > 0) episode else 0
                val targets = mutableListOf<Triple<String, String, String?>>() // subjectId, audioLabel, resourceId
                targets.add(Triple(subjectId, "Original Audio", null))

                // Query detail endpoint for dubbed audio subjects and resource detectors
                for (host in BFF_HOSTS) {
                    try {
                        val detailUrl = "$host/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
                        var headers = buildBffHeaders(detailUrl, "GET", null, token, useAltKey = false)
                        var req = Request.Builder().url(detailUrl).headers(headers).build()
                        var resp = client.newCall(req).execute()

                        if (resp.code == 401 || resp.code == 403) {
                            resp.close()
                            headers = buildBffHeaders(detailUrl, "GET", null, token, useAltKey = true)
                            req = Request.Builder().url(detailUrl).headers(headers).build()
                            resp = client.newCall(req).execute()
                        }

                        resp.use { r ->
                            val respBody = r.body?.string() ?: ""
                            if (respBody.isNotBlank()) {
                                val json = JSONObject(respBody)
                                val dataObj = json.optJSONObject("data")
                                if (dataObj != null) {
                                    // 1. Check dub subjects
                                    val dubKeys = listOf("dubs", "dubList", "languages", "audioList", "audioLanguages")
                                    var dubsArr: org.json.JSONArray? = null
                                    for (k in dubKeys) {
                                        dubsArr = dataObj.optJSONArray(k)
                                        if (dubsArr != null && dubsArr.length() > 0) break
                                    }

                                    if (dubsArr != null && dubsArr.length() > 0) {
                                        for (i in 0 until dubsArr.length()) {
                                            val dubItem = dubsArr.optJSONObject(i) ?: continue
                                            val dubId = dubItem.optString("subjectId").ifEmpty { dubItem.optString("id") }
                                            var lanName = dubItem.optString("lanName")
                                                .ifEmpty { dubItem.optString("lan") }
                                                .ifEmpty { dubItem.optString("language") }
                                                .ifEmpty { dubItem.optString("classify") }
                                                .ifEmpty { dubItem.optString("title") }

                                            if (dubId.isNotBlank() && dubId != subjectId) {
                                                if (lanName.isBlank()) lanName = "Dubbed Audio"
                                                if (!lanName.contains("Audio", ignoreCase = true) && !lanName.contains("Dub", ignoreCase = true)) {
                                                    lanName = "$lanName Audio"
                                                }
                                                val cleanLabel = lanName.replace("dub", "Audio", ignoreCase = true)
                                                targets.add(Triple(dubId, cleanLabel, null))
                                            }
                                        }
                                    }

                                    // 2. Check resource detectors / resource variants
                                    val resDetectorKeys = listOf("resourceDetectors", "resources", "resourceList", "langList")
                                    var resArr: org.json.JSONArray? = null
                                    for (rk in resDetectorKeys) {
                                        resArr = dataObj.optJSONArray(rk)
                                        if (resArr != null && resArr.length() > 0) break
                                    }
                                    if (resArr != null && resArr.length() > 0) {
                                        for (i in 0 until resArr.length()) {
                                            val resItem = resArr.optJSONObject(i) ?: continue
                                            val resId = resItem.optString("resourceId").ifEmpty { resItem.optString("id") }
                                            var lanName = resItem.optString("lanName")
                                                .ifEmpty { resItem.optString("lan") }
                                                .ifEmpty { resItem.optString("language") }
                                                .ifEmpty { resItem.optString("name") }
                                                .ifEmpty { resItem.optString("title") }
                                            if (resId.isNotBlank()) {
                                                if (lanName.isBlank()) lanName = "Audio Track ${i + 1}"
                                                if (!lanName.contains("Audio", ignoreCase = true) && !lanName.contains("Dub", ignoreCase = true)) {
                                                    lanName = "$lanName Audio"
                                                }
                                                val cleanLabel = lanName.replace("dub", "Audio", ignoreCase = true)
                                                targets.add(Triple(subjectId, cleanLabel, resId))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        break
                    } catch (e: Exception) {
                        android.util.Log.d("MovieBoxApp", "Error checking dubs/resources from $host: ${e.message}")
                    }
                }

                val bffStreams = mutableMapOf<String, String>()
                var bffServerCounter = 1

                for (target in targets) {
                    val targetSubjectId = target.first
                    val audioLabel = target.second
                    val targetResourceId = target.third

                    val bffPathBuilder = StringBuilder("/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=$targetSubjectId")
                    if (se > 0 || ep > 0) {
                        bffPathBuilder.append("&se=$se&ep=$ep")
                    }
                    if (!targetResourceId.isNullOrBlank()) {
                        bffPathBuilder.append("&resourceId=$targetResourceId")
                    }
                    val bffPath = bffPathBuilder.toString()

                for (host in BFF_HOSTS) {
                    try {
                        val url = "$host$bffPath"
                        var headers = buildBffHeaders(url, "GET", null, token, useAltKey = false)
                        var req = Request.Builder().url(url).headers(headers).build()
                        var resp = client.newCall(req).execute()

                        if (resp.code == 401 || resp.code == 403) {
                            resp.close()
                            headers = buildBffHeaders(url, "GET", null, token, useAltKey = true)
                            req = Request.Builder().url(url).headers(headers).build()
                            resp = client.newCall(req).execute()
                        }

                        resp.use { r ->
                            val respBody = r.body?.string() ?: ""
                            if (respBody.isNotBlank()) {
                                val json = JSONObject(respBody)
                                val data = json.optJSONObject("data")
                                val streams = data?.optJSONArray("streams")
                                if (streams != null && streams.length() > 0) {
                                    val captionArrayNames = listOf("extCaptions", "captions", "captionList", "subtitles", "extCaptionList", "extCaptionsList", "caption")
                                    var extCaptionsArr: org.json.JSONArray? = null
                                    for (cName in captionArrayNames) {
                                        extCaptionsArr = data.optJSONArray(cName) ?: json.optJSONArray(cName)
                                        if (extCaptionsArr != null && extCaptionsArr.length() > 0) break
                                    }

                                    fun isValidSub(u: String, s: Long): Boolean {
                                        if (u.isBlank() || u.contains("aa348f2541d13ffe")) return false
                                        if (s in 1..50) return false
                                        return true
                                    }

                                    var subUrl = ""
                                    if (extCaptionsArr != null) {
                                        for (cIdx in 0 until extCaptionsArr.length()) {
                                            val cap = extCaptionsArr.optJSONObject(cIdx) ?: continue
                                            val u = cap.optString("url").ifEmpty { cap.optString("file") }.ifEmpty { cap.optString("path") }
                                            val lan = cap.optString("lanName").ifEmpty { cap.optString("lan") }.ifEmpty { cap.optString("language") }
                                            val sz = cap.optLong("size", 0)
                                            if (isValidSub(u, sz) && (lan.contains("en", ignoreCase = true) || subUrl.isBlank())) {
                                                subUrl = u
                                                if (lan.contains("en", ignoreCase = true)) break
                                            }
                                        }
                                    }

                                    val bffStreams = mutableMapOf<String, String>()
                                    var bffServerCounter = 1
                                    for (sIdx in 0 until streams.length()) {
                                        val streamObj = streams.optJSONObject(sIdx) ?: continue
                                        val streamId = streamObj.optString("id")
                                        val signCookie = streamObj.optString("signCookie")
                                        val streamUrl = streamObj.optString("url")
                                        val format = streamObj.optString("format", "MP4")
                                        val res = streamObj.optString("resolutions", "1080")
                                        val quality = getQuality(res) ?: "1080p"

                                        var streamSubUrl = subUrl
                                        if (streamSubUrl.isBlank() && streamId.isNotBlank()) {
                                            try {
                                                val extCapUrl = "$host/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$targetSubjectId&resourceId=$streamId"
                                                val extCapReq = Request.Builder().url(extCapUrl).headers(buildBffHeaders(extCapUrl, "GET", null, token)).build()
                                                client.newCall(extCapReq).execute().use { capResp ->
                                                    val capBody = capResp.body?.string() ?: ""
                                                    if (capBody.isNotBlank()) {
                                                        val capJson = JSONObject(capBody)
                                                        val capData = capJson.optJSONObject("data") ?: capJson
                                                        val extArr = capData.optJSONArray("extCaptions") ?: capData.optJSONArray("captions")
                                                        if (extArr != null) {
                                                            for (cIdx in 0 until extArr.length()) {
                                                                val cap = extArr.optJSONObject(cIdx) ?: continue
                                                                val u = cap.optString("url").ifEmpty { cap.optString("file") }
                                                                val lan = cap.optString("lanName").ifEmpty { cap.optString("lan") }
                                                                val sz = cap.optLong("size", 0)
                                                                if (isValidSub(u, sz) && (lan.contains("en", ignoreCase = true) || streamSubUrl.isBlank())) {
                                                                    streamSubUrl = u
                                                                    if (lan.contains("en", ignoreCase = true)) break
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {}
                                        }

                                        if (streamSubUrl.isBlank()) {
                                            try {
                                                val dynamicBaseUrl = getMovieBoxBaseUrl()
                                                streamSubUrl = getCaptions(dynamicBaseUrl, targetSubjectId, detailPath, streamId, format, "https://m2box.org", okhttp3.Headers.Builder().build())
                                            } catch (e: Exception) {}
                                        }

                                        // Prioritize DASH manifest resolved from signed cookie policy.
                                        // MovieBox delivers dummy update notice MP4 files in streamUrl, but legitimate DASH streams in signCookie.
                                        val dashManifestUrl = resolveDashManifestFromPolicy(signCookie)
                                        val playableUrl = when {
                                            !dashManifestUrl.isNullOrBlank() -> dashManifestUrl
                                            isDeprecationNoticeUrl(streamUrl) -> null
                                            streamUrl.startsWith("http") -> streamUrl
                                            else -> null
                                        }

                                        if (!playableUrl.isNullOrBlank()) {
                                            val dedupKey = "$audioLabel|${cleanUrlForDedup(playableUrl)}"
                                            if (seenStreamUrls.add(dedupKey)) {
                                                val playerHeaders = mutableMapOf(
                                                    "User-Agent" to "com.community.oneroom/50020120 (Linux; U; Android 12; en_US; 2201117TY; Build/S1B.220414.015; Cronet/135.0.7012.3)",
                                                    "Referer" to "https://sportslive.wine"
                                                )
                                                if (signCookie.isNotBlank()) {
                                                    playerHeaders["Cookie"] = signCookie
                                                }
                                                val headersJson = com.google.gson.Gson().toJson(playerHeaders)
                                                val finalStreamUrl = "$playableUrl###$streamSubUrl###$headersJson"
                                                val isDash = playableUrl.endsWith(".mpd") || format.equals("DASH", ignoreCase = true)
                                                val streamType = if (isDash) "DASH" else format.uppercase()
                                                val countSuffix = if (bffServerCounter > 1) " #$bffServerCounter" else ""
                                                bffServerCounter++
                                                val label = "Moviebox ($audioLabel) $quality$countSuffix"
                                                bffStreams[label] = finalStreamUrl
                                            }
                                        }
                                    }
                                    if (bffStreams.isNotEmpty()) {
                                        onStreamFound(bffStreams)
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("MovieBoxApp", "Host $host unavailable for play-info: ${e.message}")
                    }
                }
            }

            if (seenStreamUrls.isNotEmpty()) {
                return@coroutineScope
            }

                // Fallback: If play-info didn't provide playable streams, query /subject-api/resource
                for (host in BFF_HOSTS) {
                    try {
                        val se = if (season != null && season > 0) season else 0
                        val ep = if (episode != null && episode > 0) episode else 0
                        val resPath = if (se == 0 && ep == 0) {
                            "/wefeed-mobile-bff/subject-api/resource?subjectId=$subjectId&page=1&perPage=20"
                        } else {
                            "/wefeed-mobile-bff/subject-api/resource?subjectId=$subjectId&se=$se&ep=$ep&page=1&perPage=20"
                        }
                        val url = "$host$resPath"
                        var headers = buildBffHeaders(url, "GET", null, token, useAltKey = false)
                        var req = Request.Builder().url(url).headers(headers).build()
                        var resp = client.newCall(req).execute()

                        if (resp.code == 401 || resp.code == 403) {
                            resp.close()
                            headers = buildBffHeaders(url, "GET", null, token, useAltKey = true)
                            req = Request.Builder().url(url).headers(headers).build()
                            resp = client.newCall(req).execute()
                        }

                        resp.use { r ->
                            val respBody = r.body?.string() ?: ""
                            if (respBody.isNotBlank()) {
                                val json = JSONObject(respBody)
                                val data = json.optJSONObject("data") ?: json
                                val list = data.optJSONArray("list")
                                if (list != null && list.length() > 0) {
                                    val resourceStreams = mutableMapOf<String, String>()
                                    var resCounter = 1
                                    for (i in 0 until list.length()) {
                                        val item = list.optJSONObject(i) ?: continue
                                        val itemSe = item.optInt("se", 0)
                                        val itemEp = item.optInt("ep", 0)
                                        if (se != 0 && ep != 0 && (itemSe != se || itemEp != ep)) {
                                            continue
                                        }
                                        val rawUrl = item.optString("url").ifEmpty { item.optString("downloadUrl") }
                                        val signCookie = item.optString("signCookie")
                                        val dashUrl = resolveDashManifestFromPolicy(signCookie)
                                        val playable = when {
                                            !dashUrl.isNullOrBlank() -> dashUrl
                                            isDeprecationNoticeUrl(rawUrl) -> null
                                            rawUrl.startsWith("http") -> rawUrl
                                            else -> null
                                        } ?: continue

                                        val dedup = cleanUrlForDedup(playable)
                                        if (seenStreamUrls.add(dedup)) {
                                            val res = item.optString("resolution", "1080")
                                            val quality = getQuality(res) ?: "${res}p"
                                            val playerHeaders = mutableMapOf(
                                                "User-Agent" to "com.community.oneroom/50020120 (Linux; U; Android 12; en_US; 2201117TY; Build/S1B.220414.015; Cronet/135.0.7012.3)",
                                                "Referer" to "https://sportslive.wine"
                                            )
                                            if (signCookie.isNotBlank()) {
                                                playerHeaders["Cookie"] = signCookie
                                            }
                                            val headersJson = com.google.gson.Gson().toJson(playerHeaders)
                                            val finalStreamUrl = "$playable######$headersJson"
                                            val isDash = playable.endsWith(".mpd")
                                            val streamType = if (isDash) "mpd" else "mp4"
                                            val label = "[MovieBoxBFF] [$quality] ($streamType) Resource ${resCounter++}"
                                            resourceStreams[label] = finalStreamUrl
                                        }
                                    }
                                    if (resourceStreams.isNotEmpty()) {
                                        onStreamFound(resourceStreams)
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("MovieBoxApp", "Host $host unavailable for resource: ${e.message}")
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
}



