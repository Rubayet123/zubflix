package com.example.zubflix.model

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TMDBQueryBuilder {

    fun buildQueryPath(filterState: FilterState, context: Context? = null): String? {
        val isMovie = filterState.type == "movie"
        val endpoint = if (isMovie) "/discover/movie" else "/discover/tv"

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = dateFormat.format(Date())

        val params = mutableListOf<String>()

        var hasVoteThreshold = false

        // Sort option handling
        when (filterState.sortId) {
            "trending" -> {
                params.add("sort_by=popularity.desc")
                if (isMovie) {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_YEAR, -90)
                    val ninetyDaysAgo = dateFormat.format(cal.time)
                    params.add("primary_release_date.gte=$ninetyDaysAgo")
                    params.add("primary_release_date.lte=$todayStr")
                } else {
                    params.add("first_air_date.lte=$todayStr")
                }
            }
            "vote_average.desc", "top_rated" -> {
                params.add("sort_by=vote_average.desc")
                params.add("vote_count.gte=200")
                hasVoteThreshold = true
                if (isMovie) {
                    params.add("primary_release_date.lte=$todayStr")
                } else {
                    params.add("first_air_date.lte=$todayStr")
                }
            }
            "primary_release_date.desc", "first_air_date.desc", "latest" -> {
                val sortField = if (isMovie) "primary_release_date.desc" else "first_air_date.desc"
                params.add("sort_by=$sortField")
                if (isMovie) {
                    params.add("primary_release_date.lte=$todayStr")
                } else {
                    params.add("first_air_date.lte=$todayStr")
                }
            }
            else -> { // Popular / popularity.desc
                params.add("sort_by=popularity.desc")
                if (isMovie) {
                    params.add("primary_release_date.lte=$todayStr")
                } else {
                    params.add("first_air_date.lte=$todayStr")
                }
            }
        }

        // Smart Vote Threshold based on Region / Market / General vote count (if not already set by top_rated)
        if (!hasVoteThreshold) {
            // Bypass vote threshold completely when a specific network is selected
            val isSpecificNetworkSelected = filterState.networkId != "all" && filterState.networkId.isNotEmpty()
            if (!isSpecificNetworkSelected) {
                val threshold = com.example.zubflix.util.FilterSettings.getVoteCountThresholdForRegion(context, filterState.regionCode)
                if (threshold > 0) {
                    params.add("vote_count.gte=$threshold")
                }
            }
        }

        // Genre filter
        if (filterState.genreId != "all" && filterState.genreId.isNotEmpty()) {
            params.add("with_genres=${filterState.genreId}")
        }

        // Region filter
        if (filterState.regionCode != "all" && filterState.regionCode.isNotEmpty()) {
            params.add("with_origin_country=${filterState.regionCode}")
        }

        // Network / Streaming Platform filter
        if (filterState.networkId != "all" && filterState.networkId.isNotEmpty()) {
            val networkItem = FilterConfigRepository.findNetwork(filterState.networkId, context)
            if (networkItem != null) {
                val watchRegion = if (filterState.regionCode != "all" && filterState.regionCode.isNotEmpty()) {
                    filterState.regionCode
                } else {
                    networkItem.defaultRegion
                }

                if (isMovie) {
                    if (networkItem.id == "bbc_iplayer" && networkItem.tvNetworkId != null) {
                        params.add("with_networks=${networkItem.tvNetworkId}")
                    } else {
                        val providerIdStr = when (networkItem.id) {
                            "jiohotstar" -> "122|220|3919|500"
                            "prime_video" -> "119|9|10"
                            "paramount_plus" -> "531|1771|582|1853"
                            else -> if (networkItem.movieProviderId != null) "${networkItem.movieProviderId}" else null
                        }
                        if (providerIdStr != null) {
                            params.add("with_watch_providers=$providerIdStr")
                            params.add("watch_region=$watchRegion")
                            params.add("with_watch_monetization_types=flatrate|rent|buy|ads|free")
                        } else if (networkItem.tvNetworkId != null) {
                            params.add("with_networks=${networkItem.tvNetworkId}")
                        } else {
                            return null
                        }
                    }
                } else {
                    if ((networkItem.id == "national_geographic" || networkItem.id == "bbc_iplayer" || networkItem.id == "jiohotstar" || networkItem.id == "discovery_plus") && networkItem.tvNetworkId != null) {
                        params.add("with_networks=${networkItem.tvNetworkId}")
                    } else if (networkItem.movieProviderId != null) {
                        val providerIdStr = when (networkItem.id) {
                            "prime_video" -> "119|9|10"
                            "paramount_plus" -> "531|1771|582|1853"
                            else -> "${networkItem.movieProviderId}"
                        }
                        params.add("with_watch_providers=$providerIdStr")
                        params.add("watch_region=$watchRegion")
                        params.add("with_watch_monetization_types=flatrate|rent|buy|ads|free")
                    } else if (networkItem.tvNetworkId != null) {
                        params.add("with_networks=${networkItem.tvNetworkId}")
                    } else {
                        return null
                    }
                }
            } else {
                if (isMovie) {
                    val watchRegion = if (filterState.regionCode != "all" && filterState.regionCode.isNotEmpty()) {
                        filterState.regionCode
                    } else "US"
                    params.add("with_watch_providers=${filterState.networkId}")
                    params.add("watch_region=$watchRegion")
                    params.add("with_watch_monetization_types=flatrate|rent|buy|ads|free")
                } else {
                    params.add("with_networks=${filterState.networkId}")
                }
            }
        }

        return "$endpoint?${params.joinToString("&")}"
    }
}
