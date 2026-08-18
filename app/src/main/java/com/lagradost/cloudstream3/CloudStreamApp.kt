package com.lagradost.cloudstream3

import android.app.Application
import android.content.Context
import java.lang.ref.WeakReference

class CloudStreamApp : Application() {
    companion object {
        private var _context: WeakReference<Context>? = null
        var context: Context?
            get() = _context?.get()
            set(value) {
                _context = value?.let { WeakReference(it.applicationContext) }
            }

        fun install(context: Context) {
            Companion.context = context.applicationContext
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? =
            context?.let { com.lagradost.cloudstream3.utils.DataStore.getKeyClass(it, path, valueType) }

        inline fun <reified T : Any> getKey(path: String): T? =
            context?.let { com.lagradost.cloudstream3.utils.DataStore.getKey<T>(it, path) }

        fun <T> setKey(path: String, value: T) =
            context?.let { com.lagradost.cloudstream3.utils.DataStore.setKeyAny(it, path, value) }

        fun removeKey(path: String) =
            context?.let { com.lagradost.cloudstream3.utils.DataStore.removeKey(it, path) }
    }

    override fun onCreate() {
        super.onCreate()
        install(this)
        com.example.zubflix.cloudstream.CloudStreamInitializer.init(this)
    }
}
