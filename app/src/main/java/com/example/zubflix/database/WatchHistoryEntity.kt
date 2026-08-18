package com.example.zubflix.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watch_history",
    indices = [Index(value = ["lastWatchedTimestamp"])]
)
data class WatchHistoryEntity(
    @PrimaryKey
    val itemId: String,
    val title: String,
    val imageUrl: String?,
    val isSeries: Boolean,
    val lastWatchedTimestamp: Long,
    val sourceName: String,
    
    // Playback position tracking
    val currentPosition: Long = 0L,  // Current playback position in milliseconds
    val totalDuration: Long = 0L,     // Total video duration in milliseconds
    val watchPercentage: Float = 0f,  // Percentage watched (0-100)
    val streamUrl: String? = null     // Last played stream URL for direct playback from Continue Watching
)
