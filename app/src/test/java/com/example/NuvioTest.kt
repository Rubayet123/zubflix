package com.example

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NuvioTest {
    @Test
    fun testNuvio() {
        val jsonStr = """
            {
                "streams": [
                    {
                        "name": "Nuvio Fast",
                        "title": "1080p | Stream 1",
                        "url": "https://example.com/stream.m3u8"
                    }
                ]
            }
        """.trimIndent()
        val json = JSONObject(jsonStr)
        val streams = json.optJSONArray("streams") ?: return
        
        for (i in 0 until streams.length()) {
            val stream = streams.getJSONObject(i)
            val streamUrl = stream.optString("url")
            if (streamUrl.isNotEmpty() && streamUrl != "null") {
                val rawName = stream.optString("name", "")
                val rawTitle = stream.optString("title", "")
                println("FOUND: $rawName - $rawTitle -> $streamUrl")
            }
        }
    }
}
