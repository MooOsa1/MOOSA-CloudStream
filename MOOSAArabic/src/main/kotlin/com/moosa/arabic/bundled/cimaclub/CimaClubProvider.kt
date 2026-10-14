package com.moosa.hub.bundled.cimaclub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.moosa.hub.MOOSAQualityPolicy
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CimaClubProvider : MainAPI() {
    override var mainUrl = "https://cimacub.com"
    override var name = "CimaClub"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun fix(raw: String, base: String = mainUrl): String? {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("#") || value.startsWith("javascript:", true)) {
            return null
        }
        return runCatching {
            when {
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> java.net.URI(base).resolve(value).toString()
            }
        }.getOrNull()
    }

    private fun card(element: Element): SearchResponse? {
        val a = element.selectFirst("a[href]") ?: return null
        val href = fix(a.attr("href")) ?: return null
        val title =
            element.selectFirst(".inner--title h2, .inner--title h3, h2, h3")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return null

        val poster = element.selectFirst("img")?.let {
            it.attr("data-src")
                .ifBlank { it.attr("data-lazy-src") }
                .ifBlank { it.attr("src") }
        }?.let { fix(it, href) }

        val isSeries =
            href.contains("/series/", true) ||
                href.contains("مسلسل") ||
                title.contains("مسلسل") ||
                element.selectFirst(".number, .epnum") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-عربي/" to "أفلام عربي",
        "$mainUrl/category/مسلسلات-عربي/" to "مسلسلات عربي",
        "$mainUrl/category/افلام-اجنبي/" to "أفلام أجنبي",
        "$mainUrl/category/مسلسلات-اجنبي/" to "مسلسلات أجنبي"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url =
            if (page <= 1) request.data
            else "${request.data.trimEnd('/')}/page/$page/"

        val doc = app.get(url).document
        val items = doc.select(
            "div.BlocksHolder > div.Small--Box, div.Small--Box, article"
        ).mapNotNull(::card)

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded").document
        return doc.select(
            "div.BlocksHolder > div.Small--Box, div.Small--Box, article"
        ).mapNotNull(::card)
            .distinctBy { it.url }
    }

    private fun number(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val normalized = buildString {
            value.forEach { ch ->
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
        return Regex("""\d{1,4}""")
            .findAll(normalized)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title =
            doc.selectFirst("h1.PostTitle, h1.post-title, h1")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank { url.substringAfterLast('/').replace('-', ' ') }

        val poster =
            doc.selectFirst(".MainSingle .left .image img, meta[property=og:image], img")
                ?.let {
                    if (it.tagName() == "meta") it.attr("content")
                    else it.attr("data-src").ifBlank { it.attr("src") }
                }
                ?.let { fix(it, url) }

        val plot =
            doc.selectFirst(".StoryArea p, .story p, meta[name=description]")
                ?.let {
                    if (it.tagName() == "meta") it.attr("content") else it.text()
                }
                ?.replace("قصة العرض", "")
                ?.trim()

        val year =
            doc.selectFirst(".TaxContent a[href*='/release-year/'], a[href*='/year/']")
                ?.text()
                ?.let(::number)

        val isSeries =
            url.contains("/series/", true) ||
                url.contains("مسلسل") ||
                doc.select("section.allepcont .row a, .allepcont a").isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasonLinks =
            doc.select("section.allseasonss .Small--Box a[href], .allseasonss a[href]")
                .distinctBy { it.attr("href") }

        if (seasonLinks.isNotEmpty()) {
            seasonLinks.amap { seasonLink ->
                val seasonUrl = fix(seasonLink.attr("href"), url) ?: return@amap emptyList<Episode>()
                val seasonNum =
                    number(
                        seasonLink.selectFirst(".epnum, .number")
                            ?.text()
                            .orEmpty() + " " + seasonLink.text()
                    )

                val seasonDoc =
                    if (seasonUrl.trimEnd('/') == url.trimEnd('/')) doc
                    else runCatching { app.get(seasonUrl).document }.getOrNull()
                        ?: return@amap emptyList<Episode>()

                seasonDoc.select(
                    "section.allepcont .row a[href], .allepcont .row a[href], .allepcont a[href]"
                ).mapNotNull { ep ->
                    val epUrl = fix(ep.attr("href"), seasonUrl) ?: return@mapNotNull null
                    val epName =
                        ep.selectFirst(".ep-info h2, h2, h3")
                            ?.text()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: ep.text().trim()
                    val epNum =
                        number(
                            ep.selectFirst(".epnum, .number")
                                ?.text()
                                .orEmpty() + " " + epName
                        )
                    newEpisode(epUrl) {
                        name = epName.ifBlank { "الحلقة ${epNum ?: ""}" }
                        season = seasonNum
                        episode = epNum
                        posterUrl = poster
                    }
                }
            }.flatten().let(episodes::addAll)
        }

        if (episodes.isEmpty()) {
            doc.select(
                "section.allepcont .row a[href], .allepcont .row a[href], .allepcont a[href]"
            ).mapNotNullTo(episodes) { ep ->
                val epUrl = fix(ep.attr("href"), url) ?: return@mapNotNullTo null
                val epName =
                    ep.selectFirst(".ep-info h2, h2, h3")
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: ep.text().trim()
                val epNum =
                    number(
                        ep.selectFirst(".epnum, .number")
                            ?.text()
                            .orEmpty() + " " + epName
                    )
                newEpisode(epUrl) {
                    name = epName.ifBlank { "الحلقة ${epNum ?: ""}" }
                    episode = epNum
                    posterUrl = poster
                }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val contentUrl = data.trimEnd('/')
        val candidates = linkedSetOf(
            contentUrl,
            "$contentUrl/watch/"
        )

        val seen = linkedSetOf<String>()
        var emitted = false

        suspend fun emitTarget(raw: String, referer: String) {
            val target = fix(raw, referer) ?: return
            val declaredQuality = MOOSAQualityPolicy.labeledHeight(target)
            if (declaredQuality != null &&
                declaredQuality < MOOSAQualityPolicy.MIN_HEIGHT) return
            if (!seen.add(target)) return

            if (
                target.contains(".m3u8", true) ||
                target.contains(".mp4", true)
            ) {
                val isHls = target.contains(".m3u8", true)
                val directLink = newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = target,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        quality = Regex("""(?i)(2160|1440|1080|720|480|360)""")
                            .find(target)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: Qualities.Unknown.value
                    }
                if (MOOSAQualityPolicy.accepts(directLink)) {
                    callback(directLink)
                    emitted = true
                }
                return
            }

            try {
                loadExtractor(target, referer, subtitleCallback) { link ->
                    if (
                        MOOSAQualityPolicy.accepts(link) &&
                        seen.add(link.url)
                    ) {
                        callback(link)
                        emitted = true
                    }
                }
            } catch (_: Throwable) {
            }
        }

        for (url in candidates) {
            val docs = mutableListOf<org.jsoup.nodes.Document>()

            runCatching {
                app.post(
                    url,
                    data = mapOf("watch" to "1"),
                    headers = mapOf("Referer" to contentUrl)
                ).document
            }.getOrNull()?.let(docs::add)

            runCatching {
                app.get(
                    url,
                    headers = mapOf("Referer" to contentUrl)
                ).document
            }.getOrNull()?.let(docs::add)

            for (doc in docs) {
                doc.select(
                    "ul#watch li[data-watch], [data-watch], iframe[src], iframe[data-src], .ServersList.Download a[href], a[href*='.mp4'], a[href*='.m3u8']"
                ).forEach { element ->
                    val raw =
                        element.attr("data-watch")
                            .ifBlank { element.attr("data-src") }
                            .ifBlank { element.attr("src") }
                            .ifBlank { element.attr("href") }
                    if (raw.isNotBlank()) {
                        emitTarget(raw, url)
                    }
                }
            }
        }

        return emitted
    }
}
