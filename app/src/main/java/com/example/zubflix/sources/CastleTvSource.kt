package com.example.zubflix.sources

import android.util.Base64
import android.util.Log
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CastleTvSource : StreamingSource {
    override val name: String = "Castle TV 🏰"

    companion object {
        private const val TAG = "CastleTvSource"
        private const val API_BASE = "https://api.hlowb.com"
        private const val PKG = "com.external.castle"
        private const val CHANNEL = "IndiaA"
        private const val CLIENT = "1"
        private const val LANG = "en-US"
        private const val API_UA = "okhttp/4.9.3"
        private const val PLAYBACK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        private const val APK_SIGN_KEY = "ED0955EB04E67A1D9F3305B95454FED485261475"
        private const val APP_MARKET = "GuanWang"
        private const val DEFAULT_SUFFIX = "T!BgJB"

        private val PLAYBACK_HEADERS_JSON = JSONObject().apply {
            put("User-Agent", PLAYBACK_UA)
            put("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            put("Accept-Language", "en-US,en;q=0.9")
            put("Accept-Encoding", "identity")
            put("Connection", "keep-alive")
            put("Sec-Fetch-Dest", "video")
            put("Sec-Fetch-Mode", "no-cors")
            put("Sec-Fetch-Site", "cross-site")
            put("DNT", "1")
            put("Referer", API_BASE)
        }.toString()
    }

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var cachedSecurityKey: String? = null
    private var securityKeyFetchTime = 0L
    private var cachedUserToken: String? = null
    private var cachedUserId: String? = null
    private val deviceId: String by lazy {
        java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    }

    private suspend fun ensureGuestAuth(): Pair<String?, String?> = withContext(Dispatchers.IO) {
        if (!cachedUserToken.isNullOrBlank() && !cachedUserId.isNullOrBlank()) {
            return@withContext Pair(cachedUserToken, cachedUserId)
        }

        try {
            val loginUrl = "$API_BASE/film-api/v0.1/user/guestLogin?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG&packageName=$PKG"
            val loginPayload = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceType", "android")
                put("clientType", CLIENT)
                put("packageName", PKG)
                put("channel", CHANNEL)
            }.toString()

            val decryptedResp = fetchAndDecrypt(loginUrl, loginPayload)
            if (!decryptedResp.isNullOrBlank()) {
                val root = JsonParser.parseString(decryptedResp).asJsonObject
                val dataObj = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else root
                if (dataObj.has("token") && !dataObj.get("token").isJsonNull) {
                    cachedUserToken = dataObj.get("token").asString
                }
                if (dataObj.has("userId") && !dataObj.get("userId").isJsonNull) {
                    cachedUserId = dataObj.get("userId").asString
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Guest login failed: ${e.message}")
        }

        Pair(cachedUserToken, cachedUserId)
    }

    private suspend fun getSecurityKey(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedSecurityKey != null && now - securityKeyFetchTime < 1800_000L) {
            return@withContext cachedSecurityKey
        }

        try {
            val url = "$API_BASE/v0.1/system/getSecurityKey/1?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG"
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", API_UA)
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Referer", API_BASE)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("code") && json.get("code").asInt == 200 && json.has("data")) {
                        val key = json.get("data").asString
                        if (!key.isNullOrBlank()) {
                            cachedSecurityKey = key
                            securityKeyFetchTime = now
                            return@withContext key
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching security key: ${e.message}")
        }
        null
    }

    private fun deriveKey(apiKeyB64: String): ByteArray {
        val apiKeyBytes = try {
            Base64.decode(apiKeyB64, Base64.DEFAULT)
        } catch (e: Exception) {
            apiKeyB64.toByteArray(StandardCharsets.US_ASCII)
        }

        val suffixBytes = DEFAULT_SUFFIX.toByteArray(StandardCharsets.UTF_8)
        val keyMaterial = apiKeyBytes + suffixBytes
        return when {
            keyMaterial.size < 16 -> keyMaterial + ByteArray(16 - keyMaterial.size)
            keyMaterial.size > 16 -> keyMaterial.copyOfRange(0, 16)
            else -> keyMaterial
        }
    }

    private fun decryptData(encryptedB64: String, apiKeyB64: String): String? {
        return try {
            val aesKey = deriveKey(apiKeyB64)
            val iv = aesKey
            val encryptedData = Base64.decode(encryptedB64, Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(encryptedData)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "AES decryption failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchAndDecrypt(url: String, postBodyJson: String? = null): String? = withContext(Dispatchers.IO) {
        val securityKey = getSecurityKey() ?: return@withContext null
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", API_UA)
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Referer", API_BASE)

            if (!cachedUserToken.isNullOrBlank()) {
                reqBuilder.addHeader("token", cachedUserToken!!)
            }
            if (!cachedUserId.isNullOrBlank()) {
                reqBuilder.addHeader("userId", cachedUserId!!)
            }
            reqBuilder.addHeader("deviceId", deviceId)

            if (postBodyJson != null) {
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                reqBuilder.post(postBodyJson.toRequestBody(mediaType))
                reqBuilder.addHeader("Content-Type", "application/json")
            } else {
                reqBuilder.get()
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string()?.trim() ?: return@withContext null

                val encryptedData = if (body.startsWith("{")) {
                    val jsonObj = JsonParser.parseString(body).asJsonObject
                    if (jsonObj.has("data") && !jsonObj.get("data").isJsonNull && jsonObj.get("data").isJsonPrimitive) {
                        jsonObj.get("data").asString
                    } else body
                } else body

                if (encryptedData.isBlank()) return@withContext null
                decryptData(encryptedData, securityKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndDecrypt error for $url: ${e.message}")
            null
        }
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val categoriesList = mutableListOf<StreamingCategory>()

        try {
            val url = "$API_BASE/film-api/v0.1/category/home?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG&locationId=1001&mode=1&packageName=$PKG&page=1&size=17"
            val decryptedJson = fetchAndDecrypt(url)

            if (!decryptedJson.isNullOrBlank()) {
                val root = JsonParser.parseString(decryptedJson).asJsonObject
                val dataObj = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else root
                val rows = if (dataObj.has("rows") && dataObj.get("rows").isJsonArray) dataObj.getAsJsonArray("rows") else null

                if (rows != null && rows.size() > 0) {
                    for (i in 0 until rows.size()) {
                        val row = rows[i].asJsonObject
                        val rowName = if (row.has("name") && !row.get("name").isJsonNull) row.get("name").asString else "Category $i"
                        val rowId = if (row.has("id") && !row.get("id").isJsonNull) row.get("id").asString else i.toString()

                        // Filter out adult/spam rows
                        if (rowName.contains("Hot Erotic", ignoreCase = true) || rowName.contains("Bollywood Star", ignoreCase = true)) {
                            continue
                        }

                        val contents = if (row.has("contents") && row.get("contents").isJsonArray) row.getAsJsonArray("contents") else null
                        if (contents != null && contents.size() > 0) {
                            val items = mutableListOf<StreamingItem>()
                            for (j in 0 until contents.size()) {
                                val item = contents[j].asJsonObject
                                val title = if (item.has("title") && !item.get("title").isJsonNull) item.get("title").asString else null ?: continue
                                
                                // Prioritize redirectId (actual movieId), then redirectIdStr, then id
                                val redirectId = when {
                                    item.has("redirectId") && !item.get("redirectId").isJsonNull -> item.get("redirectId").asString
                                    item.has("redirectIdStr") && !item.get("redirectIdStr").isJsonNull -> item.get("redirectIdStr").asString
                                    item.has("id") && !item.get("id").isJsonNull -> item.get("id").asString
                                    else -> null
                                } ?: continue

                                val coverImg = if (item.has("coverImage") && !item.get("coverImage").isJsonNull) item.get("coverImage").asString else null
                                val movieType = if (item.has("movieType") && !item.get("movieType").isJsonNull) item.get("movieType").asInt else 2
                                val isSeries = movieType == 1 || movieType == 3 || movieType == 5

                                val score = if (item.has("score") && !item.get("score").isJsonNull) item.get("score").asDouble else null
                                val ratingStr = score?.let { String.format(Locale.US, "%.1f", it) }
                                val resolution = if (item.has("indiaResolutionLabel") && !item.get("indiaResolutionLabel").isJsonNull) item.get("indiaResolutionLabel").asString else "1080p"

                                items.add(
                                    StreamingItem(
                                        id = redirectId,
                                        title = title.trim(),
                                        isSeries = isSeries,
                                        imageUrl = coverImg,
                                        backdropUrl = coverImg,
                                        rating = ratingStr,
                                        quality = resolution,
                                        sourceName = name
                                    )
                                )
                            }

                            if (items.isNotEmpty()) {
                                categoriesList.add(
                                    StreamingCategory(
                                        id = rowId,
                                        title = rowName,
                                        items = items
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Castle TV home categories: ${e.message}")
        }

        if (categoriesList.isEmpty()) {
            val fallbackCategories = listOf(
                "trending" to "🔥 Trending Now",
                "hindi-movies" to "🎬 Bollywood Hits",
                "south-hindi" to "🔥 South Hindi Dubbed",
                "web-series" to "📺 Web Series & Shows",
                "hollywood" to "🍿 Hollywood Blockbusters"
            )

            for ((catId, catTitle) in fallbackCategories) {
                val items = getCuratedCastleCategoryItems(catId)
                if (items.isNotEmpty()) {
                    categoriesList.add(StreamingCategory(id = catId, title = catTitle, items = items))
                }
            }
        }

        categoriesList
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<StreamingItem>()
        try {
            val url = "$API_BASE/film-api/v0.1/category/home?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG&locationId=1001&mode=1&packageName=$PKG&page=$page&size=17"
            val decryptedJson = fetchAndDecrypt(url)

            if (!decryptedJson.isNullOrBlank()) {
                val root = JsonParser.parseString(decryptedJson).asJsonObject
                val dataObj = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else root
                val rows = if (dataObj.has("rows") && dataObj.get("rows").isJsonArray) dataObj.getAsJsonArray("rows") else null

                if (rows != null) {
                    for (i in 0 until rows.size()) {
                        val row = rows[i].asJsonObject
                        val rowId = if (row.has("id") && !row.get("id").isJsonNull) row.get("id").asString else i.toString()
                        if (categoryId == rowId || categoryId == i.toString() || categoryId == "trending") {
                            val contents = if (row.has("contents") && row.get("contents").isJsonArray) row.getAsJsonArray("contents") else null
                            if (contents != null) {
                                for (j in 0 until contents.size()) {
                                    val item = contents[j].asJsonObject
                                    val title = if (item.has("title") && !item.get("title").isJsonNull) item.get("title").asString else null ?: continue
                                    val redirectId = when {
                                        item.has("redirectId") && !item.get("redirectId").isJsonNull -> item.get("redirectId").asString
                                        item.has("redirectIdStr") && !item.get("redirectIdStr").isJsonNull -> item.get("redirectIdStr").asString
                                        item.has("id") && !item.get("id").isJsonNull -> item.get("id").asString
                                        else -> null
                                    } ?: continue

                                    val coverImg = if (item.has("coverImage") && !item.get("coverImage").isJsonNull) item.get("coverImage").asString else null
                                    val movieType = if (item.has("movieType") && !item.get("movieType").isJsonNull) item.get("movieType").asInt else 2
                                    val isSeries = movieType == 1 || movieType == 3 || movieType == 5

                                    val score = if (item.has("score") && !item.get("score").isJsonNull) item.get("score").asDouble else null
                                    val ratingStr = score?.let { String.format(Locale.US, "%.1f", it) }

                                    items.add(
                                        StreamingItem(
                                            id = redirectId,
                                            title = title.trim(),
                                            isSeries = isSeries,
                                            imageUrl = coverImg,
                                            backdropUrl = coverImg,
                                            rating = ratingStr,
                                            sourceName = name
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching category content for $categoryId: ${e.message}")
        }

        if (items.isEmpty()) {
            getCuratedCastleCategoryItems(categoryId)
        } else items
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val items = mutableListOf<StreamingItem>()

        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "$API_BASE/film-api/v1.1.0/movie/searchByKeyword?channel=$CHANNEL&clientType=$CLIENT&keyword=$encodedQuery&lang=$LANG&mode=1&packageName=$PKG&page=1&size=30"
            val decryptedJson = fetchAndDecrypt(searchUrl)

            if (!decryptedJson.isNullOrBlank()) {
                val root = JsonParser.parseString(decryptedJson).asJsonObject
                val dataObj = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else root
                val rows = if (dataObj.has("rows") && dataObj.get("rows").isJsonArray) dataObj.getAsJsonArray("rows") else null

                if (rows != null) {
                    for (i in 0 until rows.size()) {
                        val item = rows[i].asJsonObject
                        val title = if (item.has("title") && !item.get("title").isJsonNull) item.get("title").asString else null ?: continue
                        val id = when {
                            item.has("id") && !item.get("id").isJsonNull -> item.get("id").asString
                            item.has("redirectId") && !item.get("redirectId").isJsonNull -> item.get("redirectId").asString
                            item.has("redirectIdStr") && !item.get("redirectIdStr").isJsonNull -> item.get("redirectIdStr").asString
                            else -> null
                        } ?: continue

                        val posterUrl = if (item.has("coverVerticalImage") && !item.get("coverVerticalImage").isJsonNull) {
                            item.get("coverVerticalImage").asString
                        } else if (item.has("coverHorizontalImage") && !item.get("coverHorizontalImage").isJsonNull) {
                            item.get("coverHorizontalImage").asString
                        } else null

                        val movieType = if (item.has("movieType") && !item.get("movieType").isJsonNull) item.get("movieType").asInt else 2
                        val isSeries = movieType == 1 || movieType == 3 || movieType == 5

                        val score = if (item.has("score") && !item.get("score").isJsonNull) item.get("score").asDouble else null
                        val ratingStr = score?.let { String.format(Locale.US, "%.1f", it) }

                        val yearStr = if (item.has("publishTime") && !item.get("publishTime").isJsonNull) {
                            val timeMs = item.get("publishTime").asLong
                            SimpleDateFormat("yyyy", Locale.US).format(Date(timeMs))
                        } else null

                        items.add(
                            StreamingItem(
                                id = id,
                                title = title.trim(),
                                isSeries = isSeries,
                                imageUrl = posterUrl,
                                backdropUrl = posterUrl,
                                rating = ratingStr,
                                year = yearStr,
                                sourceName = name
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in search for query '$query': ${e.message}")
        }

        if (items.isEmpty()) {
            getAllCuratedCastleItems().filter { it.title.contains(query, ignoreCase = true) }
        } else items
    }

    private fun cleanMovieId(rawId: String): String {
        var clean = rawId.trim()
        if (clean.startsWith("{")) {
            try {
                val obj = JSONObject(clean)
                val mId = obj.optString("movieId")
                if (mId.isNotEmpty()) {
                    clean = mId
                }
            } catch (_: Exception) {}
        }
        if (clean.contains(":")) {
            clean = clean.substringBefore(":")
        }
        if (clean.contains("/")) {
            clean = clean.substringAfterLast("/")
        }
        if (clean.contains("?")) {
            clean = clean.substringBefore("?")
        }
        return clean.trim()
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        val movieId = cleanMovieId(id)
        if (movieId.isEmpty()) return@withContext null

        try {
            val detailsUrl = "$API_BASE/film-api/v1.9.9/movie?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG&movieId=$movieId&packageName=$PKG"
            val decryptedJson = fetchAndDecrypt(detailsUrl)

            if (!decryptedJson.isNullOrBlank()) {
                val root = JsonParser.parseString(decryptedJson).asJsonObject
                val data = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else null

                if (data != null && data.has("title") && !data.get("title").isJsonNull) {
                    val title = data.get("title").asString
                    if (title.isNotBlank()) {
                        val score = if (data.has("score") && !data.get("score").isJsonNull) data.get("score").asDouble else null
                        val ratingStr = score?.let { String.format(Locale.US, "%.1f", it) }

                        val posterVertical = if (data.has("coverVerticalImage") && !data.get("coverVerticalImage").isJsonNull) data.get("coverVerticalImage").asString else null
                        val posterHorizontal = if (data.has("coverHorizontalImage") && !data.get("coverHorizontalImage").isJsonNull) data.get("coverHorizontalImage").asString else null
                        val plot = if (data.has("briefIntroduction") && !data.get("briefIntroduction").isJsonNull) data.get("briefIntroduction").asString else null

                        val yearStr = if (data.has("publishTime") && !data.get("publishTime").isJsonNull) {
                            val timeMs = data.get("publishTime").asLong
                            SimpleDateFormat("yyyy", Locale.US).format(Date(timeMs))
                        } else null

                        val movieType = if (data.has("movieType") && !data.get("movieType").isJsonNull) data.get("movieType").asInt else 2
                        val episodesArray = if (data.has("episodes") && data.get("episodes").isJsonArray) data.getAsJsonArray("episodes") else null
                        val episodeCount = episodesArray?.size() ?: 0

                        val isSeriesLike = movieType == 1 || movieType == 3 || movieType == 5 || episodeCount > 1

                        val tagsList = mutableListOf<String>()
                        if (data.has("tags") && data.get("tags").isJsonArray) {
                            val tagArr = data.getAsJsonArray("tags")
                            for (t in 0 until tagArr.size()) {
                                tagsList.add(tagArr[t].asString)
                            }
                        }

                        val actorsList = mutableListOf<String>()
                        if (data.has("actors") && data.get("actors").isJsonArray) {
                            val actorArr = data.getAsJsonArray("actors")
                            for (a in 0 until actorArr.size()) {
                                val actObj = actorArr[a].asJsonObject
                                if (actObj.has("name") && !actObj.get("name").isJsonNull) {
                                    actorsList.add(actObj.get("name").asString)
                                }
                            }
                        }

                        var seasonsList: List<StreamingSeason>? = null

                        if (isSeriesLike) {
                            val allEpisodes = mutableListOf<StreamingEpisode>()
                            val seasonsArray = if (data.has("seasons") && data.get("seasons").isJsonArray) data.getAsJsonArray("seasons") else null

                            if (seasonsArray != null && seasonsArray.size() > 1) {
                                val seasonObjList = mutableListOf<StreamingSeason>()
                                for (s in 0 until seasonsArray.size()) {
                                    val seasonObj = seasonsArray[s].asJsonObject
                                    val seasonMovieId = if (seasonObj.has("movieId") && !seasonObj.get("movieId").isJsonNull) seasonObj.get("movieId").asString else movieId
                                    val seasonNumber = if (seasonObj.has("number") && !seasonObj.get("number").isJsonNull) seasonObj.get("number").asInt else (s + 1)
                                    val seasonTitle = if (seasonObj.has("description") && !seasonObj.get("description").isJsonNull) seasonObj.get("description").asString else "Season $seasonNumber"

                                    val seasonEpisodes = mutableListOf<StreamingEpisode>()
                                    try {
                                        val sUrl = "$API_BASE/film-api/v1.9.9/movie?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG&movieId=$seasonMovieId&packageName=$PKG"
                                        val sDecrypted = fetchAndDecrypt(sUrl)
                                        if (!sDecrypted.isNullOrBlank()) {
                                            val sRoot = JsonParser.parseString(sDecrypted).asJsonObject
                                            val sData = if (sRoot.has("data") && sRoot.get("data").isJsonObject) sRoot.getAsJsonObject("data") else sRoot
                                            val sEps = if (sData.has("episodes") && sData.get("episodes").isJsonArray) sData.getAsJsonArray("episodes") else null

                                            if (sEps != null) {
                                                for (e in 0 until sEps.size()) {
                                                    val epObj = sEps[e].asJsonObject
                                                    val epId = if (epObj.has("id") && !epObj.get("id").isJsonNull) epObj.get("id").asString else "$e"
                                                    val epNumber = if (epObj.has("number") && !epObj.get("number").isJsonNull) epObj.get("number").asInt else (e + 1)
                                                    val epTitle = if (epObj.has("title") && !epObj.get("title").isJsonNull) epObj.get("title").asString else "Episode $epNumber"
                                                    val epCover = if (epObj.has("coverImage") && !epObj.get("coverImage").isJsonNull) epObj.get("coverImage").asString else posterVertical

                                                    val payload = JSONObject().apply {
                                                        put("movieId", seasonMovieId)
                                                        put("episodeId", epId)
                                                    }.toString()

                                                    seasonEpisodes.add(
                                                        StreamingEpisode(
                                                            title = epTitle,
                                                            streamUrl = payload,
                                                            stillUrl = epCover
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to load season $seasonNumber: ${e.message}")
                                    }

                                    if (seasonEpisodes.isNotEmpty()) {
                                        seasonObjList.add(
                                            StreamingSeason(
                                                title = seasonTitle,
                                                seasonNumber = seasonNumber,
                                                episodes = seasonEpisodes
                                            )
                                        )
                                    }
                                }
                                if (seasonObjList.isNotEmpty()) {
                                    seasonsList = seasonObjList
                                }
                            }

                            if (seasonsList == null && episodesArray != null && episodesArray.size() > 0) {
                                for (e in 0 until episodesArray.size()) {
                                    val epObj = episodesArray[e].asJsonObject
                                    val epId = if (epObj.has("id") && !epObj.get("id").isJsonNull) epObj.get("id").asString else "$e"
                                    val epNumber = if (epObj.has("number") && !epObj.get("number").isJsonNull) epObj.get("number").asInt else (e + 1)
                                    val epTitle = if (epObj.has("title") && !epObj.get("title").isJsonNull) epObj.get("title").asString else "Episode $epNumber"
                                    val epCover = if (epObj.has("coverImage") && !epObj.get("coverImage").isJsonNull) epObj.get("coverImage").asString else posterVertical

                                    val payload = JSONObject().apply {
                                        put("movieId", movieId)
                                        put("episodeId", epId)
                                    }.toString()

                                    allEpisodes.add(
                                        StreamingEpisode(
                                            title = epTitle,
                                            streamUrl = payload,
                                            stillUrl = epCover
                                        )
                                    )
                                }
                                seasonsList = listOf(StreamingSeason(title = "Season 1", seasonNumber = 1, episodes = allEpisodes))
                            }
                        }

                        // Default movie payload with first episode ID
                        var defaultEpisodeId = ""
                        if (episodesArray != null && episodesArray.size() > 0) {
                            val firstEp = episodesArray[0].asJsonObject
                            if (firstEp.has("id") && !firstEp.get("id").isJsonNull) {
                                defaultEpisodeId = firstEp.get("id").asString
                            }
                        }

                        val moviePayload = JSONObject().apply {
                            put("movieId", movieId)
                            if (defaultEpisodeId.isNotEmpty()) {
                                put("episodeId", defaultEpisodeId)
                            }
                        }.toString()

                        return@withContext StreamingItem(
                            id = movieId,
                            title = title,
                            isSeries = isSeriesLike,
                            imageUrl = posterVertical ?: posterHorizontal,
                            backdropUrl = posterHorizontal ?: posterVertical,
                            description = plot ?: "Watch streaming media on Castle TV.",
                            rating = ratingStr,
                            year = yearStr,
                            genres = tagsList.ifEmpty { null },
                            cast = actorsList.ifEmpty { null },
                            streamUrl = moviePayload,
                            seasons = seasonsList,
                            sourceName = name
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching details for $id: ${e.message}")
        }

        getAllCuratedCastleItems().find { cleanMovieId(it.id) == movieId || it.id == id }?.copy(
            streamUrl = JSONObject().apply { put("movieId", movieId) }.toString()
        )
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val streams = mutableMapOf<String, String>()

        try {
            var movieId = ""
            var episodeId = ""

            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                movieId = json.optString("movieId")
                episodeId = json.optString("episodeId")
            } else if (data.isNotEmpty()) {
                movieId = data
            }

            movieId = cleanMovieId(movieId)

            if (movieId.isBlank()) {
                return@withContext emptyMap()
            }

            // Step 1: Fetch movie/series details to inspect episodes and tracks
            var targetEpisodeTracks = mutableListOf<CastleTrack>()
            val detailsUrl = "$API_BASE/film-api/v1.9.9/movie?channel=$CHANNEL&clientType=$CLIENT&lang=$LANG&movieId=$movieId&packageName=$PKG"
            val detailsDecrypted = fetchAndDecrypt(detailsUrl)
            
            if (!detailsDecrypted.isNullOrBlank()) {
                val root = JsonParser.parseString(detailsDecrypted).asJsonObject
                val dataObj = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else root
                val episodes = if (dataObj.has("episodes") && dataObj.get("episodes").isJsonArray) dataObj.getAsJsonArray("episodes") else null

                if (episodes != null && episodes.size() > 0) {
                    var foundEpObj = episodes[0].asJsonObject
                    if (episodeId.isNotEmpty()) {
                        for (i in 0 until episodes.size()) {
                            val ep = episodes[i].asJsonObject
                            if (ep.has("id") && ep.get("id").asString == episodeId) {
                                foundEpObj = ep
                                break
                            }
                        }
                    } else if (foundEpObj.has("id") && !foundEpObj.get("id").isJsonNull) {
                        episodeId = foundEpObj.get("id").asString
                    }

                    // Extract tracks
                    if (foundEpObj.has("tracks") && foundEpObj.get("tracks").isJsonArray) {
                        val tracksArray = foundEpObj.getAsJsonArray("tracks")
                        for (t in 0 until tracksArray.size()) {
                            val tObj = tracksArray[t].asJsonObject
                            val langId = if (tObj.has("languageId") && !tObj.get("languageId").isJsonNull) tObj.get("languageId").asString else ""
                            val langName = if (tObj.has("languageName") && !tObj.get("languageName").isJsonNull) tObj.get("languageName").asString
                                else if (tObj.has("abbreviate") && !tObj.get("abbreviate").isJsonNull) tObj.get("abbreviate").asString
                                else "Audio"
                            val existIndividual = if (tObj.has("existIndividualVideo") && !tObj.get("existIndividualVideo").isJsonNull) tObj.get("existIndividualVideo").asBoolean else true

                            if (langId.isNotEmpty() && existIndividual) {
                                targetEpisodeTracks.add(CastleTrack(languageId = langId, languageName = langName))
                            }
                        }
                    }
                }
            }

            val endpoint = "$API_BASE/film-api/v2.0.1/movie/getVideo2?clientType=$CLIENT&packageName=$PKG&channel=$CHANNEL&lang=$LANG"
            val (token, userId) = ensureGuestAuth()

            // Step 2: Try fetching individual audio language video streams
            for (track in targetEpisodeTracks) {
                for (resolutionCode in listOf("3", "2")) { // 1080p, 720p
                    val body = JSONObject().apply {
                        put("mode", "1")
                        put("appMarket", APP_MARKET)
                        put("clientType", CLIENT)
                        put("woolUser", "true")
                        put("apkSignKey", APK_SIGN_KEY)
                        put("androidVersion", "13")
                        put("movieId", movieId)
                        put("episodeId", episodeId)
                        put("languageId", track.languageId)
                        put("isNewUser", "true")
                        put("resolution", resolutionCode)
                        put("packageName", PKG)
                        put("deviceId", deviceId)
                        put("vipType", "1")
                        if (!token.isNullOrBlank()) put("token", token)
                        if (!userId.isNullOrBlank()) put("userId", userId)
                    }.toString()

                    val decryptedResp = fetchAndDecrypt(endpoint, body)
                    if (!decryptedResp.isNullOrBlank()) {
                        parseVideoResponse(decryptedResp, track.languageName, streams)
                    }
                }
            }

            // Step 3: Fallback to shared video streams if no tracks or empty streams
            if (streams.isEmpty()) {
                for (resolutionCode in listOf("3", "2", "1")) { // 1080p, 720p, 480p
                    val body = JSONObject().apply {
                        put("mode", "1")
                        put("appMarket", APP_MARKET)
                        put("clientType", CLIENT)
                        put("woolUser", "true")
                        put("apkSignKey", APK_SIGN_KEY)
                        put("androidVersion", "13")
                        put("movieId", movieId)
                        put("episodeId", episodeId)
                        put("isNewUser", "true")
                        put("resolution", resolutionCode)
                        put("packageName", PKG)
                        put("deviceId", deviceId)
                        put("vipType", "1")
                        if (!token.isNullOrBlank()) put("token", token)
                        if (!userId.isNullOrBlank()) put("userId", userId)
                    }.toString()

                    val decryptedResp = fetchAndDecrypt(endpoint, body)
                    if (!decryptedResp.isNullOrBlank()) {
                        parseVideoResponse(decryptedResp, null, streams)
                    }
                    if (streams.isNotEmpty()) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video links: ${e.message}")
        }

        streams
    }

    private data class CastleTrack(val languageId: String, val languageName: String)

    private fun parseVideoResponse(
        decryptedJson: String,
        audioLabel: String?,
        streams: MutableMap<String, String>
    ) {
        try {
            val root = JsonParser.parseString(decryptedJson).asJsonObject
            val dataObj = if (root.has("data") && root.get("data").isJsonObject) root.getAsJsonObject("data") else root

            val mainVideoUrl = if (dataObj.has("videoUrl") && !dataObj.get("videoUrl").isJsonNull) {
                dataObj.get("videoUrl").asString
            } else null

            val prefix = if (!audioLabel.isNullOrBlank()) "[Castle TV] [$audioLabel]" else "[Castle TV]"

            // Multi-quality videos list
            if (dataObj.has("videos") && dataObj.get("videos").isJsonArray) {
                val videosArr = dataObj.getAsJsonArray("videos")
                for (i in 0 until videosArr.size()) {
                    val vidObj = videosArr[i].asJsonObject
                    val resDesc = if (vidObj.has("resolutionDescription") && !vidObj.get("resolutionDescription").isJsonNull) {
                        vidObj.get("resolutionDescription").asString
                    } else if (vidObj.has("resolution") && !vidObj.get("resolution").isJsonNull) {
                        val rCode = vidObj.get("resolution").asString
                        when (rCode) {
                            "3" -> "1080p FHD"
                            "2" -> "720p HD"
                            "1" -> "480p SD"
                            else -> "${rCode}p"
                        }
                    } else "HD"

                    val vUrl = if (vidObj.has("url") && !vidObj.get("url").isJsonNull) {
                        vidObj.get("url").asString
                    } else mainVideoUrl

                    if (!vUrl.isNullOrBlank()) {
                        val label = "$prefix $resDesc"
                        streams[label] = "$vUrl######$PLAYBACK_HEADERS_JSON"
                    }
                }
            } else if (!mainVideoUrl.isNullOrBlank()) {
                val label = "$prefix Fast HD Stream"
                streams[label] = "$mainVideoUrl######$PLAYBACK_HEADERS_JSON"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing video response: ${e.message}")
        }
    }

    private fun getCuratedCastleCategoryItems(catId: String): List<StreamingItem> {
        return when (catId) {
            "trending" -> listOf(
                StreamingItem(id = "1001", title = "Stree 2", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BMDAxY2M0YmItYmJmYi00YjI2LTk3MDUtZTM2MmI0YWEwZTFiXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "7.8", quality = "1080p", sourceName = name),
                StreamingItem(id = "1002", title = "Kalki 2898 AD", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BNDM4NTk1ZDUtZWJhYi00OWFiLTk2ZDUtYTlhNDRjOWE5MmQ1XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "7.6", quality = "4K", sourceName = name),
                StreamingItem(id = "1003", title = "Panchayat", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BOTEyNzg5NjYtYjA0YS00Y2E1LTlhY2MtOWE5YjgyNDg5ZjA2XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "8.9", quality = "1080p", sourceName = name),
                StreamingItem(id = "1004", title = "Deadpool & Wolverine", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BNzRiMjg0MzUtNTNhRS00M2IzLTg3MDMtNWQ4gwMzA3NjRkXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "7.9", quality = "4K", sourceName = name)
            )
            "hindi-movies" -> listOf(
                StreamingItem(id = "1005", title = "Jawan", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BOWI5NmU3NTUtOTlhNi00M2VlLThlMGItZDliODE1ZGFhZDgxXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "7.0", quality = "1080p", sourceName = name),
                StreamingItem(id = "1006", title = "Animal", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BNGViM2M4NmUtMmE3ZC00M2ViLTkzYjAtYTEzMDU2NDZhNTM2XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "6.6", quality = "1080p", sourceName = name),
                StreamingItem(id = "1007", title = "Fighter", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BNWE1ZDRlM2EtOGJhYi00YmU4LTlmZDgtODU1ZDE1MDlhZGI2XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "6.8", quality = "1080p", sourceName = name)
            )
            "south-hindi" -> listOf(
                StreamingItem(id = "1008", title = "Pushpa 2: The Rule", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BMmU1N2IxNTItNWU5EG00NDhhLWE1ODEtMmI4MGFlZjA5Mzg4XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "8.1", quality = "4K", sourceName = name),
                StreamingItem(id = "1009", title = "K.G.F: Chapter 2", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BMjA2MDU3ZWQtYmNmOC00Y2VjLWE5MGMtNDk5ZDMyMmE2Nzc3XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "8.3", quality = "1080p", sourceName = name),
                StreamingItem(id = "1010", title = "Salaar: Part 1 - Ceasefire", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BMmU1OWYzYjUtZTZkMS00NWVmLTg4YzAtYjFmMzg3ZjVkMzY1XkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "6.5", quality = "1080p", sourceName = name)
            )
            "web-series" -> listOf(
                StreamingItem(id = "1011", title = "Mirzapur Season 3", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BN2E2OTI1NWItZDNkYi00ODY1LWI1ZjItZDA3OWVlOWM2NGUzXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "8.5", quality = "1080p", sourceName = name),
                StreamingItem(id = "1012", title = "Farzi", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BMTY0NjM3NzE3OV5BMl5BanBnXkFtZTgwNTU5NTA3NzE@._V1_FMjpg_UX1000_.jpg", rating = "8.4", quality = "1080p", sourceName = name),
                StreamingItem(id = "1013", title = "The Family Man", isSeries = true, imageUrl = "https://m.media-amazon.com/images/M/MV5BYTMzMDA3OTEtYzUwMi00NTcwLWEyY2UtMGNkYmE3N2I0YzkxXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "8.7", quality = "1080p", sourceName = name)
            )
            "hollywood" -> listOf(
                StreamingItem(id = "1014", title = "Dune: Part Two", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BN2QyZGU4ZDctOWMzMy00NTc5LThlOGQtODhmNDI1NmY5YzAwXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "8.5", quality = "4K", sourceName = name),
                StreamingItem(id = "1015", title = "Oppenheimer", isSeries = false, imageUrl = "https://m.media-amazon.com/images/M/MV5BN2JkMDc5MGQtZjg3YS00NmFiLWIyZmQtZTGCYmZmODk1MmEyXkEyXkFqcGc@._V1_FMjpg_UX1000_.jpg", rating = "8.9", quality = "4K", sourceName = name)
            )
            else -> emptyList()
        }
    }

    private fun getAllCuratedCastleItems(): List<StreamingItem> {
        return listOf("trending", "hindi-movies", "south-hindi", "web-series", "hollywood")
            .flatMap { getCuratedCastleCategoryItems(it) }
            .distinctBy { it.id }
    }
}
