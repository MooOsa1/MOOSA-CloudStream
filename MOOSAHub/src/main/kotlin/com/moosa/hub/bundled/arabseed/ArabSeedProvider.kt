package com.moosa.hub.bundled.arabseed

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

class ArabSeedProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://m.myseed.pics"
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
        Regex("""csrf__token\s*[:=]\s*["']([A-Za-z0-9_-]{6,})["']""")
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

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        val home = getHtml("$mainUrl/main/")
            ?: getHtml(mainUrl)
            ?: return emptyList()

        val csrf = csrfToken(home) ?: return emptyList()

        val response = try {
            app.post(
                "$mainUrl/find__posts/",
                headers = requestHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                referer = "$mainUrl/",
                data = mapOf(
                    "search" to clean,
                    "search_type" to "",
                    "csrf_token" to csrf
                )
            ).text
        } catch (_: Throwable) {
            return emptyList()
        }

        val html = runCatching {
            JSONObject(response).optString("html")
        }.getOrDefault("")

        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parse(html, mainUrl)
        return doc.select("a.search__item[href], a[title][href]")
            .mapNotNull { anchor ->
                val href = normalizeUrl(anchor.attr("href")) ?: return@mapNotNull null
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

        val watchUrl = "$postUrl/watch/"
        val html = getHtml(watchUrl, postUrl)
            ?: getHtml(postUrl)
            ?: return null

        val doc = Jsoup.parse(html, watchUrl)

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val postUrl = data.substringBefore("#").trimEnd('/')
        if (!postUrl.startsWith("http")) return false

        val watchUrl = "$postUrl/watch/"
        val html = getHtml(watchUrl, postUrl) ?: return false
        val doc = Jsoup.parse(html, watchUrl)
        val seen = linkedSetOf<String>()
        var emitted = false

        fun emitDirect(url: String) {
            if (!seen.add(url)) return
            val isHls = url.contains(".m3u8", true)
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Direct",
                    url = url,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = watchUrl
                    quality = Qualities.Unknown.value
                    headers = requestHeaders + mapOf("Referer" to watchUrl)
                }
            )
            emitted = true
        }

        doc.select("video[src], video source[src], source[src]").forEach { media ->
            normalizeUrl(media.attr("src"), watchUrl)?.let(::emitDirect)
        }

        val serverLinks = linkedSetOf<String>()
        doc.select("[data-link], iframe[src], iframe[data-src], a[data-link]").forEach { element ->
            val raw = element.attr("data-link")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .trim()
            unwrapServerLink(raw, watchUrl)?.let(serverLinks::add)
        }

        for (server in serverLinks) {
            if (!seen.add(server)) continue

            if (
                server.contains(".m3u8", true) ||
                server.contains(".mp4", true)
            ) {
                emitDirect(server)
                continue
            }

            try {
                loadExtractor(
                    server,
                    watchUrl,
                    subtitleCallback
                ) { link ->
                    if (seen.add(link.url)) {
                        callback(link)
                        emitted = true
                    }
                }
            } catch (_: Throwable) {
                // A broken hoster must not stop the remaining ArabSeed servers.
            }
        }

        return emitted
    }
}
