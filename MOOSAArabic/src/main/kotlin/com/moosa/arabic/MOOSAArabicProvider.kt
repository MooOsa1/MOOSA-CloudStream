// Bundled from ramailo1/arprov (cb10a262a9fae679322c264973cf5c0f5219acdc); MIT licensed. See THIRD_PARTY_NOTICES.md.
package com.moosa.arabic

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.moosa.arabic.MOOSAQualityPolicy
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.withTimeoutOrNull

class MOOSAArabicProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://ak.sv"
    override var name = "MOOSA Arabic"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Only native Arabic titles from the source. Obvious foreign dubs are
    // not presented as Arabic originals; never machine-translate names.
    private val dubbedTitle = Regex("""(?i)(?:مدبلج(?:ة)?|dubbed|دبلجة)""")

    private fun SearchResponse.isNativeArabicListing(): Boolean =
        name.isNotBlank() && name !in setOf("مشاهدة", "شاهد", "-->") &&
            !dubbedTitle.containsMatchIn(name)

    private fun Element.titleText(): String =
        selectFirst("h1,h2,h3,.entry-title,.title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: attr("title").takeIf { it.isNotEmpty() }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName() == "a") this
            else selectFirst("a[href*='/series/'],a[href*='/movie/']") ?: return null
        val href = anchor.attr("abs:href").ifEmpty { fixUrl(anchor.attr("href")) }
        if (href.isBlank() || href.contains("/games/") || href.contains("/programs/")) return null
        val title = anchor.titleText().ifBlank {
            parents().firstOrNull { it.titleText().isNotBlank() }?.titleText().orEmpty()
        }
        if (title.isBlank()) return null
        val img = anchor.selectFirst("img") ?: anchor.parents()
            .firstOrNull { it.selectFirst("img") != null }?.selectFirst("img")
        val poster = img?.let {
            sequenceOf("data-src", "data-original", "data-lazy-src", "src")
                .map(it::attr).firstOrNull { value ->
                    value.isNotBlank() && !value.startsWith("data:", true)
                }?.let(::fixUrl)
        }
        val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, tvType) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, tvType) { posterUrl = poster }
        }
    }

    // Use Ak​​wam's ACTUAL Arabic sections. No TMDB titles, no title matching.
    // /movies?section=29 and /series?section=29 are the site's own filters.
    override val mainPage = mainPageOf(
        "$mainUrl/movies?section=29&page=" to "الأفلام العربية",
        "$mainUrl/series?section=29&page=" to "المسلسلات العربية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        // Akwam repeats an overlay, watch button and proper title link for
        // each entry. Parse BEFORE deduplicating; choose the informative title.
        return newHomePageResponse(request.name, nativeCards(doc))
    }

    // The source uses several anchors per card. Never dedupe until its
    // native URL and descriptive name have both been extracted.
    private fun nativeCards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("a[href*='/series/'],a[href*='/movie/']")
            .mapNotNull { it.toSearchResponse() }
            .groupBy { it.url }
            .values.mapNotNull { group ->
                group.filter { it.isNativeArabicListing() }
                    .maxByOrNull { it.name.length }
            }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val clean = query.trim()
        if (clean.isBlank() || page !in 1..50) return emptyList<SearchResponse>() to false
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val address = "$mainUrl/search?q=$encoded" + if (page > 1) "&page=$page" else ""
        val doc = app.get(address).document
        val parsed = nativeCards(doc)
        val next = doc.select("a[href*='page=']").any { link ->
            Regex("""[?&]page=(\d+)""").find(link.attr("abs:href"))
                ?.groupValues?.getOrNull(1)?.toIntOrNull() == page + 1
        }
        if (page != 1) return parsed to next

        // Akwam may not include season 1 among recent search results.
        // Resolve it only through Akwam's own related-season navigation.
        val normalized = clean.replace(Regex("""\s+"""), " ").trim()
        val base = !Regex("""(?:الموسم|موسم|الجزء|جزء|season|part|(?:^|\s)ج\s*\d)""",
            RegexOption.IGNORE_CASE).containsMatchIn(normalized) &&
            !normalized.contains(Regex("""\d"""))
        val foundBase = parsed.any {
            it is TvSeriesSearchResponse && it.name.trim().equals(normalized, true)
        }
        if (!base || foundBase) return parsed to next
        val candidate = parsed.firstOrNull {
            it is TvSeriesSearchResponse &&
                it.name.startsWith(normalized, ignoreCase = true) &&
                it.name.contains("موسم")
        } ?: return parsed to next
        val historical = withTimeoutOrNull(3500L) {
            try {
                val other = app.get(candidate.url).document
                val link = other.select("a[href*='/series/']").firstOrNull {
                    it.text().replace(Regex("""\s+"""), " ").trim().equals(normalized, true)
                }
                link?.attr("abs:href")?.takeIf(String::isNotBlank)?.let {
                    newTvSeriesSearchResponse(normalized, it, TvType.TvSeries)
                }
            } catch (_: Exception) { null }
        }
        return (parsed + listOfNotNull(historical)).distinctBy { it.url } to next
    }

    override suspend fun search(query: String): List<SearchResponse> =
        searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private fun String.normalizedDigits(): String = buildString {
        for (digit in this@normalizedDigits) {
            append(
                when (digit) {
                    '٠', '۰' -> '0'; '١', '۱' -> '1'; '٢', '۲' -> '2'; '٣', '۳' -> '3'
                    '٤', '۴' -> '4'; '٥', '۵' -> '5'; '٦', '۶' -> '6'; '٧', '۷' -> '7'
                    '٨', '۸' -> '8'; '٩', '۹' -> '9'; else -> digit
                }
            )
        }
    }

    private fun String.firstNumber(): Int? =
        Regex("\\d+").find(normalizedDigits())?.value?.toIntOrNull()

    private fun String.episodeNumber(): Int? {
        val text = normalizedDigits().lowercase()
            .replace('ة', 'ه')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
        Regex("""(?:s\s*\d{1,2}\s*e|الحلقه|حلقه|episode|ep)[\s:_-]*(\d{1,4})""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val ordinal = listOf(
            "الاولى" to 1, "الاول" to 1, "الثانيه" to 2, "الثاني" to 2,
            "الثالثه" to 3, "الثالث" to 3, "الرابعه" to 4, "الرابع" to 4,
            "الخامسه" to 5, "الخامس" to 5, "السادسه" to 6, "السادس" to 6,
            "السابعه" to 7, "السابع" to 7, "الثامنه" to 8, "الثامن" to 8,
            "التاسعه" to 9, "التاسع" to 9, "العاشره" to 10, "العاشر" to 10
        )
        if (text.matches(Regex("""\d{1,4}"""))) return text.toIntOrNull()
        ordinal.firstOrNull { text.contains(it.first) }?.let { return it.second }
        Regex("""(?:^|[-_/])(?:episode|ep|الحلقه|حلقه)[-_ ]*(\d{1,4})(?:\D.*)?$""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun String.seasonNumber(): Int? {
        val text = normalizedDigits().lowercase()
        Regex("(?:الموسم|موسم|season|s|ج|جزء|الجزء)[\\s:_-]*(\\d{1,2})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val words = listOf(
            "الاول" to 1, "الأول" to 1, "الثاني" to 2, "الثالث" to 3,
            "الرابع" to 4, "الخامس" to 5, "السادس" to 6, "السابع" to 7,
            "الثامن" to 8, "التاسع" to 9, "العاشر" to 10
        )
        return words.firstOrNull { text.contains("الموسم " + it.first) || text.contains("season " + it.first) }?.second
    }

    private fun publishedYear(doc: org.jsoup.nodes.Document): Int? {
        // Never read the first four-digit number from the entire HTML:
        // Akwam shows unrelated recommended movies and their years.
        val raw = doc.selectFirst(
            "meta[itemprop=datePublished], meta[property='video:release_date'], " +
                "meta[property='article:published_time']"
        )?.attr("content").orEmpty()
        return Regex("""\b(?:19|20)\d{2}\b""")
            .find(raw)?.value?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title,h1")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")?.takeIf(String::isNotBlank)?.let(::fixUrl)
            ?: doc.selectFirst("img")?.let { img ->
                sequenceOf("data-src", "data-original", "src").map(img::attr)
                    .firstOrNull(String::isNotBlank)?.let(::fixUrl)
            }
        val isSeries = url.contains("/series/")
        val episodeAnchors = if (isSeries) doc.select("a[href*='/episode/']") else emptyList()
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                year = publishedYear(doc)
                plot = doc.select("meta[name=description]").attr("content")
            }
        }
        // Akwam's title "العتاولة 2" means season 2. Never interpret
        // the numeric episode ID or a bare "حلقة 30" as a season.
        val pageSeason = title.seasonNumber()
            ?: Regex("""\s+(\d{1,2})\s*$""").find(title.normalizedDigits())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 2..20 }
            ?: 1
        val episodes = episodeAnchors
            .filter { it.text().isNotBlank() || it.attr("title").isNotBlank() }
            .distinctBy { it.attr("abs:href").ifEmpty { it.attr("href") } }
            .mapNotNull { a ->
                val href = a.attr("abs:href").ifEmpty { fixUrl(a.attr("href")) }
                if (href.isBlank()) return@mapNotNull null
                // The previous parent text crawled as high as the whole
                // series page, including "other seasons". That made S1
                // episodes inherit S2/S3/S4 from unrelated navigation links.
                val episodeName = a.text().trim()
                    .ifBlank { a.attr("title").trim() }
                    .ifBlank { href.substringAfterLast('/').replace('-', ' ') }
                val episodeNumber = episodeName.episodeNumber()
                    ?: href.episodeNumber()

                // The series title establishes the page season; season 1
                // intentionally has no explicit season name on Akwam.
                newEpisode(href) {
                    name = episodeNumber?.let { "الحلقة $it" } ?: episodeName
                    episode = episodeNumber
                    season = pageSeason
                }
            }
            .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            year = publishedYear(doc)
            plot = doc.select("meta[name=description]").attr("content")
        }
    }


    private fun extractEmbeddedMediaUrls(html: String, pageUrl: String): List<String> {
        val document = org.jsoup.Jsoup.parse(html, pageUrl)
        val candidates = linkedSetOf<String>()
        document.select("video[src],video source[src],source[src]").forEach { element ->
            element.attr("abs:src").takeIf { it.isNotBlank() }?.let(candidates::add)
        }

        val bodies = mutableListOf(html)
        document.select("script").forEach { script ->
            val text = script.data().ifBlank { script.html() }
            if (text.length <= 250_000) {
                bodies += text
                runCatching { JsUnpacker(text).unpack() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && it.length <= 500_000 }
                    ?.let(bodies::add)
            }
        }

        val mediaPattern = Regex(
            """(?i)https?://[^\s"'<>]+?\.(?:m3u8|mp4|webm|mov)(?:\?[^\s"'<>]*)?"""
        )
        val propertyPattern = Regex(
            """(?i)(?:file|src|source|hls|playlist)\s*[:=]\s*["']([^"']+)["']"""
        )
        for (body in bodies) {
            val normalized = body
                .replace("\\/", "/")
                .replace("\\" + "u0026", "&")
                .replace("&amp;", "&")
            mediaPattern.findAll(normalized).forEach { match ->
                candidates += match.value.trimEnd(')', ']', '}', ',')
            }
            propertyPattern.findAll(normalized).forEach { match ->
                val value = match.groupValues[1].trim()
                if (Regex("""(?i)\.(?:m3u8|mp4|webm|mov)(?:[?#]|$)""").containsMatchIn(value)) {
                    candidates += value
                }
            }
        }

        return candidates.mapNotNull { raw ->
            try {
                when {
                    raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                    raw.startsWith("//") -> "https:$raw"
                    else -> java.net.URI(pageUrl).resolve(raw).toString()
                }
            } catch (_: Exception) {
                null
            }
        }.distinct()
    }

    private data class PlayerPage(
        val url: String,
        val depth: Int,
        val referer: String,
        val qualityHint: Int? = null
    )

    // Quality tab IDs are site-provided identifiers, not video URL guesses.
    // Verified against current Akwam CloudStream provider implementations.
    private fun qualityFromTab(element: Element): Int? {
        val id = Regex("""\d+""")
            .find(element.attr("id"))
            ?.value
            ?.toIntOrNull()

        val mapped = when (id) {
            2 -> 360
            3 -> 480
            4 -> 720
            5 -> 1080
            else -> null
        }
        if (mapped != null) return mapped

        val label = listOf(
            element.attr("data-quality"),
            element.attr("title"),
            element.attr("aria-label")
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        return Regex("""(?i)(?<!\d)(2160|1440|1080|720|480|360)p?(?!\d)""")
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun qualityFromMediaUrl(url: String): Int? =
        Regex("""(?i)(?<!\d)(2160|1440|1080|720|576|480|360)p?(?!\d)""")
            .find(java.net.URI(url).path.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = linkedSetOf<String>()
        val pending = mutableListOf(PlayerPage(data, 0, mainUrl))
        var cursor = 0
        var emitted = false
        var foundDirectHdTransport = false
        val ownHost = runCatching { java.net.URI(mainUrl).host.lowercase() }.getOrNull().orEmpty()
        val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val emittedMediaUrls = linkedSetOf<String>()
        val mediaHostCounters = mutableMapOf<String, Int>()

        // Emit an HTTP mirror immediately when the source is found. In v56
        // this happened after the entire page crawl, so the 45s resolver
        // timeout commonly discarded HTTP alternatives before emitting them.
        @Suppress("DEPRECATION")
        fun emitLink(link: ExtractorLink) {
            // No SD and no unknown-resolution links, even in standalone
            // Akwam. Keep the confirmed 720p/1080p Downet paths intact.
            if (!MOOSAQualityPolicy.accepts(link)) return
            if (!emittedMediaUrls.add(link.url.trim())) return

            callback(link)
            emitted = true

            val isDownet = runCatching {
                val host = java.net.URI(link.url).host.orEmpty().lowercase()
                host == "downet.net" || host.endsWith(".downet.net")
            }.getOrDefault(false)

            // Some mirrors already deliver a clear HTTP MP4 directly.
            // This native provider displays it directly.
            if (isDownet && link.url.startsWith("http://", true)) {
                foundDirectHdTransport = true
            }

            if (
                !isDownet ||
                !link.url.startsWith("https://", ignoreCase = true) ||
                link.type != ExtractorLinkType.VIDEO
            ) return

            // Public MP4 transport alternative; explicitly disclose that
            // the HTTP stream is unencrypted. Never send auth headers.
            val httpUrl = link.url.replaceFirst(
                "https://",
                "http://",
                ignoreCase = true
            )
            if (!emittedMediaUrls.add(httpUrl)) return

            val host = runCatching {
                java.net.URI(httpUrl).host.orEmpty()
            }.getOrDefault("downet.net")
            val safeHeaders = link.headers.filterKeys { key ->
                !key.equals("Cookie", true) &&
                    !key.equals("Authorization", true) &&
                    !key.equals("Proxy-Authorization", true)
            }

            val alternate = ExtractorLink(
                source = this.name,
                name = "Akwam HTTP (غير مشفر) • $host",
                url = httpUrl,
                referer = link.referer,
                quality = link.quality,
                headers = safeHeaders,
                type = ExtractorLinkType.VIDEO
            )
            if (MOOSAQualityPolicy.accepts(alternate)) {
                callback(alternate)
                foundDirectHdTransport = true
            }
        }

        fun labelFor(url: String, quality: Int): String {
            val host = runCatching { java.net.URI(url).host.orEmpty() }
                .getOrDefault("")
                .removePrefix("www.")
                .ifBlank { "Direct" }
            val key = "$host|$quality"
            val index = (mediaHostCounters[key] ?: 0) + 1
            mediaHostCounters[key] = index

            val q = if (quality in listOf(2160, 1440, 1080, 720, 480, 360)) {
                " ${quality}p"
            } else {
                " • quality unknown"
            }
            return "Akwam$q • $host" + if (index > 1) " • mirror $index" else ""
        }

        suspend fun emitMedia(
            mediaUrl: String,
            referer: String,
            qualityHint: Int? = null
        ) {
            if (qualityHint != null && qualityHint < MOOSAQualityPolicy.MIN_HEIGHT) {
                return
            }
            val isHls = mediaUrl.contains(".m3u8", true) ||
                mediaUrl.contains("playlist", true)
            val headers = mapOf(
                "User-Agent" to desktopUa,
                "Referer" to referer,
                "Accept" to "*/*"
            )
            val urlQuality = qualityFromMediaUrl(mediaUrl) ?: qualityHint
            // Don't request SD HLS manifests even when encountered in JS.
            if (urlQuality != null && urlQuality < MOOSAQualityPolicy.MIN_HEIGHT) {
                return
            }

            if (isHls) {
                val variants = try {
                    M3u8Helper().m3u8Generation(
                        M3u8Helper.M3u8Stream(
                            streamUrl = mediaUrl,
                            quality = urlQuality,
                            headers = headers
                        ),
                        false
                    )
                } catch (_: Exception) {
                    emptyList()
                }

                if (variants.isNotEmpty()) {
                    for (variant in variants) {
                        val quality = variant.quality
                            ?.takeUnless { it == Qualities.Unknown.value }
                            ?: urlQuality
                            ?: Qualities.Unknown.value
                        val link = newExtractorLink(
                            source = this.name,
                            name = labelFor(variant.streamUrl, quality) + " • HLS",
                            url = variant.streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = quality
                            this.type = ExtractorLinkType.M3U8
                            this.headers = headers
                        }
                        emitLink(link)
                    }
                    return
                }
            }

            val quality = urlQuality?.let { height ->
                when (height) {
                    2160 -> Qualities.P2160.value
                    1440 -> Qualities.P1440.value
                    1080 -> Qualities.P1080.value
                    720 -> Qualities.P720.value
                    576 -> Qualities.Unknown.value
                    480 -> Qualities.P480.value
                    360 -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
            } ?: Qualities.Unknown.value
            val link = newExtractorLink(
                source = this.name,
                name = labelFor(mediaUrl, quality) + if (isHls) " • HLS" else "",
                url = mediaUrl,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality
                this.type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.headers = headers
            }
            emitLink(link)
        }

        // The stream comes directly from Akwam's matching item page. Stop the
        // expensive page crawl as soon as its HD HTTP transport is delivered;
        // continuing through 40 Akwam/iframe pages made the result spinner
        // wait long after the only usable server had already been discovered.
        while (cursor < pending.size && cursor < 18 && !foundDirectHdTransport) {
            val (url, depth, referer, qualityHint) = pending[cursor++]
            if (!visited.add(url)) continue
            val response = try {
                withTimeoutOrNull(8000L) {
                    app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to desktopUa,
                            "Referer" to referer,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                        ),
                        referer = referer
                    )
                }
            } catch (_: Exception) { null } ?: continue
            // The short domain currently redirects to another Akwam host.
            // Relative player links must be resolved against the final URL.
            val pageUrl = response.url
            val page = response.document

            // Read quality tabs first so /link/ and /download/ wrappers keep
            // their confirmed Akwam quality throughout redirects.
            val qualityTabs = page.select("div.tab-content.quality")
                .sortedByDescending { qualityFromTab(it) ?: 0 }
            for (tab in qualityTabs) {
                val quality = qualityFromTab(tab)
                // Skip SD tabs before scheduling their /link/ pages.
                if (quality != null && !MOOSAQualityPolicy.acceptsHeight(quality)) {
                    continue
                }
                for (anchor in tab.select("a[href]")) {
                    val raw = anchor.attr("href")
                    if (
                        !raw.contains("/link/") &&
                        !raw.contains("/download/") &&
                        !raw.contains("/watch/")
                    ) continue

                    val absolute = runCatching {
                        java.net.URI(pageUrl).resolve(raw).toString()
                    }.getOrNull() ?: continue

                    if (depth < 4 && !visited.contains(absolute)) {
                        pending.add(PlayerPage(absolute, depth + 1, pageUrl, quality))
                    }
                }
            }

            // The content landing page may contain unrelated video ads.
            // When Akwam quality tabs exist, prefer their specific linked
            // watch/download pages instead of scanning all landing scripts.
            if (depth > 0 || qualityTabs.isEmpty()) {
                for (media in extractEmbeddedMediaUrls(response.text, pageUrl)) {
                    emitMedia(media, pageUrl, qualityHint)
                }
            }

            val selectors = "li[data-link],a.watchBTn,a.link-show,a.aplr-link,a[data-link],a[href*='/watch/'],a[href*='/download/'],a[href*='serv='],a.link-download[href],a.link-btn[href],a[href*='downet.net'],a[href*='.mp4'],div.btn-loader > a[href],a.download-link[href],div.tab-content.quality a[href],iframe[src],iframe[data-src],iframe[data-lazy-src],video[src],video source[src],source[src]";

            for (element in page.select(selectors)) {
                if (foundDirectHdTransport) break
                val raw = element.attr("data-link")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data-lazy-src") }
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .trim()
                if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true)) continue
                val absolute = try {
                    when {
                        raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                        raw.startsWith("//") -> "https:$raw"
                        else -> java.net.URI(pageUrl).resolve(raw).toString()
                    }
                } catch (_: Exception) {
                    continue
                }
                if (absolute == url) continue
                val host = runCatching { java.net.URI(absolute).host?.lowercase().orEmpty() }.getOrNull().orEmpty()
                if (host.isBlank()) continue
                // The main content page may embed unrelated advertising.
                // When quality tabs exist, prefer those actual source links.
                if (depth == 0 && qualityTabs.isNotEmpty() &&
                    !absolute.contains("/link/") &&
                    !absolute.contains("/download/") &&
                    !absolute.contains("/watch/") &&
                    element.closest("div.tab-content.quality") == null
                ) continue
                val isOwnHost = host == ownHost ||
                    host == "akwam.ss" || host.endsWith(".akwam.ss") ||
                    host == "akwam.net" || host.endsWith(".akwam.net") ||
                    host == "ak.sv" || host.endsWith(".ak.sv")
                val isKnownWrapper = false
                val isIframe = element.tagName().equals("iframe", ignoreCase = true)
                val isDirectMedia = element.tagName().equals("video", true) ||
                    element.tagName().equals("source", true) ||
                    Regex("""(?i)\.(?:m3u8|mp4|webm|mov)(?:[?#]|$)""").containsMatchIn(absolute)

                val elementQuality = element.closest("div.tab-content.quality")
                    ?.let(::qualityFromTab)
                    ?: qualityHint
                if (
                    elementQuality != null &&
                    !MOOSAQualityPolicy.acceptsHeight(elementQuality)
                ) continue

                if (isDirectMedia) {
                    emitMedia(absolute, pageUrl, elementQuality)
                    continue
                }

                if (depth < 4 && (isOwnHost || isKnownWrapper)) {
                    pending.add(PlayerPage(absolute, depth + 1, pageUrl, elementQuality))
                    continue
                }

                try {
                    withTimeoutOrNull(6500L) {
                        loadExtractor(absolute, pageUrl, subtitleCallback) { link ->
                            if (link.quality == Qualities.Unknown.value &&
                                elementQuality != null) link.quality = elementQuality
                            emitLink(link)
                        }
                    }
                } catch (_: Exception) {
                    // Keep traversing the server page even when CloudStream has no extractor for its host.
                }

                // Server tab URLs (for example ?serv=1), iframe sources, and data-link URLs
                // are page hops. Follow them even when loadExtractor has no matching extractor.
                val isServerTarget = isIframe ||
                    element.hasAttr("data-link") ||
                    absolute.contains("serv=", true) ||
                    absolute.contains("server=", true) ||
                    absolute.contains("/watch/", true) || absolute.contains("/download/", true)
                if (depth < 4 && isServerTarget && !visited.contains(absolute)) {
                    pending.add(PlayerPage(absolute, depth + 1, pageUrl, elementQuality))
                }
            }
        }
        return emitted
    }
}
