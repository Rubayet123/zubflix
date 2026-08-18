package com.example.zubflix.provider.sdk.models

data class SearchResult(
    val id: String,
    val title: String,
    val isSeries: Boolean,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val quality: String? = null,
    val providerName: String? = null,
    val dataString: String? = null
)

data class HomeSection(
    val id: String,
    val title: String,
    val items: List<SearchResult>,
    val hideViewMore: Boolean = false
)

data class MediaDetails(
    val id: String,
    val title: String,
    val isSeries: Boolean,
    val overview: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val quality: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val seasons: List<SeasonDetails> = emptyList(),
    val directStreamUrl: String? = null,
    val providerName: String? = null
)

data class SeasonDetails(
    val seasonNumber: Int,
    val title: String,
    val episodes: List<EpisodeDetails>
)

data class EpisodeDetails(
    val episodeNumber: Int,
    val seasonNumber: Int = 1,
    val title: String,
    val dataString: String,
    val stillUrl: String? = null,
    val overview: String? = null
)

data class StreamLink(
    val name: String,             // e.g. "MovieLinkBD - 1080p" or "Fast R2 Cloud"
    val url: String,              // Playable HTTP/HLS URL
    val headers: Map<String, String> = emptyMap(),
    val quality: String? = null,   // "1080p", "720p", "480p"
    val isM3u8: Boolean = false,
    val providerName: String? = null
)

data class SubtitleLink(
    val language: String,         // e.g. "English", "Bangla"
    val url: String
)
