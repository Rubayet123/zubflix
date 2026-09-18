package com.example.zubflix.util

import com.example.R

object NetworkUtils {

    fun getLogoResId(idOrTitle: String?): Int? {
        if (idOrTitle.isNullOrBlank()) return null
        val lower = idOrTitle.lowercase()
        return when {
            lower.contains("netflix") || lower.contains(":213") -> R.drawable.netflix
            lower.contains("prime") || lower.contains("amazon") || lower.contains(":1024") -> R.drawable.prime
            lower.contains("apple") || lower.contains(":2552") -> R.drawable.apple
            lower.contains("disney") || lower.contains(":2739") -> R.drawable.disney
            lower.contains("hbo") || lower.contains("max") || lower.contains(":49") || lower.contains(":3186") -> R.drawable.hbo
            lower.contains("hulu") || lower.contains(":453") -> R.drawable.hulu
            lower.contains("paramount") || lower.contains(":4330") -> R.drawable.paramount
            lower.contains("peacock") || lower.contains(":3353") -> R.drawable.peacock
            lower.contains("bbc") || lower.contains("iplayer") || lower.contains(":4") -> R.drawable.bbciplayer
            lower.contains("mubi") -> R.drawable.mubi
            lower.contains("crunchyroll") || lower.contains(":1112") -> R.drawable.crunchyroll
            lower.contains("curiosity") || lower.contains(":1267") -> R.drawable.curiositystream
            lower.contains("discovery") || lower.contains(":4353") || lower.contains(":64") -> R.drawable.discovery_plus
            lower.contains("jio") || lower.contains("hotstar") || lower.contains(":3919") -> R.drawable.jiohotstar
            lower.contains("sony") || lower.contains("liv") || lower.contains(":2271") -> R.drawable.sonyliv
            lower.contains("zee") || lower.contains(":1516") -> R.drawable.zee5
            lower.contains("hoichoi") || lower.contains(":3057") -> R.drawable.hoichhoi
            lower.contains("national geographic") || lower.contains("nat geo") || lower.contains("natgeo") || lower.contains(":43") -> R.drawable.natgeo
            else -> null
        }
    }

    fun getBrandGradient(idOrTitle: String?): IntArray {
        if (idOrTitle.isNullOrBlank()) return intArrayOf(0xFF222630.toInt(), 0xFF12141A.toInt())
        val lower = idOrTitle.lowercase()
        return when {
            lower.contains("netflix") -> intArrayOf(0xFFE50914.toInt(), 0xFF500000.toInt())
            lower.contains("prime") || lower.contains("amazon") -> intArrayOf(0xFF00A8E1.toInt(), 0xFF002540.toInt())
            lower.contains("apple") -> intArrayOf(0xFF333333.toInt(), 0xFF111111.toInt())
            lower.contains("disney") -> intArrayOf(0xFF113CCF.toInt(), 0xFF081850.toInt())
            lower.contains("hbo") || lower.contains("max") -> intArrayOf(0xFF5822B4.toInt(), 0xFF200650.toInt())
            lower.contains("hulu") -> intArrayOf(0xFF1CE783.toInt(), 0xFF054525.toInt())
            lower.contains("paramount") -> intArrayOf(0xFF0064FF.toInt(), 0xFF002055.toInt())
            lower.contains("peacock") -> intArrayOf(0xFF00A859.toInt(), 0xFF003D20.toInt())
            lower.contains("bbc") || lower.contains("iplayer") -> intArrayOf(0xFF008080.toInt(), 0xFF003333.toInt())
            lower.contains("mubi") -> intArrayOf(0xFF001242.toInt(), 0xFF000515.toInt())
            lower.contains("crunchyroll") -> intArrayOf(0xFFFF6600.toInt(), 0xFF803300.toInt())
            lower.contains("curiosity") -> intArrayOf(0xFFFF0033.toInt(), 0xFF660014.toInt())
            lower.contains("discovery") -> intArrayOf(0xFF0243B5.toInt(), 0xFF011C50.toInt())
            lower.contains("jio") || lower.contains("hotstar") -> intArrayOf(0xFF0F1035.toInt(), 0xFF020210.toInt())
            lower.contains("sony") || lower.contains("liv") -> intArrayOf(0xFFFF3300.toInt(), 0xFF881100.toInt())
            lower.contains("zee") -> intArrayOf(0xFF8B008B.toInt(), 0xFF3B003B.toInt())
            lower.contains("hoichoi") -> intArrayOf(0xFFE50914.toInt(), 0xFF500000.toInt())
            lower.contains("national geographic") || lower.contains("nat geo") || lower.contains("natgeo") -> intArrayOf(0xFFFFCC00.toInt(), 0xFF222222.toInt())
            else -> intArrayOf(0xFF222630.toInt(), 0xFF12141A.toInt())
        }
    }
}
