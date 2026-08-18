package com.example.zubflix.sources

import android.content.Context
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.cloudstream.CloudStreamDexLoader
import com.example.zubflix.cloudstream.CloudStreamPluginManager
import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class CloudStreamSource(private val context: Context) : StreamingSource {

    override val name: String = "CloudStream Extensions"

    override suspend fun getHomeCategories(): List<StreamingCategory> {
        return emptyList()
    }

    override suspend fun search(query: String): List<StreamingItem> {
        return emptyList()
    }

    override suspend fun getDetails(id: String): StreamingItem? {
        return null
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> {
        return emptyList()
    }

    suspend fun getStreamLinksForContent(
        title: String,
        isSeries: Boolean = false,
        season: Int? = null,
        episode: Int? = null
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        val plugins = CloudStreamPluginManager.getInstalledPlugins(context).filter { it.isEnabled }
        if (plugins.isEmpty()) return@withContext emptyList()

        val tasks = plugins.map { plugin ->
            async {
                CloudStreamDexLoader.searchAndFetchStreams(
                    context = context,
                    plugin = plugin,
                    queryTitle = title,
                    isSeries = isSeries,
                    season = season,
                    episode = episode
                )
            }
        }

        tasks.awaitAll().flatten()
    }
}
