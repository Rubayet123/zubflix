package com.example.zubflix.stremio

data class StremioAddon(
    val manifestUrl: String,
    val name: String,
    val description: String?,
    val version: String?,
    val resources: List<String>,
    private var enabled: Boolean? = true
) {
    var isEnabled: Boolean
        get() = enabled ?: true
        set(value) {
            enabled = value
        }
}

data class StremioManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String? = null,
    val resources: List<Any>? = null,
    val types: List<String>? = null
)

data class StremioSubtitlesResponse(
    val subtitles: List<StremioSubtitle>? = null
)

data class StremioSubtitle(
    val id: String? = null,
    val lang: String,
    val url: String,
    val title: String? = null
)
