package com.example.zubflix.sources

import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MovieBoxINSource : StreamingSource {
    override val name: String = "MovieBoxIN"
    override val hasBackdropSupport: Boolean = true

    companion object {
        private const val SECRET_KEY_DEFAULT = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
        private const val SECRET_KEY_ALT = "Xqn2nnO41/L92o1iuXhSLHTbXvY4Z5ZZ62m8mSLA"
        private const val TOKEN_FETCH_URL = "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"

        private val HOST_POOL = listOf(
            "https://api3.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api6.aoneroom.com",
            "https://api4sg.aoneroom.com"
        )
    }

    private var mainUrl = "https://api3.aoneroom.com"
    private val tokenMutex = Mutex()

    private val deviceId: String by lazy {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    private val modernUserAgent = "com.community.mbox.in/50020126 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"

    private val modernClientInfo: String by lazy {
        """{"package_name":"com.community.mbox.in","version_name":"4.0.02.0831.03","version_code":50020126,"os":"android","os_version":"14","device_id":"$deviceId","install_store":"official","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Google","model":"Pixel 8","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}"""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var bearerToken: String? = null

    private fun md5Hex(input: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input).joinToString("") { "%02x".format(it) }
    }

    private fun buildCanonicalString(
        method: String,
        accept: String,
        contentType: String,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        val parsed = try {
            URI(url)
        } catch (_: Exception) {
            null
        }
        val path = parsed?.path ?: ""
        val query = parsed?.query
        val sortedQuery = if (!query.isNullOrBlank()) {
            val pairs = query.split("&").mapNotNull { param ->
                val p = param.split("=")
                if (p.isNotEmpty()) Pair(p[0], p.getOrNull(1) ?: "") else null
            }
            pairs.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
        } else ""
        val canonicalUrl = if (sortedQuery.isNotEmpty()) "$path?$sortedQuery" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyLength = if (bodyBytes != null && bodyBytes.isNotEmpty()) bodyBytes.size.toString() else ""
        val bodyHash = if (bodyBytes != null && bodyBytes.isNotEmpty()) {
            val chunk = if (bodyBytes.size > 102400) bodyBytes.copyOf(102400) else bodyBytes
            md5Hex(chunk)
        } else ""

        val upperMethod = method.uppercase()
        return "$upperMethod\n$accept\n$contentType\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
    }

    private fun generateXClientToken(timestamp: Long): String {
        val reversed = timestamp.toString().reversed()
        return "$timestamp,${md5Hex(reversed.toByteArray(Charsets.UTF_8))}"
    }

    private fun generateXTrSignature(
        method: String,
        accept: String,
        contentType: String,
        url: String,
        body: String?,
        useAltKey: Boolean = false,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secretKey = if (useAltKey) SECRET_KEY_ALT else SECRET_KEY_DEFAULT
        val secretBytes = android.util.Base64.decode(secretKey, android.util.Base64.DEFAULT)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val sig = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val sigB64 = android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP)
        return "$timestamp|2|$sigB64"
    }

    private fun isTokenValid(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val exp = decodeJwtExpiry(token)
        if (exp == 0L) {
            return token.length > 20
        }
        return exp > (System.currentTimeMillis() / 1000) + 60
    }

    private fun decodeJwtExpiry(token: String): Long {
        return try {
            val parts = token.split(".")
            val payload = parts.getOrNull(1) ?: return 0L
            val base64 = payload.replace('-', '+').replace('_', '/')
            val rem = base64.length % 4
            val padded = if (rem > 0) base64 + "=".repeat(4 - rem) else base64
            val json = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
            JSONObject(json).optLong("exp", 0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun persistTokenFromXUser(xUserHeader: String?) {
        if (!xUserHeader.isNullOrBlank()) {
            try {
                val tok = JSONObject(xUserHeader).optString("token")
                if (tok.isNotBlank() && isTokenValid(tok)) {
                    bearerToken = tok
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun fetchAnonymousToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            if (!forceRefresh && isTokenValid(bearerToken)) {
                return@withContext bearerToken!!
            }

            val targetHosts = mutableListOf(TOKEN_FETCH_URL)
            targetHosts.add("$mainUrl/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1")
            for (h in HOST_POOL) {
                if (h != mainUrl) {
                    targetHosts.add("$h/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1")
                }
            }

            for (tokenUrl in targetHosts) {
                val ts = System.currentTimeMillis()
                val xClientToken = generateXClientToken(ts)
                val xTrSig = generateXTrSignature(
                    method = "GET",
                    accept = "application/json",
                    contentType = "application/json",
                    url = tokenUrl,
                    body = null,
                    useAltKey = false,
                    timestamp = ts
                )

                val reqHeaders = okhttp3.Headers.Builder()
                    .add("user-agent", modernUserAgent)
                    .add("accept", "application/json")
                    .add("content-type", "application/json")
                    .add("connection", "keep-alive")
                    .add("x-client-token", xClientToken)
                    .add("x-tr-signature", xTrSig)
                    .add("x-client-info", modernClientInfo)
                    .add("x-client-status", "0")
                    .build()

                try {
                    val req = Request.Builder().url(tokenUrl).headers(reqHeaders).build()
                    client.newCall(req).execute().use { resp ->
                        val xUser = resp.header("x-user")
                        persistTokenFromXUser(xUser)
                        if (!bearerToken.isNullOrBlank()) {
                            return@withContext bearerToken!!
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MovieBoxIN", "Token fetch from $tokenUrl failed: ${e.message}")
                }
            }

            bearerToken ?: ""
        }
    }

    private suspend fun buildAuthHeaders(
        url: String,
        method: String = "GET",
        contentType: String = if (method.equals("POST", ignoreCase = true)) "application/json; charset=utf-8" else "application/json",
        accept: String = "application/json",
        body: String? = null,
        useToken: Boolean = true,
        useAltKey: Boolean = false
    ): okhttp3.Headers {
        val ts = System.currentTimeMillis()
        val xClientToken = generateXClientToken(ts)
        val xTrSig = generateXTrSignature(
            method = method,
            accept = accept,
            contentType = contentType,
            url = url,
            body = body,
            useAltKey = useAltKey,
            timestamp = ts
        )

        val builder = okhttp3.Headers.Builder()
            .add("user-agent", modernUserAgent)
            .add("accept", accept)
            .add("content-type", contentType)
            .add("connection", "keep-alive")
            .add("x-client-token", xClientToken)
            .add("x-tr-signature", xTrSig)
            .add("x-client-info", modernClientInfo)
            .add("x-client-status", "0")

        if (useToken) {
            val tok = fetchAnonymousToken()
            if (tok.isNotBlank()) {
                builder.add("Authorization", "Bearer $tok")
            }
        }

        return builder.build()
    }

    private fun extractPolicyResource(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null

        // 1. Check Edge-Cache-Cookie format: Edge-Cache-Cookie=urlprefix=aHR0cHM6...:sign=...:t=...
        val edgeMatch = Regex("Edge-Cache-Cookie=.*?urlprefix=([^:;]+)").find(signCookie)
        if (edgeMatch != null) {
            try {
                val rawPrefix = edgeMatch.groupValues[1]
                val rem = rawPrefix.length % 4
                val padded = if (rem > 0) rawPrefix + "=".repeat(4 - rem) else rawPrefix
                val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val trimmed = decoded.trimEnd('/')
                return if (trimmed.endsWith(".mpd", ignoreCase = true)) trimmed else "$trimmed/index.mpd"
            } catch (_: Exception) {}
        }

        // 2. Check CloudFront-Policy format: CloudFront-Policy=...
        val cfMatch = Regex("CloudFront-Policy=([^;]+)").find(signCookie)
        if (cfMatch != null) {
            val policyRaw = cfMatch.groupValues[1]
            val cfB64 = policyRaw.replace('-', '+').replace('~', '/').replace('_', '=')
            val rem = cfB64.length % 4
            val paddedCfB64 = if (rem > 0) cfB64 + "=".repeat(4 - rem) else cfB64
            var decodedJson: String? = null
            try {
                decodedJson = String(android.util.Base64.decode(paddedCfB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                try {
                    val stdB64 = policyRaw.replace('-', '+').replace('_', '/')
                    val stdRem = stdB64.length % 4
                    val paddedStd = if (stdRem > 0) stdB64 + "=".repeat(4 - stdRem) else stdB64
                    decodedJson = String(android.util.Base64.decode(paddedStd, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) {}
            }

            if (decodedJson != null) {
                try {
                    val root = JSONObject(decodedJson)
                    val statement = root.optJSONArray("Statement")?.optJSONObject(0)
                    val resource = statement?.optString("Resource")
                    if (!resource.isNullOrBlank()) {
                        val trimmed = resource.trimEnd('*', '/')
                        return if (trimmed.endsWith(".mpd", ignoreCase = true)) trimmed else "$trimmed/index.mpd"
                    }
                } catch (_: Exception) {}
            }
        }

        return null
    }

    data class MovieBoxMainCategory(val data: String, val title: String)

    val MAIN_PAGE_CATEGORIES = listOf(
        MovieBoxMainCategory("r|0|9167640870324258216", "Trending Movies"),
        MovieBoxMainCategory("r|0|5692654647815587592", "In Cinema"),
        MovieBoxMainCategory("r|0|414907768299210008", "Bollywood"),
        MovieBoxMainCategory("r|0|3859721901924910512", "South Indian"),
        MovieBoxMainCategory("r|0|8019599703232971616", "Hollywood"),
        MovieBoxMainCategory("r|0|1488104699998914056", "New Release"),
        MovieBoxMainCategory("r|0|6027735606879570952", "New Punjabi"),
        MovieBoxMainCategory("r|0|6144409817256968824", "New Bengali"),
        MovieBoxMainCategory("r|5|719331337777440448", "Top Series"),
        MovieBoxMainCategory("r|5|4903182713986896328", "Indian Drama"),
        MovieBoxMainCategory("r|5|1255898847918934600", "Reality TV"),
        MovieBoxMainCategory("r|5|1976033493293449744", "Asian Drama"),
        MovieBoxMainCategory("r|5|3910636007619709856", "Western TV"),
        MovieBoxMainCategory("r|5|5177200225164885656", "Turkish Drama"),
        MovieBoxMainCategory("1|1", "Movies"),
        MovieBoxMainCategory("1|2", "Series"),
        MovieBoxMainCategory("1|1006", "Anime"),
        MovieBoxMainCategory("2|2;country=Japan;genre=Animation", "Anime (Series)"),
        MovieBoxMainCategory("1|1;country=India", "Indian (Movies)"),
        MovieBoxMainCategory("1|2;country=India", "Indian (Series)"),
        MovieBoxMainCategory("1|1;classify=Hindi dub;country=United States", "USA (Movies)"),
        MovieBoxMainCategory("1|2;classify=Hindi dub;country=United States", "USA (Series)"),
        MovieBoxMainCategory("1|1;country=Japan", "Japan (Movies)"),
        MovieBoxMainCategory("1|2;country=Japan", "Japan (Series)"),
        MovieBoxMainCategory("1|1;country=China", "China (Movies)"),
        MovieBoxMainCategory("1|2;country=China", "China (Series)"),
        MovieBoxMainCategory("1|1;country=Philippines", "Philippines (Movies)"),
        MovieBoxMainCategory("1|2;country=Philippines", "Philippines (Series)"),
        MovieBoxMainCategory("1|1;country=Thailand", "Thailand (Movies)"),
        MovieBoxMainCategory("1|2;country=Thailand", "Thailand (Series)"),
        MovieBoxMainCategory("1|1;country=Nigeria", "Nollywood (Movies)"),
        MovieBoxMainCategory("1|2;country=Nigeria", "Nollywood (Series)"),
        MovieBoxMainCategory("1|1;country=Korea", "South Korean (Movies)"),
        MovieBoxMainCategory("1|2;country=Korea", "South Korean (Series)"),
        MovieBoxMainCategory("1|1;classify=Hindi dub;genre=Action", "Action (Movies)"),
        MovieBoxMainCategory("1|1;classify=Hindi dub;genre=Crime", "Crime (Movies)"),
        MovieBoxMainCategory("1|1;classify=Hindi dub;genre=Comedy", "Comedy (Movies)"),
        MovieBoxMainCategory("1|2;classify=Hindi dub;genre=Crime", "Crime (Series)"),
        MovieBoxMainCategory("1|2;classify=Hindi dub;genre=Comedy", "Comedy (Series)")
    )

    private val HOME_FEATURED_CATEGORIES = listOf(
        MovieBoxMainCategory("r|0|9167640870324258216", "Trending Movies"),
        MovieBoxMainCategory("r|0|5692654647815587592", "In Cinema"),
        MovieBoxMainCategory("r|0|414907768299210008", "Bollywood"),
        MovieBoxMainCategory("r|0|3859721901924910512", "South Indian"),
        MovieBoxMainCategory("r|0|8019599703232971616", "Hollywood"),
        MovieBoxMainCategory("r|0|1488104699998914056", "New Release"),
        MovieBoxMainCategory("r|5|719331337777440448", "Top Series"),
        MovieBoxMainCategory("r|5|4903182713986896328", "Indian Drama"),
        MovieBoxMainCategory("r|5|3910636007619709856", "Western TV"),
        MovieBoxMainCategory("1|1", "Movies"),
        MovieBoxMainCategory("1|2", "Series"),
        MovieBoxMainCategory("1|1006", "Anime")
    )

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(3)
        coroutineScope {
            val tasks = HOME_FEATURED_CATEGORIES.map { cat ->
                cat to async {
                    semaphore.withPermit {
                        try {
                            getCategoryContent(cat.data, 1)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }
            }

            val categories = mutableListOf<StreamingCategory>()
            for ((cat, deferred) in tasks) {
                val items = deferred.await()
                if (items.isNotEmpty()) {
                    categories.add(StreamingCategory(cat.data, cat.title, items))
                }
            }
            categories
        }
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val categoryData = if (categoryId.contains("|")) categoryId else {
            when (categoryId) {
                "trending", "hindi_dub" -> "r|0|9167640870324258216"
                "movies", "popular" -> "1|1"
                "series" -> "1|2"
                "bollywood" -> "r|0|414907768299210008"
                "south_indian" -> "r|0|3859721901924910512"
                "hollywood" -> "r|0|8019599703232971616"
                "new_release" -> "r|0|1488104699998914056"
                "punjabi" -> "r|0|6027735606879570952"
                "bengali" -> "r|0|6144409817256968824"
                else -> categoryId
            }
        }

        val isRanking = categoryData.startsWith("r|")
        val method: String
        val requestUrl: String
        val requestBody: String?

        if (isRanking) {
            val parts = categoryData.split("|")
            val tabId = parts.getOrNull(1) ?: "0"
            val categoryType = parts.getOrNull(2) ?: "1"
            requestUrl = "$mainUrl/wefeed-mobile-bff/tab/ranking-list?tabId=$tabId&categoryType=$categoryType&page=$page&perPage=20&restrictKid=0"
            method = "GET"
            requestBody = null
        } else {
            val basePart = categoryData.substringBefore(";")
            val queryParamsStr = categoryData.substringAfter(";", "")
            val channelId = basePart.split("|").getOrNull(1) ?: "1"
            val paramsMap = mutableMapOf<String, String>()
            if (queryParamsStr.isNotEmpty()) {
                queryParamsStr.split(";").forEach { kv ->
                    val p = kv.split("=")
                    if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                        paramsMap[p[0]] = p[1]
                    }
                }
            }
            val classify = paramsMap["classify"] ?: "All"
            val country = paramsMap["country"] ?: "All"
            val year = paramsMap["year"] ?: "All"
            val genre = paramsMap["genre"] ?: "All"
            val sort = paramsMap["sort"] ?: "ForYou"

            requestUrl = "$mainUrl/wefeed-mobile-bff/subject-api/list"
            method = "POST"
            requestBody = """{"page":$page,"perPage":20,"channelId":"$channelId","classify":"$classify","country":"$country","year":"$year","genre":"$genre","sort":"$sort","restrictKid":0}"""
        }

        for (host in listOf(mainUrl) + HOST_POOL.filter { it != mainUrl }) {
            val finalUrl = if (host == mainUrl) requestUrl else requestUrl.replace(mainUrl, host)
            val contentType = if (method == "POST") "application/json; charset=utf-8" else "application/json"
            try {
                val headers = buildAuthHeaders(
                    url = finalUrl,
                    method = method,
                    contentType = contentType,
                    body = requestBody,
                    useToken = true
                )
                val reqBuilder = Request.Builder().url(finalUrl).headers(headers)
                if (method == "POST" && requestBody != null) {
                    reqBuilder.post(requestBody.toRequestBody(contentType.toMediaType()))
                } else {
                    reqBuilder.get()
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code == 401) {
                        bearerToken = null
                        return@use
                    }
                    persistTokenFromXUser(resp.header("x-user"))
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val dataObj = json.optJSONObject("data")
                        val dataArr = json.optJSONArray("data")
                        val items = dataObj?.optJSONArray("items")
                            ?: dataObj?.optJSONArray("subjects")
                            ?: dataObj?.optJSONArray("list")
                            ?: dataArr
                        if (items != null && items.length() > 0) {
                            mainUrl = host
                            val result = mutableListOf<StreamingItem>()
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i) ?: continue
                                val titleRaw = item.optString("title").ifEmpty { item.optString("name") }
                                val title = titleRaw.substringBefore("[")
                                val id = item.optString("subjectId").ifEmpty { item.optString("id") }
                                val coverObj = item.optJSONObject("cover")
                                val poster = coverObj?.optString("url") ?: item.optString("posterUrl")
                                val isSeries = item.optInt("subjectType", 1) == 2 || item.optBoolean("isSeries", false)
                                if (id.isNotBlank() && title.isNotBlank()) {
                                    result.add(
                                        StreamingItem(
                                            id = id,
                                            title = title,
                                            imageUrl = poster,
                                            backdropUrl = poster,
                                            isSeries = isSeries,
                                            sourceName = name
                                        )
                                    )
                                }
                            }
                            if (result.isNotEmpty()) return@withContext result
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieBoxIN", "Error fetching category items from $host", e)
            }
        }
        emptyList()
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val searchBody = """{"page": 1, "perPage": 20, "keyword": "$query", "restrictKid": 0}"""
        val contentType = "application/json; charset=utf-8"

        for (host in listOf(mainUrl) + HOST_POOL.filter { it != mainUrl }) {
            try {
                val searchUrl = "$host/wefeed-mobile-bff/subject-api/search/v2"
                val headers = buildAuthHeaders(url = searchUrl, method = "POST", contentType = contentType, body = searchBody, useToken = true)
                val req = Request.Builder()
                    .url(searchUrl)
                    .headers(headers)
                    .post(searchBody.toRequestBody(contentType.toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 401) {
                        bearerToken = null
                        return@use
                    }
                    persistTokenFromXUser(resp.header("x-user"))
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val dataObj = json.optJSONObject("data")
                        val resultsArr = dataObj?.optJSONArray("results")
                        val list = mutableListOf<StreamingItem>()

                        if (resultsArr != null && resultsArr.length() > 0) {
                            for (rIdx in 0 until resultsArr.length()) {
                                val group = resultsArr.optJSONObject(rIdx) ?: continue
                                val subjects = group.optJSONArray("subjects") ?: continue
                                for (sIdx in 0 until subjects.length()) {
                                    val item = subjects.optJSONObject(sIdx) ?: continue
                                    val titleRaw = item.optString("title").ifEmpty { item.optString("name") }
                                    val title = titleRaw.substringBefore("[")
                                    val id = item.optString("subjectId").ifEmpty { item.optString("id") }
                                    val coverObj = item.optJSONObject("cover")
                                    val poster = coverObj?.optString("url") ?: item.optString("posterUrl")
                                    val isSeries = item.optInt("subjectType", 1) == 2 || item.optBoolean("isSeries", false)
                                    if (id.isNotBlank() && title.isNotBlank()) {
                                        list.add(
                                            StreamingItem(
                                                id = id,
                                                title = title,
                                                imageUrl = poster,
                                                backdropUrl = poster,
                                                isSeries = isSeries,
                                                sourceName = name
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Fallback to top-level items if results format varies
                        if (list.isEmpty()) {
                            val items = dataObj?.optJSONArray("items")
                                ?: dataObj?.optJSONArray("subjects")
                                ?: json.optJSONArray("data")
                            if (items != null) {
                                for (i in 0 until items.length()) {
                                    val item = items.optJSONObject(i) ?: continue
                                    val titleRaw = item.optString("title").ifEmpty { item.optString("name") }
                                    val title = titleRaw.substringBefore("[")
                                    val id = item.optString("subjectId").ifEmpty { item.optString("id") }
                                    val coverObj = item.optJSONObject("cover")
                                    val poster = coverObj?.optString("url") ?: item.optString("posterUrl")
                                    val isSeries = item.optInt("subjectType", 1) == 2 || item.optBoolean("isSeries", false)
                                    if (id.isNotBlank() && title.isNotBlank()) {
                                        list.add(
                                            StreamingItem(
                                                id = id,
                                                title = title,
                                                imageUrl = poster,
                                                backdropUrl = poster,
                                                isSeries = isSeries,
                                                sourceName = name
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        if (list.isNotEmpty()) {
                            mainUrl = host
                            return@withContext list
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieBoxIN", "Error executing search from $host", e)
            }
        }
        emptyList()
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        val subjectId = id.split("|").firstOrNull() ?: id
        if (subjectId.isBlank()) return@withContext null

        for (host in listOf(mainUrl) + HOST_POOL.filter { it != mainUrl }) {
            try {
                val detailUrl = "$host/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
                val headers = buildAuthHeaders(url = detailUrl, method = "GET", useToken = true)
                val request = Request.Builder().url(detailUrl).headers(headers).build()
                client.newCall(request).execute().use { resp ->
                    if (resp.code == 401) {
                        bearerToken = null
                        return@use
                    }
                    persistTokenFromXUser(resp.header("x-user"))
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val data = json.optJSONObject("data") ?: return@use
                        mainUrl = host
                        val titleRaw = data.optString("title").ifEmpty { data.optString("name") }
                        val title = titleRaw.substringBefore("[")
                        val coverObj = data.optJSONObject("cover")
                        val poster = coverObj?.optString("url") ?: data.optString("posterUrl")
                        val desc = data.optString("description").ifEmpty { data.optString("intro") }
                        val subjectType = data.optInt("subjectType", 1)
                        val isSeries = subjectType == 2 || data.optBoolean("isSeries", false)
                        val rating = data.optString("imdbRatingValue")
                            .ifEmpty { data.optString("imdbScore") }
                            .ifEmpty { data.optString("score") }
                        val year = data.optString("releaseDate").ifEmpty { data.optString("year") }

                        val seasonsList = mutableListOf<StreamingSeason>()
                        if (isSeries) {
                            try {
                                val seasonUrl = "$host/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
                                val sHeaders = buildAuthHeaders(url = seasonUrl, method = "GET", useToken = true)
                                val sReq = Request.Builder().url(seasonUrl).headers(sHeaders).build()
                                client.newCall(sReq).execute().use { sResp ->
                                    persistTokenFromXUser(sResp.header("x-user"))
                                    val sBody = sResp.body?.string() ?: ""
                                    if (sBody.isNotBlank()) {
                                        val sJson = JSONObject(sBody)
                                        val sData = sJson.optJSONObject("data")
                                        val seasonsArr = sData?.optJSONArray("seasons")
                                        if (seasonsArr != null && seasonsArr.length() > 0) {
                                            for (sIdx in 0 until seasonsArr.length()) {
                                                val seasonObj = seasonsArr.optJSONObject(sIdx) ?: continue
                                                val sNum = seasonObj.optInt("se", sIdx + 1)
                                                val maxEp = seasonObj.optInt("maxEp", 1)
                                                val epArr = seasonObj.optJSONArray("episodes")
                                                val epCount = if (epArr != null && epArr.length() > 0) epArr.length() else maxEp

                                                val epList = (1..epCount).map { epNum ->
                                                    StreamingEpisode(
                                                        title = "Episode $epNum",
                                                        streamUrl = "$subjectId|$subjectId|$sNum|$epNum"
                                                    )
                                                }
                                                seasonsList.add(StreamingSeason(title = "Season $sNum", episodes = epList, seasonNumber = sNum))
                                            }
                                        }
                                    }
                                }
                            } catch (se: Exception) {
                                android.util.Log.e("MovieBoxIN", "Error fetching season-info", se)
                            }

                            // Fallback if season-info was empty
                            if (seasonsList.isEmpty()) {
                                val seasonCount = data.optInt("seasonCount", 1).coerceAtLeast(1)
                                for (sNum in 1..seasonCount) {
                                    val epList = (1..24).map { epNum ->
                                        StreamingEpisode(
                                            title = "Episode $epNum",
                                            streamUrl = "$subjectId|$subjectId|$sNum|$epNum"
                                        )
                                    }
                                    seasonsList.add(StreamingSeason(title = "Season $sNum", episodes = epList, seasonNumber = sNum))
                                }
                            }
                        }

                        return@withContext StreamingItem(
                            id = subjectId,
                            title = title,
                            imageUrl = poster,
                            backdropUrl = poster,
                            description = desc,
                            isSeries = isSeries,
                            seasons = if (isSeries) seasonsList else null,
                            year = year,
                            rating = rating,
                            sourceName = name
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieBoxIN", "Error fetching details from $host", e)
            }
        }
        null
    }

    private fun getQuality(resolutions: String?): String? {
        if (resolutions.isNullOrBlank()) return null
        val values = resolutions.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in listOf(360, 480, 720, 1080, 2160) }
        val quality = values.maxOrNull()
        return if (quality != null) "${quality}p" else null
    }

    suspend fun extractVideoLinksStreaming(
        data: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        onStreamFound: suspend (streams: Map<String, String>) -> Unit
    ) = withContext(Dispatchers.IO) {
        var subjectId = ""
        var season = 0
        var episode = 0

        if (data.trim().startsWith("{")) {
            try {
                val json = JSONObject(data)
                subjectId = json.optString("subjectId").ifEmpty { json.optString("detailPath") }
                season = json.optInt("season", json.optInt("se", 0))
                episode = json.optInt("episode", json.optInt("ep", 0))
            } catch (_: Exception) {}
        }

        if (subjectId.isBlank()) {
            val parts = data.split("|")
            subjectId = parts.getOrNull(0) ?: ""
            if (parts.size >= 4) {
                season = parts.getOrNull(2)?.toIntOrNull() ?: 0
                episode = parts.getOrNull(3)?.toIntOrNull() ?: 0
            } else if (parts.size == 3) {
                season = parts.getOrNull(1)?.toIntOrNull() ?: 0
                episode = parts.getOrNull(2)?.toIntOrNull() ?: 0
            }
        }

        if (subjectId.isBlank()) return@withContext

        val targets = mutableListOf<Pair<String, String>>()
        targets.add(Pair(subjectId, "Original Audio"))

        // Query detail for multi-audio dubs
        for (host in listOf(mainUrl) + HOST_POOL.filter { it != mainUrl }) {
            try {
                val detailUrl = "$host/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
                val headers = buildAuthHeaders(url = detailUrl, method = "GET", useToken = true)
                val req = Request.Builder().url(detailUrl).headers(headers).build()
                client.newCall(req).execute().use { resp ->
                    persistTokenFromXUser(resp.header("x-user"))
                    val respBody = resp.body?.string() ?: ""
                    if (respBody.isNotBlank()) {
                        val json = JSONObject(respBody)
                        val dataObj = json.optJSONObject("data")
                        if (dataObj != null) {
                            val dubsArr = dataObj.optJSONArray("dubs")
                            if (dubsArr != null && dubsArr.length() > 0) {
                                for (i in 0 until dubsArr.length()) {
                                    val dubItem = dubsArr.optJSONObject(i) ?: continue
                                    val dubId = dubItem.optString("subjectId")
                                    val lanName = dubItem.optString("lanName")
                                        .ifEmpty { dubItem.optString("lan") }
                                        .ifEmpty { dubItem.optString("classify") }
                                    if (dubId.isNotBlank() && dubId != subjectId) {
                                        val label = lanName.ifEmpty { "Dubbed Audio $i" }
                                            .replace("dub", "Audio", ignoreCase = true)
                                        targets.add(Pair(dubId, label))
                                    }
                                }
                            }
                        }
                    }
                }
                break
            } catch (e: Exception) {
                android.util.Log.e("MovieBoxIN", "Error checking dubs from $host", e)
            }
        }

        val seenStreamUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val resultMap = mutableMapOf<String, String>()

        for (target in targets) {
            val targetId = target.first
            val audioLabel = target.second

            val bffPath = if (season == 0 && episode == 0) {
                "/wefeed-mobile-bff/subject-api/play-info?subjectId=$targetId"
            } else {
                "/wefeed-mobile-bff/subject-api/play-info?subjectId=$targetId&se=$season&ep=$episode"
            }

            for (host in listOf(mainUrl) + HOST_POOL.filter { it != mainUrl }) {
                try {
                    val playUrl = "$host$bffPath"
                    var headers = buildAuthHeaders(url = playUrl, method = "GET", useToken = true, useAltKey = false)
                    var req = Request.Builder().url(playUrl).headers(headers).build()
                    var resp = client.newCall(req).execute()

                    if (resp.code == 401 || resp.code == 403) {
                        resp.close()
                        // Retry using SECRET_KEY_ALT
                        headers = buildAuthHeaders(url = playUrl, method = "GET", useToken = true, useAltKey = true)
                        req = Request.Builder().url(playUrl).headers(headers).build()
                        resp = client.newCall(req).execute()
                    }

                    resp.use { r ->
                        if (r.code == 401) {
                            bearerToken = null
                            return@use
                        }
                        persistTokenFromXUser(r.header("x-user"))
                        val respBody = r.body?.string() ?: ""
                        if (respBody.isNotBlank()) {
                            val json = JSONObject(respBody)
                            val dataObj = json.optJSONObject("data") ?: return@use

                            // 1. Process resourceDetectors (direct MP4 / HLS streams)
                            val detectors = dataObj.optJSONArray("resourceDetectors")
                            if (detectors != null && detectors.length() > 0) {
                                for (dIdx in 0 until detectors.length()) {
                                    val detector = detectors.optJSONObject(dIdx) ?: continue
                                    val resList = detector.optJSONArray("resolutionList") ?: continue
                                    for (rIdx in 0 until resList.length()) {
                                        val resItem = resList.optJSONObject(rIdx) ?: continue
                                        val s = resItem.optInt("se", 0)
                                        val e = resItem.optInt("ep", 0)
                                        if ((s == 0 && e == 0) || (s == season && e == episode)) {
                                            val rLink = resItem.optString("resourceLink")
                                            val res = resItem.optInt("resolution", 1080)
                                            val quality = if (res > 0) "${res}p" else "1080p"
                                            if (rLink.startsWith("http") && seenStreamUrls.add(rLink)) {
                                                val playerHeaders = mapOf(
                                                    "User-Agent" to modernUserAgent,
                                                    "Referer" to "$host/"
                                                )
                                                val headersJson = com.google.gson.Gson().toJson(playerHeaders)
                                                val finalUrl = "$rLink######$headersJson"
                                                val streamLabel = "[MovieBox] [$quality] ($audioLabel) Direct"
                                                resultMap[streamLabel] = finalUrl
                                            }
                                        }
                                    }
                                }
                            }

                            // 2. Process streams (CloudFront & EdgeCache signed DASH/HLS/MP4 streams)
                            val streams = dataObj.optJSONArray("streams")
                            if (streams != null && streams.length() > 0) {
                                for (sIdx in 0 until streams.length()) {
                                    val streamObj = streams.optJSONObject(sIdx) ?: continue
                                    val signCookie = streamObj.optString("signCookie")
                                    val format = streamObj.optString("format", "MP4")
                                    val resolutions = streamObj.optString("resolutions", "1080")
                                    val quality = getQuality(resolutions) ?: "1080p"
                                    val rawUrl = streamObj.optString("url")
                                    val policyUrl = extractPolicyResource(signCookie)
                                    val streamUrl = (if (!policyUrl.isNullOrBlank()) policyUrl else rawUrl).trim()

                                    if (streamUrl.isNotBlank() && streamUrl.startsWith("http")) {
                                        // Filter dummy/placeholder videos
                                        if (streamUrl.contains("b164fbfb4347792950bdfbfb563d39d9") ||
                                            streamUrl.contains("hakunaymatata.com") ||
                                            streamUrl.contains("/other/2026/09/04/")
                                        ) {
                                            continue
                                        }

                                        val dedupKey = streamUrl.substringBefore("?").trim()
                                        if (seenStreamUrls.add(dedupKey)) {
                                            val playerHeaders = mutableMapOf(
                                                "User-Agent" to modernUserAgent,
                                                "Referer" to "$host/"
                                            )
                                            if (signCookie.isNotBlank()) {
                                                playerHeaders["Cookie"] = signCookie
                                            }
                                            val headersJson = com.google.gson.Gson().toJson(playerHeaders)
                                            val finalUrl = "$streamUrl######$headersJson"
                                            val streamType = if (streamUrl.contains(".mpd", ignoreCase = true) || format.equals("DASH", ignoreCase = true)) "DASH" else "HLS"
                                            val streamLabel = "[MovieBox] [$quality] ($audioLabel) $streamType"
                                            resultMap[streamLabel] = finalUrl
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (resultMap.isNotEmpty()) {
                        mainUrl = host
                        break
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MovieBoxIN", "Error fetching play-info from $host", e)
                }
            }
        }

        if (resultMap.isNotEmpty()) {
            onStreamFound(resultMap)
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val map = mutableMapOf<String, String>()
        extractVideoLinksStreaming(data, onStreamFound = { map.putAll(it) })
        map
    }
}
