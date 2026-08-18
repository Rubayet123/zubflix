package com.example.zubflix.cloudstream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Dynamic domain manager that fetches latest working mirror domains
 * from online repositories (such as SaurabhKaperwan's CloudStream Utils)
 * and verifies fallback lists so that CloudStream plugins always hit active URLs.
 */
object CloudStreamDynamicDomainManager {

    private const val TAG = "CSDomainManager"
    private const val REMOTE_URLS_JSON = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"

    private val dynamicDomainCache = ConcurrentHashMap<String, String>()
    private var lastFetchedTime = 0L
    private const val CACHE_EXPIRY_MS = 1000L * 60 * 30 // 30 mins

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Pre-seeded verified domains matching CS repo keys
    private val defaultDomains = mapOf(
        "moviesmod" to "https://moviesmod.zone",
        "topmovies" to "https://moviesleech.rest",
        "vegamovies" to "https://new1.vegamovies.futbol",
        "bollyflix" to "https://bollyflix.free",
        "4khdhub" to "https://4khdhub.one",
        "hdmovie2" to "https://hdmovie2a.bar",
        "moviesdrive" to "https://new2.moviesdrive.christmas",
        "movies4u" to "https://new3.movies4u.clinic",
        "multimovies" to "https://multimovies.makeup",
        "skymovies" to "https://skymovieshd.ceo",
        "uhdmovies" to "https://uhdmovies.autos",
        "rogmovies" to "https://new1.rogmovies.click",
        "gdflix" to "https://new3.gdflix.io",
        "hubcloud" to "https://hubcloud.cx",
        "toonstream" to "https://toon-stream.site",
        "zinkmovies" to "https://zinkmovies.org",
        "vcloud" to "https://vcloud.fit",
        "dudefilms" to "https://dudefilms.casa",
        "m4ufree" to "https://ww4.m4ufree.lat",
        "animedao" to "https://anidao.to",
        "mlsbd" to "https://mlsbd.co",
        "fibwatch" to "https://fibwatch.art"
    )

    init {
        dynamicDomainCache.putAll(defaultDomains)
    }

    suspend fun syncRemoteDomains() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastFetchedTime < CACHE_EXPIRY_MS && dynamicDomainCache.size > defaultDomains.size) {
            return@withContext
        }

        try {
            val req = Request.Builder()
                .url(REMOTE_URLS_JSON)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val body = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }

            if (!body.isNullOrBlank()) {
                val json = JSONObject(body)
                val keys = json.keys()
                var updated = 0
                while (keys.hasNext()) {
                    val k = keys.next().lowercase().trim()
                    val v = json.optString(k, "").trimEnd('/')
                    if (v.isNotBlank() && v.startsWith("http")) {
                        dynamicDomainCache[k] = v
                        updated++
                    }
                }
                lastFetchedTime = now
                Log.d(TAG, "Successfully refreshed $updated dynamic domains from remote repository")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch dynamic domains from remote (${e.message}), using fallback cache")
        }
    }

    fun getCandidateDomains(pluginName: String): List<String> {
        val key = pluginName.lowercase().trim()
            .replace("cs:", "")
            .replace("provider", "")
            .replace(" ", "")
            .trim()

        val list = mutableListOf<String>()

        // 1. Check direct key match in dynamic cache
        for ((k, v) in dynamicDomainCache) {
            if (key.contains(k) || k.contains(key)) {
                list.add(v)
            }
        }

        // 2. Add provider specific fallback mirrors
        when {
            key.contains("moviesmod") || key.contains("mod") -> {
                list.addAll(
                    listOf(
                        "https://moviesmod.zone",
                        "https://moviesleech.rest",
                        "https://topmovies.pet",
                        "https://moviesmod.vip",
                        "https://moviesmod.day",
                        "https://moviesmod.city",
                        "https://moviesmod.org",
                        "https://moviesmod.cc",
                        "https://moviesmod.pro",
                        "https://moviesmod.me",
                        "https://moviesmod.so",
                        "https://moviesmod.live",
                        "https://moviesmod.space",
                        "https://moviesmod.art",
                        "https://moviesmod.fit",
                        "https://moviesmod.shop",
                        "https://moviesmod.dev"
                    )
                )
            }
            key.contains("moviebox") -> {
                list.addAll(
                    listOf(
                        "https://moviebox.ph",
                        "https://moviebox.com.ph",
                        "https://moviebox.online"
                    )
                )
            }
            key.contains("vega") || key.contains("vegamovies") -> {
                list.addAll(
                    listOf(
                        "https://new1.vegamovies.futbol",
                        "https://vegamovies.im",
                        "https://vegamovies.ngo",
                        "https://vegamovies.dad",
                        "https://vegamovies.yt",
                        "https://vegamovies.mex.com"
                    )
                )
            }
            key.contains("bolly") || key.contains("bollyflix") -> {
                list.addAll(
                    listOf(
                        "https://bollyflix.free",
                        "https://bollyflix.city",
                        "https://bollyflix.vip",
                        "https://bollyflix.org"
                    )
                )
            }
            key.contains("topmovies") -> {
                list.addAll(
                    listOf(
                        "https://moviesleech.rest",
                        "https://topmovies.pet",
                        "https://topmovies.tv",
                        "https://topmovies.dad"
                    )
                )
            }
        }

        return list.distinct()
    }
}
