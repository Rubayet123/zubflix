package com.example.zubflix.cloudstream

import android.content.Context
import android.util.Log
import com.example.zubflix.bdix.BDIXScraper.StreamResult

object CloudStreamManager {

    private const val TAG = "CloudStreamManager"
    const val MEGA_REPO_URL = "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json"

    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        Log.d(TAG, "Initializing CloudStream Engine...")
        
        // Ensure repositories (including MegaRepo) are loaded in preferences
        CloudStreamPluginManager.getRepositories(context)
        
        // Ensure plugin directory exists
        val pluginDir = java.io.File(context.filesDir, "cs_plugins")
        if (!pluginDir.exists()) {
            pluginDir.mkdirs()
        }

        isInitialized = true
        Log.d(TAG, "CloudStream Engine initialized successfully.")
    }

    fun getInstalledScrapers(context: Context): List<InstalledCloudStreamPlugin> {
        return CloudStreamPluginManager.getInstalledPlugins(context)
    }

    fun getActiveScrapers(context: Context): List<InstalledCloudStreamPlugin> {
        return CloudStreamPluginManager.getInstalledPlugins(context).filter { it.isEnabled }
    }

    suspend fun fetchMegaRepoScrapers(): List<CloudStreamPluginManifest> {
        return CloudStreamPluginManager.fetchRepoManifest(MEGA_REPO_URL)
    }

    suspend fun installScraper(context: Context, manifest: CloudStreamPluginManifest): Boolean {
        return CloudStreamPluginManager.downloadAndInstallPlugin(context, manifest)
    }

    fun toggleScraper(context: Context, pluginId: String) {
        CloudStreamPluginManager.togglePluginEnabled(context, pluginId)
    }

    fun uninstallScraper(context: Context, pluginId: String) {
        CloudStreamPluginManager.deletePlugin(context, pluginId)
    }

    suspend fun searchStreams(
        context: Context,
        title: String,
        isSeries: Boolean = false,
        season: Int? = null,
        episode: Int? = null
    ): List<StreamResult> {
        val activePlugins = getActiveScrapers(context)
        if (activePlugins.isEmpty()) return emptyList()

        val results = mutableListOf<StreamResult>()
        for (plugin in activePlugins) {
            val pluginStreams = CloudStreamDexLoader.searchAndFetchStreams(
                context = context,
                plugin = plugin,
                queryTitle = title,
                isSeries = isSeries,
                season = season,
                episode = episode
            )
            results.addAll(pluginStreams)
        }
        return results
    }
}
