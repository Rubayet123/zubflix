package com.example.zubflix

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.LayoutInflater
import android.view.Gravity
import android.util.TypedValue
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ImageView
import kotlinx.coroutines.isActive
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.databinding.ActivityPlayerBinding
import com.example.zubflix.database.AppDatabase
import com.example.zubflix.database.WatchHistoryEntity
import com.example.zubflix.utils.VideoCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Formatter
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    
    private var videoUrl: String = ""
    private var videoUrls: List<String> = emptyList()
    private var videoNames: List<String> = emptyList()
    private var currentUrlIndex: Int = 0
    private var subtitleUrl: String? = null
    private var headersJson: String = ""
    private var videoTitle: String = ""
    private var itemId: String = ""
    private var imageUrl: String? = null
    private var isSeries: Boolean = false
    private var passedSeason: Int = -1
    private var passedEpisode: Int = -1
    private var sourceName: String = ""
    private var actualImdbId: String? = null
    private var selectedStreamName: String? = null
    
    // Lazy External Subtitles metadata storage
    private data class ExternalSubtitle(val url: String, val label: String, val lang: String, val mime: String)
    private var externalSubtitlesList = mutableListOf<ExternalSubtitle>()
    private var selectedExternalSubUrl: String? = null

    private fun getSelectedAddonName(): String? {
        val streamName = selectedStreamName ?: return null
        val addons = listOf("Pengu", "FrostStream", "OpenSubtitles")
        for (addon in addons) {
            if (streamName.contains(addon, ignoreCase = true)) {
                return addon
            }
        }
        val match = Regex("\\[([^\\]]+)\\]").find(streamName)
        if (match != null) {
            return match.groupValues[1]
        }
        return null
    }

    private lateinit var httpDataSourceFactory: androidx.media3.datasource.okhttp.OkHttpDataSource.Factory
    private lateinit var customDataSourceFactory: androidx.media3.datasource.DataSource.Factory
    
    // Fallback & Retry State
    private var transientRetryCount = 0

    // Progress persistence state
    private var lastSavedPosition = 0L
    private val saveIntervalMs = 10000L

    // Gestures state
    private var originalBrightness = 0.5f
    private var originalVolume = 0
    private var gestureOverlayJob: Job? = null
    private var currentVolumeFloat = 0.5f
    private var currentBrightnessFloat = 0.5f
    private var gestureStartY = 0f
    private var gestureStartX = 0f
    private var isVolumeGesture = false
    private var isBrightnessGesture = false
    private var isScrubbing = false
    private var scrubStartPos = 0L

    // Modular Player Components
    private lateinit var gestureController: com.example.zubflix.player.PlayerGestureController
    private lateinit var statsManager: com.example.zubflix.player.PlayerStatsManager

    // Subtitle Custom Preferences
    private var prefSubColor = Color.WHITE
    private var prefSubBg = Color.parseColor("#80000000")
    private var prefSubSize = 0.053f // normal size

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Forces landscape orientation for fully immersive cinematic playback
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Fully hide system UI & notch area
        hideSystemUI()

        // Unpack payload
        val rawUrls = intent.getStringArrayListExtra("VIDEO_URLS") ?: intent.getStringExtra("VIDEO_URL")?.let { arrayListOf(it) } ?: arrayListOf()
        videoUrls = rawUrls
        videoNames = intent.getStringArrayListExtra("VIDEO_NAMES") ?: emptyList()
        
        val targetUrl = intent.getStringExtra("VIDEO_URL")
        if (targetUrl != null) {
            val index = videoUrls.indexOf(targetUrl)
            if (index != -1) {
                currentUrlIndex = index
            }
        }
        
        if (videoUrls.isNotEmpty()) {
            val (parsedUrl, sub, headers) = parseStreamUrl(videoUrls[currentUrlIndex])
            videoUrl = sanitizeStreamUrl(parsedUrl)
            subtitleUrl = sub
            headersJson = headers
        }
        
        android.util.Log.d("PlayerActivity", "Received rawUrls: $videoUrls")
        android.util.Log.d("PlayerActivity", "Initial Parsed videoUrl: $videoUrl, subtitleUrl: $subtitleUrl, headersJson: $headersJson")
        if (videoUrl.startsWith("magnet:")) {
            android.util.Log.e("PlayerActivity", "Playback failed: URL is a magnet link, which is not supported directly.")
        }

        videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: "Video"
        itemId = intent.getStringExtra("ITEM_ID") ?: ""
        imageUrl = intent.getStringExtra("IMAGE_URL")
        isSeries = intent.getBooleanExtra("IS_SERIES", false)
        passedSeason = intent.getIntExtra("SEASON_NUMBER", -1)
        passedEpisode = intent.getIntExtra("EPISODE_NUMBER", -1)
        sourceName = intent.getStringExtra("SOURCE_NAME") ?: ""
        selectedStreamName = intent.getStringExtra("SELECTED_STREAM_NAME")
        actualImdbId = intent.getStringExtra("IMDB_ID")

        // Ensure unique itemId for each episode in a series to prevent shared playback progress
        if (isSeries && itemId.isNotEmpty()) {
            var seasonStr: String? = if (passedSeason != -1) passedSeason.toString() else null
            var episodeStr: String? = if (passedEpisode != -1) passedEpisode.toString() else null

            if (seasonStr == null || episodeStr == null) {
                val match = Regex("(?i)[sS](\\d+)\\s*[eE](\\d+)").find(videoTitle)
                    ?: Regex("(?i)Season\\s*(\\d+)\\s*Episode\\s*(\\d+)").find(videoTitle)
                if (match != null) {
                    seasonStr = match.groupValues[1]
                    episodeStr = match.groupValues[2]
                }
            }

            if (seasonStr != null && episodeStr != null) {
                // Append season:episode to the base ID if not already present
                if (!itemId.contains(":$seasonStr:$episodeStr")) {
                    itemId = "$itemId:$seasonStr:$episodeStr"
                    android.util.Log.d("PlayerActivity", "Series episode detected, updated itemId to: $itemId")
                }
            }
        }

        loadSubtitlePreferences()
        gestureController = com.example.zubflix.player.PlayerGestureController(this, binding, { player }) { timeMs -> stringForTime(timeMs) }
        statsManager = com.example.zubflix.player.PlayerStatsManager(this, binding) { player }
        setupExoPlayer()
        setupCustomControls()
        setupGestureDetectors()
        
        // Initialize sidebar listeners
        binding.btnCloseSidebar.setOnClickListener {
            hideSidebar()
        }
        binding.sidebarScrim.setOnClickListener {
            hideSidebar()
            hideSourceSidebar()
        }
        binding.btnCloseSourceSidebar.setOnClickListener {
            hideSourceSidebar()
        }
        
        // Start watch progress auto-saver
        startProgressAutoSaver()

        // Synchronize ActiveStreamManager with current item payload to clear any stale streams from previous titles
        val initialStreamItems = videoUrls.mapIndexed { idx, url ->
            val name = videoNames.getOrNull(idx)
                ?: if (videoUrls.size > 1) "Stream ${idx + 1}"
                else (selectedStreamName ?: sourceName.ifEmpty { "Default Stream" })
            com.example.zubflix.util.ActiveStreamManager.StreamItem(name, url)
        }
        com.example.zubflix.util.ActiveStreamManager.setStreams(this, initialStreamItems)

        observeActiveStreams()

        // Trigger background lazy re-scraping to discover additional qualities & BDIX/FTP stream links
        startBackgroundStreamRescraping()
    }

    private fun startBackgroundStreamRescraping() {
        // Disabled background re-scraping during active video playback to save memory and CPU resources for ExoPlayer.
    }

    private fun observeActiveStreams() {
        lifecycleScope.launch {
            com.example.zubflix.util.ActiveStreamManager.currentStreams.collect { streams ->
                if (streams.isNotEmpty()) {
                    val currentlyPlayingUrl = if (currentUrlIndex in videoUrls.indices) videoUrls[currentUrlIndex] else null

                    val newUrls = streams.map { it.url }
                    val newNames = streams.map { it.name }

                    videoUrls = newUrls
                    videoNames = newNames

                    if (currentlyPlayingUrl != null) {
                        val newIndex = videoUrls.indexOf(currentlyPlayingUrl)
                        if (newIndex != -1) {
                            currentUrlIndex = newIndex
                        }
                    }

                    if (binding.sourceSidebarContainer.visibility == View.VISIBLE) {
                        populateSourceSidebar()
                    }
                }
            }
        }
    }

    private fun parseStreamUrl(rawUrl: String): Triple<String, String?, String> {
        return if (rawUrl.contains("###")) {
            val parts = rawUrl.split("###")
            Triple(
                parts[0],
                parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
                parts.getOrNull(2) ?: ""
            )
        } else {
            Triple(rawUrl, null, "")
        }
    }

    private fun processStreamSubtitles(subData: String?) {
        if (subData.isNullOrEmpty()) return
        val trimmed = subData.trim()
        if (trimmed.startsWith("[")) {
            try {
                val jsonArray = org.json.JSONArray(trimmed)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val url = obj.optString("url")
                    if (url.isNotEmpty()) {
                        val lang = obj.optString("lang", "en")
                        val label = obj.optString("label", if (lang.isNotEmpty()) lang else "Source Subtitle")
                        val mime = when {
                            url.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                            url.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                            url.contains(".ssa", ignoreCase = true) || url.contains(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.APPLICATION_SUBRIP
                        }
                        if (!externalSubtitlesList.any { it.url == url }) {
                            externalSubtitlesList.add(ExternalSubtitle(url, label, lang, mime))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error parsing stream subtitles JSON array", e)
            }
        } else {
            val mime = when {
                trimmed.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                trimmed.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                trimmed.contains(".ssa", ignoreCase = true) || trimmed.contains(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            if (!externalSubtitlesList.any { it.url == trimmed }) {
                externalSubtitlesList.add(0, ExternalSubtitle(trimmed, "Source Subtitle", "en", mime))
            }
        }
    }

    private fun sanitizeStreamUrl(url: String): String {
        if (url.isBlank()) return url

        try {
            // 1. If it's already a valid HTTP URL → DO NOT TOUCH IT (most important for signed links)
            url.toHttpUrlOrNull()?.let { return url }

            // 2. Quick fix for obvious spaces
            val spaced = url.replace(" ", "%20")
            if (spaced.toHttpUrlOrNull() != null) return spaced

            // 3. Only sanitize if it's clearly broken (very rare now)
            //    Most modern signed MP4s should pass step 1
            if (url.contains(".mp4", ignoreCase = true) || 
                url.contains("sign=") || url.contains("token=") || url.contains("verify=")) {
                android.util.Log.w("PlayerActivity", "Preserving signed URL without sanitization: $url")
                return url
            }

            // 4. Fallback only for truly malformed URLs
            val parts = url.split("?", limit = 2)
            val baseUrl = parts[0]
            val queryStr = parts.getOrNull(1)

            val uri = Uri.parse(baseUrl)
            if (uri.scheme == null || uri.host == null) return url

            val encodedPath = uri.pathSegments.joinToString("/") { Uri.encode(it) }
            val cleanBase = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}/$encodedPath"

            if (queryStr.isNullOrEmpty()) return cleanBase

            // Minimal query sanitization - only encode keys/values if needed
            val encodedQuery = queryStr.split("&").joinToString("&") { param ->
                val pair = param.split("=", limit = 2)
                val key = Uri.encode(pair[0])
                val value = if (pair.size > 1) Uri.encode(pair[1]) else ""
                if (value.isNotEmpty()) "$key=$value" else key
            }

            return "$cleanBase?$encodedQuery"
        } catch (e: Exception) {
            android.util.Log.w("PlayerActivity", "Sanitization failed, using original URL", e)
            return url
        }
    }

    private var activeStreamHeaders = mutableMapOf<String, String>()

    private fun updateHttpDataSourceHeaders(newHeadersJson: String, currentUrl: String? = null) {
        val baseHeaders = mutableMapOf<String, String>()
        baseHeaders["Accept"] = "*/*"
        baseHeaders["Accept-Language"] = "en-US,en;q=0.9"
        
        // Add automatic Origin and Referer if URL is provided
        currentUrl?.let { url ->
            try {
                val uri = Uri.parse(url)
                val host = uri.host ?: ""
                // DO NOT add Referer/Origin for local IPs or if it's not a standard web URL
                val isLocal = host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.16.") || host == "localhost" || host == "127.0.0.1"
                if (!isLocal) {
                    val origin = "${uri.scheme}://${uri.host}"
                    baseHeaders["Origin"] = origin
                    baseHeaders["Referer"] = "$origin/"
                }
                
                // Specific header overrides for HDGhar / HDGharTV streams
                if (url.contains("hdghar", ignoreCase = true) || url.contains("ghar", ignoreCase = true) || host.contains("hdghar", ignoreCase = true)) {
                    baseHeaders["Referer"] = "https://hdghartv.cc/"
                    baseHeaders["Origin"] = "https://hdghartv.cc"
                    baseHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                }
            } catch (e: Exception) {}
        }
        
        if (newHeadersJson.isNotEmpty()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                val headersMap: Map<String, String> = com.google.gson.Gson().fromJson(newHeadersJson, type)
                android.util.Log.d("PlayerActivity", "Applying new stream headers: $headersMap")
                com.example.zubflix.util.DebugLogger.d("PlayerActivity", "Applying new stream headers: $headersMap")
                baseHeaders.putAll(headersMap)
            } catch(e: Exception) {
                com.example.zubflix.util.DebugLogger.e("PlayerActivity", "Error parsing stream headers: ${e.message}")
            }
        }
        
        activeStreamHeaders.clear()
        activeStreamHeaders.putAll(baseHeaders)

        httpDataSourceFactory.setDefaultRequestProperties(baseHeaders)
    }

    private suspend fun fetchStremioSubtitles(): List<ExternalSubtitle> {
        if (externalSubtitlesList.isNotEmpty()) return externalSubtitlesList

        val resultsList = mutableListOf<ExternalSubtitle>()
        try {
            if (actualImdbId == null && itemId.startsWith("tt")) {
                actualImdbId = itemId.split(":")[0]
            }
            
            // Extract Season/Episode if series
            var season = 1
            var episode = 1
            var cleanTitle = videoTitle
            if (isSeries) {
                if (passedSeason != -1 && passedEpisode != -1) {
                    season = passedSeason
                    episode = passedEpisode
                } else {
                    val parts = itemId.split(":")
                    if (parts.size >= 4 && parts[0] == "tv") {
                        season = parts[2].toIntOrNull() ?: 1
                        episode = parts[3].toIntOrNull() ?: 1
                    } else if (parts.size >= 3) {
                        season = parts[1].toIntOrNull() ?: 1
                        episode = parts[2].toIntOrNull() ?: 1
                    } else {
                        val match = Regex("(?i)[sS](\\d+)\\s*[eE](\\d+)").find(videoTitle)
                            ?: Regex("(?i)Season\\s*(\\d+)\\s*Episode\\s*(\\d+)").find(videoTitle)
                        if (match != null) {
                            season = match.groupValues[1].toInt()
                            episode = match.groupValues[2].toInt()
                        }
                    }
                }
                
                // Get clean title by removing season/episode info
                val match = Regex("(?i)[sS](\\d+)\\s*[eE](\\d+)").find(videoTitle)
                    ?: Regex("(?i)Season\\s*(\\d+)\\s*Episode\\s*(\\d+)").find(videoTitle)
                if (match != null) {
                    val index = videoTitle.indexOf(match.value)
                    if (index > 0) {
                        cleanTitle = videoTitle.substring(0, index).trim()
                    }
                }
            }

            if (actualImdbId == null) {
                // Fetch IMDB ID from TMDB
                val details = com.example.zubflix.utils.TmdbHelper.searchAndFetchDetails(this, cleanTitle, null, isSeries)
                actualImdbId = details?.imdbId
            }

            if (actualImdbId != null) {
                val queryId = if (isSeries) "$actualImdbId:$season:$episode" else actualImdbId!!
                val type = if (isSeries) "series" else "movie"
                
                val addons = com.example.zubflix.stremio.StremioAddonManager.getInstalledAddons(this)
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val gson = com.google.gson.Gson()

                val extraJson = "{\"query\":\"$cleanTitle\"}"
                val encodedExtra = java.net.URLEncoder.encode(extraJson, "UTF-8")

                val semaphore = Semaphore(3) // Limit concurrent requests to 3

                val results = kotlinx.coroutines.coroutineScope {
                    addons.filter { addon ->
                        addon.isEnabled && addon.resources?.any { res ->
                            res == "subtitles" || (res is Map<*, *> && res["name"] == "subtitles")
                        } == true
                    }.map { addon ->
                        async {
                            semaphore.withPermit {
                                try {
                                    val baseUrl = addon.manifestUrl.replace("/manifest.json", "")
                                    val url = "$baseUrl/subtitles/$type/$queryId.json?extra=$encodedExtra"
                                    
                                    val request = okhttp3.Request.Builder()
                                        .url(url)
                                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                                        .build()
                                    
                                    var response = client.newCall(request).execute()
                                    
                                    // Basic retry mechanism for 429
                                    if (response.code == 429) {
                                        delay(2000)
                                        response = client.newCall(request).execute()
                                    }
                                    
                                    val body = response.body?.string()
                                    
                                    if (response.isSuccessful && body != null) {
                                        val subtitlesRes = gson.fromJson(body, com.example.zubflix.stremio.StremioSubtitlesResponse::class.java)
                                        subtitlesRes?.subtitles?.mapNotNull { sub ->
                                            if (sub.url.isNotEmpty()) {
                                                val mime = when {
                                                    sub.url.contains(".vtt", ignoreCase = true) -> androidx.media3.common.MimeTypes.TEXT_VTT
                                                    sub.url.contains(".srt", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                                                    sub.url.contains(".ssa", ignoreCase = true) || sub.url.contains(".ass", ignoreCase = true) -> androidx.media3.common.MimeTypes.TEXT_SSA
                                                    else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                                                }
                                                val label = if (!sub.title.isNullOrEmpty()) {
                                                    "${sub.lang} - ${sub.title} (${addon.name})"
                                                } else {
                                                    "${sub.lang} (${addon.name})"
                                                }
                                                ExternalSubtitle(sub.url, label, sub.lang, mime)
                                            } else null
                                        } ?: emptyList()
                                    } else emptyList()
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            }
                        }
                    }.awaitAll()
                }
                
                results.forEach { resultsList.addAll(it) }
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Error fetching Stremio subs", e)
        }
        
        externalSubtitlesList.clear()
        externalSubtitlesList.addAll(resultsList)
        return resultsList
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun setupExoPlayer() {
        // Diagnostic Logging Interceptor
        val loggingInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request()
            var reqUrl = request.url.toString()
            if (reqUrl.contains("ctgfun.com", ignoreCase = true) && reqUrl.startsWith("https://", ignoreCase = true)) {
                reqUrl = reqUrl.replace("https://", "http://", ignoreCase = true)
            }
            val builder = request.newBuilder().url(reqUrl)
            
            // Re-apply active stream headers if missing (ensures redirected requests retain custom headers)
            activeStreamHeaders.forEach { (k, v) ->
                if (request.header(k) == null) {
                    builder.addHeader(k, v)
                }
            }

            // Auto-add Referer if missing but Origin is present
            if (request.header("Referer") == null && request.header("Origin") != null) {
                builder.addHeader("Referer", request.header("Origin")!! + "/")
            }
            
            val response = chain.proceed(builder.build())
            val contentType = response.header("Content-Type")
            android.util.Log.d("PlayerActivity", "HTTP ${response.code} (Type: $contentType) for URL: ${chain.request().url}")
            if (!response.isSuccessful || contentType?.contains("text/html") == true) {
                android.util.Log.e("PlayerActivity", "HTTP ${response.code} (Type: $contentType) for URL: ${chain.request().url}")
                com.example.zubflix.util.DebugLogger.e("PlayerActivity", "HTTP ${response.code} (Type: $contentType) for URL: ${chain.request().url}")
            }
            response
        }

        // Configure OkHttp with CookieJar and longer timeouts for stability
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .cookieJar(com.example.zubflix.sources.InMemoryCookieJar())
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
            
        // Configure Custom HTTP data source with browser-like User-Agent
        httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

        updateHttpDataSourceHeaders(headersJson)

        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        // Build premium LRU Cache Data Source Factory
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(VideoCacheManager.getCache(this))
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        customDataSourceFactory = androidx.media3.datasource.DataSource.Factory {
            LazySubtitleDataSource(
                cacheDataSourceFactory.createDataSource(),
                defaultDataSourceFactory.createDataSource(),
                { selectedExternalSubUrl },
                { uri ->
                    if (uri.scheme == "zubsub") {
                        uri.getQueryParameter("url")
                    } else {
                        null
                    }
                }
            )
        }

        // Custom High-Performance LoadControl (Balanced memory-safe buffer preventing OOM on seek)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                15000, // minBufferMs (15s min buffer)
                30000, // maxBufferMs (30s max buffer)
                2000,  // bufferForPlaybackMs (2s fast initial playback)
                3500   // bufferForPlaybackAfterRebufferMs (3.5s smooth recovery without stutter)
            )
            .setTargetBufferBytes(32 * 1024 * 1024) // 32MB strict memory buffer ceiling to prevent OOM
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(5000, false) // 5s back-buffer without keeping uncompressed frame blocks in RAM
            .build()

        // Adaptive Track Selector with audio capability fallback
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(false)
                    .setViewportSizeToPhysicalDisplaySize(this@PlayerActivity, true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setExceedRendererCapabilitiesIfNecessary(false)
                    .setTunnelingEnabled(false)
            )
        }

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(customDataSourceFactory, extractorsFactory)
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3))

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            .build()
        
        binding.playerView.player = player
        player?.volume = currentVolumeFloat

        // Apply visual Subtitle styles
        applySubtitleStyle()

        // Restore language preference parameters (Playback Memory)
        val trackBuilder = player?.trackSelectionParameters?.buildUpon() ?: TrackSelectionParameters.Builder(this)
        loadPreferredTrackParameters(trackBuilder)
        player?.trackSelectionParameters = trackBuilder.build()

        setupPlayerListeners()
        
        player?.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean
            ) {
                super.onLoadError(eventTime, loadEventInfo, mediaLoadData, error, wasCanceled)
                val statusHeader = loadEventInfo.responseHeaders["null"]?.firstOrNull() ?: loadEventInfo.responseHeaders["Status"]?.firstOrNull() ?: "Unknown"
                val logMsg = "🚨 Analytics LoadError: ${error.javaClass.simpleName} (${error.message}) | URI: ${loadEventInfo.uri} | HTTP Status: $statusHeader | TrackType: ${mediaLoadData.trackType} | DataType: ${mediaLoadData.dataType}"
                android.util.Log.e("PlayerActivity", logMsg, error)
                com.example.zubflix.util.DebugLogger.e("PlayerActivity", logMsg)
            }

            override fun onPlayerError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                error: androidx.media3.common.PlaybackException
            ) {
                super.onPlayerError(eventTime, error)
                val cause = error.cause
                val causeType = cause?.javaClass?.name ?: "None"
                val causeMsg = cause?.message ?: "None"
                val logMsg = "💥 Analytics PlayerError: Code=${error.errorCodeName} (${error.errorCode}) | Message=${error.message} | CauseType=$causeType | CauseMsg=$causeMsg"
                android.util.Log.e("PlayerActivity", logMsg, error)
                com.example.zubflix.util.DebugLogger.e("PlayerActivity", logMsg)

                if (cause is androidx.media3.common.ParserException) {
                    val parserMsg = "🔍 ParserException Details: contentIsMalformed=${cause.contentIsMalformed}, dataType=${cause.dataType}, cause=${cause.cause?.message}"
                    android.util.Log.e("PlayerActivity", parserMsg)
                    com.example.zubflix.util.DebugLogger.e("PlayerActivity", parserMsg)
                } else if (cause is androidx.media3.datasource.HttpDataSource.HttpDataSourceException) {
                    val httpMsg = "🌐 HttpDataSourceException Details: type=${cause.type}, responseCode=${(cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode}"
                    android.util.Log.e("PlayerActivity", httpMsg)
                    com.example.zubflix.util.DebugLogger.e("PlayerActivity", httpMsg)
                }
            }

            override fun onVideoCodecError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                videoCodecError: java.lang.Exception
            ) {
                super.onVideoCodecError(eventTime, videoCodecError)
                val msg = "📺 VideoCodecError: ${videoCodecError.javaClass.simpleName} - ${videoCodecError.message}"
                android.util.Log.e("PlayerActivity", msg, videoCodecError)
                com.example.zubflix.util.DebugLogger.e("PlayerActivity", msg)
            }

            override fun onDownstreamFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData
            ) {
                super.onDownstreamFormatChanged(eventTime, mediaLoadData)
                val format = mediaLoadData.trackFormat
                val msg = "ℹ️ Track Format Changed: mime=${format?.sampleMimeType}, container=${format?.containerMimeType}, codecs=${format?.codecs}, res=${format?.width}x${format?.height}"
                android.util.Log.d("PlayerActivity", msg)
                com.example.zubflix.util.DebugLogger.d("PlayerActivity", msg)
            }
        })

        prepareMediaAndPlay()
    }

    private fun prepareMediaAndPlay() {
        externalSubtitlesList.clear()
        selectedExternalSubUrl = null
        // Fetch Stremio subtitles and build MediaItem
        lifecycleScope.launch {
            binding.loadingContainer.visibility = View.VISIBLE
            
            val rawUrl = videoUrls[currentUrlIndex]
            val (parsedUrl, currentSubtitleUrl, currentHeadersJson) = parseStreamUrl(rawUrl)
            val currentUrl = sanitizeStreamUrl(parsedUrl)
            
            // Update headers for this specific source
            updateHttpDataSourceHeaders(currentHeadersJson, currentUrl)
            
            val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
            var directSubAdded = false
            
            // Add existing subtitle if provided in URL or stream data
            val streamSub = if (!currentSubtitleUrl.isNullOrEmpty()) currentSubtitleUrl else subtitleUrl
            processStreamSubtitles(streamSub)

            // Fetch extra subtitles first before preparing the stream
            try {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        fetchStremioSubtitles()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error pre-fetching stremio subtitles", e)
            }

            val addonName = getSelectedAddonName()
            
            // Sort external subtitles using the prioritization manager
            externalSubtitlesList.sortWith(compareBy<ExternalSubtitle> { extSub ->
                com.example.zubflix.stremio.SubtitlePrioritizationManager.getSubtitlePriority(
                    extSub.label,
                    addonName,
                    this@PlayerActivity
                )
            }.thenByDescending { extSub ->
                extSub.lang == "eng" || extSub.lang == "en"
            })

            // Auto-select based on User preference (Auto Enable Subtitles and Default Language)
            val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
            val autoEnableSubtitles = prefs.getBoolean("pref_auto_enable_subtitles", true)
            val defaultSubLang = prefs.getString("pref_default_sub_lang", "en")

            val targetLangs = when (defaultSubLang) {
                "en" -> listOf("eng", "en", "english")
                "bn" -> listOf("ben", "bn", "bengali")
                "hi" -> listOf("hin", "hi", "hindi")
                else -> listOf("eng", "en", "english")
            }

            if (!autoEnableSubtitles) {
                selectedExternalSubUrl = "OFF"
            } else {
                if (selectedExternalSubUrl == null) {
                    val autoSub = externalSubtitlesList.firstOrNull { extSub ->
                        targetLangs.contains(extSub.lang.lowercase())
                    } ?: externalSubtitlesList.firstOrNull { extSub ->
                        extSub.lang.lowercase().contains("en") || extSub.lang.lowercase().contains("eng")
                    } ?: externalSubtitlesList.firstOrNull()
                    
                    if (autoSub != null) {
                        selectedExternalSubUrl = autoSub.url
                    }
                }
            }

            // Attach ONLY the active/selected external subtitle (or direct source subtitle) to avoid ExoPlayer parsing invalid unselected subtitle tracks
            if (selectedExternalSubUrl != null && selectedExternalSubUrl != "OFF") {
                val activeExtSub = externalSubtitlesList.firstOrNull { it.url == selectedExternalSubUrl }
                if (activeExtSub != null) {
                    val dummyUri = Uri.parse("zubsub://external?url=${java.net.URLEncoder.encode(activeExtSub.url, "UTF-8")}&mime=${java.net.URLEncoder.encode(activeExtSub.mime, "UTF-8")}")
                    val subtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(dummyUri)
                        .setMimeType(activeExtSub.mime)
                        .setLanguage(activeExtSub.lang)
                        .setLabel(activeExtSub.label)
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT or androidx.media3.common.C.SELECTION_FLAG_FORCED)
                        .build()
                    subtitleConfigs.add(subtitleConfiguration)
                } else if (selectedExternalSubUrl == currentSubtitleUrl || selectedExternalSubUrl == subtitleUrl) {
                    val subUrl = selectedExternalSubUrl!!
                    val mime = when {
                        subUrl.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                        subUrl.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                        subUrl.contains(".ssa", ignoreCase = true) || subUrl.contains(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }
                    val subtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                        .setMimeType(mime)
                        .setLanguage("en")
                        .setLabel("English (Source)")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT or androidx.media3.common.C.SELECTION_FLAG_FORCED)
                        .build()
                    subtitleConfigs.add(subtitleConfiguration)
                }
            }

            binding.playerView.getVideoSurfaceView()?.visibility = View.VISIBLE

            val videoMime = when {
                currentUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                currentUrl.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                currentUrl.contains(".mp4", ignoreCase = true) || currentUrl.contains("/mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                currentUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
                else -> null
            }
            
            // Update headers with the specific URL to derive Referer/Origin
            updateHttpDataSourceHeaders(currentHeadersJson, currentUrl)
            
            // Force text track state in Player parameters before preparing
            val trackBuilder = player?.trackSelectionParameters?.buildUpon() ?: TrackSelectionParameters.Builder(this@PlayerActivity)
            if (selectedExternalSubUrl == "OFF") {
                trackBuilder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            } else {
                trackBuilder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                val selectedSub = externalSubtitlesList.firstOrNull { it.url == selectedExternalSubUrl }
                if (selectedSub != null) {
                    trackBuilder.setPreferredTextLanguage(selectedSub.lang)
                } else if (autoEnableSubtitles) {
                    trackBuilder.setPreferredTextLanguage(defaultSubLang)
                }
            }
            player?.trackSelectionParameters = trackBuilder.build()

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(currentUrl)
                .setSubtitleConfigurations(subtitleConfigs)
            
            if (videoMime != null) {
                mediaItemBuilder.setMimeType(videoMime)
            } else if (!currentUrl.contains(".m3u8", ignoreCase = true) && !currentUrl.contains(".mpd", ignoreCase = true)) {
                // Default to MP4 for unknown direct progressive streams
                mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
            }

            val mediaItem = mediaItemBuilder.build()
            
            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
            val mediaSource = DefaultMediaSourceFactory(customDataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)

            android.util.Log.d("PlayerActivity", "Setting MediaSource for URI: $currentUrl, Subtitles: ${subtitleConfigs.size}")
            com.example.zubflix.util.DebugLogger.d("PlayerActivity", "Playing: $currentUrl with ${subtitleConfigs.size} subtitles")

            player?.setMediaSource(mediaSource)
            player?.prepare()
            
            restoreSavedProgressAndPlay()
        }
    }

    private fun handleStreamPlaybackFailure(errorMsg: String = "Playback Error") {
        // 1. Pull any newly discovered streams from ActiveStreamManager background scraping
        val activeStreams = com.example.zubflix.util.ActiveStreamManager.currentStreams.value
        val existingUrls = videoUrls.toSet()
        val newStreams = activeStreams.filter { it.url !in existingUrls }
        if (newStreams.isNotEmpty()) {
            val updatedUrls = videoUrls.toMutableList()
            val updatedNames = videoNames.toMutableList()
            for (st in newStreams) {
                updatedUrls.add(st.url)
                updatedNames.add(st.name)
            }
            videoUrls = updatedUrls
            videoNames = updatedNames
        }

        // 2. Try the next available stream link
        if (currentUrlIndex < videoUrls.size - 1) {
            currentUrlIndex++
            val nextName = if (currentUrlIndex in videoNames.indices) videoNames[currentUrlIndex] else "Stream #${currentUrlIndex + 1}"
            android.util.Log.d("PlayerActivity", "Playback failed for stream ${currentUrlIndex}. Trying next candidate: $nextName")
            Toast.makeText(this@PlayerActivity, "Stream failed. Auto-switching to: $nextName (${currentUrlIndex + 1}/${videoUrls.size})...", Toast.LENGTH_SHORT).show()
            prepareMediaAndPlay()
        } else {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@PlayerActivity)
                .setTitle("Stream Unavailable")
                .setMessage("All available streams failed to play ($errorMsg). Would you like to select another source or retry?")
                .setPositiveButton("Select Source") { dialog, _ ->
                    dialog.dismiss()
                    showSourceSidebar()
                }
                .setNegativeButton("Close") { dialog, _ ->
                    dialog.dismiss()
                    finish()
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun parseHeadersToMap(headersJson: String?): Map<String, String>? {
        if (headersJson.isNullOrEmpty()) return null
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            com.google.gson.Gson().fromJson(headersJson, type)
        } catch (e: Exception) {
            null
        }
    }



    private fun reloadSubtitlesAndPrepare() {
        val p = player ?: return
        val currentPos = p.currentPosition
        
        lifecycleScope.launch {
            binding.loadingContainer.visibility = View.VISIBLE
            
            val rawUrl = videoUrls[currentUrlIndex]
            val (parsedUrl, currentSubtitleUrl, currentHeadersJson) = parseStreamUrl(rawUrl)
            val currentUrl = sanitizeStreamUrl(parsedUrl)
            
            val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
            var directSubAdded = false
            
            // Add existing subtitle if provided in URL or stream data
            val streamSub = if (!currentSubtitleUrl.isNullOrEmpty()) currentSubtitleUrl else subtitleUrl
            processStreamSubtitles(streamSub)

            val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
            val autoEnableSubtitles = prefs.getBoolean("pref_auto_enable_subtitles", true)
            val defaultSubLang = prefs.getString("pref_default_sub_lang", "en")

            val targetLangs = when (defaultSubLang) {
                "en" -> listOf("eng", "en", "english")
                "bn" -> listOf("ben", "bn", "bengali")
                "hi" -> listOf("hin", "hi", "hindi")
                else -> listOf("eng", "en", "english")
            }

            if (!autoEnableSubtitles) {
                selectedExternalSubUrl = "OFF"
            } else {
                if (selectedExternalSubUrl == null) {
                    val autoSub = externalSubtitlesList.firstOrNull { extSub ->
                        targetLangs.contains(extSub.lang.lowercase())
                    } ?: externalSubtitlesList.firstOrNull { extSub ->
                        extSub.lang.lowercase().contains("en") || extSub.lang.lowercase().contains("eng")
                    } ?: externalSubtitlesList.firstOrNull()
                    
                    if (autoSub != null) {
                        selectedExternalSubUrl = autoSub.url
                    }
                }
            }

            // Attach ONLY the active/selected external subtitle (or direct source subtitle) to avoid ExoPlayer parsing invalid unselected subtitle tracks
            if (selectedExternalSubUrl != null && selectedExternalSubUrl != "OFF") {
                val activeExtSub = externalSubtitlesList.firstOrNull { it.url == selectedExternalSubUrl }
                if (activeExtSub != null) {
                    val dummyUri = Uri.parse("zubsub://external?url=${java.net.URLEncoder.encode(activeExtSub.url, "UTF-8")}&mime=${java.net.URLEncoder.encode(activeExtSub.mime, "UTF-8")}")
                    val subtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(dummyUri)
                        .setMimeType(activeExtSub.mime)
                        .setLanguage(activeExtSub.lang)
                        .setLabel(activeExtSub.label)
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT or androidx.media3.common.C.SELECTION_FLAG_FORCED)
                        .build()
                    subtitleConfigs.add(subtitleConfiguration)
                } else if (selectedExternalSubUrl == currentSubtitleUrl || selectedExternalSubUrl == subtitleUrl) {
                    val subUrl = selectedExternalSubUrl!!
                    val mime = when {
                        subUrl.contains(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                        subUrl.contains(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
                        subUrl.contains(".ssa", ignoreCase = true) || subUrl.contains(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }
                    val subtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                        .setMimeType(mime)
                        .setLanguage("en")
                        .setLabel("English (Source)")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT or androidx.media3.common.C.SELECTION_FLAG_FORCED)
                        .build()
                    subtitleConfigs.add(subtitleConfiguration)
                }
            }

            val videoMime = when {
                currentUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                currentUrl.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                currentUrl.contains(".mp4", ignoreCase = true) || currentUrl.contains("/mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                currentUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
                else -> null
            }
            
            // Force text track state in Player parameters before preparing
            val trackBuilder = p.trackSelectionParameters.buildUpon()
            if (selectedExternalSubUrl == "OFF") {
                trackBuilder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            } else {
                trackBuilder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                val selectedSub = externalSubtitlesList.firstOrNull { it.url == selectedExternalSubUrl }
                if (selectedSub != null) {
                    trackBuilder.setPreferredTextLanguage(selectedSub.lang)
                } else if (autoEnableSubtitles) {
                    trackBuilder.setPreferredTextLanguage(defaultSubLang)
                }
            }
            p.trackSelectionParameters = trackBuilder.build()

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(currentUrl)
                .setSubtitleConfigurations(subtitleConfigs)
            
            if (videoMime != null) {
                mediaItemBuilder.setMimeType(videoMime)
            } else if (!currentUrl.contains(".m3u8", ignoreCase = true) && !currentUrl.contains(".mpd", ignoreCase = true)) {
                // Default to MP4 for unknown direct progressive streams
                mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
            }

            val mediaItem = mediaItemBuilder.build()
            
            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
            val mediaSource = DefaultMediaSourceFactory(customDataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)

            p.setMediaSource(mediaSource, false)
            p.prepare()
            p.seekTo(currentPos)
            p.play()
        }
    }

    private fun setupPlayerListeners() {
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        transientRetryCount = 0
                        binding.playerView.keepScreenOn = true
                        binding.loadingContainer.visibility = View.GONE
                    }
                    Player.STATE_BUFFERING -> {
                        binding.loadingContainer.visibility = View.VISIBLE
                        updateLoadingSpeedText()
                    }
                    Player.STATE_ENDED -> {
                        binding.playerView.keepScreenOn = false
                        binding.loadingContainer.visibility = View.GONE

                        // Detect premature stream disconnection (e.g. server closed connection abruptly before video finished)
                        val pos = player?.currentPosition ?: 0L
                        val dur = player?.duration ?: 0L
                        if (dur > 30000L && pos > 2000L && pos < dur - 20000L) {
                            android.util.Log.w("PlayerActivity", "Premature stream disconnection detected at pos $pos / dur $dur. Auto-switching to backup stream.")
                            com.example.zubflix.util.DebugLogger.w("PlayerActivity", "Premature stream disconnection detected. Switching backup stream.")
                            handleStreamPlaybackFailure("Premature Stream Disconnect")
                        }
                    }
                    Player.STATE_IDLE -> {
                        binding.loadingContainer.visibility = View.GONE
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                val cause = error.cause
                val causeInfo = "Cause: ${cause?.javaClass?.name} - ${cause?.message}"
                android.util.Log.e("PlayerActivity", "Playback error: ${error.errorCodeName} (${error.errorCode}), message: ${error.message} | $causeInfo", error)
                com.example.zubflix.util.DebugLogger.e("PlayerActivity", "Playback error: ${error.errorCodeName} (${error.errorCode}), message: ${error.message} | $causeInfo")
                
                // 1. Handle Subtitle Parsing Exception gracefully without switching or failing video sources
                val isVideoContainerError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                val errorMsg = "${error.message} ${cause?.message}".lowercase()
                val isSubtitleRelated = errorMsg.contains("sub") || errorMsg.contains("vtt") ||
                        errorMsg.contains("srt") || errorMsg.contains("ssa") || errorMsg.contains("ass") ||
                        errorMsg.contains("cue")

                if (!isVideoContainerError && isSubtitleRelated && cause is androidx.media3.common.ParserException && selectedExternalSubUrl != null && selectedExternalSubUrl != "OFF") {
                    android.util.Log.w("PlayerActivity", "External subtitle failed to parse ($cause). Disabling external subtitle and continuing video.")
                    com.example.zubflix.util.DebugLogger.w("PlayerActivity", "Selected subtitle format invalid. Playing without subtitle.")
                    Toast.makeText(this@PlayerActivity, "Subtitle format invalid. Continuing video without subtitle.", Toast.LENGTH_SHORT).show()
                    selectedExternalSubUrl = "OFF"
                    reloadSubtitlesAndPrepare()
                    return
                }

                // 2. Handle Audio Track / Decoder Failure gracefully without skipping the current video link
                val isAudioError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                        causeInfo.contains("AudioTrack", ignoreCase = true) ||
                        causeInfo.contains("AudioDecoder", ignoreCase = true)

                val hasAudioOverrides = player?.trackSelectionParameters?.overrides?.any { it.value.type == androidx.media3.common.C.TRACK_TYPE_AUDIO } == true

                if (isAudioError && hasAudioOverrides) {
                    android.util.Log.w("PlayerActivity", "Audio track error encountered. Clearing audio track overrides and reverting to default audio.")
                    Toast.makeText(this@PlayerActivity, "Selected audio track not supported. Reverting to default audio.", Toast.LENGTH_SHORT).show()

                    val currentPos = player?.currentPosition ?: 0L
                    player?.trackSelectionParameters = player?.trackSelectionParameters
                        ?.buildUpon()
                        ?.clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                        ?.build() ?: player?.trackSelectionParameters!!

                    player?.prepare()
                    if (currentPos > 0) {
                        player?.seekTo(currentPos)
                    }
                    player?.play()
                    return
                }

                // 3. Handle Transient Seek / HTTP Range / Parser Errors with max retry limit before switching stream
                val isRangeOrNetworkError = cause is androidx.media3.datasource.HttpDataSource.HttpDataSourceException ||
                        cause is java.io.IOException ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE

                val currentPos = player?.currentPosition ?: 0L
                if (isRangeOrNetworkError && currentPos > 0L && transientRetryCount < 2) {
                    transientRetryCount++
                    android.util.Log.w("PlayerActivity", "Recovering from transient seek/range error (Attempt $transientRetryCount/2) at position $currentPos: ${error.message}")
                    com.example.zubflix.util.DebugLogger.w("PlayerActivity", "Recovering stream after seek: ${error.message}")
                    player?.prepare()
                    player?.seekTo(currentPos)
                    player?.play()
                    return
                }

                transientRetryCount = 0
                handleStreamPlaybackFailure(error.errorCodeName)
            }

            override fun onTracksChanged(tracks: Tracks) {
                super.onTracksChanged(tracks)
                // Save preferred track selections if user manually triggers language change (Playback Memory)
                savePreferredLanguages()
                if (binding.sidebarContainer.visibility == View.VISIBLE && tracks.groups.isNotEmpty()) {
                    populateAudioSubSidebar()
                }
            }
        })
    }

    private fun setupCustomControls() {
        val controlView = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller) ?: return
        
        // Update custom views inside Custom Controller Layout
        controlView.findViewById<TextView>(R.id.player_item_title)?.text = videoTitle
        
        val backBtn = controlView.findViewById<View>(R.id.exo_back)
        backBtn?.setOnClickListener {
            if (binding.sidebarContainer.visibility == View.VISIBLE) {
                hideSidebar()
            } else if (binding.sourceSidebarContainer.visibility == View.VISIBLE) {
                hideSourceSidebar()
            } else {
                finish()
            }
        }

        val restartBtn = controlView.findViewById<View>(R.id.btn_restart)
        restartBtn?.setOnClickListener {
            player?.let { p ->
                p.seekTo(0)
                p.playWhenReady = true
                Toast.makeText(this, "Playing from start", Toast.LENGTH_SHORT).show()
            }
        }

        // Seekers
        val ffwdBtn = controlView.findViewById<View>(R.id.exo_ffwd_10)
        ffwdBtn?.setOnClickListener {
            player?.let { p -> p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration)) }
        }
        val rewBtn = controlView.findViewById<View>(R.id.exo_rew_5)
        rewBtn?.setOnClickListener {
            player?.let { p -> p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) }
        }

        // Configure options row click listeners inside the controller
        val speedBtn = controlView.findViewById<View>(R.id.btn_speed)
        speedBtn?.setOnClickListener {
            showPlaybackSpeedDialog()
        }
        val audioSubBtn = controlView.findViewById<View>(R.id.btn_audio_sub)
        audioSubBtn?.setOnClickListener {
            showAudioSubTrackSelector()
        }
        val aspectBtn = controlView.findViewById<View>(R.id.btn_aspect_ratio)
        aspectBtn?.setOnClickListener {
            toggleAspectRatio()
        }
        val sourcesBtn = controlView.findViewById<View>(R.id.btn_sources)
        sourcesBtn?.setOnClickListener {
            showSourceSidebar()
        }
        val statsBtn = controlView.findViewById<View>(R.id.btn_stats_toggle)
        statsBtn?.setOnClickListener {
            toggleStatsForNerds()
        }

        val playPauseBtn = controlView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
        playPauseBtn?.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) p.pause() else p.play()
            }
        }

        val progressTimeBar = controlView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)

        // Setup Android TV Focus Feedback animations for controls
        listOfNotNull(backBtn, ffwdBtn, rewBtn, playPauseBtn, speedBtn, audioSubBtn, aspectBtn, statsBtn).forEach { view ->
            com.example.zubflix.util.FocusHelper.applyControlFocus(view)
        }
    }

    private fun setupControlFocus(view: View) {
        com.example.zubflix.util.FocusHelper.applyControlFocus(view)
    }

    private fun setupGestureDetectors() {
        gestureController.setupGestures(this)
    }

    private fun showDoubleTapIndicator(text: String) {
        gestureController.showDoubleTapIndicator(text, this)
    }

    private fun updateLoadingSpeedText() {
        val bandwidthMeter = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this)
        val speedMbps = bandwidthMeter.bitrateEstimate / 1_000_000.0
        binding.tvLoadingSpeed.text = String.format(Locale.getDefault(), "Buffering... (%.1f Mbps)", speedMbps)
    }

    private fun restoreSavedProgressAndPlay() {
        if (itemId.isEmpty()) {
            player?.play()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@PlayerActivity).watchHistoryDao()
            val recent = dao.getRecentlyWatchedOnce(50)
            val match = recent.find { it.itemId == itemId }
            
            withContext(Dispatchers.Main) {
                if (match != null && match.currentPosition > 5000L) {
                    val formattedTime = stringForTime(match.currentPosition)
                    Toast.makeText(this@PlayerActivity, "Resuming from $formattedTime", Toast.LENGTH_SHORT).show()
                    player?.seekTo(match.currentPosition)
                }
                player?.play()
            }
        }
    }

    private fun startProgressAutoSaver() {
        lifecycleScope.launch {
            while (player != null) {
                saveWatchProgress()
                delay(saveIntervalMs)
            }
        }
    }

    private suspend fun saveWatchProgress() {
        val currentPos = player?.currentPosition ?: 0L
        val duration = player?.duration ?: 0L
        
        if (itemId.isEmpty() || currentPos <= 0L || duration <= 0L || currentPos == lastSavedPosition) return
        
        lastSavedPosition = currentPos
        val percent = (currentPos.toFloat() / duration.toFloat()) * 100f
        
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(this@PlayerActivity).watchHistoryDao()
            val currentStreamUrl = if (currentUrlIndex in videoUrls.indices) videoUrls[currentUrlIndex] else null
            val entity = WatchHistoryEntity(
                itemId = itemId,
                title = videoTitle,
                imageUrl = imageUrl,
                isSeries = isSeries,
                lastWatchedTimestamp = System.currentTimeMillis(),
                sourceName = sourceName,
                currentPosition = currentPos,
                totalDuration = duration,
                watchPercentage = percent,
                streamUrl = currentStreamUrl
            )
            dao.insertOrUpdate(entity)
        }
    }

    // Playback Speed Dialog
    private fun showPlaybackSpeedDialog() {
        val speeds = arrayOf("0.25x", "0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "1.75x", "2.0x", "3.0x")
        val speedValues = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
        
        val currentSpeed = player?.playbackParameters?.speed ?: 1.0f
        var selectedIdx = speedValues.indexOfFirst { Math.abs(it - currentSpeed) < 0.05f }
        if (selectedIdx == -1) selectedIdx = 3 // Default 1.0x
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(speeds, selectedIdx) { dialog, which ->
                val speed = speedValues[which]
                player?.setPlaybackSpeed(speed)
                
                // Update UI Label
                val controlView = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                val speedLabel = controlView?.findViewById<TextView>(R.id.tv_speed_label)
                speedLabel?.text = "Speed (${speeds[which].replace(" (Normal)", "")})"
                
                dialog.dismiss()
            }
            .show()
    }

    // Interactive Audio & Subtitle Track Selector
    private fun showAudioSubTrackSelector() {
        populateAudioSubSidebar()
        showSidebar()
    }

    private fun showSourceSidebar() {
        populateSourceSidebar()
        binding.sidebarScrim.visibility = View.VISIBLE
        binding.sidebarScrim.alpha = 0f
        binding.sidebarScrim.animate().alpha(1f).setDuration(300).start()

        binding.sourceSidebarContainer.visibility = View.VISIBLE
        val sidebarWidth = 380f * resources.displayMetrics.density
        binding.sourceSidebarContainer.translationX = sidebarWidth
        binding.sourceSidebarContainer.animate()
            .translationX(0f)
            .setDuration(300)
            .withEndAction {
                if (binding.sourcesContainer.childCount > 0) {
                    val focusTarget = binding.sourcesContainer.getChildAt(0)
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(focusTarget)
                } else if (binding.layoutSourceSidebarFilterTabs.childCount > 0) {
                    val tabTarget = binding.layoutSourceSidebarFilterTabs.getChildAt(0)
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(tabTarget)
                } else {
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(binding.btnCloseSourceSidebar)
                }
            }
            .start()

        binding.playerView.hideController()
    }

    private fun hideSourceSidebar() {
        binding.sidebarScrim.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.sidebarScrim.visibility = View.GONE }
            .start()

        val sidebarWidth = binding.sourceSidebarContainer.width.toFloat().let { if (it == 0f) 380f * resources.displayMetrics.density else it }
        binding.sourceSidebarContainer.animate()
            .translationX(sidebarWidth)
            .setDuration(300)
            .withEndAction { 
                binding.sourceSidebarContainer.visibility = View.GONE
                // Return focus to player view controls
                val controlView = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                val sourcesBtn = controlView?.findViewById<View>(R.id.btn_sources)
                if (sourcesBtn != null && binding.playerView.isControllerFullyVisible) {
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(sourcesBtn)
                } else {
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(binding.playerView)
                }
            }
            .start()
    }

    private data class ParsedStreamForSidebar(
        val rawName: String,
        val url: String,
        val addonName: String,
        val moniker: String,
        val torrentName: String,
        val attributes: List<String>
    )

    private fun parseStreamForSidebar(name: String, url: String): ParsedStreamForSidebar {
        var workingName = name.trim()
        
        // Parse addonName: starts with "["
        var addonName = ""
        if (workingName.startsWith("[")) {
            val endIdx = workingName.indexOf("]")
            if (endIdx > 1) {
                addonName = workingName.substring(1, endIdx)
                workingName = workingName.substring(endIdx + 1).trim()
            }
        }
        
        // Parse moniker: starts with "["
        var moniker = ""
        if (workingName.startsWith("[")) {
            val endIdx = workingName.indexOf("]")
            if (endIdx > 1) {
                moniker = workingName.substring(1, endIdx)
                workingName = workingName.substring(endIdx + 1).trim()
            }
        }
        
        // Parse attributes: ends with ")"
        var attributes = emptyList<String>()
        var torrentName = workingName
        if (workingName.endsWith(")")) {
            val startIdx = workingName.lastIndexOf("(")
            if (startIdx > 0) {
                val attrText = workingName.substring(startIdx + 1, workingName.length - 1)
                attributes = attrText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                torrentName = workingName.substring(0, startIdx).trim()
            }
        }
        
        return ParsedStreamForSidebar(name, url, addonName, moniker, torrentName, attributes)
    }

    private var selectedSourceSidebarCategory = "All"

    private fun getScraperCategoryForSidebar(streamName: String): String {
        val groupLocalScrapers = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("group_local_scrapers", true)

        val trimmed = streamName.trim()
        val lower = trimmed.lowercase()

        val isLocalScraper = trimmed.startsWith("[Local Scraper]") ||
                lower.contains("dhakaflix") || lower.contains("dflix") ||
                lower.contains("circleftp") || lower.contains("circle ftp") || lower.contains("circle") ||
                lower.contains("iccftp") || lower.contains("icc ftp") ||
                lower.contains("ctgmovie") || lower.contains("ctgmovies") ||
                lower.contains("discoveryftp") || lower.contains("discovery ftp")

        if (groupLocalScrapers && isLocalScraper) {
            return "Local Scrapers"
        }

        when {
            lower.contains("vegamovie") || lower.contains("vegamovies") -> return "VegaMovies"
            lower.contains("ctgmovie") || lower.contains("ctgmovies") -> return "CTGMovies"
            lower.contains("cinefreak") -> return "Cinefreak"
            lower.contains("moviebox") -> return "MovieBox"
            lower.contains("dhakaflix") -> return "DhakaFlix"
            lower.contains("dflix") -> return "DFlix"
            lower.contains("circle") || lower.contains("iccftp") -> return "BDIX FTP"
            lower.contains("pengu") -> return "Penguplay"
            lower.contains("stremio") || lower.contains("torrentio") -> return "Stremio"
            lower.contains("cloudstream") || lower.contains("cs") -> return "CloudStream"
        }

        if (trimmed.startsWith("[Local Scraper]")) {
            val rest = trimmed.substring("[Local Scraper]".length).trim()
            if (rest.startsWith("[")) {
                val endIdx = rest.indexOf("]")
                if (endIdx > 1) {
                    val name = rest.substring(1, endIdx).trim()
                    if (name.isNotEmpty()) return name
                }
            }
            if (rest.contains("|")) {
                val name = rest.substringBefore("|").trim()
                if (name.isNotEmpty()) return name
            } else if (rest.contains(" ")) {
                val name = rest.substringBefore(" ").trim()
                if (name.isNotEmpty()) return name
            } else if (rest.isNotEmpty()) {
                return rest
            }
            return "Local Scraper"
        } else if (trimmed.startsWith("[")) {
            val endIdx = trimmed.indexOf("]")
            if (endIdx > 1) {
                val name = trimmed.substring(1, endIdx).trim()
                if (name.isNotEmpty()) return name
            }
        }
        return "Other"
    }

    private fun isBdixStream(parsed: ParsedStreamForSidebar): Boolean {
        val fullText = "${parsed.rawName} ${parsed.addonName} ${parsed.moniker} ${parsed.torrentName} ${parsed.url}".lowercase()
        return fullText.contains("icc ftp") || fullText.contains("iccftp") ||
               fullText.contains("ctg movies") || fullText.contains("ctgmovies") ||
               fullText.contains("dhakaflix") ||
               fullText.contains("discoveryftp") || fullText.contains("discovery ftp") ||
               fullText.contains("circleftp") || fullText.contains("circle ftp")
    }

    private fun getQualityTagForSidebar(parsed: ParsedStreamForSidebar): String? {
        val fullText = "${parsed.rawName} ${parsed.torrentName} ${parsed.moniker} ${parsed.attributes.joinToString(" ")}".lowercase()
        return when {
            fullText.contains("2160p") || fullText.contains("2160") || fullText.contains("4k") || fullText.contains("uhd") -> "4K"
            fullText.contains("1080p") || fullText.contains("1080i") || fullText.contains("1080") || fullText.contains("fhd") || fullText.contains("full hd") -> "1080p"
            fullText.contains("720p") || fullText.contains("720i") || fullText.contains("720") -> "720p"
            fullText.contains("480p") || fullText.contains("480i") || fullText.contains("480") -> "480p"
            else -> null
        }
    }

    private fun populateSourceSidebar() {
        binding.sourcesContainer.removeAllViews()
        binding.layoutSourceSidebarFilterTabs.removeAllViews()

        val totalStreams = videoUrls.size
        if (totalStreams == 0) {
            binding.scrollSourceSidebarFilterTabs.visibility = View.GONE
            return
        }

        // 1. Parse and group streams into scraper categories and quality categories
        val categoriesMap = java.util.LinkedHashMap<String, Int>()
        val qualityMap = java.util.LinkedHashMap<String, Int>()
        val parsedList = ArrayList<Triple<Int, String, ParsedStreamForSidebar>>()

        for (i in videoUrls.indices) {
            val url = videoUrls[i]
            val rawName = if (i < videoNames.size) videoNames[i] else "Source ${i + 1}"
            val parsed = parseStreamForSidebar(rawName, url)
            
            val cat = getScraperCategoryForSidebar(rawName)
            categoriesMap[cat] = (categoriesMap[cat] ?: 0) + 1

            val qual = getQualityTagForSidebar(parsed)
            if (qual != null) {
                qualityMap[qual] = (qualityMap[qual] ?: 0) + 1
            }

            parsedList.add(Triple(i, cat, parsed))
        }

        val tabCategories = mutableListOf<Pair<String, Int>>()
        tabCategories.add(Pair("All", totalStreams))

        // Preferred order: Quality (1080p, 720p, 4K, 480p), BDIX, Penguplay, Local Scrapers
        listOf("1080p", "720p", "4K", "480p").forEach { q ->
            val count = qualityMap[q] ?: 0
            if (count > 0) {
                tabCategories.add(Pair(q, count))
            }
        }

        val bdixCount = parsedList.count { isBdixStream(it.third) }
        if (bdixCount > 0) {
            tabCategories.add(Pair("BDIX", bdixCount))
        }

        val penguCount = parsedList.count {
            val fullText = "${it.third.rawName} ${it.third.addonName} ${it.third.moniker}".lowercase()
            fullText.contains("pengu") || fullText.contains("stremio") || fullText.contains("torrentio") || fullText.contains("addon")
        }
        if (penguCount > 0) {
            tabCategories.add(Pair("Penguplay", penguCount))
        }

        val localCount = parsedList.count {
            val fullText = "${it.third.rawName} ${it.third.addonName} ${it.third.moniker}".lowercase()
            it.second == "Local Scrapers" || fullText.contains("[local scraper]") || fullText.contains("dhakaflix") || fullText.contains("circleftp") || fullText.contains("iccftp") || fullText.contains("ctgmovie")
        }
        if (localCount > 0) {
            tabCategories.add(Pair("Local Scrapers", localCount))
        }

        // Add remaining scraper categories
        categoriesMap.forEach { (cat, count) ->
            if (cat != "Local Scrapers" && cat != "BDIX" && cat != "Penguplay") {
                tabCategories.add(Pair(cat, count))
            }
        }

        // 2. Render filter tabs if multiple options exist
        if (tabCategories.size > 1) {
            binding.scrollSourceSidebarFilterTabs.visibility = View.VISIBLE

            val validTabNames = tabCategories.map { it.first }
            if (selectedSourceSidebarCategory != "All" && !validTabNames.contains(selectedSourceSidebarCategory)) {
                selectedSourceSidebarCategory = "All"
            }

            for ((cat, count) in tabCategories) {
                val tabView = TextView(this).apply {
                    text = "$cat ($count)"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    val padH = (12 * resources.displayMetrics.density).toInt()
                    val padV = (6 * resources.displayMetrics.density).toInt()
                    setPadding(padH, padV, padH, padV)

                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                    layoutParams = lp

                    val isSelected = (cat == selectedSourceSidebarCategory)
                    if (isSelected) {
                        setTextColor(Color.WHITE)
                        setBackgroundResource(R.drawable.bg_season_chip_selected)
                    } else {
                        setTextColor(Color.parseColor("#AAAAAA"))
                        setBackgroundResource(R.drawable.bg_season_chip_unselected)
                    }

                    isFocusable = true
                    isClickable = true

                    setOnClickListener {
                        selectedSourceSidebarCategory = cat
                        populateSourceSidebar()
                    }

                    setOnFocusChangeListener { v, hasFocus ->
                        if (hasFocus) {
                            v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                        } else {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                        }
                    }
                }
                binding.layoutSourceSidebarFilterTabs.addView(tabView)
            }
        } else {
            binding.scrollSourceSidebarFilterTabs.visibility = View.GONE
        }

        // 3. Filter list by selected category or quality or BDIX or Penguplay or Local Scrapers
        val filteredList = when (selectedSourceSidebarCategory) {
            "All" -> parsedList
            "BDIX" -> parsedList.filter { isBdixStream(it.third) }
            "4K", "1080p", "720p", "480p" -> parsedList.filter { getQualityTagForSidebar(it.third) == selectedSourceSidebarCategory }
            "Penguplay" -> parsedList.filter {
                val fullText = "${it.third.rawName} ${it.third.addonName} ${it.third.moniker}".lowercase()
                fullText.contains("pengu") || fullText.contains("stremio") || fullText.contains("torrentio") || fullText.contains("addon")
            }
            "Local Scrapers" -> parsedList.filter {
                val fullText = "${it.third.rawName} ${it.third.addonName} ${it.third.moniker}".lowercase()
                it.second == "Local Scrapers" || fullText.contains("[local scraper]") || fullText.contains("dhakaflix") || fullText.contains("circleftp") || fullText.contains("iccftp") || fullText.contains("ctgmovie")
            }
            else -> parsedList.filter { it.second == selectedSourceSidebarCategory }
        }

        val inflater = LayoutInflater.from(this)

        for ((originalIndex, cat, parsed) in filteredList) {
            val isSelected = (originalIndex == currentUrlIndex)

            val itemView = inflater.inflate(R.layout.item_nuvio_stream, binding.sourcesContainer, false)

            val tvAddonProvider = itemView.findViewById<TextView>(R.id.tv_addon_provider)
            val tvMoniker = itemView.findViewById<TextView>(R.id.tv_moniker)
            val tvTorrentTitle = itemView.findViewById<TextView>(R.id.tv_torrent_title)
            val tvBadgeResolution = itemView.findViewById<TextView>(R.id.tv_badge_resolution)
            val tvBadgeSize = itemView.findViewById<TextView>(R.id.tv_badge_size)
            val tvBadgeAudio = itemView.findViewById<TextView>(R.id.tv_badge_audio)
            val tvBadgeQuality = itemView.findViewById<TextView>(R.id.tv_badge_quality)
            val tvBadgeCodec = itemView.findViewById<TextView>(R.id.tv_badge_codec)

            val providerDisplayName = parsed.addonName.ifEmpty { cat.ifEmpty { sourceName.ifEmpty { "Stream" } } }
            tvAddonProvider.text = providerDisplayName

            if (parsed.moniker.isNotEmpty()) {
                tvMoniker.text = parsed.moniker
                tvMoniker.visibility = View.VISIBLE
            } else {
                tvMoniker.visibility = View.GONE
            }

            if (parsed.torrentName.contains("\n")) {
                val parts = parsed.torrentName.split("\n", limit = 2)
                val spannable = android.text.SpannableStringBuilder()
                spannable.append(parts[0])
                spannable.append("\n")
                val startIdx = spannable.length
                spannable.append(parts[1])
                spannable.setSpan(
                    android.text.style.AbsoluteSizeSpan(12, true),
                    startIdx,
                    spannable.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(if (isSelected) Color.parseColor("#DDDDDD") else Color.parseColor("#AAAAAA")),
                    startIdx,
                    spannable.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                tvTorrentTitle.text = spannable
            } else {
                tvTorrentTitle.text = parsed.torrentName
            }

            // Styling for currently playing stream vs available streams
            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.bg_stream_card_playing)
                tvTorrentTitle.setTextColor(Color.WHITE)
                tvTorrentTitle.setTypeface(null, Typeface.BOLD)

                tvMoniker.text = if (parsed.moniker.isNotEmpty()) "${parsed.moniker} [PLAYING]" else "[PLAYING]"
                tvMoniker.setTextColor(Color.parseColor("#FF4D4D"))
                tvMoniker.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#4D0A0A")))
                tvMoniker.visibility = View.VISIBLE
            } else {
                itemView.setBackgroundResource(R.drawable.bg_stream_card_selector)
                tvTorrentTitle.setTextColor(Color.parseColor("#EEEEEE"))
                tvTorrentTitle.setTypeface(null, Typeface.NORMAL)
                tvMoniker.setTextColor(Color.parseColor("#00D2FF"))
                tvMoniker.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#05203C")))
            }

            // Reset and configure badges
            tvBadgeResolution.visibility = View.GONE
            tvBadgeSize.visibility = View.GONE
            tvBadgeAudio.visibility = View.GONE
            tvBadgeQuality.visibility = View.GONE
            tvBadgeCodec.visibility = View.GONE

            parsed.attributes.forEach { attr ->
                val lowerAttr = attr.lowercase()
                when {
                    lowerAttr.matches(Regex(".*(4k|1080p|720p|480p|2160p).*")) -> {
                        tvBadgeResolution.text = attr
                        tvBadgeResolution.visibility = View.VISIBLE
                    }
                    lowerAttr.matches(Regex(".*(gb|mb).*")) -> {
                        tvBadgeSize.text = attr
                        tvBadgeSize.visibility = View.VISIBLE
                    }
                    lowerAttr.matches(Regex(".*(aac|ac3|dts|dolby|5\\.1|7\\.1).*")) -> {
                        tvBadgeAudio.text = attr
                        tvBadgeAudio.visibility = View.VISIBLE
                    }
                    lowerAttr.matches(Regex(".*(hdr|sdr|bluray|web-dl|webrip|cam).*")) -> {
                        tvBadgeQuality.text = attr
                        tvBadgeQuality.visibility = View.VISIBLE
                    }
                    lowerAttr.matches(Regex(".*(hevc|x265|h264|x264|av1).*")) -> {
                        tvBadgeCodec.text = attr
                        tvBadgeCodec.visibility = View.VISIBLE
                    }
                    else -> {
                        if (tvBadgeQuality.visibility == View.GONE) {
                            tvBadgeQuality.text = attr
                            tvBadgeQuality.visibility = View.VISIBLE
                        } else if (tvBadgeAudio.visibility == View.GONE) {
                            tvBadgeAudio.text = attr
                            tvBadgeAudio.visibility = View.VISIBLE
                        }
                    }
                }
            }

            itemView.setOnClickListener {
                if (!isSelected) {
                    currentUrlIndex = originalIndex
                    hideSourceSidebar()
                    prepareMediaAndPlay()
                    Toast.makeText(this, "Switched to ${parsed.torrentName}", Toast.LENGTH_SHORT).show()
                }
            }

            itemView.isFocusable = true
            itemView.isClickable = true

            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.scaleX = 1.02f
                    view.scaleY = 1.02f
                    view.translationZ = 4f
                } else {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                    view.translationZ = 0f
                }
            }

            binding.sourcesContainer.addView(itemView)
        }
    }

    private fun showSidebar() {
        binding.sidebarScrim.visibility = View.VISIBLE
        binding.sidebarScrim.alpha = 0f
        binding.sidebarScrim.animate().alpha(1f).setDuration(300).start()

        binding.sidebarContainer.visibility = View.VISIBLE
        val sidebarWidth = binding.sidebarContainer.width.toFloat().let { if (it == 0f) 380f * resources.displayMetrics.density else it }
        binding.sidebarContainer.translationX = sidebarWidth
        binding.sidebarContainer.animate()
            .translationX(0f)
            .setDuration(300)
            .withEndAction {
                if (binding.audioTracksContainer.childCount > 0) {
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(binding.audioTracksContainer.getChildAt(0))
                } else if (binding.subtitleTracksContainer.childCount > 0) {
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(binding.subtitleTracksContainer.getChildAt(0))
                } else {
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(binding.btnCloseSidebar)
                }
            }
            .start()

        binding.playerView.hideController()
    }

    private fun hideSidebar() {
        binding.sidebarScrim.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { binding.sidebarScrim.visibility = View.GONE }
            .start()

        val sidebarWidth = binding.sidebarContainer.width.toFloat().let { if (it == 0f) 380f * resources.displayMetrics.density else it }
        binding.sidebarContainer.animate()
            .translationX(sidebarWidth)
            .setDuration(300)
            .withEndAction { 
                binding.sidebarContainer.visibility = View.GONE 
                // Return focus to audio/sub button on controls
                val controlView = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                val audioSubBtn = controlView?.findViewById<View>(R.id.btn_audio_sub)
                if (audioSubBtn != null && binding.playerView.isControllerFullyVisible) {
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(audioSubBtn)
                } else {
                    com.example.zubflix.util.FocusHelper.safeRequestFocus(binding.playerView)
                }
            }
            .start()
    }

    private fun populateAudioSubSidebar() {
        val tracks = player?.currentTracks ?: return
        if (tracks.groups.isEmpty()) {
            return
        }
        val audioList = mutableListOf<Pair<Tracks.Group, Int>>()
        val subtitleList = mutableListOf<Pair<Tracks.Group, Int>>()

        for (group in tracks.groups) {
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    audioList.add(Pair(group, i))
                }
            } else if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    subtitleList.add(Pair(group, i))
                }
            }
        }

        binding.audioTracksContainer.removeAllViews()
        binding.subtitleTracksContainer.removeAllViews()

        // Populate Audio
        for (track in audioList) {
            val format = track.first.getTrackFormat(track.second)
            val langDisplay = format.language?.let { langCode ->
                try {
                    val loc = Locale(langCode)
                    val disp = loc.displayLanguage
                    if (disp.isNotEmpty()) disp else langCode
                } catch (e: Exception) {
                    langCode
                }
            }
            val mimeType = format.sampleMimeType?.lowercase(Locale.ROOT)
            val codecInfo = when {
                mimeType == "audio/ac3" -> "AC-3 5.1"
                mimeType == "audio/eac3" -> "E-AC-3"
                mimeType == "audio/eac3-joc" -> "E-AC-3 Atmos"
                mimeType == "audio/vnd.dts" -> "DTS"
                mimeType == "audio/vnd.dts.hd" -> "DTS-HD"
                mimeType == "audio/mp4a-latm" -> "AAC"
                mimeType == "audio/mpeg" -> "MP3"
                mimeType == "audio/opus" -> "Opus"
                mimeType == "audio/flac" -> "FLAC"
                mimeType == "audio/raw" || mimeType == "audio/pcm" -> "PCM"
                mimeType != null -> mimeType.substringAfter("/").uppercase(Locale.ROOT)
                else -> null
            }
            val channelInfo = if (format.channelCount > 0) "${format.channelCount}ch" else null
            val extraDetails = listOfNotNull(codecInfo, channelInfo).joinToString(", ")

            val rawLabel = format.label
            val isSupported = track.first.isTrackSupported(track.second)
            val baseLabel = if (!rawLabel.isNullOrBlank()) rawLabel else (langDisplay ?: "Audio track #${audioList.indexOf(track) + 1}")
            val formattedDetails = if (extraDetails.isNotEmpty()) "$baseLabel ($extraDetails)" else baseLabel
            val displayLabel = if (isSupported) formattedDetails else "$formattedDetails ⚠️ [Unsupported Codec]"

            val isSelected = track.first.isTrackSelected(track.second)

            val rowView = createTrackRow(displayLabel, isSelected) {
                if (!isSupported) {
                    Toast.makeText(this, "Audio format ${codecInfo ?: ""} is not supported by your device's hardware.", Toast.LENGTH_LONG).show()
                    return@createTrackRow
                }
                val trackParamsBuilder = player?.trackSelectionParameters?.buildUpon()
                    ?.clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)

                if (!format.language.isNullOrBlank()) {
                    trackParamsBuilder?.setPreferredAudioLanguage(format.language)
                    val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
                    prefs.edit().putString("pref_audio_lang", format.language).apply()
                }

                val override = TrackSelectionOverride(track.first.mediaTrackGroup, track.second)
                trackParamsBuilder?.addOverride(override)

                player?.trackSelectionParameters = trackParamsBuilder?.build() ?: player?.trackSelectionParameters!!
                Toast.makeText(this, "Switched Audio to $displayLabel", Toast.LENGTH_SHORT).show()
                populateAudioSubSidebar()
            }
            binding.audioTracksContainer.addView(rowView)
        }

        // Populate Subtitles
        val isSubDisabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(androidx.media3.common.C.TRACK_TYPE_TEXT) ?: false

        val offRow = createTrackRow("Off", isSubDisabled || selectedExternalSubUrl == "OFF") {
            selectedExternalSubUrl = "OFF"
            player?.trackSelectionParameters = player?.trackSelectionParameters
                ?.buildUpon()
                ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                ?.build() ?: player?.trackSelectionParameters!!
            Toast.makeText(this, "Subtitles Disabled", Toast.LENGTH_SHORT).show()
            populateAudioSubSidebar()
        }
        binding.subtitleTracksContainer.addView(offRow)

        // Embedded video container tracks
        for (track in subtitleList) {
            val format = track.first.getTrackFormat(track.second)
            val label = format.label ?: format.language?.let { Locale(it).displayLanguage } ?: "Container Subtitle #${subtitleList.indexOf(track) + 1}"
            val isSelected = !isSubDisabled && track.first.isTrackSelected(track.second) && selectedExternalSubUrl == null

            val rowView = createTrackRow(label, isSelected) {
                selectedExternalSubUrl = null
                player?.trackSelectionParameters = player?.trackSelectionParameters
                    ?.buildUpon()
                    ?.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    ?.clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)?.addOverride(TrackSelectionOverride(track.first.mediaTrackGroup, track.second))
                    ?.build() ?: player?.trackSelectionParameters!!
                Toast.makeText(this, "Subtitles set to $label", Toast.LENGTH_SHORT).show()
                populateAudioSubSidebar()
            }
            binding.subtitleTracksContainer.addView(rowView)
        }

        // External cloud subtitles from Stremio/OpenSubtitles
        for (extSub in externalSubtitlesList) {
            val isSelected = !isSubDisabled && selectedExternalSubUrl == extSub.url
            val rowView = createTrackRow(extSub.label, isSelected) {
                selectedExternalSubUrl = extSub.url
                val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
                prefs.edit().putString("pref_text_lang", extSub.lang).apply()
                
                reloadSubtitlesAndPrepare()
                Toast.makeText(this, "Loading subtitle: ${extSub.label}", Toast.LENGTH_SHORT).show()
                populateAudioSubSidebar()
            }
            binding.subtitleTracksContainer.addView(rowView)
        }
    }

    private fun injectExternalSubtitle(extSub: ExternalSubtitle, shouldHideSidebar: Boolean = true) {
        val p = player ?: return
        val currentPos = p.currentPosition
        val currentUri = p.currentMediaItem?.localConfiguration?.uri ?: return
        
        selectedExternalSubUrl = extSub.url
        if (shouldHideSidebar) {
            Toast.makeText(this, "Loading Cloud Subtitle: ${extSub.label}", Toast.LENGTH_SHORT).show()
        }

        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(extSub.url))
            .setMimeType(extSub.mime)
            .setLanguage(extSub.lang)
            .setLabel(extSub.label)
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .build()

        // Re-build MediaItem with new subtitle
        val currentMediaItem = p.currentMediaItem!!
        val mediaItemBuilder = currentMediaItem.buildUpon()
            .setSubtitleConfigurations(listOf(subtitleConfig))
        
        val newMediaItem = mediaItemBuilder.build()
        
        // We use a specific MediaSource to ensure it doesn't lose headers or type
        val mediaSource = when {
            currentUri.toString().contains(".mpd") -> DashMediaSource.Factory(httpDataSourceFactory).createMediaSource(newMediaItem)
            currentUri.toString().contains(".m3u8") -> HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(newMediaItem)
            else -> ProgressiveMediaSource.Factory(httpDataSourceFactory).createMediaSource(newMediaItem)
        }

        p.setMediaSource(mediaSource)
        p.prepare()
        p.seekTo(currentPos)
        p.play()
        
        if (shouldHideSidebar) {
            hideSidebar()
        }
    }

    private fun createTrackRow(label: String, isSelected: Boolean, onClick: () -> Unit): View {
        val textView = TextView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = label
            textSize = 14f
            if (isSelected) {
                setTextColor(Color.parseColor("#E50914"))
                setTypeface(null, Typeface.BOLD)
            } else {
                setTextColor(Color.parseColor("#B3FFFFFF"))
                setTypeface(null, Typeface.NORMAL)
            }
        }

        val container = android.widget.LinearLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (4 * resources.displayMetrics.density).toInt()
                setMargins(margin, margin, margin, margin)
            }
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val verticalPadding = (12 * resources.displayMetrics.density).toInt()
            val horizontalPadding = (16 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

            isClickable = true
            isFocusable = true
            
            val defaultBg = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            val focusedBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = 8 * resources.displayMetrics.density
            }
            background = defaultBg
            
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.background = focusedBg
                    view.scaleX = 1.02f
                    view.scaleY = 1.02f
                    if (!isSelected) {
                        textView.setTextColor(Color.WHITE)
                    }
                } else {
                    view.background = defaultBg
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                    if (!isSelected) {
                        textView.setTextColor(Color.parseColor("#B3FFFFFF"))
                    }
                }
            }
            
            setOnClickListener { onClick() }
        }

        val checkIcon = android.widget.ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (20 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt()
            ).apply {
                marginEnd = (12 * resources.displayMetrics.density).toInt()
            }
            setImageResource(R.drawable.ic_check)
            if (isSelected) {
                visibility = View.VISIBLE
                setColorFilter(Color.parseColor("#E50914"))
            } else {
                visibility = View.INVISIBLE
            }
        }
        container.addView(checkIcon)
        container.addView(textView)

        return container
    }

    override fun onBackPressed() {
        if (binding.sidebarContainer.visibility == View.VISIBLE) {
            hideSidebar()
        } else if (binding.sourceSidebarContainer.visibility == View.VISIBLE) {
            hideSourceSidebar()
        } else if (binding.playerView.isControllerFullyVisible) {
            binding.playerView.hideController()
        } else {
            super.onBackPressed()
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (binding.sidebarContainer.visibility == View.VISIBLE) {
                if (event.action == android.view.KeyEvent.ACTION_UP) {
                    hideSidebar()
                }
                return true
            } else if (binding.sourceSidebarContainer.visibility == View.VISIBLE) {
                if (event.action == android.view.KeyEvent.ACTION_UP) {
                    hideSourceSidebar()
                }
                return true
            } else if (binding.playerView.isControllerFullyVisible) {
                if (event.action == android.view.KeyEvent.ACTION_UP) {
                    binding.playerView.hideController()
                }
                return true
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            if (binding.sidebarContainer.visibility != View.VISIBLE && binding.sourceSidebarContainer.visibility != View.VISIBLE) {
                if (!binding.playerView.isControllerFullyVisible) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) {
                        binding.playerView.showController()
                        val controlView = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                        val playPauseBtn = controlView?.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                        com.example.zubflix.util.FocusHelper.safeRequestFocus(playPauseBtn ?: binding.playerView)
                    }
                    return true
                }
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            if (binding.sidebarContainer.visibility != View.VISIBLE && binding.sourceSidebarContainer.visibility != View.VISIBLE) {
                if (!binding.playerView.isControllerFullyVisible) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) {
                        binding.playerView.showController()
                        val controlView = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                        val playPauseBtn = controlView?.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                        com.example.zubflix.util.FocusHelper.safeRequestFocus(playPauseBtn ?: binding.playerView)
                    }
                    return true
                }
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (binding.sidebarContainer.visibility != View.VISIBLE && binding.sourceSidebarContainer.visibility != View.VISIBLE) {
                val isControlsVisible = binding.playerView.isControllerFullyVisible
                val focusedView = currentFocus
                val progressId = resources.getIdentifier("exo_progress", "id", packageName)
                val focusedId = focusedView?.id ?: 0

                // Seek ONLY if controls are hidden, or if no view has focus, or if the focused view is the progress bar (seek bar)
                val shouldSeek = !isControlsVisible || focusedView == null || focusedId == progressId

                if (shouldSeek) {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        val increment = 10000L // 10s seek
                        player?.let { p ->
                            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                p.seekTo((p.currentPosition - increment).coerceAtLeast(0))
                                showDoubleTapIndicator("-10s")
                            } else {
                                p.seekTo((p.currentPosition + increment).coerceAtMost(p.duration))
                                showDoubleTapIndicator("+10s")
                            }
                        }
                        binding.playerView.showController()
                        
                        if (progressId != 0) {
                            binding.playerView.findViewById<View>(progressId)?.requestFocus()
                        }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Toggle Aspect Ratio Mode
    private fun toggleAspectRatio() {
        val options = arrayOf("Fit Screen", "Fill Screen", "Stretch Screen", "Zoom Screen")
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        
        val currentMode = binding.playerView.resizeMode
        var selectedIdx = modes.indexOf(currentMode)
        if (selectedIdx == -1) selectedIdx = 0
        
        val nextIdx = (selectedIdx + 1) % options.size
        binding.playerView.resizeMode = modes[nextIdx]
        
        // Update Label
        val controlView = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
        val aspectLabel = controlView?.findViewById<TextView>(R.id.tv_aspect_ratio_label)
        aspectLabel?.text = "Aspect: ${options[nextIdx].replace(" Screen", "")}"
        
        Toast.makeText(this, "Aspect: ${options[nextIdx]}", Toast.LENGTH_SHORT).show()
    }

    // Subtitle Custom Styling settings
    private fun applySubtitleStyle() {
        com.example.zubflix.player.PlayerTrackManager.applySubtitleStyle(binding.playerView, this)
    }

    private fun loadSubtitlePreferences() {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        prefSubColor = prefs.getInt("sub_color", Color.WHITE)
        prefSubBg = prefs.getInt("sub_bg", Color.parseColor("#80000000"))
        val sizeStr = prefs.getString("sub_size", "0.053") ?: "0.053"
        prefSubSize = sizeStr.toFloatOrNull() ?: 0.053f
    }

    private fun saveSubtitlePref(key: String, value: Any) {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        val edit = prefs.edit()
        when (value) {
            is Int -> edit.putInt("sub_$key", value)
            is String -> edit.putString("sub_$key", value)
        }
        edit.apply()
    }

    // Playback Memory: Preferred language saving
    private fun savePreferredLanguages() {
        val params = player?.trackSelectionParameters ?: return
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        val edit = prefs.edit()
        
        val audioLangs = params.preferredAudioLanguages
        if (audioLangs.isNotEmpty()) {
            edit.putString("pref_audio_lang", audioLangs[0])
        }
        
        val textLangs = params.preferredTextLanguages
        if (textLangs.isNotEmpty()) {
            edit.putString("pref_text_lang", textLangs[0])
        }
        edit.apply()
    }

    private fun loadPreferredTrackParameters(builder: TrackSelectionParameters.Builder) {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        val prefAudio = prefs.getString("pref_audio_lang", null)
        
        val autoEnableSubtitles = prefs.getBoolean("pref_auto_enable_subtitles", true)
        val defaultSubLang = prefs.getString("pref_default_sub_lang", "en")
        
        if (prefAudio != null) {
            builder.setPreferredAudioLanguage(prefAudio)
        }
        
        if (autoEnableSubtitles) {
            builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            val prefText = prefs.getString("pref_text_lang", defaultSubLang)
            if (prefText != null && prefText != "OFF") {
                builder.setPreferredTextLanguage(prefText)
            }
        } else {
            val prefText = prefs.getString("pref_text_lang", null)
            if (prefText != null && prefText != "OFF") {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                builder.setPreferredTextLanguage(prefText)
            } else {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            }
        }
    }

    // Playback Stats / Nerds Mode Telemetry
    private fun toggleStatsForNerds() {
        statsManager.toggleStats(this)
        val isEnabled = statsManager.isStatsEnabled()
        val controlView = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
        val statsLabel = controlView?.findViewById<TextView>(R.id.tv_stats_label)
        statsLabel?.text = "Stats: ${if (isEnabled) "On" else "Off"}"
    }

    private fun stringForTime(timeMs: Long): String {
        val totalSeconds = (timeMs + 500) / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        val formatBuilder = StringBuilder()
        val formatter = Formatter(formatBuilder, Locale.getDefault())
        formatBuilder.setLength(0)
        return if (hours > 0) {
            formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString()
        } else {
            formatter.format("%02d:%02d", minutes, seconds).toString()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        lifecycleScope.launch {
            saveWatchProgress()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        statsManager.stopStatsPoller()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.getDatabase(applicationContext).watchHistoryDao().trimExcessHistory()
            } catch (_: Exception) {}
        }
        player?.release()
        player = null
    }

    private fun formatMs(ms: Long): String {
        if (ms < 0) return "00:00"
        val totalSec = ms / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hr = totalSec / 3600
        return if (hr > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hr, min, sec)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", min, sec)
        }
    }
}

