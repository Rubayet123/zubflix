package com.example.zubflix.util

import android.content.Context
import android.content.SharedPreferences

object AppearanceSettings {
    private const val PREFS_NAME = "zubflix_appearance_prefs"
    const val KEY_APP_THEME = "key_app_theme"
    const val KEY_HOMESCREEN_LAYOUT = "key_homescreen_layout"
    const val KEY_EPISODE_LAYOUT = "key_episode_layout"
    const val KEY_TV_CARD_STYLE = "key_tv_card_style"

    const val THEME_HOMEFLIX_TV = "homeflix_tv"
    const val THEME_ZUBFLIX_CLASSIC = "classic"

    const val EPISODE_LAYOUT_HORIZONTAL = "horizontal"
    const val EPISODE_LAYOUT_VERTICAL = "vertical"

    const val TV_CARD_STYLE_AUTO = "auto"
    const val TV_CARD_STYLE_PORTRAIT = "portrait"
    const val TV_CARD_STYLE_LANDSCAPE = "landscape"

    // Backwards compatibility aliases
    const val LAYOUT_HOMEFLIX_TV = THEME_HOMEFLIX_TV
    const val LAYOUT_CLASSIC = THEME_ZUBFLIX_CLASSIC

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAppTheme(context: Context): String {
        return getPrefs(context).getString(KEY_APP_THEME, THEME_ZUBFLIX_CLASSIC) ?: THEME_ZUBFLIX_CLASSIC
    }

    fun setAppTheme(context: Context, theme: String) {
        getPrefs(context).edit()
            .putString(KEY_APP_THEME, theme)
            .putString(KEY_HOMESCREEN_LAYOUT, theme)
            .apply()
    }

    fun getHomescreenLayout(context: Context): String {
        return getAppTheme(context)
    }

    fun setHomescreenLayout(context: Context, layout: String) {
        setAppTheme(context, layout)
    }

    fun getEpisodeLayout(context: Context): String {
        return getPrefs(context).getString(KEY_EPISODE_LAYOUT, EPISODE_LAYOUT_VERTICAL) ?: EPISODE_LAYOUT_VERTICAL
    }

    fun isHomeFlixTvTheme(context: Context): Boolean {
        return getAppTheme(context) == THEME_HOMEFLIX_TV
    }

    fun setEpisodeLayout(context: Context, layout: String) {
        getPrefs(context).edit().putString(KEY_EPISODE_LAYOUT, layout).apply()
    }

    fun getTvCardStyle(context: Context): String {
        return getPrefs(context).getString(KEY_TV_CARD_STYLE, TV_CARD_STYLE_PORTRAIT) ?: TV_CARD_STYLE_PORTRAIT
    }

    fun setTvCardStyle(context: Context, style: String) {
        getPrefs(context).edit().putString(KEY_TV_CARD_STYLE, style).apply()
    }
}

