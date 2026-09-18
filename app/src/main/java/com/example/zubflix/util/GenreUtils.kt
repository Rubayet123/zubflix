package com.example.zubflix.util

import com.example.R

object GenreUtils {

    fun getGenreIconRes(idOrTitle: String?): Int {
        if (idOrTitle.isNullOrBlank()) return R.drawable.ic_movie
        val lower = idOrTitle.lowercase()
        return when {
            lower.contains("action") || lower.contains("28") -> R.drawable.ic_movie
            lower.contains("adventure") || lower.contains("12") -> R.drawable.ic_globe
            lower.contains("animation") || lower.contains("16") -> R.drawable.ic_star
            lower.contains("comedy") || lower.contains("35") -> R.drawable.ic_star
            lower.contains("crime") || lower.contains("80") -> R.drawable.ic_search
            lower.contains("documentary") || lower.contains("99") -> R.drawable.ic_stats
            lower.contains("drama") || lower.contains("18") -> R.drawable.ic_movie
            lower.contains("family") || lower.contains("10751") -> R.drawable.ic_heart
            lower.contains("fantasy") || lower.contains("14") -> R.drawable.ic_star
            lower.contains("horror") || lower.contains("27") -> R.drawable.ic_movie
            lower.contains("mystery") || lower.contains("9648") -> R.drawable.ic_search
            lower.contains("romance") || lower.contains("10749") -> R.drawable.ic_heart
            lower.contains("sci") || lower.contains("science") || lower.contains("878") -> R.drawable.ic_globe
            lower.contains("thriller") || lower.contains("53") -> R.drawable.ic_movie
            else -> R.drawable.ic_movie
        }
    }

    fun getGenreGradient(idOrTitle: String?): IntArray {
        if (idOrTitle.isNullOrBlank()) return intArrayOf(0xFF232733.toInt(), 0xFF12141A.toInt())
        val lower = idOrTitle.lowercase()
        return when {
            lower.contains("action") || lower.contains("28") -> intArrayOf(0xFFB71C1C.toInt(), 0xFF3E0000.toInt())
            lower.contains("adventure") || lower.contains("12") -> intArrayOf(0xFFE65100.toInt(), 0xFF4A1A00.toInt())
            lower.contains("animation") || lower.contains("16") -> intArrayOf(0xFF7B1FA2.toInt(), 0xFF2A0042.toInt())
            lower.contains("comedy") || lower.contains("35") -> intArrayOf(0xFFF57F17.toInt(), 0xFF4A2800.toInt())
            lower.contains("crime") || lower.contains("80") -> intArrayOf(0xFF37474F.toInt(), 0xFF10171A.toInt())
            lower.contains("documentary") || lower.contains("99") -> intArrayOf(0xFF00695C.toInt(), 0xFF00221E.toInt())
            lower.contains("drama") || lower.contains("18") -> intArrayOf(0xFF283593.toInt(), 0xFF0C1035.toInt())
            lower.contains("family") || lower.contains("10751") -> intArrayOf(0xFFFB8C00.toInt(), 0xFF522A00.toInt())
            lower.contains("fantasy") || lower.contains("14") -> intArrayOf(0xFF4A148C.toInt(), 0xFF180033.toInt())
            lower.contains("horror") || lower.contains("27") -> intArrayOf(0xFF880E4F.toInt(), 0xFF260012.toInt())
            lower.contains("mystery") || lower.contains("9648") -> intArrayOf(0xFF311B92.toInt(), 0xFF0E0533.toInt())
            lower.contains("romance") || lower.contains("10749") -> intArrayOf(0xFFC2185B.toInt(), 0xFF42001B.toInt())
            lower.contains("sci") || lower.contains("science") || lower.contains("878") -> intArrayOf(0xFF00838F.toInt(), 0xFF002A30.toInt())
            lower.contains("thriller") || lower.contains("53") -> intArrayOf(0xFF424242.toInt(), 0xFF121212.toInt())
            else -> intArrayOf(0xFF232733.toInt(), 0xFF12141A.toInt())
        }
    }

    fun getGenreOverview(genreName: String): String {
        val clean = genreName.removePrefix("See All ").ifBlank { genreName }
        val lower = clean.lowercase()
        return when {
            lower.contains("action") -> "High-octane blockbusters, intense martial arts, thrilling chases, and explosive action sagas."
            lower.contains("adventure") -> "Epic journeys, treasure hunts, wilderness survival, and heroic quests across undiscovered worlds."
            lower.contains("animation") -> "Captivating animated features, anime sagas, and family-friendly animated adventures for all ages."
            lower.contains("comedy") -> "Side-splitting comedies, feel-good sitcoms, satirical humor, and stand-up specials."
            lower.contains("crime") -> "Gritty mob dramas, detective investigations, heist thrillers, and true crime chronicles."
            lower.contains("documentary") -> "Eye-opening real stories, nature chronicles, historical insights, and investigative docuseries."
            lower.contains("drama") -> "Emotionally charged stories, character studies, period pieces, and award-winning cinematic dramas."
            lower.contains("family") -> "Heartwarming movies and series crafted for wholesome entertainment and family movie nights."
            lower.contains("fantasy") -> "Magical realms, mythical creatures, superhero legends, and enchanting otherworldly sagas."
            lower.contains("horror") -> "Spine-chilling hauntings, psychological terrors, creature features, and supernatural scares."
            lower.contains("mystery") -> "Mind-bending whodunits, noir mysteries, crime puzzles, and unexpected plot twists."
            lower.contains("romance") -> "Heartwarming love stories, romantic comedies, passionate dramas, and timeless romance."
            lower.contains("sci") || lower.contains("science") -> "Futuristic odysseys, alien encounters, dystopian worlds, and mind-bending space sagas."
            lower.contains("thriller") -> "Edge-of-your-seat suspense, psychological mind games, espionage, and pulse-pounding thrillers."
            else -> "Explore top-rated movies and TV series in $clean."
        }
    }
}
