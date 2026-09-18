package com.example.zubflix.util

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object CacheSettings {
    private const val PREFS_NAME = "zubflix_cache_prefs"
    private const val KEY_METADATA_DURATION_HOURS = "cache_metadata_duration_hours"
    private const val KEY_MAX_CACHE_SIZE_MB = "cache_max_size_mb"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getMetadataCacheHours(context: Context): Int {
        return getPrefs(context).getInt(KEY_METADATA_DURATION_HOURS, 24)
    }

    fun setMetadataCacheHours(context: Context, hours: Int) {
        getPrefs(context).edit().putInt(KEY_METADATA_DURATION_HOURS, hours).apply()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("cache_ttl_hours", hours)
            .apply()
    }

    fun getMaxCacheSizeMb(context: Context): Int {
        return getPrefs(context).getInt(KEY_MAX_CACHE_SIZE_MB, 250)
    }

    fun setMaxCacheSizeMb(context: Context, sizeMb: Int) {
        getPrefs(context).edit().putInt(KEY_MAX_CACHE_SIZE_MB, sizeMb).apply()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("max_cache_size_mb", sizeMb)
            .apply()
    }

    fun getCurrentCacheSizeMb(context: Context): Double {
        var totalBytes = 0L
        try {
            val cacheDir = context.cacheDir
            totalBytes += getFolderSize(cacheDir)
            val externalCache = context.externalCacheDir
            if (externalCache != null) {
                totalBytes += getFolderSize(externalCache)
            }
            val codeCache = context.codeCacheDir
            totalBytes += getFolderSize(codeCache)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return totalBytes / (1024.0 * 1024.0)
    }

    private fun getFolderSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        var size = 0L
        val children = file.listFiles() ?: return 0L
        for (child in children) {
            size += if (child.isDirectory) {
                getFolderSize(child)
            } else {
                child.length()
            }
        }
        return size
    }

    fun clearAllCache(context: Context) {
        try {
            deleteDir(context.cacheDir)
            context.externalCacheDir?.let { deleteDir(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list()
            if (children != null) {
                for (child in children) {
                    val success = deleteDir(File(dir, child))
                    if (!success) return false
                }
            }
            return dir.delete()
        } else if (dir != null && dir.isFile) {
            return dir.delete()
        }
        return false
    }
}
