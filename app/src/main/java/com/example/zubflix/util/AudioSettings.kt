package com.example.zubflix.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings and presets for ZubFlix Audio Boost, Dynamic Range Compression (DRC),
 * and hardware LoudnessEnhancer processing.
 */
object AudioSettings {
    private const val PREFS_NAME = "zubflix_audio_prefs"

    const val KEY_DEFAULT_BOOST_LEVEL = "key_default_audio_boost_level"
    const val KEY_REMEMBER_SESSION_BOOST = "key_remember_session_boost"
    const val KEY_DIALOGUE_CLARITY = "key_dialogue_clarity"
    const val KEY_GESTURE_EXTENDED_BOOST = "key_gesture_extended_boost"

    // Audio Boost Levels (in millibels for LoudnessEnhancer)
    const val BOOST_OFF = 0          // 0 dB (100% standard)
    const val BOOST_LOW = 400        // +4 dB (125% boost)
    const val BOOST_MEDIUM = 800     // +8 dB (150% boost - recommended for TV speakers)
    const val BOOST_HIGH = 1200      // +12 dB (175% boost)
    const val BOOST_MAX = 1600       // +16 dB (200% max safe boost)

    val BOOST_LEVELS = listOf(
        BOOST_OFF to "Off (100% Normal)",
        BOOST_LOW to "+4 dB (125% Low)",
        BOOST_MEDIUM to "+8 dB (150% Medium - Recommended for TV)",
        BOOST_HIGH to "+12 dB (175% High)",
        BOOST_MAX to "+16 dB (200% Max)"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDefaultBoostLevel(context: Context): Int {
        return getPrefs(context).getInt(KEY_DEFAULT_BOOST_LEVEL, BOOST_OFF)
    }

    fun setDefaultBoostLevel(context: Context, levelMb: Int) {
        getPrefs(context).edit().putInt(KEY_DEFAULT_BOOST_LEVEL, levelMb).apply()
    }

    fun getBoostLabel(levelMb: Int): String {
        return when (levelMb) {
            BOOST_LOW -> "+4 dB (125%)"
            BOOST_MEDIUM -> "+8 dB (150%)"
            BOOST_HIGH -> "+12 dB (175%)"
            BOOST_MAX -> "+16 dB (200% Max)"
            else -> "Off (100%)"
        }
    }

    fun isRememberSessionBoostEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REMEMBER_SESSION_BOOST, false)
    }

    fun setRememberSessionBoostEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REMEMBER_SESSION_BOOST, enabled).apply()
    }

    fun isDialogueClarityEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DIALOGUE_CLARITY, true)
    }

    fun setDialogueClarityEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DIALOGUE_CLARITY, enabled).apply()
    }

    fun isGestureExtendedBoostEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GESTURE_EXTENDED_BOOST, true)
    }

    fun setGestureExtendedBoostEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GESTURE_EXTENDED_BOOST, enabled).apply()
    }
}
