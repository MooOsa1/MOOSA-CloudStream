package com.moosa.hub.bundled.arabseed

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.moosa.hub.MOOSAQualityPolicy
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class ArabSeedProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://m.myseed.tv"
    override var name = "ArabSeed"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AsianDrama)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }

    private val requestHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to UA,
            "Accept-Language" to "ar,en-US;q=0.8,en;q=0.6"
        )

    private fun normalizeUrl(raw: String, base: String = mainUrl): String? {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("#") || value.startsWith("javascript:", true)) {
            return null
        }
        return try {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> java.net.URI(base).resolve(value).toString()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun getHtml(url: String, referer: String? = null): String? =
        try {
            val response = app.get(
                url,
                headers = requestHeaders,
                referer = referer
            )
            if (response.code in 200..299) response.text else null
        } catch (_: Throwable) {
            null
        }

    private fun csrfToken(html: String): String? =
        Regex("""["']?csrf__token["']?\s*[:=]\s*["']([^"']{6,})["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""var\s+csrf_token\s*=\s*["']([^"']{6,})["']""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

    private fun Element.poster(): String? =
        selectFirst("img")
            ?.let { image ->
                image.attr("data-src")
                    .ifBlank { image.attr("data-lazy-src") }
                    .ifBlank { image.attr("src") }
            }
            ?.let { normalizeUrl(it) }

    private fun looksLikeMovie(url: String): Boolean {
        val decoded = runCatching {
            URLDecoder.decode(url, "UTF-8")
        }.getOrDefault(url)
        return decoded.contains("فيلم-") ||
            decoded.contains("/movie", ignoreCase = true)
    }

    private fun contentCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        return doc.select("a[title][href]")
            .mapNotNull { anchor ->
                val href = normalizeUrl(anchor.attr("href")) ?: return@mapNotNull null
                if (
                    href.contains("/watch/", true) ||
                    href.contains("/category/", true) ||
                    href.contains("/tag/", true)
                ) {
                    return@mapNotNull null
                }

                val title = anchor.attr("title")
                    .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
                    .ifBlank { anchor.text() }
                    .trim()

                if (title.isBlank()) return@mapNotNull null

                val poster = anchor.poster()
                if (looksLikeMovie(href)) {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        posterUrl = poster
                    }
                } else {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        posterUrl = poster
                    }
                }
            }
            .distinctBy { it.url }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/main/" to "المضاف حديثاً",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val html = getHtml(url).orEmpty()
        return newHomePageResponse(
            request.name,
            contentCards(html)
        )
    }

    // MySeed changed search from JSON POST /find__posts/ to GET listings.
    // The old CSRF API now returns no results even while its website works.
    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank() || page !in 1..30) return emptyList()

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = if (page == 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val html = getHtml(url) ?: return emptyList()
        return contentCards(html)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        searchPage(query, 1)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val results = searchPage(query, page)
        return newSearchResponseList(
            results,
            results.size >= 10 && page < 30
        )
    }

    private fun String.normalizedDigits(): String = buildString {
        for (ch in this@normalizedDigits) {
            append(
                when (ch) {
                    '٠', '۰' -> '0'
                    '١', '۱' -> '1'
                    '٢', '۲' -> '2'
                    '٣', '۳' -> '3'
                    '٤', '۴' -> '4'
                    '٥', '۵' -> '5'
                    '٦', '۶' -> '6'
                    '٧', '۷' -> '7'
                    '٨', '۸' -> '8'
                    '٩', '۹' -> '9'
                    else -> ch
                }
            )
        }
    }

    private fun episodeNumber(value: String): Int? {
        val text = value.normalizedDigits()
            .lowercase()
            .replace('ة', 'ه')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')

        val patterns = listOf(
            Regex("""(?:الحلقه|حلقه)\s*[:#-]?\s*(\d{1,4})"""),
            Regex("""\b(?:episode|ep)\s*[:#-]?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE),
            Regex("""\be(\d{1,4})\b""", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }

        return Regex("""\d{1,4}""")
            .findAll(text)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()
    }

    private fun seasonNumber(value: String): Int? {
        val text = value.normalizedDigits()
            .lowercase()
            .replace('ة', 'ه')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')

        Regex("""(?:الموسم|موسم|season|part)\s*[:#-]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        val words = listOf(
            "الاول" to 1,
            "الثاني" to 2,
            "الثالث" to 3,
            "الرابع" to 4,
            "الخامس" to 5,
            "السادس" to 6,
            "السابع" to 7,
            "الثامن" to 8,
            "التاسع" to 9,
            "العاشر" to 10
        )
        return words.firstOrNull { (word, _) ->
            text.contains("الموسم $word") || text.contains("موسم $word")
        }?.second
    }

    override suspend fun load(url: String): LoadResponse? {
        val postUrl = url.substringBefore("#").trimEnd('/')
        if (!postUrl.startsWith("http")) return null

        // Content pages contain the series/episode list; /watch/ is a
        // player page and may have no series metadata.
        val html = getHtml(postUrl)
            ?: getHtml("$postUrl/watch/", postUrl)
            ?: return null
        val doc = Jsoup.parse(html, postUrl)
        val watchUrl = doc.selectFirst("a.watch__btn[href]")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
            ?: "$postUrl/watch/"

        val title = doc.selectFirst(".as-hero-title, h1, .entry-title, .post-title")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank {
                runCatching {
                    URLDecoder.decode(postUrl.substringAfterLast('/'), "UTF-8")
                        .replace('-', ' ')
                }.getOrDefault(postUrl)
            }

        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".as-hero-image, img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.let { normalizeUrl(it, watchUrl) }

        val plot = doc.selectFirst(".as-hero-story, .story, meta[name=description]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.trim()

        val pageSeason = seasonNumber("$title $postUrl")

        val episodeAnchors = doc.select(".episodes__list a[href], .episodes-list a[href], a[href*='الحلقة'], a[href*='episode']")
            .mapNotNull { anchor ->
                val href = normalizeUrl(anchor.attr("href"), watchUrl) ?: return@mapNotNull null
                if (href == postUrl || href == watchUrl) return@mapNotNull null

                val label = anchor.selectFirst(".epi__num, .epnum, .episode-number")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                    .ifBlank { anchor.text().trim() }

                val ep = episodeNumber("$label $href") ?: return@mapNotNull null
                Triple(href, label, ep)
            }
            .distinctBy { it.first }

        if (episodeAnchors.isEmpty() || looksLikeMovie(postUrl)) {
            return newMovieLoadResponse(
                title,
                postUrl,
                TvType.Movie,
                postUrl
            ) {
                posterUrl = poster
                this.plot = plot
            }
        }

        val seriesTitle = title
            .replace(Regex("""\s+(?:الحلقة|الحلقه)\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { title }

        val episodes = episodeAnchors.map { (href, label, number) ->
            newEpisode(href) {
                name = label.ifBlank { "الحلقة $number" }
                episode = number
                season = pageSeason
            }
        }

        return newTvSeriesLoadResponse(
            seriesTitle,
            postUrl,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private fun unwrapServerLink(raw: String, base: String): String? {
        val normalized = normalizeUrl(raw, base) ?: return null
        // ArabSeed redirects can wrap the actual host inside a query
        // parameter. Passing this wrapper to loadExtractor never yields video.
        val wrapped = when {
            normalized.contains("/watch/?url=", true) ->
                normalized.substringAfter("url=").substringBefore("&")
            normalized.contains("/download/?download_url=", true) ->
                normalized.substringAfter("download_url=").substringBefore("&")
            else -> null
        }
        if (wrapped != null) {
            val decoded = runCatching {
                URLDecoder.decode(wrapped, "UTF-8")
            }.getOrDefault(wrapped)
            return normalizeUrl(decoded, base)
        }

        // Download anchors have the shape /l/<Base64(https://host/file)>.
        // They lead to a category page, not a playable file. Unwrap before
        // passing to CloudStream host-specific extractors.
        val path = runCatching { java.net.URI(normalized).path }.getOrNull()
        val encodedLink = path
            ?.let { Regex("""^/l/([A-Za-z0-9_-]+={0,2})/?$""")
                .matchEntire(it)
                ?.groupValues
                ?.getOrNull(1)
            }
        if (encodedLink != null) {
            val decoded = runCatching {
                String(
                    Base64.decode(encodedLink, Base64.URL_SAFE or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
            }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                return normalizeUrl(decoded, base)
            }
        }

        if (!normalized.contains("/vid/?id=", true)) return normalized

        return try {
            val encoded = normalized.substringAfter("id=")
                .substringBefore("&")
                .trim()
            val decodedParam = URLDecoder.decode(encoded, "UTF-8")
            val decoded = String(
                Base64.decode(decodedParam, Base64.DEFAULT),
                Charsets.UTF_8
            ).trim()
            normalizeUrl(decoded, base)
        } catch (_: Throwable) {
            null
        }
    }

    private data class WatchServer(
        val id: String,
        val quality: Int,
        val label: String,
        val link: String? = null
    )

    private fun postIdFrom(html: String, doc: org.jsoup.nodes.Document): String? {
        doc.selectFirst("li[data-post][data-server]")
            ?.attr("data-post")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        doc.selectFirst("[data-post-id]")
            ?.attr("data-post-id")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val patterns = listOf(
            Regex("""(?:post_id|psot_id)["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:var|let|const)\s+post_id\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            pattern.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return null
    }

    private fun advertisedQualities(doc: org.jsoup.nodes.Document): List<Int> {
        val fromQualityTabs =
            doc.select("li[data-quality], ul.qualities__list li[data-quality]")
                .mapNotNull { it.attr("data-quality").toIntOrNull() }

        val fromServers =
            doc.select("li[data-server][data-qu]")
                .mapNotNull { it.attr("data-qu").toIntOrNull() }

        return (fromQualityTabs + fromServers)
            .filter { it in MOOSAQualityPolicy.MIN_HEIGHT..2160 }
            .distinct()
            .sortedDescending()
    }

    private fun parseServerElement(
        element: Element,
        fallbackQuality: Int
    ): WatchServer? {
        val link =
            element.attr("data-src")
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-url") }
                .trim()
                .takeIf { it.isNotBlank() }
        val id = element.attr("data-server").trim()
            .ifBlank { link?.hashCode()?.toString().orEmpty() }
        if (id.isBlank()) return null

        val quality =
            element.attr("data-qu")
                .toIntOrNull()
                ?.takeIf { it > 0 }
                ?: fallbackQuality
        if (quality > 0 && !MOOSAQualityPolicy.acceptsHeight(quality)) return null

        val label =
            element.attr("data-server-name")
                .ifBlank { element.selectFirst("span")?.text().orEmpty() }
                .ifBlank { element.text() }
                .trim()
                .ifBlank { "Server $id" }

        return WatchServer(
            id = id,
            quality = quality,
            label = label,
            link = link
        )
    }

    private fun parseServerResponse(
        body: String,
        baseUrl: String
    ): String? {
        val trimmed = body.trim()

        if (trimmed.startsWith("{")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()

            json?.optString("server")
                ?.replace("\\/", "/")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    return normalizeUrl(raw, baseUrl)
                }

            json?.optString("html")
                ?.replace("\\/", "/")
                ?.replace("\\\"", "\"")
                ?.takeIf { it.isNotBlank() }
                ?.let { html ->
                    val doc = Jsoup.parse(html, baseUrl)
                    val raw =
                        doc.selectFirst("iframe[src]")?.attr("src")
                            ?: doc.selectFirst("[data-link]")?.attr("data-link")
                    normalizeUrl(raw.orEmpty(), baseUrl)
                        ?.let { return it }
                }
        }

        val doc = Jsoup.parse(body, baseUrl)
        val raw =
            doc.selectFirst("iframe[src]")?.attr("src")
                ?: doc.selectFirst("[data-link]")?.attr("data-link")
                ?: doc.selectFirst("video[src], source[src]")?.attr("src")

        return normalizeUrl(raw.orEmpty(), baseUrl)
    }

    private suspend fun qualityServers(
        origin: String,
        watchUrl: String,
        postId: String,
        csrf: String,
        quality: Int
    ): List<WatchServer> {
        val body = try {
            app.post(
                "$origin/get__quality__servers/",
                headers = requestHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Origin" to origin
                ),
                referer = watchUrl,
                data = mapOf(
                    "post_id" to postId,
                    "quality" to quality.toString(),
                    "csrf_token" to csrf
                )
            ).text
        } catch (_: Throwable) {
            return emptyList()
        }

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return emptyList()

        val html = json.optString("html")
            .replace("\\/", "/")
            .replace("\\\"", "\"")

        val doc = Jsoup.parse(html, origin)
        // Current MySeed returns li[data-src], often without data-server.
        val servers = doc.select("li[data-server], li[data-src], li[data-link]")
            .mapNotNull { parseServerElement(it, quality) }
            .toMutableList()

        val activeLink =
            json.optString("server")
                .replace("\\/", "/")
                .trim()
                .takeIf { it.isNotBlank() }

        if (activeLink != null && servers.isNotEmpty()) {
            val activeId =
                doc.selectFirst("li.active[data-server]")
                    ?.attr("data-server")
                    ?.takeIf { it.isNotBlank() }
                    ?: servers.first().id

            val index = servers.indexOfFirst { it.id == activeId }
            if (index >= 0 && servers[index].link.isNullOrBlank()) {
                servers[index] = servers[index].copy(link = activeLink)
            }
        }

        return servers.distinctBy { it.id }
    }

    private suspend fun serverUrl(
        origin: String,
        watchUrl: String,
        postId: String,
        csrf: String,
        server: WatchServer
    ): String? {
        server.link
            ?.let { unwrapServerLink(it, watchUrl) }
            ?.let { return it }

        val body = try {
            app.post(
                "$origin/get__watch__server/",
                headers = requestHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Origin" to origin
                ),
                referer = watchUrl,
                data = mapOf(
                    "post_id" to postId,
                    "quality" to server.quality.toString(),
                    "server" to server.id,
                    "csrf_token" to csrf
                )
            ).text
        } catch (_: Throwable) {
            return null
        }

        return parseServerResponse(body, origin)
            ?.let { unwrapServerLink(it, watchUrl) }
    }

    private fun inferDirectQuality(
        url: String,
        fallback: Int
    ): Int {
        return Regex("""(?i)(2160|1440|1080|720|576|480|360)(?:p)?""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: fallback
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val postUrl = data.substringBefore("#").trimEnd('/')
        if (!postUrl.startsWith("http")) return false

        // A post/episode URL is NOT necessarily the watch URL. ArabSeed
        // exposes the real player URL via the .watch__btn element.
        val contentPage = runCatching {
            app.get(
                postUrl,
                headers = requestHeaders
            )
        }.getOrNull()

        val contentDoc = contentPage
            ?.takeIf { it.code in 200..299 }
            ?.let { Jsoup.parse(it.text, it.url) }

        val buttonWatchUrl = contentDoc
            ?.selectFirst("a.watch__btn[href], .watch__btn[href]")
            ?.let { normalizeUrl(it.attr("href"), contentPage?.url ?: postUrl) }

        val basePostUrl = postUrl
            .removeSuffix("/watch")
            .removeSuffix("/download")
            .trimEnd('/')

        val potentialUrls = linkedSetOf<String>()
        if (buttonWatchUrl != null) potentialUrls += buttonWatchUrl
        if (postUrl.endsWith("/watch", true)) potentialUrls += "$postUrl/"
        if (postUrl.contains("/watch/", true)) potentialUrls += postUrl
        potentialUrls += "$basePostUrl/watch/"
        potentialUrls += postUrl

        var playerResponse: com.lagradost.nicehttp.NiceResponse? = null

        for (candidate in potentialUrls) {
            val attempt = try {
                app.get(
                    candidate,
                    headers = requestHeaders,
                    referer = contentPage?.url ?: postUrl
                )
            } catch (_: Throwable) {
                continue
            }
            if (attempt.code !in 200..299) continue

            val candidateDoc = Jsoup.parse(attempt.text, attempt.url)
            val hasPlayer =
                candidateDoc.select(
                    ".servers__list, li[data-src], li[data-server], li[data-quality], " +
                        "iframe[src], video[src], video source[src], [data-link]"
                ).isNotEmpty() ||
                    csrfToken(attempt.text) != null

            if (hasPlayer) {
                playerResponse = attempt
                break
            }
            if (playerResponse == null) playerResponse = attempt
        }

        val response = playerResponse ?: return false
        val watchUrl = response.url
        val origin = runCatching {
            val uri = java.net.URI(watchUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)

        val html = response.text
        val doc = Jsoup.parse(html, watchUrl)
        val seen = linkedSetOf<String>()
        var emitted = false

        suspend fun emitDirect(
            url: String,
            qualityHint: Int,
            label: String
        ) {
            if (!MOOSAQualityPolicy.acceptsHeight(qualityHint)) return
            val normalized = normalizeUrl(url, watchUrl) ?: return
            if (!seen.add(normalized)) return

            val isHls = normalized.contains(".m3u8", true)
            val quality = inferDirectQuality(normalized, qualityHint)

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • $label",
                    url = normalized,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = watchUrl
                    this.quality = quality
                    headers = requestHeaders + mapOf("Referer" to watchUrl)
                }
            )
            emitted = true
        }

        suspend fun resolveDirectShell(
            url: String,
            quality: Int,
            label: String
        ): Boolean {
            if (!url.contains("vids.php", true)) return false

            val shell = try {
                app.get(
                    url,
                    headers = requestHeaders,
                    referer = watchUrl
                )
            } catch (_: Throwable) {
                return false
            }

            val shellDoc = Jsoup.parse(shell.text, shell.url)
            val gateRaw = shellDoc.selectFirst("iframe[src]")?.attr("src")
                ?: return false
            val gateUrl = normalizeUrl(gateRaw, shell.url) ?: return false

            val gate = try {
                app.get(
                    gateUrl,
                    headers = requestHeaders,
                    referer = "$origin/"
                )
            } catch (_: Throwable) {
                return false
            }

            val gateDoc = Jsoup.parse(gate.text, gate.url)
            val media = linkedSetOf<String>()

            gateDoc.select("video[src], video source[src], source[src]").forEach { element ->
                normalizeUrl(element.attr("src"), gate.url)?.let(media::add)
            }

            Regex(
                """(?i)https?://[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?"""
            ).findAll(
                gate.text.replace("\\/", "/")
            ).forEach { match ->
                media += match.value
            }

            for (mediaUrl in media) {
                emitDirect(mediaUrl, quality, label)
            }

            return media.isNotEmpty()
        }

        suspend fun resolveServer(
            server: WatchServer,
            target: String
        ): Int {
            if (!MOOSAQualityPolicy.acceptsHeight(server.quality)) return 0
            val before = seen.size

            if (
                target.contains(".m3u8", true) ||
                target.contains(".mp4", true)
            ) {
                emitDirect(
                    target,
                    server.quality,
                    "${server.label} ${server.quality}p"
                )
                return seen.size - before
            }

            if (
                resolveDirectShell(
                    target,
                    server.quality,
                    "${server.label} ${server.quality}p"
                )
            ) {
                return seen.size - before
            }

            try {
                loadExtractor(
                    target,
                    watchUrl,
                    subtitleCallback
                ) { link ->
                    // This link comes from a confirmed quality tab.
                    // Never send unknown/SD results to the user.
                    if (
                        link.quality == Qualities.Unknown.value &&
                        MOOSAQualityPolicy.acceptsHeight(server.quality)
                    ) {
                        link.quality = server.quality
                    }
                    if (
                        MOOSAQualityPolicy.accepts(link) &&
                        seen.add(link.url.trim())
                    ) {
                        callback(link)
                        emitted = true
                    }
                }
            } catch (_: Throwable) {
                // One dead host must not block the rest.
            }

            return seen.size - before
        }

        val csrf = csrfToken(html)
        val postId = postIdFrom(html, doc)
        val qualities = advertisedQualities(doc)
            .ifEmpty {
                if (csrf != null && postId != null) {
                    listOf(1080, 720, 480)
                } else {
                    emptyList()
                }
            }

        var successfulQualityLevels = 0

        if (
            !csrf.isNullOrBlank() &&
            !postId.isNullOrBlank() &&
            qualities.isNotEmpty()
        ) {
            for (quality in qualities) {
                val servers =
                    qualityServers(
                        origin = origin,
                        watchUrl = watchUrl,
                        postId = postId,
                        csrf = csrf,
                        quality = quality
                    )

                var linksAtThisQuality = 0

                for (server in servers) {
                    val target =
                        serverUrl(
                            origin = origin,
                            watchUrl = watchUrl,
                            postId = postId,
                            csrf = csrf,
                            server = server
                        ) ?: continue

                    linksAtThisQuality +=
                        resolveServer(server, target)
                }

                if (linksAtThisQuality > 0) {
                    successfulQualityLevels++

                    // Keep the highest working quality plus one fallback tier.
                    // This prevents 480p/360p duplicates from flooding the source list.
                    if (successfulQualityLevels >= 2) {
                        break
                    }
                }
            }
        }

        // Visible/active servers are a fallback when the AJAX quality API is missing.
        if (!emitted) {
            val visibleServers =
                doc.select("li[data-server], li[data-src], [data-link], iframe[src], iframe[data-src]")
                    .mapNotNull { element ->
                        val serverId = element.attr("data-server").trim()
                        val quality =
                            element.attr("data-qu")
                                .toIntOrNull()
                                ?: qualities.firstOrNull()
                                ?: 0
                        val label =
                            element.attr("data-server-name")
                                .ifBlank { element.selectFirst("span")?.text().orEmpty() }
                                .ifBlank { "Server" }
                        val raw =
                            element.attr("data-src")
                                .ifBlank { element.attr("data-link") }
                                .ifBlank { element.attr("src") }
                                .trim()

                        val link = unwrapServerLink(raw, watchUrl)
                            ?: return@mapNotNull null

                        WatchServer(
                            id = serverId.ifBlank { link.hashCode().toString() },
                            quality = quality,
                            label = label,
                            link = link
                        )
                    }
                    .distinctBy { it.link }

            for (server in visibleServers) {
                if (!MOOSAQualityPolicy.acceptsHeight(server.quality)) continue
                val target = server.link ?: continue
                resolveServer(server, target)
            }
        }

        // Download page can expose direct/high-bitrate mirrors that are not in watch servers.
        val downloadUrl = contentDoc
            ?.selectFirst("a.download__btn[href], .download__btn[href]")
            ?.let { normalizeUrl(it.attr("href"), contentPage?.url ?: postUrl) }
            ?: "$basePostUrl/download/"
        try {
            val downloadResponse = app.get(
                downloadUrl,
                headers = requestHeaders,
                referer = postUrl
            )

            if (downloadResponse.code in 200..299) {
                val downloadDoc =
                    Jsoup.parse(
                        downloadResponse.text,
                        downloadResponse.url
                    )

                val targets =
                    downloadDoc.select(
                        "ul.downloads__links__list a[href], a.download__item[href], a[href*='.mp4'], a[href*='.m3u8']"
                    )
                        .mapNotNull { anchor ->
                            val label = anchor.text().trim()
                            val quality = Regex(
                                """(?i)(?:^|\W)(2160|1440|1080|720|480|360)p(?:\W|$)"""
                            ).find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            if (!MOOSAQualityPolicy.acceptsHeight(quality)) {
                                return@mapNotNull null
                            }

                            val raw = normalizeUrl(
                                anchor.attr("href"),
                                downloadResponse.url
                            ) ?: return@mapNotNull null
                            val target = unwrapServerLink(
                                raw,
                                downloadResponse.url
                            ) ?: return@mapNotNull null
                            Triple(target, label, quality!!)
                        }
                        .distinctBy { it.first }
                        .take(8)

                for ((target, label, quality) in targets) {
                    if (
                        target.contains(".mp4", true) ||
                        target.contains(".m3u8", true)
                    ) {
                        emitDirect(
                            target,
                            quality,
                            "Download - $label"
                        )
                        continue
                    }

                    try {
                        loadExtractor(
                            target,
                            downloadResponse.url,
                            subtitleCallback
                        ) { link ->
                            if (link.quality == Qualities.Unknown.value) {
                                link.quality = quality
                            }
                            if (
                                MOOSAQualityPolicy.accepts(link) &&
                                seen.add(link.url.trim())
                            ) {
                                callback(link)
                                emitted = true
                            }
                        }
                    } catch (_: Throwable) {
                        // Keep trying remaining download mirrors.
                    }
                }
            }
        } catch (_: Throwable) {
            // Watch path is still valid when download page is unavailable.
        }

        return emitted
    }

}
