package com.example

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * CloudStreamInitializer sets CloudStreamApp.context, the nicehttp Requests app instance,
 * ContextHelper, and initializes DataStore for plugin runtime execution.
 */
object CloudStreamInitializer {
    private const val TAG = "CloudStreamInitializer"
    private var isInitialized = false

    @Synchronized
    fun init(context: Context) {
        try {
            val appContext = context.applicationContext
            CloudStreamApp.install(appContext)
            CloudStreamApp.context = appContext
            
            // Set weak reference context for cloudstream extensions via reflection & helper
            try {
                val ctxClass = Class.forName("com.lagradost.api.ContextHelper_androidKt")
                val setCtxMethod = ctxClass.getMethod("setCtx", WeakReference::class.java)
                setCtxMethod.invoke(null, WeakReference(appContext))
                Log.d(TAG, "ContextHelper setCtx initialized")
            } catch (e: Throwable) {
                Log.w(TAG, "ContextHelper setCtx warning: ${e.message}")
            }

            // Initialize the global nicehttp Requests instance (used by all CS plugins as `app.get()`, `app.post()`, etc.)
            try {
                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val defaultHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9"
                )

                // Instantiate Requests through reflection or direct
                val requestsClass = Class.forName("com.lagradost.nicehttp.Requests")
                val requestsConstructor = requestsClass.getConstructor(
                    OkHttpClient::class.java,
                    Map::class.java,
                    String::class.java,
                    Map::class.java,
                    Map::class.java,
                    Int::class.javaPrimitiveType,
                    TimeUnit::class.java,
                    Long::class.javaPrimitiveType,
                    Class.forName("com.lagradost.nicehttp.ResponseParser")
                )
                // Default constructor can also be used
                val requestsInstance = try {
                    requestsClass.getDeclaredConstructor().newInstance()
                } catch (_: Throwable) {
                    null
                }

                if (requestsInstance != null) {
                    // Set baseClient
                    try {
                        val setBaseClientMethod = requestsClass.getMethod("setBaseClient", OkHttpClient::class.java)
                        setBaseClientMethod.invoke(requestsInstance, okHttpClient)
                    } catch (_: Throwable) {}

                    // Set defaultHeaders
                    try {
                        val setDefaultHeadersMethod = requestsClass.getMethod("setDefaultHeaders", Map::class.java)
                        setDefaultHeadersMethod.invoke(requestsInstance, defaultHeaders)
                    } catch (_: Throwable) {}

                    // Assign to MainActivityKt.setApp
                    val mainActivityKtClass = Class.forName("com.lagradost.cloudstream3.MainActivityKt")
                    val setAppMethod = mainActivityKtClass.getMethod("setApp", requestsClass)
                    setAppMethod.invoke(null, requestsInstance)
                    Log.d(TAG, "CloudStream global nicehttp Requests `app` initialized successfully via reflection")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize global nicehttp `app`: ${e.message}", e)
            }

            DataStore.init(appContext)
            isInitialized = true
            Log.d(TAG, "CloudStreamApp.context and DataStore initialized successfully with ($appContext)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize CloudStream context and DataStore: ${e.message}", e)
        }
    }

    fun isInitialized(): Boolean = isInitialized
}


