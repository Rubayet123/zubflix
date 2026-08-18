package com.example.zubflix.bdix

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object BDIXUtils {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()

    fun extractYear(text: String): Int? = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(text)?.value?.toInt()

    fun extractQuality(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("2160p") || t.contains("4k") -> "4K"
            t.contains("1080p") -> "1080p"
            t.contains("720p") -> "720p"
            else -> "SD"
        }
    }

    fun scoreQuality(text: String): Int {
        val t = text.lowercase()
        return when {
            t.contains("2160p") || t.contains("4k") -> 40
            t.contains("1080p") -> 30
            t.contains("720p") -> 20
            else -> 10
        }
    }

    fun titlesMatch(a: String, b: String): Boolean {
        val cleanA = cleanTitle(a)
        val cleanB = cleanTitle(b)
        if (cleanA.isBlank() || cleanB.isBlank()) return false

        val n1 = cleanA.replace(Regex("[^a-z0-9]"), "")
        val n2 = cleanB.replace(Regex("[^a-z0-9]"), "")
        if (n1 == n2) return true

        val minLen = minOf(n1.length, n2.length)
        if (minLen >= 4 && (n1.contains(n2) || n2.contains(n1))) {
            return true
        }
        return false
    }

    private fun cleanTitle(text: String): String {
        return text.lowercase()
            .replace(Regex("\\b(19\\d{2}|20\\d{2})\\b"), "")
            .replace(Regex("\\b(4k|2160p|1080p|720p|480p|360p|hdrip|webrip|web-dl|bluray|x264|x265|hevc|esub|hdtv)\\b"), "")
            .trim()
    }

    fun extractSeasonEpisode(filename: String): Pair<Int, Int>? {
        val match = Regex("S(\\d+)\\D*E(\\d+)", RegexOption.IGNORE_CASE).find(filename)
        return match?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    fun getNameFromPath(href: String): String = href.split("/").lastOrNull()?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
}
