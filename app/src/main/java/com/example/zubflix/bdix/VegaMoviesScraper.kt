package com.example.zubflix.bdix

import com.example.zubflix.bdix.BDIXScraper.MediaMeta
import com.example.zubflix.bdix.BDIXScraper.StreamResult
import com.example.zubflix.bdix.BDIXUtils.encode
import okhttp3.Request
import org.json.JSONObject

internal object VegaMoviesScraper : LocalScraper {
    override val id: String = "vegamovies"
    override val name: String = "VegaMovies Scraper"
    override val description: String = "Native high-speed scraper for VegaMovies"

    private const val SOURCE = "VegaMovies"
    private var cachedBaseUrl = "https://new1.vegamovies.futbol"
    private var cachedHubDomain = "https://hubcloud.cx"
    private var cachedVcDomain = "https://vcloud.fit"
    private var lastDomainRefreshTime = 0L
    private const val DOMAIN_CACHE_TTL = 4 * 60 * 60 * 1000L // 4 hours

    private val MOBILE_UAS = listOf(
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
    )

    private fun getHeaders(referer: String): Map<String, String> {
        val ua = MOBILE_UAS.random()
        return mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to referer
        )
    }

    private suspend fun refreshDomains() {
        val now = System.currentTimeMillis()
        if (now - lastDomainRefreshTime < DOMAIN_CACHE_TTL) return

        try {
            val request = Request.Builder()
                .url("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
                .build()
            BDIXUtils.client.newCall(request).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (!body.isNullOrBlank()) {
                        val obj = JSONObject(body)
                        if (obj.has("vegamovies")) cachedBaseUrl = obj.getString("vegamovies").trimEnd('/')
                        if (obj.has("hubcloud")) cachedHubDomain = obj.getString("hubcloud").trimEnd('/')
                        if (obj.has("vcloud")) cachedVcDomain = obj.getString("vcloud").trimEnd('/')
                        lastDomainRefreshTime = now
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to defaults
        }
    }

    private fun getOrigin(url: String): String {
        return try {
            val parts = url.split("//")
            if (parts.size < 2) url else parts[0] + "//" + parts[1].split("/")[0]
        } catch (e: Exception) {
            url
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$cachedBaseUrl$url"
        return "$cachedBaseUrl/$url"
    }

    private fun parseQuality(text: String): String {
        val m = Regex("(2160|1080|720|480)\\s*P", RegexOption.IGNORE_CASE).find(text)
        if (m != null) return m.groupValues[1] + "p"
        if (Regex("4K|UHD", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "2160p"
        if (Regex("1440|2K", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "1440p"
        return "HD"
    }

    data class VegamoviesSearchItem(
        val postId: String,
        val title: String,
        val permalink: String,
        val imdbId: String,
        val year: Int?
    )

    private suspend fun searchByTitle(query: String, year: Int?): List<VegamoviesSearchItem> {
        val term = query + if (year != null) " $year" else ""
        val url = "$cachedBaseUrl/search.php?q=${term.encode()}&page=1&per_page=15"

        val headers = getHeaders("$cachedBaseUrl/")
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        return try {
            BDIXUtils.client.newCall(requestBuilder.build()).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val body = res.body?.string() ?: return emptyList()
                val obj = JSONObject(body)
                val hits = obj.optJSONArray("hits") ?: return emptyList()
                val list = mutableListOf<VegamoviesSearchItem>()
                for (i in 0 until hits.length()) {
                    val hit = hits.optJSONObject(i) ?: continue
                    val doc = hit.optJSONObject("document") ?: continue
                    val id = doc.optString("id", "")
                    val postTitle = doc.optString("post_title", "").replace("Download", "", ignoreCase = true).trim()
                    val permalink = doc.optString("permalink", "")
                    val imdbId = doc.optString("imdb_id", "")

                    var extractedYear: Int? = null
                    val categoryArr = doc.optJSONArray("category")
                    if (categoryArr != null) {
                        for (j in 0 until categoryArr.length()) {
                            val catStr = categoryArr.optString(j, "")
                            val m = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(catStr)
                            if (m != null) {
                                extractedYear = m.value.toIntOrNull()
                                break
                            }
                        }
                    }
                    if (extractedYear == null) {
                        val m = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(postTitle)
                        extractedYear = m?.value?.toIntOrNull()
                    }

                    list.add(
                        VegamoviesSearchItem(
                            postId = id,
                            title = postTitle,
                            permalink = permalink,
                            imdbId = imdbId,
                            year = extractedYear
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class PostContent(val title: String, val html: String)

    private suspend fun fetchPostContent(postId: String, permalink: String?): PostContent? {
        if (postId.isBlank()) return null
        val apiUrl = "$cachedBaseUrl/wp-json/wp/v2/posts/$postId"
        val headers = getHeaders("$cachedBaseUrl/")

        val requestBuilder = Request.Builder().url(apiUrl)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }

        try {
            BDIXUtils.client.newCall(requestBuilder.build()).execute().use { res ->
                if (res.isSuccessful) {
                    val txt = res.body?.string()
                    if (!txt.isNullOrBlank()) {
                        val json = JSONObject(txt)
                        val contentObj = json.optJSONObject("content")
                        val renderedHtml = contentObj?.optString("rendered", "") ?: ""
                        if (renderedHtml.isNotEmpty() && Regex("nexdrive|vcloud|hubcloud|fastdl|genxfm", RegexOption.IGNORE_CASE).containsMatchIn(renderedHtml)) {
                            val titleObj = json.optJSONObject("title")
                            val title = (titleObj?.optString("rendered", "") ?: "").replace("Download", "", ignoreCase = true).trim()
                            return PostContent(title, renderedHtml)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fall back to HTML
        }

        val targetUrl = if (!permalink.isNullOrBlank()) {
            fixUrl(permalink)
        } else {
            "$cachedBaseUrl/?p=$postId"
        }

        try {
            val fallbackBuilder = Request.Builder().url(targetUrl)
            getHeaders("$cachedBaseUrl/").forEach { (k, v) -> fallbackBuilder.header(k, v) }
            BDIXUtils.client.newCall(fallbackBuilder.build()).execute().use { res ->
                if (res.isSuccessful) {
                    val txt = res.body?.string() ?: ""
                    val doc = org.jsoup.Jsoup.parse(txt)
                    val entryContent = doc.selectFirst(".entry-content, .post-content")?.html() ?: doc.body()?.html() ?: ""
                    if (entryContent.isNotEmpty()) {
                        val title = doc.title().replace("Download", "", ignoreCase = true).trim()
                        return PostContent(title, entryContent)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    data class ExtractedLink(val href: String, val quality: String, val label: String)

    private val EXCLUDED_BUTTONS = listOf("filepress", "gdtot", "dropgalaxy", "gdflix", "gdlink")

    private fun extractNexdriveLinks(contentHtml: String): List<ExtractedLink> {
        if (contentHtml.isBlank()) return emptyList()
        val doc = org.jsoup.Jsoup.parse(contentHtml)
        val links = mutableListOf<ExtractedLink>()
        val seenUrls = mutableSetOf<String>()

        val elements = doc.select("a[href*=nexdrive], a[href*=genxfm], a[href*=fastdl], a[href*=vcloud], a[href*=hubcloud]")
        for (el in elements) {
            try {
                val href = el.attr("href") ?: continue
                if (href.isBlank()) continue
                val linkText = el.text().trim()
                if (EXCLUDED_BUTTONS.any { linkText.lowercase().contains(it) }) continue
                if (!seenUrls.add(href)) continue

                var quality = "HD"
                var label = if (linkText.isNotEmpty()) linkText else "Download"

                val hrefPos = contentHtml.indexOf(href)
                if (hrefPos > 0) {
                    val beforeHref = contentHtml.substring(maxOf(0, hrefPos - 3000), hrefPos)

                    val headingMatches = Regex("<h[1-6][^>]*>([\\s\\S]*?)</h[1-6]>", RegexOption.IGNORE_CASE).findAll(beforeHref)
                    if (headingMatches.any()) {
                        val lastHText = headingMatches.last().groupValues[1].replace(Regex("<[^>]*>"), "").replace("Download", "", ignoreCase = true).trim()
                        if (lastHText.length > 5) {
                            label = lastHText
                        }
                    }

                    val qualityPattern = Regex("(?:^|>|\\s)(\\d{3,4}p|4K|UHD|HDR)(?:<|\\s|$)", RegexOption.IGNORE_CASE)
                    val qMatches = qualityPattern.findAll(beforeHref)
                    var lastMatch: String? = null
                    if (qMatches.any()) {
                        lastMatch = qMatches.last().groupValues[1]
                    }
                    if (lastMatch != null) {
                        quality = parseQuality(lastMatch)
                    } else {
                        val headingQ = Regex("<(?:h[1-6]|strong|b)[^>]*>[^<]*?(\\d{3,4}p|4K|UHD)[^<]*?</", RegexOption.IGNORE_CASE).find(beforeHref)
                        if (headingQ != null) {
                            quality = parseQuality(headingQ.groupValues[1])
                        }
                    }
                }

                if (quality == "480p") continue

                links.add(
                    ExtractedLink(
                        href = fixUrl(href),
                        quality = quality,
                        label = label
                    )
                )
            } catch (e: Exception) {
                // ignore
            }
        }
        return links
    }

    private fun extractSeasonFromContent(contentHtml: String, targetSeason: Int?): String {
        if (contentHtml.isBlank() || targetSeason == null) return contentHtml
        val mainContent = contentHtml.split("id=\"comments\"").first().split("class=\"comments-area\"").first()

        val seasonRegex = Regex("(?:Season|Saison|Staffel)\\s+0*(\\d+)\\b(?!\\s*(?:-|–|to|and|&|&#))", RegexOption.IGNORE_CASE)
        val matches = seasonRegex.findAll(mainContent)

        data class SeasonPos(val season: Int, val index: Int)
        val positions = mutableListOf<SeasonPos>()

        for (m in matches) {
            val lastH = mainContent.lastIndexOf("<h", m.range.first)
            val lastStrong = mainContent.lastIndexOf("<strong", m.range.first)
            var pos = maxOf(lastH, lastStrong)
            if (pos < 0 || m.range.first - pos > 500) {
                pos = m.range.first
            }

            val snippet = mainContent.substring(pos, minOf(mainContent.length, m.range.first + 50))
            if (snippet.contains("download", ignoreCase = true) || snippet.contains("episode", ignoreCase = true)) {
                continue
            }
            positions.add(SeasonPos(m.groupValues[1].toInt(), pos))
        }

        if (positions.isEmpty()) return mainContent

        val targetPos = positions.find { it.season == targetSeason } ?: return mainContent
        val startIdx = targetPos.index
        val nextPos = positions.find { it.index > startIdx && it.season != targetSeason }
        val endIdx = nextPos?.index ?: mainContent.length

        return mainContent.substring(startIdx, endIdx)
    }

    private fun makeStreamResult(
        name: String,
        titleRaw: String,
        url: String,
        quality: String,
        headers: Map<String, String> = emptyMap()
    ): StreamResult {
        val resLabel = quality.ifEmpty { "1080p" }
        val rawTitle = titleRaw.replace(Regex("[\\n\\t]+"), " ").replace(Regex("\\s{2,}"), " ").trim()

        var filename = ""
        val fnMatch = Regex("\\[\\s*([^\\]]+\\.(?:mkv|mp4|avi|zip|rar|ts))\\s*\\]", RegexOption.IGNORE_CASE).find(rawTitle)
        var cleanedTitle = rawTitle
        if (fnMatch != null) {
            filename = fnMatch.groupValues[1].trim()
            cleanedTitle = rawTitle.replace(fnMatch.value, "").trim()
        }

        var sizeStr = "N/A"
        val szMatch = Regex("\\[\\s*(\\d+(?:\\.\\d+)?\\s*[MG]B)\\s*\\]", RegexOption.IGNORE_CASE).find(titleRaw)
        if (szMatch != null) sizeStr = szMatch.groupValues[1].trim()

        var container = "MKV"
        if (filename.lowercase().endsWith(".mp4")) container = "MP4"

        var ripType = "WEB-DL"
        if (Regex("bluray|blu\\-ray|bdrip", RegexOption.IGNORE_CASE).containsMatchIn(titleRaw)) ripType = "BluRay"
        else if (Regex("hdrip|webrip", RegexOption.IGNORE_CASE).containsMatchIn(titleRaw)) ripType = "WEBRip"

        val imax = if (Regex("imax", RegexOption.IGNORE_CASE).containsMatchIn(titleRaw)) " | 👁️ iMAX" else ""

        var hdrStr = ""
        val lowerTitleRaw = titleRaw.lowercase()
        if (lowerTitleRaw.contains("dolby vision") || lowerTitleRaw.contains("dovi")) hdrStr = "Dolby Vision"
        else if (titleRaw.contains("HDR10", ignoreCase = true)) hdrStr = "HDR10"
        else if (titleRaw.contains("HDR", ignoreCase = true)) hdrStr = "HDR"
        else if (titleRaw.contains("10bit", ignoreCase = true) || titleRaw.contains("10-bit", ignoreCase = true)) hdrStr = "10Bit"
        else if (lowerTitleRaw.contains("sdr")) hdrStr = "SDR"

        var codec = "H.264"
        if (titleRaw.contains("hevc", ignoreCase = true)) codec = "HEVC"
        else if (titleRaw.contains("x265", ignoreCase = true) || titleRaw.contains("h265", ignoreCase = true)) codec = "H.265"
        else if (titleRaw.contains("x264", ignoreCase = true) || titleRaw.contains("h264", ignoreCase = true)) codec = "H.264"

        val videoInfo = if (hdrStr.isNotEmpty()) " | 🔆 $hdrStr • ⚡ $codec" else " | ⚡ $codec"

        var audioStr = ""
        val audioMatch = Regex("(TrueHD\\s*7\\.1|DDP\\s*7\\.1|DDP\\s*5\\.1|DD\\s*5\\.1|5\\.1|AAC)", RegexOption.IGNORE_CASE).find(titleRaw)
        if (audioMatch != null) {
            var aLabel = audioMatch.groupValues[1].uppercase().replace(Regex("\\s+"), "")
            if (aLabel == "5.1") aLabel = "DDP5.1"
            if (aLabel.contains("TRUEHD")) aLabel = "TrueHD 7.1"
            audioStr = aLabel
        } else if (titleRaw.contains("dolby digital", ignoreCase = true) || titleRaw.contains("dd", ignoreCase = true)) {
            audioStr = "Dolby Digital"
        } else if (titleRaw.contains("dolby", ignoreCase = true)) {
            audioStr = "Dolby"
        }

        if (titleRaw.contains("atmos", ignoreCase = true)) {
            audioStr = if (audioStr.isNotEmpty()) "$audioStr • 🔊 Atmos" else "🔊 Atmos"
        }
        if (audioStr.isEmpty()) audioStr = "Auto"

        val langs = mutableListOf<String>()
        val isDual = Regex("dual|hindi\\-eng|eng\\-hin", RegexOption.IGNORE_CASE).containsMatchIn(titleRaw)
        if (isDual) {
            langs.add("English 🇺🇸 • Hindi 🇮🇳")
        } else {
            val lowerRaw = titleRaw.lowercase()
            if (lowerRaw.contains("hindi") || lowerRaw.contains("hin")) langs.add("Hindi 🇮🇳")
            if (lowerRaw.contains("english") || lowerRaw.contains("eng")) langs.add("English 🇺🇸")
            if (langs.isEmpty()) langs.add("English 🇺🇸")
        }
        val langStr = langs.joinToString(" • ")

        val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(titleRaw)
        val yrTag = if (yearMatch != null) "(${yearMatch.value})" else ""
        val cleanTarget = filename.ifEmpty { cleanedTitle }
        val seMatch = Regex("[sS](\\d+)\\s*[eE](\\d+)", RegexOption.IGNORE_CASE).find(cleanTarget)

        var headerDisplay = ""
        if (seMatch != null) {
            val showName = cleanTarget.split(Regex("[sS]\\d+", RegexOption.IGNORE_CASE)).firstOrNull()?.replace(Regex("[\\.\\-_]"), " ")?.replace(Regex("[\\{\\[\\(].*$"), "")?.trim() ?: ""
            val sNum = seMatch.groupValues[1].toIntOrNull() ?: 1
            val eNum = seMatch.groupValues[2].toIntOrNull() ?: 1
            headerDisplay = "$showName - S$sNum E$eNum"
        } else {
            val movieName = cleanedTitle.split(Regex("[\\.\\-_]\\d{3,4}p", RegexOption.IGNORE_CASE)).firstOrNull()?.replace(Regex("[\\.\\-_]"), " ")?.replace(Regex("\\d{3,4}p.*", RegexOption.IGNORE_CASE), "")?.replace(Regex("[\\{\\[\\(].*$"), "")?.trim() ?: ""
            headerDisplay = movieName + if (yrTag.isNotEmpty()) " - $yrTag" else ""
        }
        headerDisplay = headerDisplay.replace(Regex("\\s+"), " ").replace(Regex("\\s+-\\s+-\\s+"), " - ").replace(Regex("-\\s*$"), "").trim()

        var serverHost = "Play Stream"
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("/hub2/") || lowerUrl.contains("hubcloud") || lowerUrl.contains("homelander.buzz") || lowerUrl.contains("whistle.lat") || lowerUrl.contains("mandalorian.buzz")) {
            serverHost = "HubCloud"
        } else if (lowerUrl.contains(".r2.dev") || lowerUrl.contains("vcloud")) {
            serverHost = "vCloud"
        }

        val simpleTitle = "$serverHost • $ripType$imax"
        val attrList = mutableListOf<String>()
        attrList.add(resLabel)
        if (sizeStr != "N/A" && sizeStr.isNotEmpty()) {
            attrList.add(sizeStr)
        }
        if (audioStr != "Auto" && audioStr.isNotEmpty()) {
            attrList.add(audioStr)
        }
        if (hdrStr.isNotEmpty()) {
            attrList.add(hdrStr)
        }
        attrList.add(codec)
        attrList.add(if (isDual) "Dual-Audio" else "Single Audio")

        val attributesFormatted = attrList.joinToString(" | ")
        val formattedTitle = "$simpleTitle ($attributesFormatted)"

        val score = when (resLabel.lowercase()) {
            "2160p", "4k" -> 40
            "1080p" -> 30
            "720p" -> 20
            else -> 10
        }

        return StreamResult(
            source = SOURCE,
            title = formattedTitle,
            url = url,
            qualityScore = score,
            mediaTitle = headerDisplay
        )
    }

    private suspend fun fetchHtml(url: String, referer: String): String? {
        val builder = Request.Builder().url(url)
        getHeaders(referer).forEach { (k, v) -> builder.header(k, v) }
        builder.header("Cookie", "xla=s4t")
        return try {
            BDIXUtils.client.newCall(builder.build()).execute().use { res ->
                if (res.isSuccessful) res.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractSingleVc(
        vcUrl: String,
        referer: String,
        targetSeason: Int?,
        targetEpisode: Int?,
        displayLabel: String,
        quality: String,
        episodeTitle: String
    ): List<StreamResult> {
        val lower = vcUrl.lowercase()
        val isHub = lower.contains("hubcloud")
        val latestBase = if (isHub) cachedHubDomain else cachedVcDomain
        val curBase = getOrigin(vcUrl)
        var targetVcUrl = vcUrl
        if (curBase != latestBase && (lower.contains("vcloud") || lower.contains("hubcloud"))) {
            targetVcUrl = vcUrl.replace(curBase, latestBase)
        }

        val rawHtml = fetchHtml(targetVcUrl, referer) ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(rawHtml)
        val pageTitle = doc.title() ?: ""

        if (targetSeason != null || targetEpisode != null) {
            val seMatch = Regex("[.\\s_\\-](?:S|Season)\\s*0*(\\d{1,2})[.\\s_\\-]*(?:E|Ep|Episode)\\s*0*(\\d{1,2})[.\\s_\\-]", RegexOption.IGNORE_CASE).find(pageTitle)
            if (seMatch != null) {
                val vcSeason = seMatch.groupValues[1].toIntOrNull()
                val vcEpisode = seMatch.groupValues[2].toIntOrNull()
                if (targetSeason != null && vcSeason != targetSeason) return emptyList()
                if (targetEpisode != null && vcEpisode != targetEpisode) return emptyList()
            } else {
                val sMatch = Regex("[.\\s_\\-](?:S|Season)\\s*0*(\\d{1,2})[.\\s_\\-]", RegexOption.IGNORE_CASE).find(pageTitle)
                if (sMatch != null && targetSeason != null) {
                    val sNum = sMatch.groupValues[1].toIntOrNull()
                    if (sNum != targetSeason) return emptyList()
                }
            }
        }

        var bridgeUrl = ""
        val doubleAtobMatch = Regex("var\\s+url\\s*=\\s*atob\\(atob\\('([^']+)'\\)\\)").find(rawHtml)
        val varMatch = Regex("var\\s+url\\s*=\\s*['\"]([^'\"]+)['\"]").find(rawHtml)

        if (doubleAtobMatch != null) {
            try {
                val encoded = doubleAtobMatch.groupValues[1]
                val dec1 = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).decodeToString()
                bridgeUrl = android.util.Base64.decode(dec1, android.util.Base64.DEFAULT).decodeToString()
            } catch (e: Exception) {
                bridgeUrl = doubleAtobMatch.groupValues[1]
            }
        } else if (varMatch != null) {
            bridgeUrl = varMatch.groupValues[1]
        }

        val cardHeader = doc.selectFirst("div.card-header")?.text() ?: ""
        val detectedQuality = parseQuality(cardHeader).ifEmpty { quality }

        val streams = mutableListOf<StreamResult>()

        if (bridgeUrl.isNotEmpty() && bridgeUrl.contains(".workers.dev")) {
            val min = (System.currentTimeMillis() / 60000) % 60
            val wUrl = "$bridgeUrl?s=${1 + min}"
            streams.add(
                makeStreamResult(
                    name = "Worker | $detectedQuality",
                    titleRaw = "$displayLabel [$cardHeader]",
                    url = wUrl,
                    quality = detectedQuality,
                    headers = mapOf("Referer" to targetVcUrl)
                )
            )
            bridgeUrl = ""
        }

        val buttons = doc.select("a.btn, a")
        for (btn in buttons) {
            val href = btn.attr("href") ?: continue
            val text = btn.text().trim()
            val lowerText = text.lowercase()

            if (href.isBlank() || href == "#" || href.lowercase().contains(".zip")) continue
            if (lowerText.contains("10gbps") || lowerText.contains("gdflix") || lowerText.contains("dropgalaxy") || lowerText.contains("telegram")) continue

            if (lowerText.contains("fslv2")) {
                streams.add(
                    makeStreamResult(
                        name = "FSLv2 (Fast) | $detectedQuality",
                        titleRaw = "$displayLabel [$cardHeader]",
                        url = href,
                        quality = detectedQuality,
                        headers = mapOf("Referer" to targetVcUrl)
                    )
                )
            } else if (lowerText.contains("fsl")) {
                val min = (System.currentTimeMillis() / 60000) % 60
                val syncedFsl = if (href.contains("?")) "$href&s=${1 + min}" else "$href?s=${1 + min}"
                streams.add(
                    makeStreamResult(
                        name = "FSL | $detectedQuality",
                        titleRaw = "$displayLabel [$cardHeader]",
                        url = syncedFsl,
                        quality = detectedQuality,
                        headers = mapOf("Referer" to targetVcUrl)
                    )
                )
            } else if (lowerText.contains("worker")) {
                val min = (System.currentTimeMillis() / 60000) % 60
                val syncedW = if (href.contains("?")) "$href&s=${1 + min}" else "$href?s=${1 + min}"
                streams.add(
                    makeStreamResult(
                        name = "Worker | $detectedQuality",
                        titleRaw = "$displayLabel [$cardHeader]",
                        url = syncedW,
                        quality = detectedQuality,
                        headers = mapOf("Referer" to targetVcUrl)
                    )
                )
            }
        }

        if (streams.isNotEmpty()) {
            return streams
        }

        if (bridgeUrl.isEmpty()) {
            var downloadHref = doc.selectFirst("#download")?.attr("href")
            if (downloadHref.isNullOrBlank()) {
                downloadHref = doc.select("a").firstOrNull { el ->
                    val h = el.attr("href") ?: ""
                    h.contains("hubcloud.php") || h.contains("token") || h.contains("dl")
                }?.attr("href")
            }
            if (!downloadHref.isNullOrBlank()) {
                bridgeUrl = if (downloadHref.startsWith("http")) {
                    downloadHref
                } else {
                    getOrigin(targetVcUrl) + "/" + downloadHref.removePrefix("/")
                }
            }
        }

        if (bridgeUrl.isEmpty()) {
            val altVc = doc.select("a[href*=vcloud.zip]").firstOrNull { el ->
                val h = el.attr("href") ?: ""
                !h.contains("/api/") && h != targetVcUrl
            }?.attr("href")
            if (!altVc.isNullOrBlank()) {
                return extractSingleVc(altVc, referer, targetSeason, targetEpisode, displayLabel, quality, episodeTitle)
            }
        }

        if (bridgeUrl.isEmpty()) return emptyList()
        if (!bridgeUrl.contains("://")) {
            bridgeUrl = getOrigin(targetVcUrl) + if (bridgeUrl.startsWith("/")) bridgeUrl else "/$bridgeUrl"
        }

        val bridgeHtml = fetchHtml(bridgeUrl, targetVcUrl) ?: return emptyList()
        val bridgeDoc = org.jsoup.Jsoup.parse(bridgeHtml)
        val bridgeHeader = bridgeDoc.selectFirst("div.card-header")?.text() ?: ""
        val bridgeQuality = parseQuality(bridgeHeader).ifEmpty { detectedQuality }

        val bridgeVarMatch = Regex("var\\s+url\\s*=\\s*['\"]([^'\"]+)['\"]").find(bridgeHtml)
        if (bridgeVarMatch != null && bridgeVarMatch.groupValues[1].contains(".workers.dev")) {
            val min = (System.currentTimeMillis() / 60000) % 60
            val wUrl2 = bridgeVarMatch.groupValues[1] + "?s=${1 + min}"
            streams.add(
                makeStreamResult(
                    name = "Worker | $bridgeQuality",
                    titleRaw = "$displayLabel [$bridgeHeader]",
                    url = wUrl2,
                    quality = bridgeQuality,
                    headers = mapOf("Referer" to bridgeUrl)
                )
            )
        }

        val bridgeButtons = bridgeDoc.select("a.btn, a")
        for (btn in bridgeButtons) {
            val href = btn.attr("href") ?: continue
            val text = btn.text().trim()
            val lowerText = text.lowercase()

            if (href.isBlank() || href == "#" || href.lowercase().contains(".zip")) continue
            if (lowerText.contains("10gbps") || lowerText.contains("gdflix") || lowerText.contains("dropgalaxy") || lowerText.contains("telegram")) continue

            if (lowerText.contains("fslv2")) {
                streams.add(
                    makeStreamResult(
                        name = "FSLv2 (Fast) | $bridgeQuality",
                        titleRaw = "$displayLabel [$bridgeHeader]",
                        url = href,
                        quality = bridgeQuality,
                        headers = mapOf("Referer" to bridgeUrl)
                    )
                )
            } else if (lowerText.contains("fsl")) {
                val min = (System.currentTimeMillis() / 60000) % 60
                val syncedFsl = if (href.contains("?")) "$href&s=${1 + min}" else "$href?s=${1 + min}"
                streams.add(
                    makeStreamResult(
                        name = "FSL | $bridgeQuality",
                        titleRaw = "$displayLabel [$bridgeHeader]",
                        url = syncedFsl,
                        quality = bridgeQuality,
                        headers = mapOf("Referer" to bridgeUrl)
                    )
                )
            }
        }

        if (streams.isEmpty()) {
            val fslHref = bridgeDoc.selectFirst("#fsl")?.attr("href")
            if (!fslHref.isNullOrBlank()) {
                val min = (System.currentTimeMillis() / 60000) % 60
                val syncedFsl2 = if (fslHref.contains("?")) "$fslHref&s=${1 + min}" else "$fslHref?s=${1 + min}"
                streams.add(
                    makeStreamResult(
                        name = "FSL | $bridgeQuality",
                        titleRaw = "$displayLabel [$bridgeHeader]",
                        url = syncedFsl2,
                        quality = bridgeQuality,
                        headers = mapOf("Referer" to bridgeUrl)
                    )
                )
            }
        }

        return streams
    }

    private suspend fun loadStreamsFromUrl(
        url: String,
        displayLabel: String,
        quality: String,
        referer: String,
        targetSeason: Int?,
        targetEpisode: Int?,
        episodeTitle: String
    ): List<StreamResult> {
        val lower = url.lowercase()
        if (lower.contains("vcloud") || lower.contains("hubcloud")) {
            return extractSingleVc(url, referer, targetSeason, targetEpisode, displayLabel, quality, episodeTitle)
        }

        if (lower.contains("nexdrive") || lower.contains("genxfm") || lower.contains("fastdl")) {
            val html = fetchHtml(url, referer) ?: return emptyList()
            val doc = org.jsoup.Jsoup.parse(html)
            val streamTasks = mutableListOf<suspend () -> List<StreamResult>>()

            val links = doc.select("a[href*=vcloud], a[href*=hubcloud]")
            for (el in links) {
                val href = el.attr("href") ?: continue
                if (href.contains("/api/index.php?link=")) {
                    streamTasks.add {
                        val apiHtml = fetchHtml(href, url) ?: return@add emptyList()
                        val apiDoc = org.jsoup.Jsoup.parse(apiHtml)
                        var nextHref = apiDoc.selectFirst("a.btn-success, a.btn")?.attr("href")
                        if (!nextHref.isNullOrBlank()) {
                            if (nextHref.startsWith("/")) {
                                nextHref = getOrigin(href) + nextHref
                            }
                            extractSingleVc(nextHref, href, targetSeason, targetEpisode, displayLabel, quality, episodeTitle)
                        } else {
                            emptyList()
                        }
                    }
                } else {
                    streamTasks.add {
                        extractSingleVc(href, url, targetSeason, targetEpisode, displayLabel, quality, episodeTitle)
                    }
                }
            }

            if (targetEpisode != null) {
                val epIdx = targetEpisode - 1
                if (epIdx >= 0 && epIdx < streamTasks.size) {
                    val res = streamTasks[epIdx]()
                    if (res.isNotEmpty()) return res
                }
            }

            val allResults = mutableListOf<StreamResult>()
            for (task in streamTasks) {
                allResults.addAll(task())
            }
            return allResults
        }

        return emptyList()
    }

    private suspend fun extractFromPost(
        postObj: PostContent,
        isTv: Boolean,
        targetSeason: Int?,
        targetEpisode: Int?,
        year: Int?
    ): List<StreamResult> {
        try {
            var contentHtml = postObj.html
            var seasonTag = ""

            if (isTv && targetSeason != null) {
                val filtered = extractSeasonFromContent(contentHtml, targetSeason)
                if (filtered.isNotEmpty()) contentHtml = filtered
                seasonTag = " S$targetSeason"
                if (targetEpisode != null) seasonTag += "E$targetEpisode"
            }

            val epTag = (seasonTag.trim().ifEmpty { year?.toString() ?: "" }).trim()
            val links = extractNexdriveLinks(contentHtml)
            val capped = if (links.size > 15) links.take(15) else links

            if (capped.isEmpty()) return emptyList()

            val results = mutableListOf<StreamResult>()
            for (link in capped) {
                val q = link.quality.ifEmpty { "HD" }
                val dispLabel = link.label.ifEmpty { "$seasonTag [$q]" }
                val res = loadStreamsFromUrl(
                    url = link.href,
                    displayLabel = dispLabel,
                    quality = q,
                    referer = "$cachedBaseUrl/",
                    targetSeason = targetSeason,
                    targetEpisode = targetEpisode,
                    episodeTitle = epTag
                )
                results.addAll(res)
            }
            return results
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun isStrictMatch(
        mediaTitle: String,
        mediaYear: Int?,
        postTitle: String,
        postYear: Int?,
        altTitles: List<String> = emptyList()
    ): Boolean {
        if (postTitle.isBlank()) return false
        val cleanPost = postTitle.lowercase().replace(Regex("download\\s*", RegexOption.IGNORE_CASE), "").replace(Regex("[^a-z0-9\\s]"), " ").trim().replace(Regex("\\s+"), " ")
        val candidates = (listOf(mediaTitle) + altTitles).filter { it.isNotBlank() }

        var titleMatched = false
        for (cand in candidates) {
            val cleanCand = cand.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").trim().replace(Regex("\\s+"), " ")
            if (cleanCand.isNotEmpty() && (cleanPost.contains(cleanCand) || cleanPost.startsWith(cleanCand))) {
                titleMatched = true
                break
            }
        }
        if (!titleMatched) return false

        if (mediaYear != null && postYear != null) {
            if (kotlin.math.abs(mediaYear - postYear) > 1) return false
        }
        return true
    }

    override suspend fun getStreams(
        type: String,
        meta: MediaMeta,
        season: Int?,
        episode: Int?
    ): List<StreamResult> {
        refreshDomains()
        val isTv = type == "series" || type == "tv"

        var query = meta.name
        if (isTv && season != null) {
            query += " season $season"
        }

        var results = searchByTitle(query, meta.year)
        if (results.isEmpty() && isTv && season != null) {
            results = searchByTitle(meta.name, meta.year)
        }

        if (results.isEmpty()) return emptyList()

        var matched: VegamoviesSearchItem? = null
        for (r in results) {
            if (isTv && season != null) {
                val rangeMatch = Regex("(?:s|season|staffel|saison)\\s*0*(\\d+)\\s*(?:-|–|to|and|&|&#)\\s*0*(\\d+)\\b", RegexOption.IGNORE_CASE).find(r.title)
                var isS = false
                if (rangeMatch != null) {
                    val sStart = rangeMatch.groupValues[1].toIntOrNull() ?: 0
                    val sEnd = rangeMatch.groupValues[2].toIntOrNull() ?: 0
                    if (season in sStart..sEnd) isS = true
                }
                if (!isS) {
                    isS = Regex("(?:s|season|staffel|saison)\\s*0*$season\\b", RegexOption.IGNORE_CASE).containsMatchIn(r.title)
                }
                if (isS && isStrictMatch(meta.name, meta.year, r.title, r.year)) {
                    matched = r
                    break
                }
            } else {
                if (isStrictMatch(meta.name, meta.year, r.title, r.year)) {
                    matched = r
                    break
                }
            }
        }

        if (matched == null) {
            matched = results.firstOrNull { r ->
                val isPostTv = Regex("(?:s\\d+|season|series|complete|episode)", RegexOption.IGNORE_CASE).containsMatchIn(r.title)
                if (isTv && !isPostTv && r.year != null && meta.year != null && kotlin.math.abs(meta.year - r.year) > 2) {
                    false
                } else {
                    isStrictMatch(meta.name, meta.year, r.title, r.year)
                }
            }
        }

        val best = matched ?: return emptyList()
        val postObj = fetchPostContent(best.postId, best.permalink) ?: return emptyList()

        val streams = extractFromPost(postObj, isTv, season, episode, meta.year)
        return streams.distinctBy { it.url }.sortedByDescending { it.qualityScore }
    }
}
