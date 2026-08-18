package com.example.zubflix.sources

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class InMemoryCookieJar : CookieJar {
    private val cookieStore = HashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val currentCookies = cookieStore[host] ?: ArrayList()
        val newCookies = cookies.toMutableList()
        val mergedCookies = currentCookies.filter { existing ->
            newCookies.none { new -> new.name == existing.name }
        }.toMutableList()
        mergedCookies.addAll(newCookies)
        cookieStore[host] = mergedCookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        return cookieStore[host] ?: emptyList()
    }
}
