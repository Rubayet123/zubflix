package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.CloudStreamApp
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.json.JSONObject

object DataStore {
    private const val PREFS_NAME = "cloudstream_plugin_datastore"
    private var defaultContext: Context? = null

    fun init(context: Context) {
        defaultContext = context.applicationContext
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPrefs(): SharedPreferences? {
        return (defaultContext ?: CloudStreamApp.context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun <T : Any> getKeyClass(context: Context, path: String, valueType: Class<T>): T? {
        val json = getPrefs(context).getString(path, null) ?: return null
        return try {
            when (valueType) {
                String::class.java -> json as? T
                Int::class.java, java.lang.Integer::class.java -> json.toIntOrNull() as? T
                Boolean::class.java, java.lang.Boolean::class.java -> json.toBooleanStrictOrNull() as? T
                Long::class.java, java.lang.Long::class.java -> json.toLongOrNull() as? T
                Double::class.java, java.lang.Double::class.java -> json.toDoubleOrNull() as? T
                else -> {
                    val adapter = moshi.adapter(valueType)
                    adapter.fromJson(json)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    inline fun <reified T : Any> getKey(context: Context, path: String): T? {
        return getKeyClass(context, path, T::class.java)
    }

    fun <T> setKeyAny(context: Context, path: String, value: T) {
        val editor = getPrefs(context).edit()
        if (value == null) {
            editor.remove(path).apply()
            return
        }
        val serialized = when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> try {
                val adapter = moshi.adapter(value.javaClass)
                @Suppress("UNCHECKED_CAST")
                (adapter as com.squareup.moshi.JsonAdapter<Any>).toJson(value)
            } catch (e: Exception) {
                value.toString()
            }
        }
        editor.putString(path, serialized).apply()
    }

    fun <T> setKey(context: Context, path: String, value: T) = setKeyAny(context, path, value)

    fun removeKey(context: Context, path: String) {
        getPrefs(context).edit().remove(path).apply()
    }

    fun containsKey(context: Context, path: String): Boolean {
        return getPrefs(context).contains(path)
    }
}
