package com.example.zubflix.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ActiveStreamManager {
    data class StreamItem(val name: String, val url: String)

    private val _currentStreams = MutableStateFlow<List<StreamItem>>(emptyList())
    val currentStreams: StateFlow<List<StreamItem>> = _currentStreams

    fun setStreams(context: Context, streams: List<StreamItem>) {
        val sorted = streams.sortedByDescending { PlaybackSettings.getAutoPlayStreamScore(context, it.name) }
        _currentStreams.value = sorted
    }

    fun addStreams(context: Context, newStreams: List<StreamItem>) {
        val existing = _currentStreams.value.toMutableList()
        val existingUrls = existing.map { it.url }.toSet()
        val filtered = newStreams.filter { it.url !in existingUrls }
        if (filtered.isNotEmpty()) {
            existing.addAll(filtered)
            val sorted = existing.sortedByDescending { PlaybackSettings.getAutoPlayStreamScore(context, it.name) }
            _currentStreams.value = sorted
        }
    }

    fun clear() {
        _currentStreams.value = emptyList()
    }
}
