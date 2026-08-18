package com.example.zubflix.cloudstream

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import dalvik.system.PathClassLoader
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object CloudStreamPluginHost {

    private const val TAG = "CSPluginHost"
    private val loadedPlugins = mutableMapOf<String, BasePlugin>()
    private val providerMap = mutableMapOf<String, MainAPI>()
    private val fileToProviders = mutableMapOf<String, List<MainAPI>>()

    data class PluginManifest(
        val pluginClassName: String?,
        val name: String?,
        val requiresResources: Boolean = false
    )

    fun readManifest(file: File): PluginManifest? {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                val jsonStr = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                PluginManifest(
                    pluginClassName = obj.optString("pluginClassName").takeIf { it.isNotBlank() },
                    name = obj.optString("name").takeIf { it.isNotBlank() },
                    requiresResources = obj.optBoolean("requiresResources", false)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read manifest for ${file.name}: ${e.message}")
            null
        }
    }

    @Synchronized
    fun loadPlugin(context: Context, pluginFile: File): List<MainAPI> {
        val appContext = context.applicationContext
        CloudStreamInitializer.init(appContext)

        // Asynchronously sync latest dynamic working domains for providers
        try {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                CloudStreamDynamicDomainManager.syncRemoteDomains()
            }
        } catch (_: Throwable) {}

        val filePath = pluginFile.absolutePath
        if (!pluginFile.exists()) {
            Log.e(TAG, "Plugin file does not exist: $filePath")
            return emptyList()
        }

        if (fileToProviders.containsKey(filePath)) {
            return fileToProviders[filePath] ?: emptyList()
        }

        return try {
            val manifest = readManifest(pluginFile)
            val className = manifest?.pluginClassName
            val loader = PathClassLoader(filePath, appContext.classLoader)

            if (manifest?.requiresResources == true) {
                try {
                    val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                    AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                        .invoke(assets, filePath)
                    val customRes = Resources(
                        assets,
                        appContext.resources.displayMetrics,
                        appContext.resources.configuration
                    )
                    Plugin::class.java.getMethod("setResources", Resources::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not attach custom resources: ${e.message}")
                }
            }

            val beforeProviders = APIHolder.allProviders.toList()

            // 1. Try loading class from manifest
            if (!className.isNullOrBlank()) {
                try {
                    Log.d(TAG, "Loading plugin class: $className from $filePath")
                    val clazz = loader.loadClass(className)
                    val obj = clazz.getDeclaredConstructor().newInstance()
                    if (obj is BasePlugin) {
                        obj.filename = filePath
                        if (obj is Plugin) {
                            obj.load(appContext)
                        } else {
                            obj.load()
                        }
                        loadedPlugins[filePath] = obj
                    } else if (obj is MainAPI) {
                        if (!APIHolder.allProviders.contains(obj)) {
                            APIHolder.allProviders.add(obj)
                        }
                    } else {
                        try {
                            clazz.getMethod("load", Context::class.java).invoke(obj, appContext)
                        } catch (e: NoSuchMethodException) {
                            try {
                                clazz.getMethod("load").invoke(obj)
                            } catch (ignored: Exception) {}
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Class loading with manifest className $className failed: ${e.message}")
                }
            }

            // 2. Scan DEX entries if no providers were registered yet or if manifest had missing class
            val midProviders = APIHolder.allProviders.toList()
            if (midProviders.size == beforeProviders.size) {
                try {
                    @Suppress("DEPRECATION")
                    val dexFile = dalvik.system.DexFile(filePath)
                    val entries = dexFile.entries()
                    while (entries.hasMoreElements()) {
                        val entryName = entries.nextElement()
                        if (entryName.startsWith("java.") || entryName.startsWith("android.") || entryName.startsWith("kotlin.") || entryName.startsWith("androidx.")) continue
                        try {
                            val c = loader.loadClass(entryName)
                            if (java.lang.reflect.Modifier.isAbstract(c.modifiers)) continue

                            if (BasePlugin::class.java.isAssignableFrom(c)) {
                                val pObj = c.getDeclaredConstructor().newInstance() as BasePlugin
                                pObj.filename = filePath
                                if (pObj is Plugin) {
                                    pObj.load(appContext)
                                } else {
                                    pObj.load()
                                }
                                loadedPlugins[filePath] = pObj
                            } else if (MainAPI::class.java.isAssignableFrom(c)) {
                                val apiObj = c.getDeclaredConstructor().newInstance() as MainAPI
                                if (!APIHolder.allProviders.contains(apiObj)) {
                                    APIHolder.allProviders.add(apiObj)
                                }
                            }
                        } catch (ignored: Throwable) {}
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Dex scanning fallback failed: ${e.message}")
                }
            }

            val afterProviders = APIHolder.allProviders.toList()
            val newProviders = afterProviders.filterNot { beforeProviders.contains(it) }
            val registeredForThisFile = if (newProviders.isNotEmpty()) newProviders else afterProviders
            fileToProviders[filePath] = registeredForThisFile

            for (provider in registeredForThisFile) {
                val pName = provider.name.lowercase().trim()
                providerMap[pName] = provider
                providerMap[provider.mainUrl.lowercase().trim()] = provider
                val cleanName = pName.replace("provider", "").replace("cs:", "").replace(" ", "").trim()
                if (cleanName.isNotBlank()) {
                    providerMap[cleanName] = provider
                }
            }

            val pluginDisplayName = manifest?.name ?: pluginFile.nameWithoutExtension
            Log.d(TAG, "Successfully loaded plugin $pluginDisplayName (${registeredForThisFile.size} providers registered). Total API providers: ${APIHolder.allProviders.size}")
            registeredForThisFile
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load plugin ${pluginFile.name}: ${e.message}", e)
            emptyList()
        }
    }

    fun getProvidersForFile(context: Context, pluginFile: File): List<MainAPI> {
        val filePath = pluginFile.absolutePath
        fileToProviders[filePath]?.let { return it }
        return loadPlugin(context, pluginFile)
    }

    fun getProvider(nameOrUrl: String): MainAPI? {
        val clean = nameOrUrl.lowercase().replace("cs:", "").trim()
        providerMap[clean]?.let { return it }
        providerMap[nameOrUrl.lowercase()]?.let { return it }
        return APIHolder.allProviders.find { 
            it.name.equals(clean, ignoreCase = true) ||
            it.name.equals(nameOrUrl, ignoreCase = true) ||
            it.mainUrl.equals(nameOrUrl, ignoreCase = true) ||
            it.name.contains(clean, ignoreCase = true) ||
            clean.contains(it.name, ignoreCase = true)
        }
    }

    fun getAllProviders(): List<MainAPI> {
        return APIHolder.allProviders.toList()
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> {
        return try {
            api.search(query) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error in search on ${api.name}: ${e.message}")
            emptyList()
        }
    }

    suspend fun loadDetails(api: MainAPI, url: String): LoadResponse? {
        return try {
            var targetUrl = url
            if (!targetUrl.startsWith("http") && !targetUrl.contains("://")) {
                val cleanDomain = api.mainUrl.trimEnd('/')
                targetUrl = if (targetUrl.startsWith("/")) "$cleanDomain$targetUrl" else "$cleanDomain/$targetUrl"
            }

            val directResp = try {
                api.load(targetUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Direct api.load($targetUrl) failed on ${api.name}: ${e.message}")
                null
            }

            if (directResp != null) return directResp

            // If direct load failed, attempt search fallback with query
            val query = url.substringAfterLast("/").replace("-", " ").replace("_", " ").trim()
            if (query.isNotBlank()) {
                val searchResults = search(api, query)
                val firstMatch = searchResults.firstOrNull()
                if (firstMatch != null && firstMatch.url.isNotBlank()) {
                    var matchUrl = firstMatch.url
                    if (!matchUrl.startsWith("http") && !matchUrl.contains("://")) {
                        val cleanDomain = api.mainUrl.trimEnd('/')
                        matchUrl = if (matchUrl.startsWith("/")) "$cleanDomain$matchUrl" else "$cleanDomain/$matchUrl"
                    }
                    Log.d(TAG, "Resolved loadDetails via search fallback: '$query' -> $matchUrl")
                    return api.load(matchUrl)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadDetails on ${api.name}: ${e.message}")
            null
        }
    }

    suspend fun resolveStreamLinks(
        api: MainAPI,
        mediaData: String,
        onLink: (ExtractorLink) -> Unit,
        onSub: (SubtitleFile) -> Unit
    ) {
        try {
            api.loadLinks(
                data = mediaData,
                isCasting = false,
                subtitleCallback = { sub -> onSub(sub) },
                callback = { link -> onLink(link) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in resolveStreamLinks on ${api.name}: ${e.message}")
        }
    }
}
