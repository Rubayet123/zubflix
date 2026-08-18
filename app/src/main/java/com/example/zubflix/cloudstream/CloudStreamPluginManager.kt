package com.example.zubflix.cloudstream

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object CloudStreamPluginManager {

    private const val TAG = "CloudStreamPluginManager"
    private const val PREFS_NAME = "cloudstream_plugins_prefs"
    private const val KEY_INSTALLED_PLUGINS = "key_installed_plugins"
    private const val KEY_REPOSITORIES = "key_repositories"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Default repositories if empty
    val DEFAULT_REPOS = listOf(
        CloudStreamRepo(
            name = "CNC Repo",
            url = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json",
            description = "CNC Movie and Series provider repository"
        ),
        CloudStreamRepo(
            name = "Phisher Repo",
            url = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json",
            description = "Phisher Hindi, English, and regional Indian extensions"
        ),
        CloudStreamRepo(
            name = "Megix Repo",
            url = "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
            description = "CSX Hindi and English content provider extensions"
        ),
        CloudStreamRepo(
            name = "Aniyomi Compat",
            url = "https://raw.githubusercontent.com/CranberrySoup/AniyomiCompatExtension/master/repo.json",
            description = "Aniyomi extension compatibility provider for CloudStream"
        )
    )

    // Deprecated
    val MEGA_SUB_REPOS = emptyList<CloudStreamRepo>()

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getInstalledPlugins(context: Context): List<InstalledCloudStreamPlugin> {
        val json = getPrefs(context).getString(KEY_INSTALLED_PLUGINS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<InstalledCloudStreamPlugin>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading installed plugins: ${e.message}")
            emptyList()
        }
    }

    fun saveInstalledPlugins(context: Context, list: List<InstalledCloudStreamPlugin>) {
        val json = gson.toJson(list)
        getPrefs(context).edit().putString(KEY_INSTALLED_PLUGINS, json).apply()
    }

    fun getRepositories(context: Context): List<CloudStreamRepo> {
        val json = getPrefs(context).getString(KEY_REPOSITORIES, null)
        val repos = if (json.isNullOrEmpty()) {
            saveRepositories(context, DEFAULT_REPOS)
            DEFAULT_REPOS.toMutableList()
        } else {
            try {
                val type = object : TypeToken<List<CloudStreamRepo>>() {}.type
                gson.fromJson<List<CloudStreamRepo>>(json, type)?.toMutableList() ?: DEFAULT_REPOS.toMutableList()
            } catch (e: Exception) {
                DEFAULT_REPOS.toMutableList()
            }
        }

        // Clean up any old injected/default repositories (Hexated, Likely Stream, Italian, CakesTwix, etc.)
        val urlsToRemove = setOf(
            "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json",
            "https://raw.githubusercontent.com/hexated/cloudstream-extensions-hexated/builds/plugins.json",
            "https://raw.githubusercontent.com/recloudstream/cloudstream-extensions/builds/plugins.json",
            "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json",
            "https://raw.githubusercontent.com/gian-fr/italianprovider/builds/repo.json",
            "https://raw.githubusercontent.com/gian-fr/italianproviders/builds/repo.json",
            "https://raw.githubusercontent.com/cakestwix/cloudstream-extensions-uk/master/repo.json",
            "https://codeberg.org/cakestwix/cloudstream-extensions-uk/raw/branch/master/repo.json",
            "https://raw.githubusercontent.com/techtanic/skillshare-repo/builds/repo.json",
            "https://git.disroot.org/ayza/fstream/raw/branch/main/repo.json",
            "https://raw.githubusercontent.com/luna712/luna712-cloudstream-extensions/master/repo.json",
            "https://raw.githubusercontent.com/redowan99/redowan-cloudstream/master/repo.json",
            "https://raw.githubusercontent.com/abodabodd/re-3arabi/refs/heads/main/repo",
            "https://raw.githubusercontent.com/dogior/dogiorshadenough/refs/heads/builds/repo.json",
            "https://raw.githubusercontent.com/diegon7771/italiainstreaming/builds/repo.json",
            "https://gitlab.com/tearrs/cloudstream-vietnamese/-/raw/main/repo.json",
            "https://raw.githubusercontent.com/ycngmn/cuxplug/refs/heads/main/repo.json",
            "https://raw.githubusercontent.com/bnyro/germanproviders/refs/heads/master/repo.json",
            "https://raw.githubusercontent.com/sarapcanagii/pitipitii/master/repo.json",
            "https://raw.githubusercontent.com/tekuma25/indostream/builds/repo.json",
            "https://raw.githubusercontent.com/saimuelbr/saimuelrepo/refs/heads/main/builds/repo.json",
            "https://raw.githubusercontent.com/kraptor123/cs-kraptor/refs/heads/master/repo.json",
            "https://raw.githubusercontent.com/kraptor123/cs-karma/refs/heads/master/repo.json",
            "https://raw.githubusercontent.com/redblacker8/storm-ext/refs/heads/builds/repo.json",
            "https://raw.githubusercontent.com/rockhero1234/cinephile/refs/heads/builds/repo.json",
            "https://raw.githubusercontent.com/med1245/cartoonyrepo/builds/repo.json",
            "https://raw.githubusercontent.com/reflex755/reflexrepo/refs/heads/builds/repo.json"
        )

        var changed = false
        val initialSize = repos.size
        repos.removeAll { repo ->
            urlsToRemove.contains(repo.url.lowercase()) ||
            repo.name.equals("Hexated Repository", ignoreCase = true) ||
            repo.name.equals("Likely Stream Repository", ignoreCase = true) ||
            repo.name.contains("Italian provider", ignoreCase = true) ||
            repo.name.contains("CakesTwix", ignoreCase = true) ||
            repo.name.equals("Mega Repository", ignoreCase = true)
        }
        if (repos.size != initialSize) {
            changed = true
        }

        // Ensure all DEFAULT_REPOS are present
        for (defaultRepo in DEFAULT_REPOS) {
            if (repos.none { it.url.equals(defaultRepo.url, ignoreCase = true) }) {
                repos.add(defaultRepo)
                changed = true
            }
        }

        if (changed) {
            saveRepositories(context, repos)
        }

        return repos
    }

    fun saveRepositories(context: Context, repos: List<CloudStreamRepo>) {
        val json = gson.toJson(repos)
        getPrefs(context).edit().putString(KEY_REPOSITORIES, json).apply()
    }

    fun addRepository(context: Context, repo: CloudStreamRepo) {
        val current = getRepositories(context).toMutableList()
        if (current.none { it.url.equals(repo.url, ignoreCase = true) }) {
            current.add(repo)
            saveRepositories(context, current)
        }
    }

    fun removeRepository(context: Context, repoUrl: String) {
        val current = getRepositories(context).filterNot { it.url.equals(repoUrl, ignoreCase = true) }
        saveRepositories(context, current)
    }

    suspend fun fetchRepoManifest(repoUrl: String): List<CloudStreamPluginManifest> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(repoUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string()?.trim() ?: return@withContext emptyList()

                if (body.startsWith("[")) {
                    val type = object : TypeToken<List<CloudStreamPluginManifest>>() {}.type
                    val manifests: List<CloudStreamPluginManifest> = gson.fromJson(body, type) ?: emptyList()
                    manifests
                } else if (body.startsWith("{")) {
                    val jsonObject = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val pluginLists = jsonObject.getAsJsonArray("pluginLists")
                    if (pluginLists != null && pluginLists.size() > 0) {
                        val deferreds = pluginLists.map { element ->
                            val subUrl = element.asString
                            async {
                                if (!subUrl.isNullOrBlank()) {
                                    try {
                                        withTimeout(5000) {
                                            fetchRepoManifest(subUrl)
                                        }
                                    } catch (e: Exception) {
                                        emptyList()
                                    }
                                } else {
                                    emptyList()
                                }
                            }
                        }
                        deferreds.flatMap { it.await() }
                    } else {
                        try {
                            val manifest = gson.fromJson(body, CloudStreamPluginManifest::class.java)
                            if (manifest != null && !manifest.name.isNullOrBlank()) {
                                listOf(manifest)
                            } else emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching repo manifest from $repoUrl: ${e.message}")
            emptyList()
        }
    }

    suspend fun downloadAndInstallPlugin(
        context: Context,
        manifest: CloudStreamPluginManifest,
        mainClassName: String = "com.hexated.MainAPI"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val pluginDir = File(context.filesDir, "cs_plugins")
            if (!pluginDir.exists()) pluginDir.mkdirs()

            val fileName = "cs_${System.currentTimeMillis()}_${manifest.name.replace("\\s+".toRegex(), "_")}.cs3"
            val targetFile = File(pluginDir, fileName)

            val request = Request.Builder()
                .url(manifest.pluginUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val inputStream = response.body?.byteStream() ?: return@withContext false

                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (!targetFile.exists() || targetFile.length() == 0L) {
                return@withContext false
            }

            val internalManifest = CloudStreamPluginHost.readManifest(targetFile)
            val resolvedClass = internalManifest?.pluginClassName ?: mainClassName
            val pluginDisplayName = internalManifest?.name ?: manifest.name

            // Save plugin record
            val newPlugin = InstalledCloudStreamPlugin(
                id = manifest.pluginUrl.hashCode().toString(),
                name = pluginDisplayName,
                filePath = targetFile.absolutePath,
                mainClass = resolvedClass,
                version = manifest.version,
                author = manifest.authors?.firstOrNull() ?: "Community",
                description = manifest.description,
                isEnabled = true,
                repoUrl = manifest.pluginUrl
            )

            val currentList = getInstalledPlugins(context).toMutableList()
            currentList.removeAll { it.id == newPlugin.id || it.name.equals(newPlugin.name, ignoreCase = true) }
            currentList.add(newPlugin)
            saveInstalledPlugins(context, currentList)

            // Instantly update the repositories list based on new plugin installation
            getRepositories(context)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading plugin ${manifest.name}: ${e.message}")
            false
        }
    }

    fun togglePluginEnabled(context: Context, pluginId: String) {
        val list = getInstalledPlugins(context).map {
            if (it.id == pluginId) it.copy(isEnabled = !it.isEnabled) else it
        }
        saveInstalledPlugins(context, list)
        getRepositories(context)
    }

    fun deletePlugin(context: Context, pluginId: String) {
        val list = getInstalledPlugins(context).toMutableList()
        val target = list.find { it.id == pluginId }
        if (target != null) {
            try {
                val file = File(target.filePath)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete plugin file: ${e.message}")
            }
            list.remove(target)
            saveInstalledPlugins(context, list)
        }
    }

    /**
     * Checks all registered repositories for plugin updates.
     * Compares latest repo manifest versions against installed plugin versions.
     */
    suspend fun checkForPluginUpdates(context: Context): List<PluginUpdateInfo> = withContext(Dispatchers.IO) {
        val installed = getInstalledPlugins(context)
        if (installed.isEmpty()) return@withContext emptyList()

        val repos = getRepositories(context)
        val allManifests = mutableListOf<CloudStreamPluginManifest>()

        // Fetch manifests concurrently from all repositories
        val deferreds = repos.map { repo ->
            async {
                try {
                    fetchRepoManifest(repo.url)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        for (deferred in deferreds) {
            allManifests.addAll(deferred.await())
        }

        val updateList = mutableListOf<PluginUpdateInfo>()

        for (plugin in installed) {
            // Find matching manifest in repo by URL or Name
            val match = allManifests.firstOrNull { manifest ->
                (!plugin.repoUrl.isNullOrBlank() && plugin.repoUrl.equals(manifest.pluginUrl, ignoreCase = true)) ||
                plugin.name.equals(manifest.name, ignoreCase = true)
            }

            if (match != null) {
                val currentVer = plugin.version
                val repoVer = match.version
                if (repoVer > currentVer) {
                    updateList.add(
                        PluginUpdateInfo(
                            pluginId = plugin.id,
                            pluginName = plugin.name,
                            currentVersion = currentVer,
                            newVersion = repoVer,
                            manifest = match
                        )
                    )
                }
            }
        }

        updateList
    }

    /**
     * Downloads and applies an update for a single plugin.
     */
    suspend fun updatePlugin(context: Context, updateInfo: PluginUpdateInfo): Boolean {
        return downloadAndInstallPlugin(
            context = context,
            manifest = updateInfo.manifest
        )
    }

    /**
     * Updates all plugins in the provided update list.
     */
    suspend fun updateAllPlugins(context: Context, updateList: List<PluginUpdateInfo>): Int {
        var count = 0
        for (info in updateList) {
            if (updatePlugin(context, info)) {
                count++
            }
        }
        return count
    }
}

data class PluginUpdateInfo(
    val pluginId: String,
    val pluginName: String,
    val currentVersion: Int,
    val newVersion: Int,
    val manifest: CloudStreamPluginManifest
)
