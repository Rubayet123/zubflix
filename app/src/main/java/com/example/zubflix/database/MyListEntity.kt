package com.example.zubflix.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "my_list")
data class MyListEntity(
    @PrimaryKey
    val itemId: String,
    val title: String,
    val imageUrl: String?,
    val isSeries: Boolean,
    val sourceName: String,
    val addedTimestamp: Long = System.currentTimeMillis()
)
