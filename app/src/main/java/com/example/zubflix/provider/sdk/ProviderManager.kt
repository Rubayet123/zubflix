package com.example.zubflix.provider.sdk

import android.content.Context
import com.example.zubflix.SourceManager
import com.example.zubflix.provider.sdk.models.SearchResult
import com.example.zubflix.provider.sdk.models.StreamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Central registry and lifecycle manager for ZubFlix Content Providers.
 */
object ProviderManager {

    private val registeredProviders = mutableListOf<ContentProvider>()
    
    private val _providersState = MutableStateFlow<List<ContentProvider>>(emptyList())
    val providersState: StateFlow<List<ContentProvider>> = _providersState.asStateFlow()

    /**
     * Initializes ProviderManager with all active sources and registers them as ContentProviders.
     */
    fun initialize(context: Context) {
        // Ensure SourceManager is initialized first
        SourceManager.initialize(context)

        registeredProviders.clear()
        
        // Wrap legacy sources from SourceManager as ContentProviders
        val legacySources = SourceManager.getAllSources()
        for (src in legacySources) {
            registeredProviders.add(LegacyStreamingSourceAdapter.asContentProvider(src))
        }

        // Sort by priority (higher priority first)
        registeredProviders.sortByDescending { it.priority }
        
        notifyStateChanged(context)
    }

    /**
     * Registers a new ContentProvider plugin at runtime.
     */
    fun registerProvider(provider: ContentProvider, context: Context? = null) {
        if (registeredProviders.none { it.name.equals(provider.name, ignoreCase = true) }) {
            registeredProviders.add(provider)
            registeredProviders.sortByDescending { it.priority }
            context?.let { notifyStateChanged(it) }
        }
    }

    /**
     * Unregisters a ContentProvider by name.
     */
    fun unregisterProvider(name: String, context: Context? = null) {
        registeredProviders.removeAll { it.name.equals(name, ignoreCase = true) }
        context?.let { notifyStateChanged(it) }
    }

    fun getAllProviders(): List<ContentProvider> = registeredProviders.toList()

    fun getEnabledProviders(context: Context): List<ContentProvider> {
        val enabled = registeredProviders.filter { isProviderEnabled(context, it.name) }
        return if (enabled.isEmpty()) registeredProviders.toList() else enabled
    }

    fun isProviderEnabled(context: Context, providerName: String): Boolean {
        return SourceManager.isSourceEnabled(context, providerName)
    }

    fun setProviderEnabled(context: Context, providerName: String, enabled: Boolean) {
        SourceManager.setSourceEnabled(context, providerName, enabled)
        notifyStateChanged(context)
    }

    fun getProviderByName(name: String): ContentProvider? {
        if (name.isBlank()) return null
        return registeredProviders.find { 
            it.name.equals(name, ignoreCase = true) || 
            it.name.contains(name, ignoreCase = true) ||
            name.contains(it.name, ignoreCase = true) 
        }
    }

    /**
     * Executes parallel search across all enabled Content Providers.
     */
    suspend fun searchAll(context: Context, query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val providers = getEnabledProviders(context)
        coroutineScope {
            providers.map { provider ->
                async {
                    try {
                        provider.search(query)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Resolves playable streams for a specific provider and media ID.
     */
    suspend fun fetchStreams(
        providerName: String,
        mediaId: String,
        season: Int? = null,
        episode: Int? = null
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val provider = getProviderByName(providerName) ?: return@withContext emptyList()
        try {
            provider.streams(mediaId, season, episode)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun invalidateCaches() {
        registeredProviders.forEach { it.invalidateCache() }
        SourceManager.invalidateAllCaches()
    }

    private fun notifyStateChanged(context: Context) {
        _providersState.value = getEnabledProviders(context)
    }
}
