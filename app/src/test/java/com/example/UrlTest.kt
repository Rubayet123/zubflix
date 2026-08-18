package com.example

import org.junit.Test
import okhttp3.Request
import okhttp3.OkHttpClient

class UrlTest {
    @Test
    fun testUrl() {
        val url = "https://pengu.uk/%7B%22source_111477%22%3A%22on%22%2C%22source_4khdhub%22%3A%22on%22%2C%22source_moviebox%22%3A%22on%22%2C%22source_moviesdrives%22%3A%22on%22%2C%22source_vaplayer%22%3A%22on%22%2C%22source_hdghartv%22%3A%22on%22%2C%22res_1080%22%3A%22on%22%2C%22res_720%22%3A%22on%22%2C%22disable_direct%22%3A%22on%22%7D/stream/movie/tt37287335.json"
        try {
            val req = Request.Builder().url(url).build()
            println("OKHTTP URL: ${req.url}")
            
            val client = OkHttpClient()
            val resp = client.newCall(req).execute()
            println("CODE: ${resp.code}")
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
        }
    }
}
