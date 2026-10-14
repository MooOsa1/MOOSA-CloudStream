package com.moosa.hub.bundled.shahidwbas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.moosa.hub.MOOSAQualityPolicy
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * Independent ShahidWBas adapter. Streams are accepted ONLY when their
 * extractor specifies 720p+ or a master playlist specifies RESOLUTION=WxH.
 */
class ShahidWBasProvider : MainAPI() {
    override var name = "ShahidWBas"
    override var mainUrl = "https://w30.shahidwbas.tv"
    override var lang = "ar"
    override val hasMainPage = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    private fun absolute(raw: String, base: String = mainUrl): String? =
        runCatching {
            when {
                raw.isBlank() || raw.startsWith("#") -> null
                raw.startsWith("https://", true) || raw.startsWith("http://", true) -> raw
                raw.startsWith("//") -> "https:" + raw
                else -> URI(base).resolve(raw).toString()
            }
        }.getOrNull()

    private fun Element.card(): SearchResponse? {
        val anchor = selectFirst("a[href*='watch.php']") ?: return null
        val url = absolute(anchor.attr("href")) ?: return null
        val title = selectFirst(".caption h3 a")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null

        val image = selectFirst("img")?.let { img ->
            absolute(img.attr("data-src").ifBlank { img.attr("src") })
        }
        val isSeries = title.contains("الحلقة") ||
            title.contains("حلقة") ||
            title.contains("الموسم")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = image
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = image
            }
        }
    }

    override val mainPage = mainPageOf(
        mainUrl + "/index.php" to "جديد شاهد وبس"
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl + "/index.php", headers = headers).document
        val cards = document.select("ul.pm-ul-browse-videos li")
            .mapNotNull { it.card() }.distinctBy { it.url }
        return newHomePageResponse(request.name, cards)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val html = app.post(
            mainUrl + "/ajax-search.php",
            data = mapOf("queryString" to query.trim()),
            headers = headers + mapOf(
                "Referer" to mainUrl + "/",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text
        return Jsoup.parseBodyFragment(html)
            .select("li")
            .mapNotNull { it.card() }
            .distinctBy { it.url }
    }

    private fun episodeNumber(label: String): Int? =
        Regex("""(?:الحلقة|حلقة)\s*(\d{1,3})""")
            .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")?.let { absolute(it, url) }
        val plot = doc.selectFirst("div[itemprop=description]")?.text()?.trim()
        val id = Regex("""(?:[?&]vid=)([A-Za-z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1)
        val episodes = mutableListOf<Episode>()

        doc.select("div.SeasonsBoxUL button.tablinks")
            .forEachIndexed { index, tab ->
                val season = Regex("""\d+""").find(tab.text())
                    ?.value?.toIntOrNull() ?: index + 1
                doc.select("#Season" + index + " a[href*='watch.php']")
                    .forEach { anchor ->
                        val link = absolute(anchor.attr("href"), url) ?: return@forEach
                        val epId = Regex("""(?:[?&]vid=)([A-Za-z0-9_-]+)""")
                            .find(link)?.groupValues?.getOrNull(1) ?: return@forEach
                        val label = anchor.text().trim()
                        episodes += newEpisode(mainUrl + "/play.php?vid=" + epId) {
                            name = label
                            this.season = season
                            episode = episodeNumber(label)
                            posterUrl = poster
                        }
                    }
            }

        if (episodes.isEmpty() && id != null && title.contains("الحلقة")) {
            episodes += newEpisode(mainUrl + "/play.php?vid=" + id) {
                name = title
                season = 1
                episode = episodeNumber(title)
                posterUrl = poster
            }
        }
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                title, url, TvType.TvSeries, episodes.distinctBy { it.data }
            ) {
                posterUrl = poster
                this.plot = plot
            }
        }
        if (id == null) return null
        return newMovieLoadResponse(
            title, url, TvType.Movie, mainUrl + "/play.php?vid=" + id
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun hlsVariants(
        iframeUrl: String,
        referer: String,
        accept: (ExtractorLink) -> Unit
    ) {
        val page = app.get(iframeUrl, headers = headers, referer = referer).text
        val rawMaster = Regex(
            """(?i)(?:file|source)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']"""
        ).find(page)?.groupValues?.getOrNull(1)?.replace("\\/", "/") ?: return
        val master = absolute(rawMaster, iframeUrl) ?: return
        val manifest = app.get(
            master, headers = headers, referer = iframeUrl
        ).text

        var height: Int? = null
        for (line in manifest.lineSequence().map { it.trim() }) {
            if (line.startsWith("#EXT-X-STREAM-INF:", true)) {
                height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                continue
            }
            if (line.isBlank() || line.startsWith("#")) continue
            val resolution = height
            height = null
            if (!MOOSAQualityPolicy.acceptsHeight(resolution)) continue
            val finalUrl = absolute(line, master) ?: continue
            val link = newExtractorLink(
                source = name,
                name = name + " " + resolution + "p HLS",
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = iframeUrl
                quality = resolution!!
                this.headers = headers + mapOf("Referer" to iframeUrl)
            }
            accept(link)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val referer = data.replace("/play.php", "/watch.php")
        val page = app.get(data, headers = headers, referer = referer)
        val emitted = linkedSetOf<String>()
        fun accept(link: ExtractorLink) {
            if (MOOSAQualityPolicy.accepts(link) && emitted.add(link.url)) {
                callback(link)
            }
        }

        for (server in page.document.select("ul.list_servers li[data-embed]")) {
            val embed = Jsoup.parseBodyFragment(server.attr("data-embed"))
                .selectFirst("iframe[src]")?.attr("src") ?: continue
            val iframe = absolute(embed, page.url) ?: continue
            if (iframe.contains("liiivideo.com", ignoreCase = true)) {
                runCatching { hlsVariants(iframe, page.url, ::accept) }
            } else {
                runCatching {
                    loadExtractor(iframe, page.url, subtitleCallback, ::accept)
                }
            }
        }
        return emitted.isNotEmpty()
    }
}
