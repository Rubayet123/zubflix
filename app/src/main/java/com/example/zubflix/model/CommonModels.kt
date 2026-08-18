package com.example.zubflix.model

// Unified data models for the main app
data class StreamingItem(
    val id: String,
    val title: String,
    val isSeries: Boolean,
    val isCategory: Boolean = false,
    val sourceName: String? = null,
    
    // Nullable fields (Scrapers might not find these)
    val imageUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val streamUrl: String? = null, // For single video items
    val videoSources: Map<String, String>? = null, // [Server Name -> Stream URL]
    val subtitles: Map<String, String>? = null,    // [Lang -> Url]
    
    // Series Data
    val seasons: List<StreamingSeason>? = null,
    
    // Detailed Metadata
    val quality: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val rating: String? = null,    // e.g., "8.5/10"
    val genres: List<String>? = null,
    val cast: List<String>? = null,
    
    // Continue Watching Progress
    val watchPercentage: Float? = null  // 0-100, for progress bar display
)

data class StreamingSeason(
    val title: String, // e.g., "Season 1"
    val episodes: List<StreamingEpisode>,
    val seasonNumber: Int = 1
)

data class StreamingEpisode(
    val title: String, // e.g., "S1E1 - Pilot"
    val streamUrl: String,
    val stillUrl: String? = null,
    val overview: String? = null,
    val airDate: String? = null,
    val voteAverage: Double? = null,
    val runtime: Int? = null
)

data class StreamingCategory(
    val id: String,
    val title: String,
    val items: List<StreamingItem>,
    val hideViewMore: Boolean = false
)

interface StreamingSource {
    val name: String
    suspend fun getHomeCategories(): List<StreamingCategory>
    suspend fun search(query: String): List<StreamingItem>
    suspend fun getDetails(id: String): StreamingItem?
    
    // New method for infinite scroll pagination
    suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem>

    // New method for on-demand/lazy loading of season episodes
    suspend fun getSeasonEpisodes(itemId: String, seasonNumber: Int): List<StreamingEpisode> = emptyList()

    // New method for lazy video link extraction (for sources that expire links or have complex scraping)
    // Returns a map of "Source Name" -> "URL"
    suspend fun extractVideoLinks(data: String): Map<String, String> = mapOf("Default" to data)

    /**
     * Clears internal memory caches for this source.
     */
    fun invalidateCache() {}
}
