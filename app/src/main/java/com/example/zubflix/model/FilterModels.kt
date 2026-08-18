package com.example.zubflix.model

data class FilterState(
    val type: String = "movie", // "movie" or "tv"
    val genreId: String = "all",
    val networkId: String = "all",
    val regionCode: String = "all",
    val sortId: String = "popularity.desc"
)

data class GenreFilterItem(
    val id: String,
    val name: String
)

data class RegionFilterItem(
    val code: String,
    val name: String,
    val voteTier: String = "small" // "large", "medium", "small"
)

data class NetworkFilterItem(
    val id: String,
    val name: String,
    val tvNetworkId: Int?,
    val movieProviderId: Int?,
    val defaultRegion: String = "US"
)

data class SortOptionFilterItem(
    val id: String,
    val title: String
)
