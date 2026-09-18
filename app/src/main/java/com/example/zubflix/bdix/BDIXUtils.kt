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

    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "in", "of", "to", "and", "or", "for", "on", "at", "by", "with", "from",
        "this", "that", "these", "those", "it", "its", "us", "we", "you", "he", "she", "they", "me", "him", "her"
    )

    fun titlesMatch(a: String, b: String): Boolean {
        return titleMatchScore(a, b) >= 0.70
    }

    /**
     * Calculates a similarity score between 0.0 and 1.0 for two media titles.
     */
    fun titleMatchScore(a: String, b: String): Double {
        val cleanA = cleanTitle(a)
        val cleanB = cleanTitle(b)
        if (cleanA.isBlank() || cleanB.isBlank()) return 0.0

        val n1 = cleanA.replace(Regex("[^a-z0-9]"), "")
        val n2 = cleanB.replace(Regex("[^a-z0-9]"), "")
        if (n1 == n2 && n1.isNotEmpty()) return 1.0

        // Extract significant words (ignoring common short stop words unless they are the entire title)
        val allWordsA = cleanA.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val allWordsB = cleanB.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

        val sigWordsA = allWordsA.filter { it.length >= 2 && !STOP_WORDS.contains(it) }
        val sigWordsB = allWordsB.filter { it.length >= 2 && !STOP_WORDS.contains(it) }

        // If either has no significant words (e.g. title is "Us" or "It"), fall back to all words
        val effectiveA = if (sigWordsA.isNotEmpty()) sigWordsA else allWordsA
        val effectiveB = if (sigWordsB.isNotEmpty()) sigWordsB else allWordsB

        if (effectiveA.isEmpty() || effectiveB.isEmpty()) return 0.0

        val setA = effectiveA.toSet()
        val setB = effectiveB.toSet()
        val commonWords = setA.intersect(setB)

        // Exact match of significant words set
        if (setA == setB) return 0.95

        // If no common significant words, they do not match
        if (commonWords.isEmpty()) return 0.0

        val minWords = minOf(setA.size, setB.size)
        val maxWords = maxOf(setA.size, setB.size)

        // Jaccard similarity: intersection over union
        val unionSize = (setA + setB).size
        val jaccard = commonWords.size.toDouble() / unionSize.toDouble()

        // Overlap ratio relative to the shorter title
        val overlapRatio = commonWords.size.toDouble() / minWords.toDouble()

        // If the shorter title is only 1 or 2 words, require all of its words to be present in the longer title
        // and enforce minimum Jaccard so "Among Us" doesn't match "This is us" or "Us"
        if (minWords <= 2) {
            if (overlapRatio < 1.0) {
                return 0.0 // Shorter title wasn't fully matched
            }
            // All words of shorter title present; score based on coverage of longer title
            return 0.70 + (0.30 * jaccard)
        }

        // For 3 or more words, allow 1 missing word only if overlap ratio is high (>= 0.75)
        if (overlapRatio >= 0.75 && jaccard >= 0.50) {
            return (overlapRatio * 0.6) + (jaccard * 0.4)
        }

        return jaccard
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
