package com.example

import org.junit.Test
import androidx.media3.datasource.DefaultHttpDataSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ApiTest {
    @Test
    fun testHeaders() {
        val factory = DefaultHttpDataSource.Factory()
        val json = """{"User-Agent": "Test", "Referer": "Test"}"""
        val type = object : TypeToken<Map<String, String>>() {}.type
        val map: Map<String, String> = Gson().fromJson(json, type)
        factory.setDefaultRequestProperties(map)
        println("Success")
    }
}
