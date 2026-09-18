package com.example.zubflix.cloudstream

import android.content.Context
import android.util.Log
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object CloudStreamDexLoader {

    private const val TAG = "CloudStreamDexLoader"

    data class PluginMainPageCategory(val title: String, val pathOrUrl: String)

    private fun getApiForPlugin(context: Context, plugin: InstalledCloudStreamPlugin): MainAPI? {
        val file = File(plugin.filePath)
        val fileProviders = if (file.exists()) {
            CloudStreamPluginHost.getProvidersForFile(context, file)
        } else emptyList()

        val cleanPluginName = plugin.name.replace("CS:", "").replace("cs:", "").replace("provider", "", ignoreCase = true).replace(" ", "").trim()
        
        val api = fileProviders.find { 
            val cleanApiName = it.name.replace("provider", "", ignoreCase = true).replace(" ", "").trim()
            cleanApiName.equals(cleanPluginName, ignoreCase = true) ||
            it.name.equals(cleanPluginName, ignoreCase = true) || 
            it.name.equals(plugin.name, ignoreCase = true) ||
            cleanApiName.contains(cleanPluginName, ignoreCase = true) ||
            cleanPluginName.contains(cleanApiName, ignoreCase = true)
        } ?: fileProviders.firstOrNull() ?: CloudStreamPluginHost.getProvider(plugin.name) ?: CloudStreamPluginHost.getProvider(cleanPluginName)

        api?.let {
            try {
                it.init()
            } catch (e: Throwable) {
                Log.w(TAG, "Error invoking init() on API ${it.name}: ${e.message}")
            }
        }
        return api
    }

    fun cleanMediaTitle(raw: String): String {
        if (raw.isBlank()) return raw
        var t = raw.replace(Regex("<[^>]+>"), "")
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()

        // Remove leading Download / Watch / Stream / Free Download prefixes
        t = t.replace(Regex("^(?i)(download|watch|stream|free\\s+download|full\\s+movie)\\s+"), "")

        // Remove trailing quality/format/codec/size info while preserving Title + (Year/Season)
        t = t.replace(Regex("(?i)\\s*(\\{[^}]*\\}|\\[[^]]*\\]|\\|.*|WEB-DL|WEBRip|BluRay|HDRip|HDTC|HDTS|CAMRip|ESub|Dual\\s+Audio|Multi\\s+Audio|Hindi\\s+Dubbed|English\\s+Dubbed|480p|720p|1080p|2160p|4K|HEVC|x264|x265|AAC|DDP|5\\.1|10bit|MAX).*$"), "")

        // Clean any trailing dashes, colons, or pipes
        t = t.replace(Regex("[\\s\\-|:]+$"), "").replace(Regex("\\s+"), " ").trim()
        return if (t.isBlank()) raw.trim() else t
    }

    suspend fun invokeReflectiveMainPage(
        context: Context,
        plugin: InstalledCloudStreamPlugin
    ): List<StreamingCategory>? = withContext(Dispatchers.IO) {
        try {
            val api = getApiForPlugin(context, plugin)
            if (api == null) {
                Log.e(TAG, "No MainAPI found for plugin: ${plugin.name} (File: ${plugin.filePath})")
                return@withContext null
            }
            Log.d(TAG, "Fetching main page for ${api.name} (hasMainPage: ${api.hasMainPage}, mainPage count: ${api.mainPage.size}, mainUrl: ${api.mainUrl})")

            CloudStreamDynamicDomainManager.syncRemoteDomains()

            val candidateDomains = getCandidateDomains(api.name).ifEmpty { 
                listOf(api.mainUrl).filter { it.isNotBlank() } 
            }

            // 1. Iterate candidate domains to find working mirror for reflective getMainPage
            for (domain in candidateDomains) {
                val cleanDomain = domain.trimEnd('/')
                if (cleanDomain.isNotBlank() && !api.mainUrl.startsWith(cleanDomain)) {
                    try {
                        api.mainUrl = cleanDomain
                    } catch (_: Throwable) {}
                }

                val categories = mutableListOf<StreamingCategory>()

                if (api.mainPage.isNotEmpty()) {
                    val resolved = coroutineScope {
                        val tasks = api.mainPage.map { mp ->
                            async {
                                try {
                                    var reqData = mp.data
                                    if (reqData.startsWith("http")) {
                                        val uri = android.net.Uri.parse(reqData)
                                        val path = uri.encodedPath ?: ""
                                        val query = if (!uri.encodedQuery.isNullOrBlank()) "?${uri.encodedQuery}" else ""
                                        reqData = "$cleanDomain$path$query"
                                    }

                                    Log.d(TAG, "Calling getMainPage(1, ${mp.name}) on ${api.name} [URL: $reqData]")
                                    val resp = api.getMainPage(1, MainPageRequest(mp.name, reqData, mp.horizontalImages))
                                    if (resp != null && resp.items.isNotEmpty()) {
                                        val items = mutableListOf<StreamingItem>()
                                        for (homeList in resp.items) {
                                            val mapped = homeList.list.mapNotNull { searchResp -> mapSearchResponseToItem(searchResp, plugin) }
                                            items.addAll(mapped)
                                        }
                                        if (items.isNotEmpty()) {
                                            val catTitle = mp.name.ifBlank { "Featured" }
                                            StreamingCategory(
                                                id = "cs_${mp.name}_${plugin.id}",
                                                title = catTitle,
                                                items = items
                                            )
                                        } else null
                                    } else null
                                } catch (e: Throwable) {
                                    Log.w(TAG, "Error fetching main page section '${mp.name}' on ${api.name}: ${e.message}")
                                    null
                                }
                            }
                        }
                        tasks.awaitAll().filterNotNull()
                    }
                    if (resolved.isNotEmpty()) {
                        categories.addAll(resolved)
                    }
                }

                // Generic home requests fallback for this domain if mainPage was empty
                if (categories.isEmpty()) {
                    val candidateRequests = listOf(
                        MainPageRequest("Home", cleanDomain, false),
                        MainPageRequest("Latest Movies", "$cleanDomain/movies/", false),
                        MainPageRequest("Latest Web Series", "$cleanDomain/web-series/", false),
                        MainPageRequest(api.name, cleanDomain, false)
                    )
                    for (req in candidateRequests) {
                        try {
                            val resp = api.getMainPage(1, req) ?: continue
                            for (homeList in resp.items) {
                                val items = homeList.list.mapNotNull { mapSearchResponseToItem(it, plugin) }
                                if (items.isNotEmpty()) {
                                    val catTitle = homeList.name.ifBlank { req.name.ifBlank { "Featured" } }
                                    categories.add(
                                        StreamingCategory(
                                            id = "cs_${catTitle}_${plugin.id}",
                                            title = catTitle,
                                            items = items
                                        )
                                    )
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                if (categories.isNotEmpty()) {
                    Log.d(TAG, "Successfully populated ${categories.size} categories reflectively for ${api.name} on $cleanDomain")
                    return@withContext categories
                }
            }

            Log.w(TAG, "No main page categories populated reflectively for ${api.name}")
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Error in invokeReflectiveMainPage for ${plugin.name}: ${e.message}", e)
            null
        }
    }

    suspend fun invokeReflectiveCategoryContent(
        context: Context,
        plugin: InstalledCloudStreamPlugin,
        categoryId: String,
        page: Int
    ): List<StreamingItem>? = withContext(Dispatchers.IO) {
        try {
            val api = getApiForPlugin(context, plugin) ?: return@withContext null
            val matchedMp = api.mainPage.find { categoryId.contains(it.name, ignoreCase = true) }
            if (matchedMp != null) {
                val resp = api.getMainPage(page, MainPageRequest(matchedMp.name, matchedMp.data, false))
                val items = resp?.items?.flatMap { it.list }?.mapNotNull { mapSearchResponseToItem(it, plugin) }
                if (!items.isNullOrEmpty()) return@withContext items
            }

            if (categoryId.contains("disc_")) {
                val query = categoryId.substringAfter("disc_").substringBefore("_")
                val searchResults = CloudStreamPluginHost.search(api, query)
                val items = searchResults.mapNotNull { mapSearchResponseToItem(it, plugin) }
                if (items.isNotEmpty()) return@withContext items
            }

            null
        } catch (e: Throwable) {
            Log.e(TAG, "Error in invokeReflectiveCategoryContent for ${plugin.name}: ${e.message}")
            null
        }
    }

    suspend fun invokeReflectiveSearch(
        context: Context,
        plugin: InstalledCloudStreamPlugin,
        query: String
    ): List<StreamingItem>? = withContext(Dispatchers.IO) {
        try {
            val api = getApiForPlugin(context, plugin) ?: return@withContext null
            val searchResults = CloudStreamPluginHost.search(api, query)
            val items = searchResults.mapNotNull { mapSearchResponseToItem(it, plugin) }
            if (items.isNotEmpty()) items else null
        } catch (e: Exception) {
            Log.e(TAG, "Error in invokeReflectiveSearch for ${plugin.name}: ${e.message}", e)
            null
        }
    }

    suspend fun invokeReflectiveLoad(
        context: Context,
        plugin: InstalledCloudStreamPlugin,
        itemUrl: String
    ): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            val api = getApiForPlugin(context, plugin) ?: return@withContext null
            val loadResp = CloudStreamPluginHost.loadDetails(api, itemUrl) ?: return@withContext null

            val title = loadResp.name
            val posterUrl = loadResp.posterUrl ?: ""
            val plot = loadResp.plot
            val ratingStr = loadResp.score?.toString() ?: ""
            val yearStr = loadResp.year?.toString() ?: ""

            when (loadResp) {
                is TvSeriesLoadResponse -> {
                    val seasonMap = mutableMapOf<Int, MutableList<StreamingEpisode>>()
                    for (ep in loadResp.episodes) {
                        val sNum = ep.season ?: 1
                        val epNum = ep.episode ?: 1
                        val epName = ep.name ?: "Episode $epNum"
                        val epData = ep.data.ifBlank { itemUrl }

                        val streamPayload = JSONObject().apply {
                            put("data", epData)
                            put("url", itemUrl)
                            put("title", title)
                            put("isSeries", true)
                            put("season", sNum)
                            put("episode", epNum)
                            put("pluginId", plugin.id)
                        }.toString()

                        val epModel = StreamingEpisode(
                            title = "S${sNum}E${epNum} - $epName",
                            streamUrl = streamPayload,
                            stillUrl = ep.posterUrl ?: posterUrl
                        )
                        seasonMap.getOrPut(sNum) { mutableListOf() }.add(epModel)
                    }

                    val seasonsList = seasonMap.entries.map { (sNum, eps) ->
                        StreamingSeason(
                            title = "Season $sNum",
                            seasonNumber = sNum,
                            episodes = eps
                        )
                    }.sortedBy { it.seasonNumber }

                    val mainStreamPayload = JSONObject().apply {
                        put("data", itemUrl)
                        put("url", itemUrl)
                        put("title", title)
                        put("isSeries", true)
                        put("pluginId", plugin.id)
                    }.toString()

                    StreamingItem(
                        id = itemUrl,
                        title = title,
                        isSeries = true,
                        imageUrl = posterUrl,
                        backdropUrl = posterUrl,
                        description = plot,
                        rating = ratingStr.takeIf { it.isNotBlank() },
                        year = yearStr.takeIf { it.isNotBlank() },
                        streamUrl = mainStreamPayload,
                        seasons = seasonsList.takeIf { it.isNotEmpty() },
                        sourceName = "CS: ${plugin.name}"
                    )
                }

                is AnimeLoadResponse -> {
                    val seasonMap = mutableMapOf<Int, MutableList<StreamingEpisode>>()
                    val episodesList = loadResp.episodes.values.flatten()
                    for (ep in episodesList) {
                        val sNum = ep.season ?: 1
                        val epNum = ep.episode ?: 1
                        val epName = ep.name ?: "Episode $epNum"
                        val epData = ep.data.ifBlank { itemUrl }

                        val streamPayload = JSONObject().apply {
                            put("data", epData)
                            put("url", itemUrl)
                            put("title", title)
                            put("isSeries", true)
                            put("season", sNum)
                            put("episode", epNum)
                            put("pluginId", plugin.id)
                        }.toString()

                        val epModel = StreamingEpisode(
                            title = "Episode $epNum - $epName",
                            streamUrl = streamPayload,
                            stillUrl = ep.posterUrl ?: posterUrl
                        )
                        seasonMap.getOrPut(sNum) { mutableListOf() }.add(epModel)
                    }

                    val seasonsList = seasonMap.entries.map { (sNum, eps) ->
                        StreamingSeason(
                            title = "Season $sNum",
                            seasonNumber = sNum,
                            episodes = eps
                        )
                    }.sortedBy { it.seasonNumber }

                    val mainStreamPayload = JSONObject().apply {
                        put("data", itemUrl)
                        put("url", itemUrl)
                        put("title", title)
                        put("isSeries", true)
                        put("pluginId", plugin.id)
                    }.toString()

                    StreamingItem(
                        id = itemUrl,
                        title = title,
                        isSeries = true,
                        imageUrl = posterUrl,
                        backdropUrl = posterUrl,
                        description = plot,
                        rating = ratingStr.takeIf { it.isNotBlank() },
                        year = yearStr.takeIf { it.isNotBlank() },
                        streamUrl = mainStreamPayload,
                        seasons = seasonsList.takeIf { it.isNotEmpty() },
                        sourceName = "CS: ${plugin.name}"
                    )
                }

                is MovieLoadResponse -> {
                    val dataUrl = loadResp.dataUrl.ifBlank { itemUrl }
                    val streamPayload = JSONObject().apply {
                        put("data", dataUrl)
                        put("url", itemUrl)
                        put("title", title)
                        put("isSeries", false)
                        put("pluginId", plugin.id)
                    }.toString()

                    StreamingItem(
                        id = itemUrl,
                        title = title,
                        isSeries = false,
                        imageUrl = posterUrl,
                        backdropUrl = posterUrl,
                        description = plot,
                        rating = ratingStr.takeIf { it.isNotBlank() },
                        year = yearStr.takeIf { it.isNotBlank() },
                        streamUrl = streamPayload,
                        sourceName = "CS: ${plugin.name}"
                    )
                }

                else -> {
                    val streamPayload = JSONObject().apply {
                        put("data", itemUrl)
                        put("url", itemUrl)
                        put("title", title)
                        put("isSeries", false)
                        put("pluginId", plugin.id)
                    }.toString()

                    StreamingItem(
                        id = itemUrl,
                        title = title,
                        isSeries = false,
                        imageUrl = posterUrl,
                        backdropUrl = posterUrl,
                        description = plot,
                        rating = ratingStr.takeIf { it.isNotBlank() },
                        year = yearStr.takeIf { it.isNotBlank() },
                        streamUrl = streamPayload,
                        sourceName = "CS: ${plugin.name}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in invokeReflectiveLoad for ${plugin.name}: ${e.message}", e)
            null
        }
    }

    suspend fun invokeReflectiveLoadLinks(
        context: Context,
        plugin: InstalledCloudStreamPlugin,
        data: String
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val api = getApiForPlugin(context, plugin) ?: return@withContext null
            val links = mutableMapOf<String, String>()

            CloudStreamPluginHost.resolveStreamLinks(
                api = api,
                mediaData = data,
                onLink = { link ->
                    val qName = link.name.ifBlank { "${link.quality}p" }
                    val label = "[${api.name}] $qName"
                    synchronized(links) {
                        links[label] = link.url
                    }
                },
                onSub = { _ -> }
            )

            if (links.isNotEmpty()) links else null
        } catch (e: Exception) {
            Log.e(TAG, "Error in invokeReflectiveLoadLinks for ${plugin.name}: ${e.message}", e)
            null
        }
    }

    suspend fun searchAndFetchStreams(
        context: Context,
        plugin: InstalledCloudStreamPlugin,
        queryTitle: String,
        isSeries: Boolean = false,
        season: Int? = null,
        episode: Int? = null,
        onStreamFound: ((StreamResult) -> Unit)? = null
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StreamResult>()
        try {
            val api = getApiForPlugin(context, plugin) ?: return@withContext emptyList()
            Log.d(TAG, "Executing native CloudStream search on ${api.name} for '$queryTitle'")
            val searchResults = CloudStreamPluginHost.search(api, queryTitle)
            if (searchResults.isEmpty()) {
                Log.d(TAG, "No search results on ${api.name} for '$queryTitle'")
                return@withContext emptyList()
            }

            // Find best matching item
            val matchedItem = searchResults.firstOrNull {
                it.name.contains(queryTitle, ignoreCase = true) || queryTitle.contains(it.name, ignoreCase = true)
            } ?: searchResults.first()

            val details = CloudStreamPluginHost.loadDetails(api, matchedItem.url) ?: return@withContext emptyList()

            var loadData = matchedItem.url
            if (details is TvSeriesLoadResponse) {
                val targetSeason = season ?: 1
                val targetEpisode = episode ?: 1
                val matchedEp = details.episodes.firstOrNull {
                    (it.season == targetSeason || it.season == null) && (it.episode == targetEpisode || (episode == null && it.episode == null))
                } ?: if (episode == null) details.episodes.firstOrNull() else null
                if (matchedEp == null) return@withContext emptyList()
                loadData = matchedEp.data.ifBlank { matchedItem.url }
            } else if (details is AnimeLoadResponse) {
                val targetSeason = season ?: 1
                val targetEpisode = episode ?: 1
                val allEps = details.episodes.values.flatten()
                val matchedEp = allEps.firstOrNull {
                    (it.season == targetSeason || it.season == null) && (it.episode == targetEpisode || (episode == null && it.episode == null))
                } ?: if (episode == null) allEps.firstOrNull() else null
                if (matchedEp == null) return@withContext emptyList()
                loadData = matchedEp.data.ifBlank { matchedItem.url }
            } else if (details is MovieLoadResponse) {
                loadData = details.dataUrl.ifBlank { matchedItem.url }
            }

            CloudStreamPluginHost.resolveStreamLinks(
                api = api,
                mediaData = loadData,
                onLink = { link ->
                    val qName = link.name.ifBlank { "${link.quality}p" }
                    val streamResult = StreamResult(
                        source = api.name,
                        title = "${api.name} - $qName",
                        url = link.url,
                        qualityScore = link.quality,
                        mediaTitle = details.name,
                        isHls = link.url.contains(".m3u8", ignoreCase = true)
                    )
                    synchronized(results) {
                        results.add(streamResult)
                    }
                    onStreamFound?.invoke(streamResult)
                },
                onSub = { _ -> }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in searchAndFetchStreams on ${plugin.name}: ${e.message}", e)
        }
        results
    }

    fun getPluginMainPageInfo(context: Context, plugin: InstalledCloudStreamPlugin): Pair<String, List<PluginMainPageCategory>> {
        val api = getApiForPlugin(context, plugin) ?: return Pair("", emptyList())
        val categories = api.mainPage.map { PluginMainPageCategory(it.name, it.data) }
        return Pair(api.mainUrl, categories)
    }

    fun getCandidateDomains(pluginName: String): List<String> {
        return CloudStreamDynamicDomainManager.getCandidateDomains(pluginName)
    }

    private fun mapSearchResponseToItem(resp: SearchResponse, plugin: InstalledCloudStreamPlugin): StreamingItem? {
        val itemUrl = resp.url
        val rawTitle = resp.name
        if (itemUrl.isBlank() || rawTitle.isBlank()) return null
        val cleanTitle = cleanMediaTitle(rawTitle)

        val isTv = when (resp.type) {
            TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.AnimeMovie, TvType.Cartoon -> true
            else -> false
        }

        val streamPayload = JSONObject().apply {
            put("url", itemUrl)
            put("title", cleanTitle)
            put("rawTitle", rawTitle)
            put("isSeries", isTv)
            put("pluginId", plugin.id)
        }.toString()

        return StreamingItem(
            id = itemUrl,
            title = cleanTitle,
            isSeries = isTv,
            imageUrl = resp.posterUrl ?: "",
            backdropUrl = resp.posterUrl ?: "",
            streamUrl = streamPayload,
            sourceName = "CS: ${plugin.name}"
        )
    }
}
