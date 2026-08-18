package com.example.zubflix.provider.sdk

import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.example.zubflix.provider.sdk.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Adapter utility providing bi-directional conversion between legacy [StreamingSource]
 * and modern [ContentProvider] SDK interface.
 */
object LegacyStreamingSourceAdapter {

    fun asContentProvider(source: StreamingSource): ContentProvider {
        return object : ContentProvider {
            override val name: String get() = source.name

            override suspend fun home(): List<HomeSection> {
                return source.getHomeCategories().map { categoryToHomeSection(it) }
            }

            override suspend fun search(query: String, page: Int): List<SearchResult> {
                return source.search(query).map { streamingItemToSearchResult(it) }
            }

            override suspend fun details(id: String): MediaDetails? {
                val item = source.getDetails(id) ?: return null
                return streamingItemToMediaDetails(item)
            }

            override suspend fun getSectionItems(sectionId: String, page: Int): List<SearchResult> {
                return source.getCategoryContent(sectionId, page).map { streamingItemToSearchResult(it) }
            }

            override suspend fun getSeasonEpisodes(id: String, seasonNumber: Int): List<EpisodeDetails> {
                return source.getSeasonEpisodes(id, seasonNumber).map { streamingEpisodeToDetails(it, seasonNumber) }
            }

            override suspend fun streams(id: String, season: Int?, episode: Int?): List<StreamLink> {
                val linksMap = source.extractVideoLinks(id)
                val gson = Gson()
                return linksMap.map { (label, rawVal) ->
                    var url = rawVal
                    var headersMap = emptyMap<String, String>()
                    if (rawVal.contains("######")) {
                        val parts = rawVal.split("######")
                        url = parts[0]
                        if (parts.size > 1) {
                            try {
                                val type = object : TypeToken<Map<String, String>>() {}.type
                                headersMap = gson.fromJson(parts[1], type) ?: emptyMap()
                            } catch (_: Exception) { }
                        }
                    }
                    val isM3u8 = url.contains(".m3u8") || label.contains("hls", ignoreCase = true)
                    StreamLink(
                        name = label,
                        url = url,
                        headers = headersMap,
                        isM3u8 = isM3u8,
                        providerName = source.name
                    )
                }
            }

            override fun invalidateCache() {
                source.invalidateCache()
            }
        }
    }

    fun asStreamingSource(provider: ContentProvider): StreamingSource {
        return object : StreamingSource {
            override val name: String get() = provider.name

            override suspend fun getHomeCategories(): List<StreamingCategory> {
                return provider.home().map { homeSectionToCategory(it) }
            }

            override suspend fun search(query: String): List<StreamingItem> {
                return provider.search(query).map { searchResultToStreamingItem(it) }
            }

            override suspend fun getDetails(id: String): StreamingItem? {
                val details = provider.details(id) ?: return null
                return mediaDetailsToStreamingItem(details)
            }

            override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> {
                return provider.getSectionItems(categoryId, page).map { searchResultToStreamingItem(it) }
            }

            override suspend fun getSeasonEpisodes(itemId: String, seasonNumber: Int): List<StreamingEpisode> {
                return provider.getSeasonEpisodes(itemId, seasonNumber).map { episodeDetailsToStreamingEpisode(it) }
            }

            override suspend fun extractVideoLinks(data: String): Map<String, String> {
                val streamLinks = provider.streams(data)
                if (streamLinks.isEmpty()) return mapOf("Default" to data)
                
                val gson = Gson()
                val resultMap = mutableMapOf<String, String>()
                for (link in streamLinks) {
                    if (link.headers.isNotEmpty()) {
                        val jsonHeaders = gson.toJson(link.headers)
                        resultMap[link.name] = "${link.url}######$jsonHeaders"
                    } else {
                        resultMap[link.name] = link.url
                    }
                }
                return resultMap
            }

            override fun invalidateCache() {
                provider.invalidateCache()
            }
        }
    }

    // Mapping helper methods
    fun categoryToHomeSection(cat: StreamingCategory): HomeSection {
        return HomeSection(
            id = cat.id,
            title = cat.title,
            items = cat.items.map { streamingItemToSearchResult(it) },
            hideViewMore = cat.hideViewMore
        )
    }

    fun homeSectionToCategory(sec: HomeSection): StreamingCategory {
        return StreamingCategory(
            id = sec.id,
            title = sec.title,
            items = sec.items.map { searchResultToStreamingItem(it) },
            hideViewMore = sec.hideViewMore
        )
    }

    fun streamingItemToSearchResult(item: StreamingItem): SearchResult {
        return SearchResult(
            id = item.id,
            title = item.title,
            isSeries = item.isSeries,
            posterUrl = item.imageUrl,
            rating = item.rating,
            year = item.year,
            quality = item.quality,
            providerName = item.sourceName,
            dataString = item.streamUrl ?: item.id
        )
    }

    fun searchResultToStreamingItem(res: SearchResult): StreamingItem {
        return StreamingItem(
            id = res.id,
            title = res.title,
            isSeries = res.isSeries,
            imageUrl = res.posterUrl,
            rating = res.rating,
            year = res.year,
            quality = res.quality,
            sourceName = res.providerName,
            streamUrl = res.dataString ?: res.id
        )
    }

    fun streamingItemToMediaDetails(item: StreamingItem): MediaDetails {
        return MediaDetails(
            id = item.id,
            title = item.title,
            isSeries = item.isSeries,
            overview = item.description,
            posterUrl = item.imageUrl,
            rating = item.rating,
            year = item.year,
            duration = item.duration,
            quality = item.quality,
            genres = item.genres ?: emptyList(),
            cast = item.cast ?: emptyList(),
            seasons = item.seasons?.map { seasonToDetails(it) } ?: emptyList(),
            directStreamUrl = item.streamUrl,
            providerName = item.sourceName
        )
    }

    fun mediaDetailsToStreamingItem(details: MediaDetails): StreamingItem {
        return StreamingItem(
            id = details.id,
            title = details.title,
            isSeries = details.isSeries,
            description = details.overview,
            imageUrl = details.posterUrl,
            rating = details.rating,
            year = details.year,
            duration = details.duration,
            quality = details.quality,
            genres = details.genres,
            cast = details.cast,
            seasons = details.seasons.map { detailsToSeason(it) },
            streamUrl = details.directStreamUrl ?: details.id,
            sourceName = details.providerName
        )
    }

    fun seasonToDetails(s: StreamingSeason): SeasonDetails {
        return SeasonDetails(
            seasonNumber = s.seasonNumber,
            title = s.title,
            episodes = s.episodes.map { streamingEpisodeToDetails(it, s.seasonNumber) }
        )
    }

    fun detailsToSeason(d: SeasonDetails): StreamingSeason {
        return StreamingSeason(
            title = d.title,
            episodes = d.episodes.map { episodeDetailsToStreamingEpisode(it) },
            seasonNumber = d.seasonNumber
        )
    }

    fun streamingEpisodeToDetails(ep: StreamingEpisode, seasonNum: Int = 1): EpisodeDetails {
        return EpisodeDetails(
            episodeNumber = 1,
            seasonNumber = seasonNum,
            title = ep.title,
            dataString = ep.streamUrl,
            stillUrl = ep.stillUrl,
            overview = ep.overview
        )
    }

    fun episodeDetailsToStreamingEpisode(ep: EpisodeDetails): StreamingEpisode {
        return StreamingEpisode(
            title = ep.title,
            streamUrl = ep.dataString,
            stillUrl = ep.stillUrl,
            overview = ep.overview
        )
    }
}
