package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult

interface LocalScraper {
    val id: String
    val name: String
    val description: String
    suspend fun getStreams(type: String, meta: MediaMeta, season: Int?, episode: Int?): List<StreamResult>
}
