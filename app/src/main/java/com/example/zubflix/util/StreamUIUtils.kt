package com.example.zubflix.util

import android.graphics.Color
import android.content.res.ColorStateList

object StreamUIUtils {

    data class AddonStyle(
        val bgHex: String,
        val textHex: String
    )

    fun getAddonStyle(addonName: String): AddonStyle {
        val trimmed = addonName.trim()
        val lower = trimmed.lowercase()

        return when {
            // 1. Local Scrapers (Emerald / Cyan Teal)
            lower.contains("local scraper") || lower.contains("dhakaflix") || 
            lower.contains("circleftp") || lower.contains("circle ftp") || 
            lower.contains("iccftp") || lower.contains("icc ftp") || 
            lower.contains("ctgmovie") || lower.contains("discoveryftp") -> {
                AddonStyle("#00594C", "#E0F2F1")
            }

            // 2. Penguplay (Electric Violet / Indigo)
            lower.contains("pengu") -> {
                AddonStyle("#3A0CA3", "#E0E7FF")
            }

            // 3. HDHub / HDHub4u (Deep Royal Navy Blue)
            lower.contains("hdhub") -> {
                AddonStyle("#0077B6", "#E0F2FE")
            }

            // 4. Torrentio (Deep Wine / Amethyst)
            lower.contains("torrentio") -> {
                AddonStyle("#4A148C", "#F3E8FF")
            }

            // 5. CyberFlix (Deep Magenta / Plum)
            lower.contains("cyberflix") -> {
                AddonStyle("#701A75", "#FCE7F3")
            }

            // 6. KnightCrawler (Slate Charcoal)
            lower.contains("knight") -> {
                AddonStyle("#1E293B", "#E2E8F0")
            }

            // 7. VegaMovies / Cinefreak / Built-in Scrapers (Crimson Red)
            lower.contains("vegamovie") || lower.contains("cinefreak") || 
            lower.contains("world4u") || lower.contains("hdghartv") -> {
                AddonStyle("#881337", "#FEE2E2")
            }

            // 8. Deterministic Palette Fallback based on name hash
            else -> {
                val palette = listOf(
                    AddonStyle("#1F2937", "#F3F4F6"), // Dark Slate
                    AddonStyle("#312E81", "#E0E7FF"), // Deep Indigo
                    AddonStyle("#064E3B", "#D1FAE5"), // Forest Emerald
                    AddonStyle("#701A75", "#FCE7F3"), // Plum
                    AddonStyle("#1E3A8A", "#DBEAFE"), // Royal Navy
                    AddonStyle("#831843", "#FCE7F3"), // Deep Rose
                    AddonStyle("#3B0764", "#F3E8FF"), // Amethyst
                    AddonStyle("#7C2D12", "#FFEDD5")  // Bronze Amber
                )
                val hash = Math.abs(lower.hashCode())
                palette[hash % palette.size]
            }
        }
    }

    /**
     * Clean up duplicate tags, sizes, and clutter from torrent release title
     */
    fun sanitizeTitle(rawTitle: String, attributes: List<String>): String {
        var clean = rawTitle.trim()

        // Strip initial duplicate scraper prefix if present, e.g. [Local Scraper] or [PenguPlay]
        if (clean.startsWith("[")) {
            val closingIdx = clean.indexOf("]")
            if (closingIdx in 1..25) {
                val prefix = clean.substring(1, closingIdx).lowercase()
                if (prefix.contains("scraper") || prefix.contains("addon") || prefix.contains("pengu") || prefix.contains("hdhub") || prefix.contains("fslv")) {
                    clean = clean.substring(closingIdx + 1).trim()
                }
            }
        }

        // Clean up redundant disk icon / size bracket like [💾 2.32 GB] or [1080p]
        clean = clean.replace(Regex("\\[💾\\s*[^]]+\\]"), "")
        clean = clean.replace(Regex("\\[\\d+(\\.\\d+)?\\s*(GB|MB)\\]", RegexOption.IGNORE_CASE), "")

        // Normalize spacing
        clean = clean.replace(Regex("\\s+"), " ").trim()

        return if (clean.isNotEmpty()) clean else rawTitle
    }
}
