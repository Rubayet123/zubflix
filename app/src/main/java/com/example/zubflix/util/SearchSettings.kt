package com.example.zubflix.util

import android.content.Context
import android.content.SharedPreferences

object SearchSettings {
    private const val PREFS_NAME = "zubflix_search_prefs"
    private const val KEY_GLOBAL_SEARCH_DEFAULT = "always_enable_global_search"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isGlobalSearchDefault(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GLOBAL_SEARCH_DEFAULT, true)
    }

    fun setGlobalSearchDefault(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GLOBAL_SEARCH_DEFAULT, enabled).apply()
        // Sync with app_prefs
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("search_global_enabled", enabled)
            .apply()
    }
}
