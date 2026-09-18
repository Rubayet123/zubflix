package com.example.zubflix.stremio

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object StremioAddonManager {
    private const val PREFS_NAME = "stremio_addons_prefs"
    private const val KEY_ADDONS = "installed_addons"

    private val gson = Gson()

    fun getInstalledAddons(context: Context): List<StremioAddon> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentString = prefs.getString(KEY_ADDONS, "[]")
        val currentAddons = try {
            val type = object : TypeToken<List<StremioAddon>>() {}.type
            gson.fromJson<List<StremioAddon>>(currentString, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }

        val oldPenguUrl = "https://pengu.uk/%7B%22source_moviebox%22%3A%22on%22%2C%22res_1080%22%3A%22on%22%2C%22disable_direct%22%3A%22on%22%7D/manifest.json"
        val nuvioUrl = "https://nuviostreams.hayd.uk/manifest.json"
        val defaultUrl = "https://opensubtitles-v3.strem.io/manifest.json"
        val newPenguUrl = "https://pengu.uk/%7B%22source_moviebox%22%3A%22on%22%2C%22res_2160%22%3A%22on%22%2C%22res_1080%22%3A%22on%22%2C%22res_720%22%3A%22on%22%2C%22disable_direct%22%3A%22on%22%7D/manifest.json"
        val frostStreamUrl = "https://froststream.cloutteam.com/manifest.json"
        val oldPenguUrl2 = "https://pengu.uk/%7B%22source_111477%22%3A%22on%22%2C%22source_4khdhub%22%3A%22on%22%2C%22source_moviebox%22%3A%22on%22%2C%22source_moviesdrives%22%3A%22on%22%2C%22source_vaplayer%22%3A%22on%22%2C%22source_hdghartv%22%3A%22on%22%2C%22res_1080%22%3A%22on%22%2C%22res_720%22%3A%22on%22%2C%22disable_direct%22%3A%22on%22%7D/manifest.json"
        val penguAuthUrl = "https://pengu.uk/%7B%22auth_token%22%3A%22_NeW9Jl1TGqCG4hDWaUws-qqYf0_cVqJpEf0cbXiH_M%22%7D/manifest.json"
        val bdixUrl = "internal://bdix.scraper/manifest.json"

        var modified = false
        val list = currentAddons.toMutableList()

        // 1. Remove defunct defaults & internal pseudo-addons (Nuvio Scraper Engine & FrostStream)
        val sizeBefore = list.size
        list.removeAll { 
            it.manifestUrl == oldPenguUrl || 
            it.manifestUrl == nuvioUrl || 
            it.manifestUrl == oldPenguUrl2 ||
            it.manifestUrl == newPenguUrl ||
            it.manifestUrl == frostStreamUrl ||
            it.manifestUrl == bdixUrl ||
            it.manifestUrl.contains("127.0.0.1") ||
            it.manifestUrl.contains("localhost:8080") ||
            it.manifestUrl.startsWith("internal://") ||
            it.manifestUrl.contains("froststream", ignoreCase = true) ||
            it.manifestUrl.contains("7a82163c306e", ignoreCase = true) ||
            it.manifestUrl.contains("stremio-netflix-catalog-addon", ignoreCase = true) ||
            it.name.contains("froststream", ignoreCase = true) ||
            it.name.contains("Nuvio Scrapers Engine", ignoreCase = true)
        }
        if (list.size != sizeBefore) {
            modified = true
        }

        // 2. Add and ensure default initial addons are always present and enabled
        val bharatUrl = "https://bharat-binge.semi-column.workers.dev/lang%3Ahi-recent-movie%2Clang%3Ahi-recent-series/manifest.json"
        
        fun ensureAndEnable(url: String, name: String, desc: String, res: List<String>, defaultEnabled: Boolean = true) {
            val index = list.indexOfFirst { it.manifestUrl == url }
            if (index == -1) {
                list.add(StremioAddon(url, name, desc, "1.0.0", res, defaultEnabled))
                modified = true
            } else {
                // Keep user's preference for isEnabled (do NOT force true)
                if (list[index].resources.isEmpty() || (url == bharatUrl && !list[index].resources.contains("catalog"))) {
                    val currentEnabledState = list[index].isEnabled
                    val updatedAddon = list[index].copy(resources = res)
                    updatedAddon.isEnabled = currentEnabledState
                    list[index] = updatedAddon
                    modified = true
                }
            }
        }

        val cncVerseBridgeUrl = "http://127.0.0.1:8080/manifest.json"
        val cncVerseHostedUrl = "https://cncverse-bridge.hayd.uk/manifest.json"

        val hdhubUrl = "https://hdhub.thevolecitor.qzz.io/eyJ0b3Jib3giOiJ1bnNldCIsInF1YWxpdGllcyI6IjEwODBwIiwic29ydCI6ImFzYyIsImNhdGFsb2dzIjoiIn0/manifest.json"

        ensureAndEnable(defaultUrl, "OpenSubtitles v3", "OpenSubtitles v3 API for Nuvio", listOf("subtitles"), defaultEnabled = true)
        ensureAndEnable(penguAuthUrl, "Penguplay", "Penguplay Stremio addon for streaming sources", listOf("stream"), defaultEnabled = true)
        ensureAndEnable(hdhubUrl, "HDHub", "HDHub Stremio addon for 1080p stream sources", listOf("stream"), defaultEnabled = true)
        ensureAndEnable(bharatUrl, "Bharat Binge", "Discover Indian content - Hindi New Releases & Regional catalogs", listOf("catalog", "meta"), defaultEnabled = true)
        ensureAndEnable(cncVerseHostedUrl, "CNCVerse Bridge", "CNCVerse CloudStream Bridge addon for scraped streams", listOf("stream", "catalog"), defaultEnabled = false)
        
        if (!prefs.getBoolean("INITIALIZED_DEFAULTS_V5", false)) {
            val cncIdx = list.indexOfFirst { it.manifestUrl == cncVerseHostedUrl || it.name.contains("CNCVerse", ignoreCase = true) }
            if (cncIdx != -1 && list[cncIdx].isEnabled) {
                list[cncIdx].isEnabled = false
                modified = true
            }
            prefs.edit().putBoolean("INITIALIZED_DEFAULTS_V5", true).apply()
        }

        if (modified) {
            saveAddons(context, list)
            return list
        }

        return currentAddons
    }

    fun toggleAddonEnabled(context: Context, manifestUrl: String) {
        val currentAddons = getInstalledAddons(context).toMutableList()
        val index = currentAddons.indexOfFirst { it.manifestUrl == manifestUrl }
        if (index != -1) {
            currentAddons[index].isEnabled = !currentAddons[index].isEnabled
            saveAddons(context, currentAddons)
        }
    }

    fun moveAddon(context: Context, manifestUrl: String, up: Boolean) {
        val currentAddons = getInstalledAddons(context).toMutableList()
        val index = currentAddons.indexOfFirst { it.manifestUrl == manifestUrl }
        if (index != -1) {
            val newIndex = if (up) index - 1 else index + 1
            if (newIndex in 0 until currentAddons.size) {
                val item = currentAddons.removeAt(index)
                currentAddons.add(newIndex, item)
                saveAddons(context, currentAddons)
            }
        }
    }

    fun addAddon(context: Context, addon: StremioAddon) {
        val currentAddons = getInstalledAddons(context).toMutableList()
        if (currentAddons.none { it.manifestUrl == addon.manifestUrl }) {
            currentAddons.add(addon)
            saveAddons(context, currentAddons)
        }
    }

    fun updateAddon(context: Context, oldManifestUrl: String, updatedAddon: StremioAddon) {
        val currentAddons = getInstalledAddons(context).toMutableList()
        val index = currentAddons.indexOfFirst { it.manifestUrl == oldManifestUrl }
        if (index != -1) {
            currentAddons[index] = updatedAddon
            saveAddons(context, currentAddons)
        } else {
            addAddon(context, updatedAddon)
        }
    }

    fun removeAddon(context: Context, manifestUrl: String) {
        val currentAddons = getInstalledAddons(context).toMutableList()
        val filtered = currentAddons.filter { it.manifestUrl != manifestUrl }
        saveAddons(context, filtered)
    }

    private fun saveAddons(context: Context, addons: List<StremioAddon>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val addonsString = gson.toJson(addons)
        prefs.edit().putString(KEY_ADDONS, addonsString).apply()
    }
}