@UnstableApi
private class LazySubtitleDataSource(
    private val cacheDataSource: androidx.media3.datasource.DataSource,
    private val defaultDataSource: androidx.media3.datasource.DataSource,
    private val getActiveSubUrl: () -> String?,
    private val getSubUrlForDummy: (Uri) -> String?
) : androidx.media3.datasource.DataSource {
    private var activeStream: androidx.media3.datasource.DataSource? = null
    private var dummyStream: java.io.ByteArrayInputStream? = null
    private var openedUri: Uri? = null
    private val transferListeners = mutableListOf<androidx.media3.datasource.TransferListener>()

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        transferListeners.add(transferListener)
        cacheDataSource.addTransferListener(transferListener)
        defaultDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        // Reset any previously open streams cleanly
        close()
        openedUri = dataSpec.uri
        val realUrl = getSubUrlForDummy(dataSpec.uri)
        if (realUrl != null) {
            val activeUrl = getActiveSubUrl()
            if (activeUrl != null && activeUrl == realUrl) {
                android.util.Log.d("LazySubtitleDS", "Loading active subtitle from network: $realUrl")
                val realDataSpec = dataSpec.buildUpon().setUri(Uri.parse(realUrl)).build()
                activeStream = defaultDataSource
                dummyStream = null
                try {
                    return defaultDataSource.open(realDataSpec)
                } catch (e: Exception) {
                    android.util.Log.w("LazySubtitleDS", "Failed to open active subtitle network stream ($realUrl): ${e.message}. Serving dummy empty subtitle.")
                }
            }
            android.util.Log.d("LazySubtitleDS", "Serving dummy metadata for unselected subtitle: $realUrl")
            val mime = dataSpec.uri.getQueryParameter("mime")
            val dummyContent = when {
                mime != null && mime.contains("subrip", ignoreCase = true) -> {
                    "1\n00:00:00,000 --> 00:00:01,000\n \n"
                }
                mime != null && (mime.contains("ssa", ignoreCase = true) || mime.contains("ass", ignoreCase = true)) -> {
                    "[Script Info]\nTitle: Dummy\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,, \n"
                }
                else -> {
                    "WEBVTT\n\n"
                }
            }
            val bytes = dummyContent.toByteArray(Charsets.UTF_8)
            dummyStream = java.io.ByteArrayInputStream(bytes)
            activeStream = null
            return bytes.size.toLong()
        }

        // Route all video streams directly through default upstream to prevent LRU cache thrashing and disk lockup during long playback
        activeStream = defaultDataSource
        dummyStream = null
        return activeStream!!.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = dummyStream
        if (stream != null) {
            return stream.read(buffer, offset, length)
        }
        return activeStream?.read(buffer, offset, length) ?: -1
    }

    override fun getUri(): Uri? {
        return activeStream?.getUri() ?: openedUri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return activeStream?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        try {
            dummyStream?.close()
        } catch (_: Exception) {}
        dummyStream = null
        try {
            activeStream?.close()
        } catch (_: Exception) {}
        activeStream = null
    }
}

