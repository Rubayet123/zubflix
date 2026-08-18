package com.example.zubflix.model

import android.content.Context
import org.json.JSONArray

object FilterConfigRepository {

    @Volatile
    private var movieGenresCache: List<GenreFilterItem>? = null

    @Volatile
    private var tvGenresCache: List<GenreFilterItem>? = null

    @Volatile
    private var regionsCache: List<RegionFilterItem>? = null

    @Volatile
    private var networksCache: List<NetworkFilterItem>? = null

    @Volatile
    private var sortOptionsCache: List<SortOptionFilterItem>? = null

    fun getMovieGenres(context: Context? = null): List<GenreFilterItem> {
        movieGenresCache?.let { return it }
        if (context == null) return defaultMovieGenres
        return try {
            val json = loadJsonAsset(context, "filters/movie_genres.json")
            val array = JSONArray(json)
            val list = mutableListOf<GenreFilterItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(GenreFilterItem(obj.getString("id"), obj.getString("name")))
            }
            movieGenresCache = list
            list
        } catch (e: Exception) {
            defaultMovieGenres
        }
    }

    fun getTvGenres(context: Context? = null): List<GenreFilterItem> {
        tvGenresCache?.let { return it }
        if (context == null) return defaultTvGenres
        return try {
            val json = loadJsonAsset(context, "filters/tv_genres.json")
            val array = JSONArray(json)
            val list = mutableListOf<GenreFilterItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(GenreFilterItem(obj.getString("id"), obj.getString("name")))
            }
            tvGenresCache = list
            list
        } catch (e: Exception) {
            defaultTvGenres
        }
    }

    fun getRegions(context: Context? = null): List<RegionFilterItem> {
        regionsCache?.let { return it }
        if (context == null) return defaultRegions
        return try {
            val json = loadJsonAsset(context, "filters/regions.json")
            val array = JSONArray(json)
            val list = mutableListOf<RegionFilterItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    RegionFilterItem(
                        code = obj.getString("code"),
                        name = obj.getString("name"),
                        voteTier = obj.optString("voteTier", "small")
                    )
                )
            }
            regionsCache = list
            list
        } catch (e: Exception) {
            defaultRegions
        }
    }

    fun getNetworks(context: Context? = null): List<NetworkFilterItem> {
        networksCache?.let { return it }
        if (context == null) return defaultNetworks
        return try {
            val json = loadJsonAsset(context, "filters/networks.json")
            val array = JSONArray(json)
            val list = mutableListOf<NetworkFilterItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val tvNetworkId = if (obj.isNull("tvNetworkId")) null else obj.optInt("tvNetworkId")
                val movieProviderId = if (obj.isNull("movieProviderId")) null else obj.optInt("movieProviderId")
                val defaultRegion = obj.optString("defaultRegion", "US")
                list.add(NetworkFilterItem(id, name, tvNetworkId, movieProviderId, defaultRegion))
            }
            networksCache = list
            list
        } catch (e: Exception) {
            defaultNetworks
        }
    }

    fun getSortOptions(context: Context? = null): List<SortOptionFilterItem> {
        sortOptionsCache?.let { return it }
        if (context == null) return defaultSortOptions
        return try {
            val json = loadJsonAsset(context, "filters/sort_options.json")
            val array = JSONArray(json)
            val list = mutableListOf<SortOptionFilterItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(SortOptionFilterItem(obj.getString("id"), obj.getString("title")))
            }
            sortOptionsCache = list
            list
        } catch (e: Exception) {
            defaultSortOptions
        }
    }

    fun findNetwork(id: String, context: Context? = null): NetworkFilterItem? {
        return getNetworks(context).find { it.id == id }
    }

    fun findRegion(code: String, context: Context? = null): RegionFilterItem? {
        return getRegions(context).find { it.code == code }
    }

    private fun loadJsonAsset(context: Context, path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    private val defaultMovieGenres = listOf(
        GenreFilterItem("all", "All Genres"),
        GenreFilterItem("28", "Action"),
        GenreFilterItem("12", "Adventure"),
        GenreFilterItem("16", "Animation"),
        GenreFilterItem("35", "Comedy"),
        GenreFilterItem("80", "Crime"),
        GenreFilterItem("99", "Documentary"),
        GenreFilterItem("18", "Drama"),
        GenreFilterItem("10751", "Family"),
        GenreFilterItem("14", "Fantasy"),
        GenreFilterItem("36", "History"),
        GenreFilterItem("27", "Horror"),
        GenreFilterItem("10402", "Music"),
        GenreFilterItem("9648", "Mystery"),
        GenreFilterItem("10749", "Romance"),
        GenreFilterItem("878", "Sci-Fi"),
        GenreFilterItem("10770", "TV Movie"),
        GenreFilterItem("53", "Thriller"),
        GenreFilterItem("10752", "War"),
        GenreFilterItem("37", "Western")
    )

    private val defaultTvGenres = listOf(
        GenreFilterItem("all", "All Genres"),
        GenreFilterItem("10759", "Action & Adventure"),
        GenreFilterItem("16", "Animation"),
        GenreFilterItem("35", "Comedy"),
        GenreFilterItem("80", "Crime"),
        GenreFilterItem("99", "Documentary"),
        GenreFilterItem("18", "Drama"),
        GenreFilterItem("10751", "Family"),
        GenreFilterItem("10762", "Kids"),
        GenreFilterItem("9648", "Mystery"),
        GenreFilterItem("10763", "News"),
        GenreFilterItem("10764", "Reality"),
        GenreFilterItem("10765", "Sci-Fi & Fantasy"),
        GenreFilterItem("10766", "Soap"),
        GenreFilterItem("10767", "Talk"),
        GenreFilterItem("10768", "War & Politics"),
        GenreFilterItem("37", "Western")
    )

    private val defaultRegions = listOf(
        RegionFilterItem("all", "All Regions", "small"),
        RegionFilterItem("US", "United States", "large"),
        RegionFilterItem("GB", "United Kingdom", "large"),
        RegionFilterItem("IN", "India", "medium"),
        RegionFilterItem("KR", "South Korea", "large"),
        RegionFilterItem("JP", "Japan", "large")
    )

    private val defaultNetworks = listOf(
        NetworkFilterItem("all", "All Networks", null, null, "US"),
        NetworkFilterItem("netflix", "Netflix", 213, 8, "US"),
        NetworkFilterItem("prime_video", "Amazon Prime Video", 1024, 119, "US"),
        NetworkFilterItem("disney_plus", "Disney+", 2739, 337, "US"),
        NetworkFilterItem("apple_tv_plus", "Apple TV+", 2552, 350, "US"),
        NetworkFilterItem("hbo_max", "HBO / Max", 49, 1899, "US"),
        NetworkFilterItem("hulu", "Hulu", 453, 15, "US"),
        NetworkFilterItem("paramount_plus", "Paramount+", 4330, 531, "US"),
        NetworkFilterItem("peacock", "Peacock", 3353, 386, "US"),
        NetworkFilterItem("bbc_iplayer", "BBC iPlayer", 4, 338, "GB"),
        NetworkFilterItem("mubi", "MUBI", null, 11, "US"),
        NetworkFilterItem("crunchyroll", "Crunchyroll", 1112, 283, "US"),
        NetworkFilterItem("discovery_plus", "Discovery+", 4353, 433, "US"),
        NetworkFilterItem("curiosity_stream", "CuriosityStream", 1267, 190, "US"),
        NetworkFilterItem("national_geographic", "National Geographic", 43, 337, "US"),
        NetworkFilterItem("jiohotstar", "JioHotstar", 3919, 220, "IN"),
        NetworkFilterItem("sonyliv", "Sony LIV", 2271, 237, "IN"),
        NetworkFilterItem("zee5", "ZEE5", 1516, 232, "IN"),
        NetworkFilterItem("hoichoi", "Hoichoi", 3057, 315, "IN")
    )

    private val defaultSortOptions = listOf(
        SortOptionFilterItem("popularity.desc", "Popular"),
        SortOptionFilterItem("primary_release_date.desc", "New Releases"),
        SortOptionFilterItem("vote_average.desc", "Top Rated"),
        SortOptionFilterItem("trending", "Trending")
    )
}
