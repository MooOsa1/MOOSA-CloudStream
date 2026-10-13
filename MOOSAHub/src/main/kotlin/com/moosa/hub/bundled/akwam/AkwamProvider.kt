// Bundled from ramailo1/arprov (cb10a262a9fae679322c264973cf5c0f5219acdc); MIT licensed. See THIRD_PARTY_NOTICES.md.
package com.moosa.hub.bundled.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AkwamProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://akwam.ss"
    override var name = "Akwam"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.Cartoon)

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
        val poster = anchor.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: anchor.parents().firstOrNull { it.selectFirst("img") != null }
            ?.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val tvType = if (href.contains("/series/")) TvType.TvSeries else TvType.Movie
        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, tvType) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, tvType) { posterUrl = poster }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies?page=" to "Movies",
        "$mainUrl/series?section=29&page=" to "مسلسلات عربي",
        "$mainUrl/series?page=" to "Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = request.data + page
        val doc = app.get(url).document
        val items = doc.select("a[href*='/series/'],a[href*='/movie/']")
            .distinctBy { it.attr("abs:href").ifEmpty { it.attr("href") } }
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/search?q=$encoded").document
        return doc.select("a[href*='/series/'],a[href*='/movie/']")
            .distinctBy { it.attr("abs:href").ifEmpty { it.attr("href") } }
            .mapNotNull { it.toSearchResponse() }
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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title,h1")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val isSeries = url.contains("/series/")
        val episodeAnchors = if (isSeries) doc.select("a[href*='/episode/']") else emptyList()
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                year = Regex("\\b(19|20)\\d{2}\\b").find(doc.text())?.value?.toIntOrNull()
                plot = doc.select("meta[name=description]").attr("content")
            }
        }
        val pageSeason = (title + " " + url).seasonNumber()
        val episodes = episodeAnchors.distinctBy { it.attr("abs:href").ifEmpty { it.attr("href") } }
            .mapNotNull { a ->
                val href = a.attr("abs:href").ifEmpty { fixUrl(a.attr("href")) }
                if (href.isBlank()) return@mapNotNull null
                val context = buildString {
                    append(a.text()).append(' ').append(a.attr("href"))
                    a.parents().take(4).forEach { append(' ').append(it.text()) }
                }
                val episodeName = a.text().ifBlank { context }
                val episodeNumber = episodeName.episodeNumber() ?: href.episodeNumber()
                newEpisode(href) {
                    name = episodeName
                    episode = episodeNumber
                    season = context.seasonNumber() ?: pageSeason
                }
            }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            year = Regex("\\b(19|20)\\d{2}\\b").find(doc.text())?.value?.toIntOrNull()
            plot = doc.select("meta[name=description]").attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = linkedSetOf<String>()
        val pending = mutableListOf(Triple(data, 0, mainUrl))
        var cursor = 0
        var emitted = false
        val ownHost = runCatching { java.net.URI(mainUrl).host.lowercase() }.getOrNull().orEmpty()

        while (cursor < pending.size && cursor < 20) {
            val (url, depth, referer) = pending[cursor++]
            if (!visited.add(url)) continue
            val page = try {
                app.get(url, headers = mapOf("Referer" to referer)).document
            } catch (_: Exception) {
                continue
            }

            val selectors = "li[data-link],a.watchBTn,a[data-link],a[href*='/watch/'],a[href*='/download/'],div.tab-content.quality a[href],iframe[src],iframe[data-src],iframe[data-lazy-src],video[src],video source[src],source[src]"

            for (element in page.select(selectors)) {
                val raw = element.attr("data-link")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data-lazy-src") }
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .trim()
                if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true)) continue
                val absolute = try {
                    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw
                    else java.net.URI(url).resolve(raw).toString()
                } catch (_: Exception) {
                    continue
                }
                if (absolute == url) continue
                val host = runCatching { java.net.URI(absolute).host?.lowercase().orEmpty() }.getOrNull().orEmpty()
                if (host.isBlank()) continue
                val isOwnHost = host == ownHost || host.endsWith(".akwam.ss") || host.endsWith(".akwam.net") || host.endsWith(".ak.sv")
                val isKnownWrapper = false
                val isIframe = element.tagName().equals("iframe", ignoreCase = true)

                if (depth < 3 && (isOwnHost || isKnownWrapper)) {
                    pending.add(Triple(absolute, depth + 1, url))
                    continue
                }

                try {
                    loadExtractor(absolute, url, subtitleCallback) { link ->
                        emitted = true
                        callback(link)
                    }
                } catch (_: Exception) {
                    // Keep trying the other server entries.
                }

                if (depth < 3 && isIframe && !visited.contains(absolute)) {
                    pending.add(Triple(absolute, depth + 1, url))
                }
            }
        }
        return emitted
    }
}
}
