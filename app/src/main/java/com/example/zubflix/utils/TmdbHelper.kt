package com.example.zubflix.utils

import android.content.Context
import android.util.Log
import com.example.zubflix.SourceManager
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object TmdbHelper {
    // Force recompile to resolve clearCache reference
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val TMDB_API = "https://api.themoviedb.org/3"

    data class CastMember(
        val name: String,
        val character: String?,
        val profilePath: String?,
        val id: Int? = null
    )

    data class PersonCredit(
        val id: Int,
        val title: String,
        val mediaType: String, // "movie" or "tv"
        val posterPath: String?,
        val backdropPath: String?,
        val releaseDate: String?,
        val year: String?,
        val voteAverage: String?,
        val character: String?,
        val department: String?,
        val job: String?,
        val overview: String?,
        val popularity: Double
    )

    data class PersonDetails(
        val id: Int,
        val name: String,
        val biography: String?,
        val birthday: String?,
        val deathday: String?,
        val placeOfBirth: String?,
        val profilePath: String?,
        val knownForDepartment: String?,
        val credits: List<PersonCredit>
    )

    data class TmdbEpisode(
        val seasonNumber: Int,
        val episodeNumber: Int,
        val title: String,
        val stillPath: String?,
        val overview: String?
    )

    data class TmdbSeason(
        val seasonNumber: Int,
        val name: String,
        val episodes: List<TmdbEpisode>
    )

    data class TmdbDetails(
        val rating: String? = null,
        val overview: String? = null,
        val cast: List<String>? = null,
        val castMembers: List<CastMember>? = null,
        val posterPath: String? = null,
        val backdropPath: String? = null,
        val genres: List<String>? = null,
        val year: String? = null,
        val imdbId: String? = null
    )

    suspend fun searchAndFetchDetails(
        context: Context,
        title: String,
        year: String?,
        isSeries: Boolean
    ): TmdbDetails? = withContext(Dispatchers.IO) {
        try {
            val apiKey = SourceManager.getTmdbApiKey(context)
            if (apiKey.isEmpty()) return@withContext null

            // Check preferences
            val useRatings = SourceManager.isTmdbRatingsEnabled(context)
            val usePlot = SourceManager.isTmdbPlotEnabled(context)
            val useCast = SourceManager.isTmdbCastEnabled(context)
            val useBackdrops = SourceManager.isTmdbBackdropsEnabled(context)
            val useGenres = SourceManager.isTmdbGenresEnabled(context)
            val usePersistentCache = SourceManager.isPersistentTmdbCacheEnabled(context)

            // If nothing is enabled, skip lookup
            if (!useRatings && !usePlot && !useCast && !useBackdrops && !useGenres) {
                return@withContext null
            }

            val queryKey = "${title.lowercase()}_${year ?: ""}_${if (isSeries) "tv" else "movie"}"
            val dbHelper = TmdbDatabaseHelper(context)

            // 1. Check Persistent Cache
            if (usePersistentCache) {
                val cachedJson = dbHelper.getItem(queryKey)
                if (cachedJson != null) {
                    try {
                        val cJson = JSONObject(cachedJson)
                        return@withContext TmdbDetails(
                            rating = if (useRatings) cJson.optString("rating", null) else null,
                            overview = if (usePlot) cJson.optString("overview", null) else null,
                            cast = if (useCast) {
                                val arr = cJson.optJSONArray("cast")
                                if (arr != null) (0 until arr.length()).map { arr.getString(it) } else null
                            } else null,
                            castMembers = if (useCast) {
                                val arr = cJson.optJSONArray("castMembers")
                                if (arr != null) {
                                    (0 until arr.length()).map {
                                        val mObj = arr.getJSONObject(it)
                                        CastMember(
                                            name = mObj.getString("name"),
                                            character = mObj.optString("character", null),
                                            profilePath = mObj.optString("profilePath", null),
                                            id = if (mObj.has("id")) mObj.optInt("id", -1).takeIf { id -> id != -1 } else null
                                        )
                                    }
                                } else null
                            } else null,
                            posterPath = if (useBackdrops) cJson.optString("posterPath", null) else null,
                            backdropPath = if (useBackdrops) cJson.optString("backdropPath", null) else null,
                            genres = if (useGenres) {
                                val arr = cJson.optJSONArray("genres")
                                if (arr != null) (0 until arr.length()).map { arr.getString(it) } else null
                            } else null,
                            year = cJson.optString("year", null),
                            imdbId = cJson.optString("imdbId", null).let { if (it == "null" || it.isEmpty()) null else it }
                        )
                    } catch (e: Exception) {
                        Log.e("TmdbHelper", "Error parsing cached TMDB data", e)
                    }
                }
            }

            // 2. Search
            val type = if (isSeries) "tv" else "movie"
            val query = URLEncoder.encode(title, "UTF-8")
            val yearParam = if (!year.isNullOrEmpty()) "&year=$year&first_air_date_year=$year" else "" 
            
            val searchUrl = "$TMDB_API/search/$type?api_key=$apiKey&query=$query$yearParam"
            Log.d("TmdbHelper", "Searching TMDB: $searchUrl")
            
            val searchRes = client.newCall(Request.Builder().url(searchUrl).build()).execute()
            val searchJson = JSONObject(searchRes.body?.string() ?: "")
            val results = searchJson.optJSONArray("results")
            
            if (results == null || results.length() == 0) {
                Log.d("TmdbHelper", "No results found for '$title'")
                return@withContext null
            }

            // Get first match
            val match = results.getJSONObject(0)
            val id = match.getInt("id")
            
            // 3. Fetch Details
            val detailsUrl = "$TMDB_API/$type/$id?api_key=$apiKey&append_to_response=credits,external_ids"
            val detailsRes = client.newCall(Request.Builder().url(detailsUrl).build()).execute()
            val json = JSONObject(detailsRes.body?.string() ?: "")

            // Extract Data
            val rating = "${String.format("%.1f", json.optDouble("vote_average"))}"
            val overview = json.optString("overview")
            val poster = json.optString("poster_path")
            val backdrop = json.optString("backdrop_path")
            
            val credits = json.optJSONObject("credits")
            val cArr = credits?.optJSONArray("cast")
            val castList = if (cArr != null) {
                (0 until minOf(cArr.length(), 10)).map { cArr.getJSONObject(it).getString("name") }
            } else null

            val castMembersList = if (cArr != null) {
                (0 until minOf(cArr.length(), 10)).map {
                    val cObj = cArr.getJSONObject(it)
                    val profile = cObj.optString("profile_path", null)
                    CastMember(
                        name = cObj.getString("name"),
                        character = cObj.optString("character", null),
                        profilePath = if (!profile.isNullOrEmpty() && profile != "null") "https://image.tmdb.org/t/p/w185$profile" else null,
                        id = cObj.optInt("id", -1).takeIf { id -> id != -1 }
                    )
                }
            } else null
            
            val gArr = json.optJSONArray("genres")
            val genresList = if (gArr != null && gArr.length() > 0) {
                 (0 until gArr.length()).map { gArr.getJSONObject(it).getString("name") }
            } else null

            val releaseDate = json.optString("release_date").ifEmpty { json.optString("first_air_date") }
            val releaseYear = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
            
            val externalIds = json.optJSONObject("external_ids")
            var imdbId = externalIds?.optString("imdb_id")
            if (imdbId.isNullOrEmpty() || imdbId == "null") {
                imdbId = json.optString("imdb_id") // Sometimes it's on the root object for movies
                if (imdbId == "null") imdbId = null
            }

            val details = TmdbDetails(
                rating = rating,
                overview = overview,
                cast = castList,
                castMembers = castMembersList,
                posterPath = if (!poster.isNullOrEmpty()) "https://image.tmdb.org/t/p/w500$poster" else null,
                backdropPath = if (!backdrop.isNullOrEmpty()) "https://image.tmdb.org/t/p/original$backdrop" else null,
                genres = genresList,
                year = releaseYear,
                imdbId = imdbId
            )

            // 4. Save to Persistent Cache
            if (usePersistentCache) {
                val saveJson = JSONObject().apply {
                    put("rating", details.rating)
                    put("overview", details.overview)
                    put("cast", if (details.cast != null) org.json.JSONArray(details.cast) else null)
                    
                    val castMembersArr = org.json.JSONArray()
                    details.castMembers?.forEach { member ->
                        val mObj = JSONObject().apply {
                            put("name", member.name)
                            put("character", member.character)
                            put("profilePath", member.profilePath)
                            if (member.id != null) put("id", member.id)
                        }
                        castMembersArr.put(mObj)
                    }
                    put("castMembers", castMembersArr)

                    put("posterPath", details.posterPath)
                    put("backdropPath", details.backdropPath)
                    put("genres", if (details.genres != null) org.json.JSONArray(details.genres) else null)
                    put("year", details.year)
                    put("imdbId", details.imdbId)
                }
                dbHelper.saveItem(queryKey, saveJson.toString())
            }

            // Filter by user preference for immediate return
            details.copy(
                rating = if (useRatings) details.rating else null,
                overview = if (usePlot) details.overview else null,
                cast = if (useCast) details.cast else null,
                castMembers = if (useCast) details.castMembers else null,
                posterPath = if (useBackdrops) details.posterPath else null,
                backdropPath = if (useBackdrops) details.backdropPath else null,
                genres = if (useGenres) details.genres else null
                // imdbId is always returned if available
            )
        } catch (e: Exception) {
            Log.e("TmdbHelper", "Error fetching TMDB data", e)
            null
        }
    }

    suspend fun fetchTvSeasonsAndEpisodes(
        context: Context,
        imdbId: String?,
        title: String,
        year: String?
    ): List<TmdbSeason>? = withContext(Dispatchers.IO) {
        try {
            val apiKey = SourceManager.getTmdbApiKey(context)
            if (apiKey.isEmpty()) return@withContext null

            var tmdbTvId: Int? = null

            // 1. If imdbId is available (e.g., tt1234567), use /find endpoint
            if (!imdbId.isNullOrEmpty() && imdbId.startsWith("tt")) {
                val findUrl = "$TMDB_API/find/$imdbId?api_key=$apiKey&external_source=imdb_id"
                val findRes = client.newCall(Request.Builder().url(findUrl).build()).execute()
                val findJson = JSONObject(findRes.body?.string() ?: "")
                val tvResults = findJson.optJSONArray("tv_results")
                if (tvResults != null && tvResults.length() > 0) {
                    tmdbTvId = tvResults.getJSONObject(0).optInt("id")
                }
            }

            // 2. Fallback to search if find failed
            if (tmdbTvId == null && title.isNotBlank()) {
                val query = URLEncoder.encode(title, "UTF-8")
                val yearParam = if (!year.isNullOrEmpty()) "&first_air_date_year=$year" else ""
                val searchUrl = "$TMDB_API/search/tv?api_key=$apiKey&query=$query$yearParam"
                val searchRes = client.newCall(Request.Builder().url(searchUrl).build()).execute()
                val searchJson = JSONObject(searchRes.body?.string() ?: "")
                val results = searchJson.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    tmdbTvId = results.getJSONObject(0).optInt("id")
                }
            }

            if (tmdbTvId == null) return@withContext null

            // 3. Get TV details to find seasons
            val tvDetailsUrl = "$TMDB_API/tv/$tmdbTvId?api_key=$apiKey"
            val tvRes = client.newCall(Request.Builder().url(tvDetailsUrl).build()).execute()
            val tvJson = JSONObject(tvRes.body?.string() ?: "")
            val seasonsArr = tvJson.optJSONArray("seasons") ?: return@withContext null

            val resultSeasons = mutableListOf<TmdbSeason>()

            for (s in 0 until seasonsArr.length()) {
                val sObj = seasonsArr.getJSONObject(s)
                val sNum = sObj.optInt("season_number", 0)
                if (sNum <= 0) continue // Skip specials (season 0)
                val sName = sObj.optString("name").ifEmpty { "Season $sNum" }

                // Fetch episodes for season
                val seasonUrl = "$TMDB_API/tv/$tmdbTvId/season/$sNum?api_key=$apiKey"
                val seasonRes = client.newCall(Request.Builder().url(seasonUrl).build()).execute()
                val seasonJson = JSONObject(seasonRes.body?.string() ?: "")
                val episodesArr = seasonJson.optJSONArray("episodes") ?: continue

                val episodesList = mutableListOf<TmdbEpisode>()
                for (e in 0 until episodesArr.length()) {
                    val epObj = episodesArr.getJSONObject(e)
                    val epNum = epObj.optInt("episode_number", e + 1)
                    val epTitle = epObj.optString("name").ifEmpty { "Episode $epNum" }
                    val stillPath = epObj.optString("still_path", null)
                    val fullStill = if (!stillPath.isNullOrEmpty() && stillPath != "null") "https://image.tmdb.org/t/p/w500$stillPath" else null
                    val overview = epObj.optString("overview", "")

                    episodesList.add(
                        TmdbEpisode(
                            seasonNumber = sNum,
                            episodeNumber = epNum,
                            title = epTitle,
                            stillPath = fullStill,
                            overview = overview
                        )
                    )
                }

                if (episodesList.isNotEmpty()) {
                    resultSeasons.add(
                        TmdbSeason(
                            seasonNumber = sNum,
                            name = sName,
                            episodes = episodesList
                        )
                    )
                }
            }

            if (resultSeasons.isNotEmpty()) resultSeasons else null
        } catch (e: Exception) {
            Log.e("TmdbHelper", "Error fetching TV seasons from TMDB", e)
            null
        }
    }

    suspend fun fetchPersonDetails(
        context: Context,
        personId: Int?,
        personName: String
    ): PersonDetails? = withContext(Dispatchers.IO) {
        try {
            val apiKey = SourceManager.getTmdbApiKey(context)
            if (apiKey.isEmpty()) return@withContext null

            var resolvedId = personId

            // If ID is missing or invalid, search person by name
            if (resolvedId == null || resolvedId <= 0) {
                val query = URLEncoder.encode(personName, "UTF-8")
                val searchUrl = "$TMDB_API/search/person?api_key=$apiKey&query=$query"
                val searchRes = client.newCall(Request.Builder().url(searchUrl).build()).execute()
                val searchJson = JSONObject(searchRes.body?.string() ?: "")
                val results = searchJson.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    resolvedId = results.getJSONObject(0).getInt("id")
                }
            }

            if (resolvedId == null || resolvedId <= 0) {
                return@withContext null
            }

            val detailsUrl = "$TMDB_API/person/$resolvedId?api_key=$apiKey&append_to_response=combined_credits,external_ids"
            val detailsRes = client.newCall(Request.Builder().url(detailsUrl).build()).execute()
            val json = JSONObject(detailsRes.body?.string() ?: "")

            val id = json.getInt("id")
            val name = json.optString("name", personName)
            val biography = json.optString("biography", null).takeIf { !it.isNullOrBlank() }
            val birthday = json.optString("birthday", null).takeIf { !it.isNullOrBlank() }
            val deathday = json.optString("deathday", null).takeIf { !it.isNullOrBlank() }
            val placeOfBirth = json.optString("place_of_birth", null).takeIf { !it.isNullOrBlank() }
            val profile = json.optString("profile_path", null).takeIf { !it.isNullOrBlank() }
            val profilePath = if (profile != null) "https://image.tmdb.org/t/p/h632$profile" else null
            val knownFor = json.optString("known_for_department", "Acting")

            val combinedCredits = json.optJSONObject("combined_credits")
            val rawCreditsList = mutableListOf<PersonCredit>()

            // 1. Cast credits
            val castArr = combinedCredits?.optJSONArray("cast")
            if (castArr != null) {
                for (i in 0 until castArr.length()) {
                    val c = castArr.getJSONObject(i)
                    val creditId = c.getInt("id")
                    val mediaType = c.optString("media_type", "movie")
                    val title = if (mediaType == "tv") c.optString("name") else c.optString("title")
                    if (title.isBlank()) continue

                    val poster = c.optString("poster_path", null).takeIf { !it.isNullOrBlank() }
                    val backdrop = c.optString("backdrop_path", null).takeIf { !it.isNullOrBlank() }
                    val relDate = if (mediaType == "tv") c.optString("first_air_date", "") else c.optString("release_date", "")
                    val year = if (relDate.length >= 4) relDate.substring(0, 4) else null
                    val voteAvg = c.optDouble("vote_average", 0.0)
                    val rating = if (voteAvg > 0.0) String.format("%.1f", voteAvg) else null
                    val character = c.optString("character", null).takeIf { !it.isNullOrBlank() }
                    val overview = c.optString("overview", null).takeIf { !it.isNullOrBlank() }
                    val popularity = c.optDouble("popularity", 0.0)

                    rawCreditsList.add(
                        PersonCredit(
                            id = creditId,
                            title = title,
                            mediaType = mediaType,
                            posterPath = if (poster != null) "https://image.tmdb.org/t/p/w500$poster" else null,
                            backdropPath = if (backdrop != null) "https://image.tmdb.org/t/p/original$backdrop" else null,
                            releaseDate = relDate.takeIf { it.isNotBlank() },
                            year = year,
                            voteAverage = rating,
                            character = character,
                            department = "Acting",
                            job = null,
                            overview = overview,
                            popularity = popularity
                        )
                    )
                }
            }

            // 2. Crew credits
            val crewArr = combinedCredits?.optJSONArray("crew")
            if (crewArr != null) {
                for (i in 0 until crewArr.length()) {
                    val c = crewArr.getJSONObject(i)
                    val creditId = c.getInt("id")
                    val mediaType = c.optString("media_type", "movie")
                    val title = if (mediaType == "tv") c.optString("name") else c.optString("title")
                    if (title.isBlank()) continue

                    val poster = c.optString("poster_path", null).takeIf { !it.isNullOrBlank() }
                    val backdrop = c.optString("backdrop_path", null).takeIf { !it.isNullOrBlank() }
                    val relDate = if (mediaType == "tv") c.optString("first_air_date", "") else c.optString("release_date", "")
                    val year = if (relDate.length >= 4) relDate.substring(0, 4) else null
                    val voteAvg = c.optDouble("vote_average", 0.0)
                    val rating = if (voteAvg > 0.0) String.format("%.1f", voteAvg) else null
                    val department = c.optString("department", "Crew")
                    val job = c.optString("job", null).takeIf { !it.isNullOrBlank() }
                    val overview = c.optString("overview", null).takeIf { !it.isNullOrBlank() }
                    val popularity = c.optDouble("popularity", 0.0)

                    rawCreditsList.add(
                        PersonCredit(
                            id = creditId,
                            title = title,
                            mediaType = mediaType,
                            posterPath = if (poster != null) "https://image.tmdb.org/t/p/w500$poster" else null,
                            backdropPath = if (backdrop != null) "https://image.tmdb.org/t/p/original$backdrop" else null,
                            releaseDate = relDate.takeIf { it.isNotBlank() },
                            year = year,
                            voteAverage = rating,
                            character = null,
                            department = department,
                            job = job,
                            overview = overview,
                            popularity = popularity
                        )
                    )
                }
            }

            // Deduplicate items (prefer acting role if present) and sort by popularity
            val distinctCredits = rawCreditsList
                .groupBy { "${it.mediaType}_${it.id}" }
                .map { (_, list) ->
                    list.firstOrNull { it.character != null } ?: list.first()
                }
                .sortedByDescending { it.popularity }

            PersonDetails(
                id = id,
                name = name,
                biography = biography,
                birthday = birthday,
                deathday = deathday,
                placeOfBirth = placeOfBirth,
                profilePath = profilePath,
                knownForDepartment = knownFor,
                credits = distinctCredits
            )
        } catch (e: Exception) {
            Log.e("TmdbHelper", "Error fetching person details", e)
            null
        }
    }

    fun clearCache(context: Context) {
        TmdbDatabaseHelper(context).clearAll()
    }
}
