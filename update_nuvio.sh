sed -i '/override suspend fun extractVideoLinks(data: String): Map<String, String>/i \
    suspend fun extractVideoLinksStreaming(data: String, onProgress: suspend (done: Int, total: Int) -> Unit, onStreamFound: suspend (streams: Map<String, String>) -> Unit) = withContext(Dispatchers.IO) {\
        try {\
            val parts = data.removePrefix("nuvio_resolve/").split(":")\
            if (parts.isEmpty()) return@withContext\
            val type = parts[0]\
            val imdbId = parts.getOrNull(1) ?: return@withContext\
            val addonEndpoints = mutableListOf<Pair<String, String>>()\
            var bdixEnabled = false\
            context?.let { ctx ->\
                try {\
                    val addons = com.example.zubflix.stremio.StremioAddonManager.getInstalledAddons(ctx).filter { it.isEnabled }\
                    val streamAddons = addons.filter { \
                        it.resources.contains("stream") || it.manifestUrl.contains("pengu", ignoreCase = true) || it.manifestUrl.contains("stream", ignoreCase = true) || it.name.contains("stream", ignoreCase = true)\
                    }\
                    for (addon in streamAddons) {\
                        if (addon.manifestUrl == "internal://bdix.scraper/manifest.json") {\
                            bdixEnabled = true\
                            continue\
                        }\
                        val baseUrl = addon.manifestUrl.substringBeforeLast("/manifest.json")\
                        val addonEndpoint = if (type == "movie") {\
                            "$baseUrl/stream/movie/$imdbId.json"\
                        } else {\
                            val season = parts.getOrNull(2)\
                            val episode = parts.getOrNull(3)\
                            if (season != null && episode != null) {\
                                "$baseUrl/stream/series/$imdbId:$season:$episode.json"\
                            } else null\
                        }\
                        if (addonEndpoint != null) addonEndpoints.add(Pair(addonEndpoint, addon.name))\
                    }\
                } catch (e: Exception) {}\
            }\
            if (addonEndpoints.isEmpty() && !bdixEnabled) return@withContext\
            val contentTitle = if (type == "movie") parts.getOrNull(2)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "" else parts.getOrNull(4)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""\
            val contentYear = if (type == "movie") parts.getOrNull(3)?.toIntOrNull() else parts.getOrNull(5)?.toIntOrNull()\
            val totalSources = addonEndpoints.size + (if (bdixEnabled && contentTitle.isNotEmpty()) 1 else 0)\
            var doneSources = 0\
            onProgress(doneSources, totalSources)\
            val uniqueNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()\
            val processStreams = suspend { streams: List<Pair<String, String>> ->\
                if (streams.isNotEmpty()) {\
                    val batch = LinkedHashMap<String, String>()\
                    for ((name, url) in streams) {\
                        var uniqueName = name\
                        var duplicateCount = 1\
                        while (!uniqueNames.add(uniqueName)) {\
                            uniqueName = "$name ($duplicateCount)"\
                            duplicateCount++\
                        }\
                        batch[uniqueName] = url\
                    }\
                    onStreamFound(batch)\
                }\
            }\
            val deferredList = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()\
            if (bdixEnabled && contentTitle.isNotEmpty()) {\
                deferredList.add(async {\
                    val season = if (type == "series") parts.getOrNull(2)?.toIntOrNull() else null\
                    val episode = if (type == "series") parts.getOrNull(3)?.toIntOrNull() else null\
                    val bdixStreams = com.example.zubflix.bdix.BDIXScraper.getStreams(type, contentTitle, contentYear, season, episode)\
                    processStreams(bdixStreams.map { Pair("[BDIX] ${it.source} ${it.title}", it.url) })\
                    synchronized(this@NuvioSource) { doneSources++ }\
                    onProgress(doneSources, totalSources)\
                })\
            }\
            for ((endpoint, name) in addonEndpoints) {\
                deferredList.add(async {\
                    val streams = kotlinx.coroutines.withTimeoutOrNull(10000) { fetchStreamsFromUrl(endpoint, name) } ?: emptyList()\
                    processStreams(streams)\
                    synchronized(this@NuvioSource) { doneSources++ }\
                    onProgress(doneSources, totalSources)\
                })\
            }\
            deferredList.awaitAll()\
        } catch (e: Exception) {\
            Log.e("Nuvio", "Error extracting video links streaming", e)\
        }\
    }\
' app/src/main/java/com/example/zubflix/sources/NuvioSource.kt
bash update_nuvio.sh
