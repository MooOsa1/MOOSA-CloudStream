package com.moosa.hub.bundled.wecima

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.moosa.hub.MOOSAQualityPolicy
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.ac"
    override var name = "WeCima"
    override var lang = "ar"
    override val hasMainPage = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$mainUrl/"
        )

    private fun updateBase(finalUrl: String) {
        runCatching {
            val uri = java.net.URI(finalUrl)
            val host = uri.host ?: return
            val scheme = uri.scheme ?: "https"
            mainUrl = "$scheme://$host"
        }
    }

    private suspend fun getDoc(
        url: String,
        referer: String? = null
    ): org.jsoup.nodes.Document {
        val response = app.get(
            url,
            referer = referer ?: mainUrl,
            headers = headers,
            allowRedirects = true,
            interceptor = cloudflareKiller
        )
        updateBase(response.url)
        return response.document
    }

    private suspend fun postText(
        url: String,
        data: Map<String, String>,
        referer: String
    ): String {
        val response = app.post(
            url,
            data = data,
            referer = referer,
            headers = headers + mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            allowRedirects = true,
            interceptor = cloudflareKiller
        )
        updateBase(response.url)
        return response.text
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/arabic-series" to "مسلسلات عربية",
        "$mainUrl/category/arabic-movies" to "أفلام عربية",
        "$mainUrl/seriestv" to "أحدث المسلسلات",
        "$mainUrl/movies" to "أحدث الأفلام"
    )

    private fun Element.poster(base: String): String? {
        val raw =
            selectFirst("span.BG--GridItem")
                ?.attr("data-src")
                ?.takeIf { it.isNotBlank() }
                ?: selectFirst("span.BG--GridItem")
                    ?.attr("style")
                    ?.let {
                        Regex("""url\(['"]?(.*?)['"]?\)""")
                            .find(it)
                            ?.groupValues
                            ?.getOrNull(1)
                    }
                ?: selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }

        return fix(raw.orEmpty(), base)
    }

    private fun fix(raw: String, base: String = mainUrl): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return runCatching {
            when {
                value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                value.startsWith("//") -> "https:$value"
                else -> java.net.URI(base).resolve(value).toString()
            }
        }.getOrNull()
    }

    private fun Element.toSearchResponse(base: String): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = fix(a.attr("href"), base) ?: return null
        val title =
            selectFirst("h2, strong, h3")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return null

        val poster = poster(base)
        val isSeries =
            href.contains("/series/", true) ||
                title.contains("مسلسل") ||
                selectFirst(".episode, .epnum") != null

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url =
            if (page <= 1) request.data
            else "${request.data.trimEnd('/')}/page/$page"

        val doc = getDoc(url)
        val items =
            doc.select(
                "div.Grid--WecimaPosts div.GridItem, div.GridItem"
            )
                .mapNotNull { it.toSearchResponse(url) }
                .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()

        runCatching { getDoc(mainUrl) }

        val body = try {
            postText(
                "$mainUrl/search",
                mapOf("q" to clean),
                mainUrl
            )
        } catch (_: Throwable) {
            return emptyList()
        }

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return emptyList()
        val results = json.optJSONArray("results")
            ?: return emptyList()

        val out = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            val slug = item.optString("slug").trim()
            val poster = item.optString("image").trim().takeIf { it.isNotBlank() }
            val year = item.optString("year").toIntOrNull()
            val isTv = item.optInt("istv", -1)

            if (title.isBlank() || slug.isBlank() || isTv == 2) continue

            val href =
                if (slug.startsWith("http")) slug
                else {
                    val encoded =
                        URLEncoder.encode(slug, "UTF-8")
                            .replace("+", "%20")
                    if (isTv == 0) {
                        "$mainUrl/watch/$encoded"
                    } else {
                        "$mainUrl/series/$encoded"
                    }
                }

            if (isTv == 0) {
                out += newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    posterUrl = poster
                    this.year = year
                }
            } else {
                out += newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                    this.year = year
                }
            }
        }

        return out.distinctBy { it.url }
    }

    private fun normalizeDigits(value: String): String = buildString {
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

    private fun number(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return Regex("""\d{1,4}""")
            .findAll(normalizeDigits(value))
            .lastOrNull()
            ?.value
            ?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)
        val episodeSeriesUrl =
            if (
                url.contains("/watch/", true)
            ) {
                document.selectFirst(
                    ".Breadcrumb--UX a[href*='/series/'], a[href*='/series/']"
                )
                    ?.attr("href")
                    ?.let { fix(it, url) }
            } else {
                null
            }

        val seriesUrl =
            when {
                url.contains("/series/", true) -> url
                episodeSeriesUrl != null -> episodeSeriesUrl
                else -> null
            }

        if (seriesUrl != null) {
            val seriesDoc =
                if (seriesUrl.trimEnd('/') == url.trimEnd('/')) {
                    document
                } else {
                    getDoc(seriesUrl, url)
                }

            val title =
                seriesDoc.selectFirst(
                    "div.Title--Content--Single-begin h1, h1"
                )
                    ?.text()
                    ?.trim()
                    .orEmpty()

            val poster =
                seriesDoc.selectFirst(
                    "meta[property=og:image]"
                )
                    ?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: seriesDoc.selectFirst("wecima.separated--top")
                        ?.let { element ->
                            val style =
                                element.attr("data-lazy-style")
                                    .ifBlank { element.attr("style") }
                            Regex("""url\(['"]?(.*?)['"]?\)""")
                                .find(style)
                                ?.groupValues
                                ?.getOrNull(1)
                        }

            val plot =
                seriesDoc.selectFirst("div.StoryMovieContent")
                    ?.text()
                    ?.trim()

            val year =
                seriesDoc.select(
                    "ul.Terms--Content--Single-begin li"
                )
                    .firstOrNull {
                        it.selectFirst("span")
                            ?.text()
                            ?.contains("السنة") == true
                    }
                    ?.text()
                    ?.let(::number)

            val episodes = mutableListOf<Episode>()
            val seasons =
                seriesDoc.select(
                    "div.List--Seasons--Episodes a.SeasonsEpisodes"
                )

            if (seasons.isNotEmpty()) {
                seasons.amap { seasonEl ->
                    val seasonNum =
                        number(seasonEl.text())
                            ?: seasonEl.attr("data-season").toIntOrNull()
                    val postId = seasonEl.attr("data-id")
                    val seasonId = seasonEl.attr("data-season")

                    val html = runCatching {
                        postText(
                            "$mainUrl/ajax/Episode",
                            mapOf(
                                "post_id" to postId,
                                "season" to seasonId
                            ),
                            seriesUrl
                        )
                    }.getOrNull()
                        ?: return@amap emptyList<Episode>()

                    val seasonDoc =
                        Jsoup.parse(html, mainUrl)

                    seasonDoc.select(
                        "a.hoverable.activable[href], .EpisodesList a[href], a[href*='/watch/']"
                    ).mapNotNull { ep ->
                        val epUrl =
                            fix(ep.attr("href"), mainUrl)
                                ?: return@mapNotNull null
                        val epName =
                            ep.selectFirst("episodetitle, .episodetitle")
                                ?.text()
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?: ep.text().trim()
                        val epNum = number(epName)

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
                seriesDoc.select(
                    ".EpisodesList.Full--Width a[href], .EpisodesList a[href], a[href*='/watch/']"
                ).mapNotNullTo(episodes) { ep ->
                    val epUrl =
                        fix(ep.attr("href"), seriesUrl)
                            ?: return@mapNotNullTo null
                    val epName =
                        ep.selectFirst("episodetitle, .episodetitle")
                            ?.text()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: ep.text().trim()
                    val epNum = number(epName)
                    newEpisode(epUrl) {
                        name = epName.ifBlank { "الحلقة ${epNum ?: ""}" }
                        season = 1
                        episode = epNum
                        posterUrl = poster
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title.ifBlank { "WeCima Series" },
                seriesUrl,
                TvType.TvSeries,
                episodes.distinctBy { it.data }
                    .sortedWith(
                        compareBy(
                            { it.season ?: 1 },
                            { it.episode ?: Int.MAX_VALUE }
                        )
                    )
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val title =
            document.selectFirst(
                "div.Title--Content--Single-begin h1, h1"
            )
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank { "WeCima Movie" }

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val plot =
            document.selectFirst("div.StoryMovieContent")
                ?.text()
                ?.trim()

        val year =
            document.select(
                "ul.Terms--Content--Single-begin li"
            )
                .firstOrNull {
                    it.selectFirst("span")
                        ?.text()
                        ?.contains("السنة") == true
                }
                ?.text()
                ?.let(::number)

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

    private fun decodeWecimaUrl(value: String): String? {
        val clean = value
            .trim()
            .replace(" ", "")
            .replace("+", "")

        if (clean.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates += clean
        if (!clean.startsWith("aHR0")) {
            candidates += "aHR0c$clean"
            candidates += "aHR0$clean"
        }

        for (candidate in candidates) {
            val padded =
                candidate + "=".repeat((4 - candidate.length % 4) % 4)

            val decoded = runCatching {
                String(
                    Base64.decode(
                        padded,
                        Base64.DEFAULT
                    ),
                    Charsets.UTF_8
                ).trim()
            }.getOrNull()

            if (
                decoded != null &&
                decoded.startsWith("http")
            ) {
                return decoded
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            getDoc(data)
        } catch (_: Throwable) {
            return false
        }

        val targets = linkedSetOf<String>()

        document.select(
            "ul.WatchServersList [data-url], [data-url]"
        ).forEach { item ->
            val raw = item.attr("data-url")
            decodeWecimaUrl(raw)
                ?.let(targets::add)
        }

        document.select(
            ".openLinkDown[data-href], [data-href].openLinkDown"
        ).forEach { item ->
            val raw = item.attr("data-href")
            decodeWecimaUrl(raw)
                ?.let(targets::add)
        }

        var emitted = false
        val seen = linkedSetOf<String>()

        for (target in targets) {
            val declaredQuality = MOOSAQualityPolicy.labeledHeight(target)
            if (declaredQuality != null &&
                declaredQuality < MOOSAQualityPolicy.MIN_HEIGHT) continue
            if (!seen.add(target)) continue

            if (
                target.contains(".m3u8", true) ||
                target.contains(".mp4", true)
            ) {
                val isHls = target.contains(".m3u8", true)
                val directLink = newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = target,
                        type =
                            if (isHls) ExtractorLinkType.M3U8
                            else ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality =
                            Regex("""(?i)(2160|1440|1080|720|480|360)""")
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
                continue
            }

            try {
                loadExtractor(
                    target,
                    data,
                    subtitleCallback
                ) { link ->
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

        return emitted
    }
}
