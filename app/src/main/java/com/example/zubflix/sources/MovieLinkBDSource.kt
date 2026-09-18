package com.example.zubflix.sources

import com.example.zubflix.model.StreamingCategory
import com.example.zubflix.model.StreamingEpisode
import com.example.zubflix.model.StreamingItem
import com.example.zubflix.model.StreamingSeason
import com.example.zubflix.model.StreamingSource
import com.example.zubflix.provider.sdk.ContentProvider
import com.example.zubflix.provider.sdk.LegacyStreamingSourceAdapter
import com.example.zubflix.provider.sdk.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MovieLinkBDSource : StreamingSource {
    override val name: String = "MovieLinkBD"
    private val mainUrl: String = "https://movielinkbd.one"

    private val client = createUnsafeOkHttpClient()

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        } catch (e: Exception) {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "max-age=0"
    )

    private var resolvedBase: String? = null

    private suspend fun getBase(): String = withContext(Dispatchers.IO) {
        resolvedBase?.let { return@withContext it }

        // Seeds: MovieLinkBD active domains and unblocked proxy gateways
        val seeds = listOf(
            "https://tg522w.movielinkbd.pw",
            "https://8hk778.movielinkbd.pw",
            "https://movielinkbd.pw",
            "https://movielinkbd.one",
            "https://movielinkbd.com",
            "https://tg522w.movielinkbd.li",
            "https://movielinkbd.li",
            "https://movielinkbd.tv",
            "https://movielinkbd.work",
            "https://movielinkbd.shop"
        )

        for (seed in seeds) {
            try {
                val requestBuilder = Request.Builder().url(seed)
                headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                val request = requestBuilder.build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 302 && response.code != 301) {
                        return@use
                    }

                    val finalUrl = response.request.url.toString()
                    val responseBodyStr = response.body?.string() ?: ""
                    val doc = Jsoup.parse(responseBodyStr, finalUrl)

                    // 1. If this seed page already contains movie cards, this is directly the active base!
                    val hasCards = doc.select("div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card, a[href*='/movie/']").isNotEmpty()
                    if (hasCards) {
                        val uri = java.net.URI(finalUrl.trimEnd('/'))
                        val base = "${uri.scheme}://${uri.host}"
                        resolvedBase = base
                        return@withContext base
                    }

                    // 2. If it is a landing/portal hub (such as movielinkbd.one), look for candidate target links
                    val candidates = mutableListOf<String>()

                    // Priority 1: Proxy CTA link (class="cta-proxy", or containing "movielinkbd.pw")
                    doc.select("a.cta-proxy, a[href*='movielinkbd.pw'], a:contains(Proxy Site), a:contains(প্রক্সি)").forEach { a ->
                        val href = a.attr("abs:href").trim()
                        if (href.isNotEmpty()) candidates.add(href)
                    }

                    // Priority 2: New site CTA link
                    doc.select("a.cta-main, a:contains(New Site), a:contains(Visit MovieLinkBD)").forEach { a ->
                        val href = a.attr("abs:href").trim()
                        if (href.isNotEmpty()) candidates.add(href)
                    }

                    // Priority 3: Other official mirrors
                    doc.select(".mlbd-home-mirrors a, a[href*='movielinkbd']").forEach { a ->
                        val href = a.attr("abs:href").trim()
                        if (href.isNotEmpty() && !href.contains("movielinkbd.one") && !href.contains("movielinkbd.com")) {
                            candidates.add(href)
                        }
                    }

                    // Verify candidate targets
                    for (cand in candidates.distinct()) {
                        try {
                            val candReq = Request.Builder().url(cand).apply {
                                headers.forEach { (k, v) -> addHeader(k, v) }
                            }.build()
                            client.newCall(candReq).execute().use { candResp ->
                                if (candResp.isSuccessful || candResp.code == 302 || candResp.code == 301) {
                                    val candFinal = candResp.request.url.toString()
                                    val candHtml = candResp.body?.string() ?: ""
                                    if (candHtml.contains("movie-card") || candHtml.contains("/movie/") || candHtml.contains("MovieLinkBD")) {
                                        val uri = java.net.URI(candFinal.trimEnd('/'))
                                        val base = "${uri.scheme}://${uri.host}"
                                        resolvedBase = base
                                        return@withContext base
                                    }
                                }
                            }
                        } catch (ce: Exception) {
                            // Candidate unreachable, try next
                        }
                    }
                }
            } catch (e: Exception) {
                // Seed failed, continue to next seed
            }
        }

        // Ultimate fallback
        val ultimateFallback = "https://8hk778.movielinkbd.pw"
        resolvedBase = ultimateFallback
        ultimateFallback
    }

    private fun fixUrlDomain(url: String, base: String): String {
        if (url.isBlank()) return url
        return try {
            val uri = java.net.URI(url)
            val host = uri.host
            if (host == null || !host.contains("movielinkbd") || host.contains("play")) {
                url
            } else {
                val path = uri.rawPath ?: ""
                val query = uri.rawQuery?.let { "?$it" } ?: ""
                val fragment = uri.rawFragment?.let { "#$it" } ?: ""
                "${base.trimEnd('/')}/${path.trimStart('/')}$query$fragment"
            }
        } catch (e: Exception) {
            url
        }
    }

    private suspend fun executeGet(url: String): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403) {
                resolvedBase = null
            }
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string() ?: ""
        }
    }

    private fun parseMovieCards(doc: org.jsoup.nodes.Document, base: String): List<StreamingItem> {
        val results = mutableListOf<StreamingItem>()
        val cards = doc.select("div.movie-item, div.item-box, div.film-item, div.post-item, .movie-card")
        val movieLinkPattern = "a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/drama/'], a[href*='/download18plus/']"

        if (cards.isNotEmpty()) {
            cards.forEach { card ->
                val titleAnchor = card.selectFirst(".content a.title, .content a[href], a.title, [class*='title'] a")
                val fallbackAnchor = card.selectFirst(movieLinkPattern)
                val aTag = titleAnchor ?: fallbackAnchor ?: return@forEach

                var href = aTag.attr("href").trim().ifEmpty { fallbackAnchor?.attr("href")?.trim() ?: "" }
                if (href.isBlank() || href.contains("/page/") || href.contains("/type/") ||
                    href.contains("/category/") || href.contains("/genre/") || href.contains("/language/")) return@forEach

                if (!href.startsWith("http")) {
                    href = base + (if (href.startsWith("/")) "" else "/") + href
                }

                val title = card.selectFirst(".content .title, a.title, .title, .movie-title, h3, h2")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: titleAnchor?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("a[title]")?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("img")?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                    ?: aTag.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: ""
                if (title.isBlank() || title.equals("Movie", ignoreCase = true) || title.length < 2) return@forEach

                val img = card.selectFirst("img")
                var poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("mlbd_load") }
                if (poster != null && poster.isNotBlank() && !poster.startsWith("http")) {
                    poster = base + (if (poster.startsWith("/")) "" else "/") + poster
                }

                val isSeries = href.contains("/series/") || href.contains("/anime/") || href.contains("/drama/") ||
                        title.contains("Season", ignoreCase = true) || title.contains("Episode", ignoreCase = true) ||
                        title.contains("Ep ", ignoreCase = true) || title.contains("Ep0", ignoreCase = true)
                val cleanTitle = title.substringBefore("[").substringBefore("(").trim()

                results.add(
                    StreamingItem(
                        id = href,
                        title = cleanTitle,
                        isSeries = isSeries,
                        imageUrl = poster?.takeIf { it.isNotBlank() },
                        sourceName = name
                    )
                )
            }
            if (results.isNotEmpty()) return results
        }

        val seen = mutableSetOf<String>()
        doc.select(movieLinkPattern).forEach { a ->
            var href = a.attr("href").trim()
            if (href.isBlank() || href.contains("/page/") || href.contains("/type/") ||
                href.contains("/category/") || href.contains("/genre/") || href.contains("/language/")) return@forEach

            if (!href.startsWith("http")) {
                href = base + (if (href.startsWith("/")) "" else "/") + href
            }
            if (!seen.add(href)) return@forEach

            val img = a.selectFirst("img")
            var poster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("mlbd_load") }
            if (poster != null && poster.isNotEmpty() && !poster.startsWith("http")) {
                poster = base + (if (poster.startsWith("/")) "" else "/") + poster
            }

            val titleEl = a.parent()?.selectFirst(".content .title, a.title, .title, .movie-title, h3, h2, [class*='name']")
            val title = titleEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.text().trim().takeIf { it.isNotEmpty() }
                ?: a.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach

            if (title.length < 3 || title.matches(Regex("""^\d+$"""))) return@forEach

            val isSeries = href.contains("/series/") || href.contains("/anime/") || href.contains("/drama/") ||
                    title.contains("Season", ignoreCase = true) || title.contains("Episode", ignoreCase = true) ||
                    title.contains("Ep ", ignoreCase = true)
            val cleanTitle = title.substringBefore("[").substringBefore("(").trim()

            results.add(
                StreamingItem(
                    id = href,
                    title = cleanTitle,
                    isSeries = isSeries,
                    imageUrl = poster?.takeIf { it.isNotEmpty() },
                    sourceName = name
                )
            )
        }

        return results
    }

    override suspend fun getHomeCategories(): List<StreamingCategory> = withContext(Dispatchers.IO) {
        val base = getBase()
        val categoriesToFetch = listOf(
            "$base/" to "Recently Added",
            "$base/type/movies" to "All Movies",
            "$base/type/series" to "Web Series",
            "$base/language/bangla" to "Bangla Movies",
            "$base/language/bangla-dubbed" to "Bangla Dubbed",
            "$base/language/hindi" to "Hindi Movies",
            "$base/language/dual-audio" to "Dual Audio",
            "$base/language/english" to "English Movies",
            "$base/southIndian" to "South Indian Movies",
            "$base/language/korean" to "Korean",
            "$base/drama" to "K/J/C Drama",
            "$base/anime" to "Anime Zone",
            "$base/ongoing" to "Ongoing Series",
            "$base/genre/action" to "Action",
            "$base/genre/thriller" to "Thriller",
            "$base/genre/horror" to "Horror",
            "$base/genre/romance" to "Romance"
        )

        val tasks = categoriesToFetch.map { (url, catTitle) ->
            async {
                try {
                    val html = executeGet(url)
                    val doc = Jsoup.parse(html, base)
                    val items = parseMovieCards(doc, base)
                    if (items.isNotEmpty()) {
                        StreamingCategory(id = url, title = catTitle, items = items)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
        }

        tasks.awaitAll().filterNotNull()
    }

    override suspend fun getCategoryContent(categoryId: String, page: Int): List<StreamingItem> = withContext(Dispatchers.IO) {
        val base = getBase()
        val url = if (page <= 1) {
            categoryId
        } else {
            val cleanBase = categoryId.trimEnd('/')
            if (cleanBase.contains("?q=")) {
                "$cleanBase&page=$page"
            } else if (cleanBase.contains("?search=")) {
                val parts = cleanBase.split("?search=")
                "${parts[0]}/page/$page/?search=${parts[1]}"
            } else if (cleanBase.contains("?")) {
                "$cleanBase&page=$page"
            } else {
                "$cleanBase/page/$page"
            }
        }

        try {
            val fixedUrl = fixUrlDomain(url, base)
            val html = executeGet(fixedUrl)
            val doc = Jsoup.parse(html, base)
            parseMovieCards(doc, base)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<StreamingItem> = withContext(Dispatchers.IO) {
        val base = getBase()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val primaryUrl = "$base/search?q=$encoded"
        try {
            val html = executeGet(primaryUrl)
            val doc = Jsoup.parse(html, base)
            val results = parseMovieCards(doc, base)
            if (results.isNotEmpty()) {
                return@withContext results
            }
        } catch (e: Exception) {
            // Try fallback search URL
        }

        try {
            val fallbackUrl = "$base/?search=$encoded"
            val html = executeGet(fallbackUrl)
            val doc = Jsoup.parse(html, base)
            parseMovieCards(doc, base)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(id: String): StreamingItem? = withContext(Dispatchers.IO) {
        try {
            val base = getBase()
            val targetUrl = if (id.startsWith("http")) id else "$base/$id"
            val fixedTargetUrl = fixUrlDomain(targetUrl, base)
            val html = executeGet(fixedTargetUrl)
            val doc = Jsoup.parse(html, fixedTargetUrl)

            val rawTitle = doc.selectFirst("h2.mb-2, h1, .movie-title, .film-title")?.text()?.trim()
                ?: doc.title().substringBefore("•").substringBefore("|").substringBefore("on MovieLinkBD").trim()

            val cleanTitle = rawTitle.substringBefore("[").substringBefore("(").trim()
            val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)

            val poster = doc.select("img")
                .firstOrNull { el ->
                    val src = el.attr("data-src").ifEmpty { el.attr("src") }
                    src.contains("poster", ignoreCase = true) || src.contains("uploads", ignoreCase = true) || src.contains("i.imgbd.org", ignoreCase = true)
                }?.let { el -> el.attr("data-src").ifEmpty { el.attr("src") } }
                ?.takeIf { it.isNotEmpty() }

            fun metaVal(label: String): String? {
                return doc.select("li, p, span, div").firstOrNull { el ->
                    el.text().contains(label, ignoreCase = true)
                }?.text()?.substringAfter(":")?.trim()
            }

            val plot = doc.selectFirst(".storyline p, .storyline, [class*='story'] p, [class*='plot']")
                ?.text()?.trim()
                ?: metaVal("Storyline")

            val genreString = metaVal("Genre")
            val genres = genreString?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val castString = metaVal("Cast")
            val cast = castString?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val language = metaVal("Language")
            val score = doc.selectFirst("[class*='imdb'], [class*='rating']")?.text()
                ?.let { Regex("""[0-9.]+""").find(it)?.value }

            val fullPlot = buildString {
                language?.let { append("Language: $it\n") }
                if (genres.isNotEmpty()) append("Genre: ${genres.joinToString(", ")}\n")
                if (cast.isNotEmpty()) append("Cast: ${cast.joinToString(", ")}\n")
                plot?.let { append("\n$it") }
            }.trim()

            val isSeries = targetUrl.contains("/series/") || targetUrl.contains("/anime/") || targetUrl.contains("/drama/")
            val seasons = mutableListOf<StreamingSeason>()

            if (isSeries) {
                val epCards = doc.select(".ep-card, [data-ep], div.card:has(h5:contains(Episode)), div.card:has(h4:contains(Episode))")
                val episodes = mutableListOf<StreamingEpisode>()
                val seenEpNums = mutableSetOf<Int>()

                if (epCards.isNotEmpty()) {
                    epCards.forEach { card ->
                        val dataEpNum = card.attr("data-episode-number").toIntOrNull()
                        val dataEp = card.attr("data-ep")
                        val hText = card.selectFirst("h5, h4, h3")?.text() ?: ""
                        val epNumMatch = Regex("""(?:Episode|Ep|E)[^\d]*(\d+)""", RegexOption.IGNORE_CASE).find("$dataEp $hText")
                        val epNum = dataEpNum ?: epNumMatch?.groupValues?.get(1)?.toIntOrNull()
                        if (epNum != null && seenEpNums.add(epNum)) {
                            episodes.add(
                                StreamingEpisode(
                                    title = "Episode $epNum",
                                    streamUrl = "$targetUrl|ep$epNum",
                                    overview = "Episode $epNum of $cleanTitle",
                                    stillUrl = poster
                                )
                            )
                        }
                    }
                    episodes.sortBy { ep ->
                        Regex("""\d+""").find(ep.title)?.value?.toIntOrNull() ?: 0
                    }
                }

                if (episodes.isEmpty()) {
                    val episodeSections = doc.select(
                        "div.episode-section, div.season-section, h3:contains(Episode), h4:contains(Episode), " +
                                "div[class*='episode'], div[class*='season'], strong:contains(Ep), b:contains(Ep)"
                    )

                    if (episodeSections.isNotEmpty()) {
                        var epCounter = 1
                        episodeSections.forEach { section ->
                            val sectionText = section.text()
                            val epRange = Regex("""(?:Ep|Episode)[^\d]*(\d+)(?:[^\d]+(\d+))?""", RegexOption.IGNORE_CASE)
                                .find(sectionText)
                            val start = epRange?.groupValues?.get(1)?.toIntOrNull() ?: epCounter
                            val end = epRange?.groupValues?.get(2)?.toIntOrNull() ?: start

                            for (epNum in start..end) {
                                if (seenEpNums.add(epNum)) {
                                    episodes.add(
                                        StreamingEpisode(
                                            title = "Episode $epNum",
                                            streamUrl = "$targetUrl|ep$epNum",
                                            overview = "Episode $epNum of $cleanTitle",
                                            stillUrl = poster
                                        )
                                    )
                                }
                                epCounter = epNum + 1
                            }
                        }
                    }
                }

                if (episodes.isEmpty()) {
                    val linkAnchors = doc.select("a[href*='/file/'], a[href*='/getLink/'], a[href*='/getWatch/']")
                    val count = if (linkAnchors.isNotEmpty()) linkAnchors.size else 1
                    for (epNum in 1..count) {
                        episodes.add(
                            StreamingEpisode(
                                title = "Episode $epNum",
                                streamUrl = "$targetUrl|ep$epNum",
                                overview = "Episode $epNum of $cleanTitle",
                                stillUrl = poster
                            )
                        )
                    }
                }

                val finalEpisodes = episodes.distinctBy { ep ->
                    Regex("""\d+""").find(ep.title)?.value?.toIntOrNull() ?: ep.title
                }

                seasons.add(
                    StreamingSeason(
                        title = "Season 1",
                        episodes = finalEpisodes,
                        seasonNumber = 1
                    )
                )
            }

            StreamingItem(
                id = targetUrl,
                title = cleanTitle,
                isSeries = isSeries,
                imageUrl = poster,
                description = fullPlot.ifBlank { null },
                streamUrl = if (!isSeries) targetUrl else null,
                seasons = if (isSeries) seasons else null,
                year = year,
                rating = score,
                genres = genres.ifEmpty { null },
                cast = cast.ifEmpty { null },
                sourceName = name
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun extractVideoLinksStreaming(
        data: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
        onStreamFound: suspend (streams: Map<String, String>) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val base = getBase()
            val parts = data.split("|ep")
            val targetUrl = fixUrlDomain(parts[0], base)
            val epNum = parts.getOrNull(1)?.toIntOrNull()

            val html = executeGet(targetUrl)
            val doc = Jsoup.parse(html, targetUrl)

            val isSeries = targetUrl.contains("/series/") || targetUrl.contains("/anime/") || targetUrl.contains("/drama/")

            val linkAnchors = doc.select("a[href*='/getLink/']")
            val watchAnchors = doc.select("a[href*='/getWatch/']")
            val fileAnchors = doc.select("a[href*='/file/']")

            val targetLinks = mutableListOf<Pair<String, String>>()

            if (!isSeries || epNum == null) {
                (fileAnchors + linkAnchors + watchAnchors).forEach { a ->
                    val href = a.attr("href").trim()
                    if (href.isNotEmpty()) {
                        val fullHref = if (href.startsWith("http")) href else "$base$href"
                        targetLinks.add(Pair(a.text().trim(), fullHref))
                    }
                }
            } else {
                val epCards = doc.select(".ep-card, [data-ep], div.card:has(h5:contains(Episode)), div.card:has(h4:contains(Episode))")
                if (epCards.isNotEmpty()) {
                    epCards.forEach { card ->
                        val dataEpNum = card.attr("data-episode-number").toIntOrNull()
                        val dataEp = card.attr("data-ep")
                        val hText = card.selectFirst("h5, h4, h3")?.text() ?: ""
                        val match = Regex("""(?:Episode|Ep|E)[^\d]*(\d+)""", RegexOption.IGNORE_CASE).find("$dataEp $hText")
                        val cardEpNum = dataEpNum ?: match?.groupValues?.get(1)?.toIntOrNull()
                        if (cardEpNum == epNum) {
                            card.select("a[href*='/file/'], a[href*='/getLink/'], a[href*='/getWatch/']").forEach { a ->
                                val href = a.attr("href").trim()
                                if (href.isNotEmpty()) {
                                    val fullHref = if (href.startsWith("http")) href else "$base$href"
                                    targetLinks.add(Pair(a.text().trim(), fullHref))
                                }
                            }
                        }
                    }
                }

                if (targetLinks.isEmpty()) {
                    val episodeSections = doc.select(
                        "div.episode-section, div.season-section, h3:contains(Episode), h4:contains(Episode), " +
                                "div[class*='episode'], div[class*='season'], strong:contains(Ep), b:contains(Ep)"
                    )

                    if (episodeSections.isNotEmpty()) {
                        episodeSections.forEach { section ->
                            val sectionText = section.text()
                            val epRange = Regex("""(?:Ep|Episode)[^\d]*(\d+)(?:[^\d]+(\d+))?""", RegexOption.IGNORE_CASE)
                                .find(sectionText)
                            val start = epRange?.groupValues?.get(1)?.toIntOrNull() ?: 1
                            val end = epRange?.groupValues?.get(2)?.toIntOrNull() ?: start

                            if (epNum in start..end) {
                                var sib = section.nextElementSibling()
                                while (sib != null && !sib.tagName().matches(Regex("""h[1-6]""", RegexOption.IGNORE_CASE))) {
                                    sib.select("a[href*='/file/'], a[href*='/getLink/'], a[href*='/getWatch/']").forEach { a ->
                                        val href = a.attr("href").trim()
                                        if (href.isNotEmpty()) {
                                            val fullHref = if (href.startsWith("http")) href else "$base$href"
                                            targetLinks.add(Pair(a.text().trim(), fullHref))
                                        }
                                    }
                                    sib = sib.nextElementSibling()
                                }
                            }
                        }
                    }
                }

                // If episode sections didn't yield specific links, search all anchors for matching Ep text
                if (targetLinks.isEmpty()) {
                    (fileAnchors + linkAnchors + watchAnchors).forEach { a ->
                        val aText = a.text().trim()
                        val epMatch = Regex("""(?:Ep|Episode|E)[^\d]*(\d+)""", RegexOption.IGNORE_CASE).find(aText)
                        val num = epMatch?.groupValues?.get(1)?.toIntOrNull()
                        if (num == epNum) {
                            val href = a.attr("href").trim()
                            if (href.isNotEmpty()) {
                                val fullHref = if (href.startsWith("http")) href else "$base$href"
                                targetLinks.add(Pair(aText, fullHref))
                            }
                        }
                    }
                }
            }

            var doneCount = 0
            val totalCount = targetLinks.size.coerceAtLeast(1)
            onProgress(0, totalCount)

            val sortedTargetLinks = targetLinks.sortedByDescending { (label, _) ->
                val lower = label.lowercase()
                when {
                    lower.contains("2160p") || lower.contains("4k") -> 2160
                    lower.contains("1080p") || lower.contains("fhd") -> 1080
                    lower.contains("720p") || lower.contains("hd") -> 720
                    lower.contains("480p") || lower.contains("sd") -> 480
                    lower.contains("360p") -> 360
                    else -> 0
                }
            }

            sortedTargetLinks.forEach { (label, url) ->
                val qualityLabel = extractQualityLabel(label)
                val itemResults = mutableMapOf<String, String>()

                if (url.contains("/getLink/")) {
                    try {
                        val linkHtml = executeGet(url)
                        val linkDoc = Jsoup.parse(linkHtml, url)
                        val fileAnchor = linkDoc.selectFirst("a[href*='/file/'], a[href*='/open/'], a[href*='token='], a[href*='/get/'], a:contains(Download), a:contains(Open)")
                        if (fileAnchor != null) {
                            val fileUrl = fileAnchor.attr("href").trim()
                            val fullFileUrl = if (fileUrl.startsWith("http")) fileUrl else "$base$fileUrl"
                            if (fileUrl.contains("/open/")) {
                                val resolved = resolveOpenUrl(fullFileUrl, base)
                                if (resolved != null) {
                                    val finalSize = extractSizeFromText(label, linkDoc.text()) ?: getUrlContentLength(resolved)
                                    val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                                    val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                                    val headersJson = com.google.gson.Gson().toJson(mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                                        "Referer" to "$base/"
                                    ))
                                    val sourceLabel = when {
                                        resolved.contains("r2.dev") || resolved.contains("cloudflarestorage.com") -> "Fast R2 Cloud"
                                        resolved.contains("instantcloud.org") -> "Instant Cloud"
                                        else -> "Direct Link"
                                    }
                                    itemResults["[MovieLinkBD] [$moniker] $sourceLabel Stream ($qualityLabel$sizeAttr | $sourceLabel)"] = "$resolved######$headersJson"
                                }
                            } else {
                                resolveFileUrl(fullFileUrl, qualityLabel, label, base, itemResults)
                            }
                        }

                        linkDoc.select("a[href]").forEach { a ->
                            val href = a.attr("href").trim()
                            if (href.startsWith("http") && !href.contains("movielinkbd") &&
                                !href.contains("telegram") && !href.contains("t.me") &&
                                !href.contains("facebook") && !href.contains("google.com")) {
                                val resolved = if (href.contains("r2.dev") || href.contains("cloudflarestorage.com") ||
                                    href.endsWith(".mp4", ignoreCase = true) || href.endsWith(".mkv", ignoreCase = true) ||
                                    href.endsWith(".m3u8", ignoreCase = true)) {
                                    href
                                } else {
                                    resolveOpenUrl(href, base)
                                }
                                if (resolved != null && (resolved.contains("r2.dev") || resolved.contains("cloudflarestorage") ||
                                    resolved.endsWith(".mp4", ignoreCase = true) || resolved.endsWith(".mkv", ignoreCase = true) ||
                                    resolved.endsWith(".m3u8", ignoreCase = true) || resolved.contains("token="))) {
                                    val finalSize = extractSizeFromText(label, linkDoc.text()) ?: getUrlContentLength(resolved)
                                    val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                                    val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                                    val headersJson = com.google.gson.Gson().toJson(mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                                        "Referer" to "$base/"
                                    ))
                                    itemResults["[MovieLinkBD] [$moniker] Mirror Server ($qualityLabel$sizeAttr | Mirror)"] = "$resolved######$headersJson"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else if (url.contains("/getWatch/")) {
                    try {
                        val watchHtml = executeGet(url)
                        val watchDoc = Jsoup.parse(watchHtml, url)
                        var videoSrc = watchDoc.selectFirst("video source, video[src]")?.attr("src")
                        if (videoSrc.isNullOrBlank()) {
                            val iframeSrc = watchDoc.selectFirst("iframe[src]")?.attr("src")
                            if (!iframeSrc.isNullOrBlank()) {
                                val fullIframeSrc = if (iframeSrc.startsWith("http")) iframeSrc else "$base$iframeSrc"
                                videoSrc = resolveOpenUrl(fullIframeSrc, base)
                            }
                        }
                        if (!videoSrc.isNullOrBlank()) {
                            val fullVideoSrc = if (videoSrc.startsWith("http")) videoSrc else "$base$videoSrc"
                            if (fullVideoSrc.contains("r2.dev") || fullVideoSrc.contains("cloudflarestorage.com") ||
                                fullVideoSrc.endsWith(".mp4", ignoreCase = true) || fullVideoSrc.endsWith(".mkv", ignoreCase = true) ||
                                fullVideoSrc.endsWith(".m3u8", ignoreCase = true) || fullVideoSrc.contains("token=")) {
                                val finalSize = extractSizeFromText(label, watchDoc.text()) ?: getUrlContentLength(fullVideoSrc)
                                val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                                val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                                val headersJson = com.google.gson.Gson().toJson(mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                                    "Referer" to "$base/"
                                ))
                                itemResults["[MovieLinkBD] [$moniker] Online Stream ($qualityLabel$sizeAttr | Watch Online)"] = "$fullVideoSrc######$headersJson"
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else if (url.contains("/file/")) {
                    resolveFileUrl(url, qualityLabel, label, base, itemResults)
                }

                doneCount++
                if (itemResults.isNotEmpty()) {
                    onStreamFound(itemResults)
                }
                onProgress(doneCount, totalCount)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun extractVideoLinks(data: String): Map<String, String> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()
        extractVideoLinksStreaming(
            data = data,
            onStreamFound = { streams ->
                results.putAll(streams)
            }
        )
        results
    }

    private suspend fun resolveOpenUrl(openUrl: String, base: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(openUrl)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            client.newCall(req).execute().use { response ->
                val finalUrl = response.request.url.toString()

                if (finalUrl.contains("r2.dev") || finalUrl.contains("cloudflarestorage.com") ||
                    finalUrl.contains("instantcloud.org") || finalUrl.endsWith(".mp4", ignoreCase = true) ||
                    finalUrl.endsWith(".mkv", ignoreCase = true) || finalUrl.endsWith(".m3u8", ignoreCase = true)) {
                    return@withContext finalUrl
                }

                val html = response.body?.string() ?: ""
                val doc = Jsoup.parse(html, finalUrl)

                val cdnAnchor = doc.selectFirst(
                    "a[href*='r2.dev'], a[href*='cloudflarestorage.com'], a[href*='instantcloud.org'], " +
                    "a[href*='.mp4'], a[href*='.mkv'], a[href*='.m3u8'], a:contains(Download), a:contains(Fast Cloud), " +
                    "source[src], video[src]"
                )

                if (cdnAnchor != null) {
                    val href = (cdnAnchor.attr("href").ifEmpty { cdnAnchor.attr("src") }).trim()
                    if (href.isNotEmpty()) {
                        return@withContext if (href.startsWith("http")) href else "$base$href"
                    }
                }

                val jsMatch = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(html)
                if (jsMatch != null) {
                    val target = jsMatch.groupValues[1]
                    if (target.startsWith("http")) return@withContext target else return@withContext "$base$target"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    private suspend fun resolveFileUrl(fileUrl: String, qualityLabel: String, label: String, base: String, results: MutableMap<String, String>) {
        try {
            val html = executeGet(fileUrl)
            val doc = Jsoup.parse(html, fileUrl)

            val extractedSize = extractSizeFromText(label, doc.text())

            val headersJson = com.google.gson.Gson().toJson(mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to "$base/"
            ))

            // 1. Check for modern MovieLinkBD GDL action buttons (FSL Fast CDN, Instant Cloud, XCloud)
            val gdlActions = doc.select("a.gdl-action, a.fsl-download, a[data-host], a[data-btn]")
            if (gdlActions.isNotEmpty()) {
                val tempEntries = mutableListOf<Pair<String, String>>()
                for (action in gdlActions) {
                    val actHref = action.attr("href").trim()
                    if (actHref.isBlank() || actHref.startsWith("javascript") ||
                        actHref.contains("telegram") || actHref.contains("t.me") || actHref.contains("facebook")) continue

                    val fullActUrl = if (actHref.startsWith("http")) actHref else "$base$actHref"
                    val host = action.attr("data-host").lowercase()
                    val isFsl = host == "fsl" || fullActUrl.contains("/file/") || action.hasClass("fsl-download")

                    if (isFsl) {
                        try {
                            val fslHtml = executeGet(fullActUrl)
                            val fslDoc = Jsoup.parse(fslHtml, fullActUrl)
                            val fslDownload = fslDoc.selectFirst("a.fsl-download, a[data-host='fsl'], a[href*='cdn.'], a[href*='/d/']")
                            val streamUrl = fslDownload?.attr("href")?.trim() ?: fullActUrl
                            if (streamUrl.startsWith("http") && (streamUrl.contains("cdn.") || streamUrl.contains("/d/"))) {
                                val finalSize = extractedSize ?: getUrlContentLength(streamUrl)
                                val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                                val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                                val key = "[MovieLinkBD] [$moniker] FSL Fast CDN ($qualityLabel$sizeAttr | Direct Stream)"
                                tempEntries.add(Pair(key, "$streamUrl######$headersJson"))
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else if (host == "instantcloud" || fullActUrl.contains("instantcloud.org")) {
                        val instantDownload = if (fullActUrl.endsWith("/download")) fullActUrl else "$fullActUrl/download"
                        val finalSize = extractedSize ?: getUrlContentLength(instantDownload)
                        val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                        val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                        tempEntries.add(Pair("[MovieLinkBD] [$moniker] Instant Cloud ($qualityLabel$sizeAttr | Instant Cloud)", "$instantDownload######$headersJson"))
                    } else if (host == "xcloud" || fullActUrl.contains("xcloud")) {
                        val finalSize = extractedSize ?: getUrlContentLength(fullActUrl)
                        val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                        val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                        tempEntries.add(Pair("[MovieLinkBD] [$moniker] XCloud ($qualityLabel$sizeAttr | XCloud)", "$fullActUrl######$headersJson"))
                    }
                }

                if (tempEntries.isNotEmpty()) {
                    tempEntries.sortByDescending { (k, _) ->
                        when {
                            k.contains("FSL Fast CDN") -> 3
                            k.contains("Instant Cloud") -> 2
                            k.contains("XCloud") -> 1
                            else -> 0
                        }
                    }
                    tempEntries.forEach { (k, v) -> results[k] = v }
                    return
                }
            }

            // 2. Legacy direct link elements
            val directLinkEl = doc.selectFirst("a:contains(Open Direct Download Link), a[href*='token=']")
            if (directLinkEl != null) {
                val href = directLinkEl.attr("href").trim()
                val directUrl = if (href.startsWith("http")) href else "$base$href"
                val directHtml = executeGet(directUrl)
                val directDoc = Jsoup.parse(directHtml, directUrl)

                val legacyExtractedSize = extractSizeFromText(label, doc.text(), directDoc.text())

                var count = 1
                val tempEntries = mutableListOf<Pair<String, String>>()

                directDoc.select("a[href]").forEach { a ->
                    val rawHref = a.attr("href").trim()
                    var fullPlayUrl = if (rawHref.startsWith("http")) rawHref else "$base$rawHref"

                    if (rawHref.contains("/open/") || rawHref.contains("r2.dev") ||
                        rawHref.contains("movielinkbd.mom") || rawHref.contains("instantcloud.org") ||
                        rawHref.contains("cloudflarestorage.com")) {

                        if (rawHref.contains("/open/")) {
                            val resolved = resolveOpenUrl(fullPlayUrl, base)
                            if (resolved != null) {
                                fullPlayUrl = resolved
                            } else {
                                return@forEach
                            }
                        }

                        val sourceLabel = when {
                            fullPlayUrl.contains("r2.dev") || fullPlayUrl.contains("cloudflarestorage.com") -> "Fast R2 Cloud"
                            fullPlayUrl.contains("movielinkbd") -> "Direct Mom"
                            fullPlayUrl.contains("instantcloud.org") -> "Instant Cloud"
                            else -> "Direct Link $count"
                        }

                        val finalSize = legacyExtractedSize ?: getUrlContentLength(fullPlayUrl)
                        val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                        val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel

                        val key = "[MovieLinkBD] [$moniker] $sourceLabel Stream ($qualityLabel$sizeAttr | $sourceLabel)"
                        val valWithHeaders = "$fullPlayUrl######$headersJson"
                        tempEntries.add(Pair(key, valWithHeaders))
                        count++
                    }
                }

                tempEntries.sortByDescending { (k, _) ->
                    when {
                        k.contains("Fast R2 Cloud") -> 3
                        k.contains("Instant Cloud") -> 2
                        k.contains("Direct Mom") -> 1
                        else -> 0
                    }
                }

                tempEntries.forEach { (k, v) -> results[k] = v }
            } else {
                val finalSize = extractSizeFromText(label, doc.text()) ?: getUrlContentLength(fileUrl)
                val sizeAttr = if (finalSize != null) " | $finalSize" else ""
                val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
                results["[MovieLinkBD] [$moniker] Direct Download ($qualityLabel$sizeAttr | Download Link)"] = "$fileUrl######$headersJson"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val finalSize = extractSizeFromText(label) ?: getUrlContentLength(fileUrl)
            val sizeAttr = if (finalSize != null) " | $finalSize" else ""
            val moniker = if (finalSize != null) "$qualityLabel - $finalSize" else qualityLabel
            val headersJson = com.google.gson.Gson().toJson(mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to "$base/"
            ))
            results["[MovieLinkBD] [$moniker] Download Link ($qualityLabel$sizeAttr | Direct File)"] = "$fileUrl######$headersJson"
        }
    }

    private fun extractSizeFromText(vararg texts: String?): String? {
        val sizeRegex = Regex("""(?i)\b([0-9.]+\s*(?:GB|MB|GiB|MiB))\b""")
        for (text in texts) {
            if (text.isNullOrBlank()) continue
            val match = sizeRegex.find(text)
            if (match != null) {
                val sizeStr = match.groupValues[1].uppercase()
                return sizeStr
            }
        }
        return null
    }

    private suspend fun getUrlContentLength(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext null
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val contentLength = response.header("Content-Length")?.toLongOrNull()
                    if (contentLength != null && contentLength > 100_000) {
                        return@withContext formatBytes(contentLength)
                    }
                }
            }
        } catch (e: Exception) {
            // HEAD request fail ignore
        }
        null
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
            else -> String.format(java.util.Locale.US, "%d KB", bytes / 1024)
        }
    }

    private fun extractQualityLabel(text: String): String {
        return when {
            text.contains("4K", ignoreCase = true) || text.contains("2160", ignoreCase = true) -> "4K"
            text.contains("1080", ignoreCase = true) -> "1080p"
            text.contains("720p HEVC", ignoreCase = true) || text.contains("720 HEVC", ignoreCase = true) -> "720p HEVC"
            text.contains("720", ignoreCase = true) -> "720p"
            text.contains("480", ignoreCase = true) -> "480p"
            text.contains("360", ignoreCase = true) -> "360p"
            text.contains("Watch Online", ignoreCase = true) -> "Stream"
            text.contains("Download", ignoreCase = true) -> "Download"
            else -> text.take(30).trim().ifEmpty { "Unknown" }
        }
    }
}
