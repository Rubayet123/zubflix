package com.example.netflix.model

import com.google.gson.JsonElement

data class Post(
    val id: Int,
    val name: String?,
    val title: String?,
    val imageSm: String?,
    val image: String?,
    val metaData: String?,
    val content: JsonElement?,
    val quality: String?,
    val year: String?,
    val watchTime: String?
)

data class CircleFtpResponse(
    val posts: List<Post>
)
