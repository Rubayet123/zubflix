import sys
import re

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'r') as f:
    content = f.read()

# Replace signature
old_sig = """    private fun playVideo(
        title: String,
        url: String,
        allUrls: List<String> = listOf(url),
        streamName: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null
    ) {"""

new_sig = """    private fun playVideo(
        title: String,
        url: String,
        allUrls: List<String> = listOf(url),
        streamName: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        imdbId: String? = null,
        allNames: List<String> = emptyList()
    ) {"""

content = content.replace(old_sig, new_sig)

# Add putStringArrayListExtra("VIDEO_NAMES", ArrayList(allNames))
old_intent = """        val intent = Intent(this, PlayerActivity::class.java).apply {
            putStringArrayListExtra("VIDEO_URLS", ArrayList(allUrls))
            putExtra("VIDEO_TITLE", title)"""

new_intent = """        val intent = Intent(this, PlayerActivity::class.java).apply {
            putStringArrayListExtra("VIDEO_URLS", ArrayList(allUrls))
            putStringArrayListExtra("VIDEO_NAMES", ArrayList(allNames))
            putExtra("VIDEO_TITLE", title)"""

content = content.replace(old_intent, new_intent)

# Replace playVideo call for single source resolution
content = content.replace(
    "playVideo(title, finalUrl, resolvedSources.values.toList(), rawName, seasonNumber, episodeNumber, imdbId)",
    "playVideo(title, finalUrl, resolvedSources.values.toList(), rawName, seasonNumber, episodeNumber, imdbId, resolvedSources.keys.toList())"
)

# Replace playVideo call for multiple source resolution
content = content.replace(
    "val allUrls = sourceList.map { it.second }",
    "val allUrls = sourceList.map { it.second }\n                    val allNames = sourceList.map { it.first }"
)
content = content.replace(
    "playVideo(title, item.second, allUrls, item.first, seasonNumber, episodeNumber, imdbId)",
    "playVideo(title, item.second, allUrls, item.first, seasonNumber, episodeNumber, imdbId, allNames)"
)

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'w') as f:
    f.write(content)
