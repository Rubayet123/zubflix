package com.example.zubflix.cloudstream

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class CloudStreamRepo(
    val name: String,
    val url: String,
    val pluginUrl: String? = null,
    val description: String? = null,
    val iconUrl: String? = null
)

@Keep
data class CloudStreamPluginManifest(
    val name: String = "",
    @SerializedName("url", alternate = ["pluginUrl"]) val pluginUrl: String = "",
    val version: Int = 1,
    val description: String? = null,
    val authors: List<String>? = emptyList(),
    val language: String? = "en",
    val status: Int = 1, // 1 = OK, 3 = Down
    @SerializedName("tvTypes", alternate = ["types"]) val tvTypes: List<String>? = emptyList(),
    @SerializedName("fileSize") val fileSize: Long? = 0L,
    val iconUrl: String? = null,
    val repoName: String? = null
)

@Keep
data class InstalledCloudStreamPlugin(
    val id: String, // Unique plugin key
    val name: String,
    val filePath: String, // Local path to downloaded .cs3/.apk/.dex
    val mainClass: String, // Reflection entrypoint class
    val version: Int = 1,
    val author: String? = "Unknown",
    val description: String? = null,
    val isEnabled: Boolean = true,
    val repoUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)
