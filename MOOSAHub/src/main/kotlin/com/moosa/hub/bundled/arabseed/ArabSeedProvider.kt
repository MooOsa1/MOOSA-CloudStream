// Bundled from ramailo1/arprov (cb10a262a9fae679322c264973cf5c0f5219acdc); MIT licensed. See THIRD_PARTY_NOTICES.md.
package com.moosa.hub.bundled.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ArabSeedProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://arabseed.sabaq.news"
    override var name = "ArabSeed"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun Element.cardTitle(): String =
        selectFirst("h1,h2,h3,.entry-title,.post-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: attr("title").takeIf { it.isNotEmpty() }
            ?: text().trim()

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName() == "a") this
            else selectFirst("a[href]") ?: return null
        val href = anchor.attr("abs:href").ifEmpty { fixUrl(anchor.attr("href")) }
        if (href.isBlank() || href == mainUrl || href.contains("/category/") ||
            href.contains("/tag/") || href.contains("/actor/")) return null
        val title = anchor.cardTitle().ifBlank {
            anchor.parents().firstOrNull { it.cardTitle().isNotBlank() }?.cardTitle().orEmpty()
        }
        if (title.isBlank()) return null
        val image = anchor.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: anchor.parents().firstOrNull { it.selectFirst("img") != null }
            ?.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val isSeries = href.contains("/series/") ||
            title.contains("مسلسل") || title.contains("الحلقة") || title.contains("الحلقه")
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, type) { posterUrl = image }
        } else {
            newMovieSearchResponse(title, href, type) { posterUrl = image }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/recently/" to "المضاف حديثا",
        "$mainUrl/category/movies/arabic-movies/" to "أفلام عربي",
        "$mainUrl/category/tv/arabic-series/" to "مسلسلات عربي",
        "$mainUrl/category/tv/" to "مسلسلات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val results = doc.select("a[href]")
            .filter { a ->
                val href = a.attr("abs:href")
                href.contains("/series/") || href.contains("الحلقة") ||
                    href.contains("episode", ignoreCase = true)
            }
            .distinctBy { it.attr("abs:href").ifEmpty { it.attr("href") } }
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        return doc.select("a[href]")
            .filter { a ->
                val href = a.attr("abs:href")
                !href.contains("/category/") && !href.contains("/tag/") &&
                    (href.contains("/series/") || a.text().contains("فيلم") ||
                        a.text().contains("مسلسل") || a.text().contains("الحلقة"))
            }
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

    private fun String.seasonNumber(): Int? {
        val text = normalizedDigits().lowercase()
        Regex("(?:الموسم|موسم|season|s)[\\s:_-]*(\\d{1,2})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val words = listOf(
            "الأول" to 1, "الاول" to 1, "الثاني" to 2, "الثالث" to 3,
            "الرابع" to 4, "الخامس" to 5, "السادس" to 6, "السابع" to 7,
            "الثامن" to 8, "التاسع" to 9, "العاشر" to 10
        )
        return words.firstOrNull { text.contains("الموسم " + it.first) || text.contains("season " + it.first) }?.second
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1,.post__name")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val articleText = doc.text()
        val episodeAnchors = doc.select("a[href]").filter { a ->
            val text = a.text().lowercase()
            text.contains("الحلقة") || text.contains("الحلقه") ||
                a.attr("href").contains("episode", ignoreCase = true)
        }
        val isSeries = url.contains("/series/") || title.contains("مسلسل") ||
            title.contains("الحلقة") || title.contains("الحلقه")
        val year = Regex("\\b(19|20)\\d{2}\\b").find(articleText)?.value?.toIntOrNull()
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = doc.select("meta[name=description]").attr("content")
            }
        }
        val pageSeason = (title + " " + articleText.take(500)).seasonNumber()
        val episodes = episodeAnchors.distinctBy { it.attr("abs:href").ifEmpty { it.attr("href") } }
            .mapNotNull { a ->
                val href = a.attr("abs:href").ifEmpty { fixUrl(a.attr("href")) }
                if (href.isBlank() || href == url) return@mapNotNull null
                val context = buildString {
                    append(a.text()).append(' ').append(a.attr("href"))
                    a.parents().take(4).forEach { append(' ').append(it.text().take(400)) }
                }
                val epName = a.text().ifBlank { context }
                newEpisode(href) {
                    name = epName
                    episode = epName.firstNumber() ?: a.attr("href").firstNumber()
                    season = context.seasonNumber() ?: pageSeason
                }
            }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = doc.select("meta[name=description]").attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val viewLinks = doc.select("a[href]").map { a ->
            a.attr("abs:href").ifEmpty { fixUrl(a.attr("href")) }
        }.filter { it.contains("/watch/") || it == data && data.contains("/watch/") }
            .distinct()
        val startUrls = if (viewLinks.isNotEmpty()) viewLinks else listOf(data)
        for (url in startUrls) {
            try {
                val page = app.get(url, headers = mapOf("Referer" to data)).document
                val links = linkedSetOf<String>()
                page.select("a[href],iframe[src],video[src],video source[src]").forEach { el ->
                    val raw = if (el.hasAttr("href")) el.attr("href") else el.attr("src")
                    if (raw.isBlank()) return@forEach
                    val absolute = el.attr("abs:href").ifEmpty { el.attr("abs:src") }.ifEmpty { fixUrl(raw) }
                    val host = absolute.substringAfter("://", "").substringBefore('/').lowercase()
                    if (absolute.contains(".m3u8") || absolute.contains(".mp4") ||
                        (host.isNotBlank() && !host.contains("arabseed.sabaq.news"))
                    ) links.add(absolute)
                }
                for (link in links) {
                    try { loadExtractor(link, url, subtitleCallback, callback) } catch (_: Exception) { }
                }
            } catch (_: Exception) {
                // A failed page/host is isolated so other server links can still be tried.
            }
        }
        return viewLinks.isNotEmpty()
    }
}
