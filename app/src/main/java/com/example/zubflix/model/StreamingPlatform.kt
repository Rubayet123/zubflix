package com.example.zubflix.model

import android.content.Context
import org.json.JSONArray

data class StreamingPlatform(
    val id: String,
    val name: String,
    val tvNetworkId: Int?,
    val movieProviderId: Int?,
    val defaultRegion: String
)

object StreamingPlatformRegistry {

    private val defaultPlatforms = listOf(
        StreamingPlatform("netflix", "Netflix", 213, 8, "US"),
        StreamingPlatform("prime_video", "Amazon Prime Video", 1024, 119, "US"),
        StreamingPlatform("disney_plus", "Disney+", 2739, 337, "US"),
        StreamingPlatform("apple_tv_plus", "Apple TV+", 2552, 350, "US"),
        StreamingPlatform("hbo_max", "HBO / Max", 49, 1899, "US"),
        StreamingPlatform("hulu", "Hulu", 453, 15, "US"),
        StreamingPlatform("paramount_plus", "Paramount+", 4330, 531, "US"),
        StreamingPlatform("peacock", "Peacock", 3353, 386, "US"),
        StreamingPlatform("bbc_iplayer", "BBC iPlayer", 4, 338, "GB"),
        StreamingPlatform("mubi", "MUBI", null, 11, "US"),
        StreamingPlatform("crunchyroll", "Crunchyroll", 1112, 283, "US"),
        StreamingPlatform("discovery_plus", "Discovery+", 4353, 433, "US"),
        StreamingPlatform("curiosity_stream", "CuriosityStream", 1267, 190, "US"),
        StreamingPlatform("national_geographic", "National Geographic", 43, 337, "US"),
        StreamingPlatform("jiohotstar", "JioHotstar", 3919, 220, "IN"),
        StreamingPlatform("sonyliv", "Sony LIV", 2271, 237, "IN"),
        StreamingPlatform("zee5", "ZEE5", 1516, 232, "IN"),
        StreamingPlatform("hoichoi", "Hoichoi", 3057, 315, "IN")
    )

    @Volatile
    private var loadedPlatforms: List<StreamingPlatform>? = null

    fun getPlatforms(context: Context? = null): List<StreamingPlatform> {
        loadedPlatforms?.let { return it }
        if (context == null) return defaultPlatforms

        return try {
            val jsonString = context.assets.open("streaming_platforms.json")
                .bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<StreamingPlatform>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                val tvNetworkId = if (obj.isNull("tvNetworkId")) null else obj.optInt("tvNetworkId")
                val movieProviderId = if (obj.isNull("movieProviderId")) null else obj.optInt("movieProviderId")
                val defaultRegion = obj.optString("defaultRegion", "US")
                list.add(StreamingPlatform(id, name, tvNetworkId, movieProviderId, defaultRegion))
            }
            loadedPlatforms = list
            list
        } catch (e: Exception) {
            defaultPlatforms
        }
    }

    fun findPlatform(id: String, context: Context? = null): StreamingPlatform? {
        return getPlatforms(context).find { it.id == id }
    }
}
