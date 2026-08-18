package com.example.zubflix.provider.sdk

import com.example.zubflix.provider.sdk.models.*

/**
 * Standard interface for streaming plugins/sources in ZubFlix Provider SDK.
 */
interface ContentProvider {
    val name: String
    val priority: Int get() = 0

    suspend fun home(): List<HomeSection>
    suspend fun search(query: String, page: Int = 1): List<SearchResult>
    suspend fun details(id: String): MediaDetails?
    
    suspend fun getSectionItems(sectionId: String, page: Int = 1): List<SearchResult> = emptyList()
    suspend fun getSeasonEpisodes(id: String, seasonNumber: Int): List<EpisodeDetails> = emptyList()
    
    suspend fun streams(id: String, season: Int? = null, episode: Int? = null): List<StreamLink>
    suspend fun subtitles(id: String, season: Int? = null, episode: Int? = null): List<SubtitleLink> = emptyList()

    fun invalidateCache() {}
}
