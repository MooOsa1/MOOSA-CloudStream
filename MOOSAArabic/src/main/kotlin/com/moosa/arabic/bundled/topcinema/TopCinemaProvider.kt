// Bundled from ramailo1/arprov (cb10a262a9fae679322c264973cf5c0f5219acdc); MIT licensed. See THIRD_PARTY_NOTICES.md.
package com.moosa.arabic.bundled.topcinema


import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.moosa.arabic.MOOSAQualityPolicy
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit


class TopCinemaProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.topcinema.vip"
    override var name = "TopCinema"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.7,en;q=0.3",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
        "Referer" to "$mainUrl/"
    )

    // WordPress API Data Classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpTitle(
        @JsonProperty("rendered") val rendered: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpFeaturedMedia(
        @JsonProperty("source_url") val sourceUrl: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpEmbedded(
        @JsonProperty("wp:featuredmedia") val featuredMedia: List<WpFeaturedMedia>?
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpPost(
        @JsonProperty("id") val id: Int,
        @JsonProperty("date") val date: String,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: WpTitle,
        @JsonProperty("content") val content: WpTitle?, 
        @JsonProperty("categories") val categories: List<Int>?,
        @JsonProperty("yoast_head_json") val yoastHead: YoastHead?,
        @JsonProperty("_embedded") val embedded: WpEmbedded?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YoastHead(
        @JsonProperty("og_image") val ogImage: List<Map<String, Any>>? // Handling as generic map to be safe
    )

    private fun String.cleanTitle(): String {
        return this.replace("مشاهدة|فيلم|مترجم|مسلسل|اون لاين|كامل|جميع الحلقات|الموسم|الحلقة|انمي|تحميل".toRegex(), "")
            .replace(Regex("\\(\\d+\\)"), "")
            .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun WpPost.toSearchResponse(): SearchResponse {
        val titleRaw = this.title.rendered
            .replace("&#8211;", "-")
            .replace("&#038;", "&")
            .replace("&#8217;", "'")
        
        val title = titleRaw.cleanTitle()
        
        // Extract Image safely - Try Embedded first, then Yoast
        val posterUrl = this.embedded?.featuredMedia?.firstOrNull()?.sourceUrl
            ?: this.yoastHead?.ogImage?.firstOrNull()?.get("url")?.toString()

        val href = this.link

        // Accurate Type Detection via Category IDs
        val seriesCategories = setOf(70137, 4, 17979, 53293, 56428, 53911, 53256, 56302, 76, 38, 59186, 67)
        val isSeries = this.categories?.any { it in seriesCategories } == true || 
                       titleRaw.contains("مسلسل") || 
                       titleRaw.contains("انمي")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // Map keywords/names to WordPress Category IDs
    override val mainPage = mainPageOf(
        "" to "الأحدث",
        "70137" to "مسلسلات رمضان 2026",
        "1207" to "أفلام أجنبية",
        "20349" to "أفلام عربية",
        "1895" to "أفلام أنمي",
        "4" to "مسلسلات أجنبية",
        "17979" to "مسلسلات عربية",
        "38" to "مسلسلات أنمي",
        "59186" to "مسلسلات كورية"
    )

    companion object {
        private val cacheMutex = Mutex()
        private var cachedHomePage: HomePageResponse? = null
        private var lastCacheTime: Long = 0
        private const val CACHE_TTL = 60 * 1000 // 60 seconds
        
        // Limit global parallel category fetches to avoid server throttling
        private val categorySemaphore = Semaphore(3)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val now = System.currentTimeMillis()
        
        // Handle concurrent calls by waiting for the same lock and reusing cache
        cacheMutex.withLock {
            if (cachedHomePage != null && (now - lastCacheTime) < CACHE_TTL) {
                return cachedHomePage!!
            }
            
            val result = fetchMainPageInternal()
            
            cachedHomePage = result
            lastCacheTime = System.currentTimeMillis()
            return result
        }
    }

    private suspend fun fetchMainPageInternal(): HomePageResponse = coroutineScope {
        val items = mainPage.map { (name, data) ->
            async {
                categorySemaphore.withPermit {
                    try {
                        val url = if (data.isEmpty()) {
                            "$mainUrl/wp-json/wp/v2/posts?per_page=10&_embed"
                        } else {
                            "$mainUrl/wp-json/wp/v2/posts?categories=$data&per_page=10&_embed"
                        }

                        val responseText = app.get(url).text
                        val response = mapper.readValue(responseText, object : com.fasterxml.jackson.core.type.TypeReference<List<WpPost>>() {})
                        val searchResponses = response.mapNotNull { it.toSearchResponse() }
                        
                        if (searchResponses.isNotEmpty()) {
                            HomePageList(name, searchResponses)
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        newHomePageResponse(items)
    }

    // WordPress /posts?search often returned the newest unrelated articles.
    // The site's current /search endpoint has accurate Arabic episodes.
    private fun topCinemaSeason(title: String): Int? {
        val numeric = Regex("""(?:الموسم|الجزء|ج)\s*(\d{1,2})""")
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (numeric != null) return numeric

        val word = Regex(
            """(?:الموسم|الجزء)\s*(الاول|الأول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر)"""
        ).find(title)?.groupValues?.getOrNull(1) ?: return null
        return mapOf(
            "الاول" to 1, "الأول" to 1,
            "الثاني" to 2, "الثالث" to 3,
            "الرابع" to 4, "الخامس" to 5,
            "السادس" to 6, "السابع" to 7,
            "الثامن" to 8, "التاسع" to 9,
            "العاشر" to 10
        )[word]
    }

    private fun topCinemaSeriesBase(title: String): String {
        val stripped = title
            .replace(Regex("""^(?:مشاهدة\s+)?(?:مسلسل|انمي|أنمي)\s+"""), "")
            .replace(Regex("""\s+(?:الموسم|الجزء|الحلقة|الحلقه|ج\s*\d).*$"""), "")
            .trim()
        return stripped.ifBlank { title.trim() }
    }

    private fun topCinemaEpisode(title: String): Int? =
        Regex("""(?:الحلقة|الحلقه|حلقة)\s*(\d{1,4})""")
            .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        if (query.isBlank() || page !in 1..8) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/wp-json/wp/v2/search?search=$encoded&per_page=30&page=$page"
        val raw = try {
            app.get(url, headers = headers).text
        } catch (_: Exception) {
            return emptyList()
        }
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val rawTitle = Jsoup.parseBodyFragment(
                item.optString("title", "")
            ).text().trim()
            val href = item.optString("url", "").trim()
            if (rawTitle.isBlank() || !href.startsWith("http")) {
                return@mapNotNull null
            }

            val series = rawTitle.contains("مسلسل") ||
                rawTitle.contains("الحلقة") ||
                rawTitle.contains("انمي")
            if (series) {
                val baseTitle = topCinemaSeriesBase(rawTitle)
                val season = topCinemaSeason(rawTitle)
                val displayName = if (season != null) {
                    "$baseTitle الموسم $season"
                } else {
                    baseTitle
                }
                newTvSeriesSearchResponse(
                    displayName, href, TvType.TvSeries
                )
            } else {
                val cleaned = rawTitle
                    .replace(Regex("""^(?:مشاهدة\s+)?(?:فيلم)\s+"""), "")
                    .replace(Regex("""\s+(?:مترجم|مدبلج|كامل|اونلاين).*$"""), "")
                    .trim()
                newMovieSearchResponse(cleaned.ifBlank { rawTitle }, href, TvType.Movie)
            }
        }.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val results = searchPage(query, page)
        return newSearchResponseList(results, results.size >= 30 && page < 8)
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url, headers = headers).document

        // WordPress returns individual episode posts in search, but the
        // episode itself lists every episode of that season. Keep the
        // requested season identity and avoid showing the newest E30 as E1.
        val currentTitle = doc.select("h1").map { it.text().trim() }
            .firstOrNull { it.startsWith("مسلسل ") && it.contains("الحلقة") }
        val episodeAnchors = doc.select(
            ".allepcont a[href], .EpisodesList a[href]"
        )
        if (!currentTitle.isNullOrBlank() && episodeAnchors.isNotEmpty()) {
            val pageSeason = topCinemaSeason(currentTitle) ?: 1
            val seasonEpisodes = episodeAnchors
                .mapNotNull { element ->
                    val episodeUrl = fixUrl(element.attr("href"))
                    val label = element.text().trim()
                    val number = topCinemaEpisode(label)
                        ?: topCinemaEpisode(episodeUrl)
                        ?: return@mapNotNull null
                    newEpisode(episodeUrl) {
                        name = "الحلقة $number"
                        episode = number
                        season = pageSeason
                    }
                }
                .distinctBy { it.data }

            if (seasonEpisodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    topCinemaSeriesBase(currentTitle),
                    url,
                    TvType.TvSeries,
                    seasonEpisodes
                ) {
                    this.posterUrl = doc.select("meta[property='og:image']")
                        .attr("content")
                    plot = doc.select(".StoryArea, .Story, .description").text()
                }
            }
        }
        
        // Smart Redirect: If on episode page, find breadcrumb series link
        val seriesLink = doc.select(".breadcrumbs a[href*='/series/'], .breadcrumbs a[href*='/مسلسل/'], .breadcrumbs a:nth-last-child(2)").firstOrNull()
        if (seriesLink != null && url.contains("/episodes/|/الحلقة/".toRegex())) {
            val seriesUrl = fixUrl(seriesLink.attr("href"))
            if (seriesUrl != url) {
                doc = app.get(seriesUrl, headers = headers).document
            }
        }

        val titleRaw = doc.select("h1.title, .movie-title, .PostTitle, h1").text()
        val title = titleRaw.cleanTitle()
        // Determine type: If it has seasons/episodes list, it is a series
        val hasEpisodes = doc.select(".allepcont, .EpisodesList, .list-episodes, .seasonslist").isNotEmpty()
        val isMovie = !hasEpisodes && !url.contains("/series/|/مسلسل/|/season/".toRegex())

        val posterUrl = doc.select("meta[property='og:image']").attr("content")
        val synopsis = doc.select(".description, .plot, .summary, .StoryArea, .Story").text()
        val year = doc.select(".year, .release-year, a[href*='/release-year/']").text().filter { it.isDigit() }.toIntOrNull()
        val tags = doc.select(".genre a, .categories a").map { it.text() }
        
        val recommendations = doc.select(".related-movies .movie-item, .Block--Item, .Small--Box, .AsidePost").mapNotNull { element ->
             val recTitle = element.select("h3").text()
             val recHref = element.select("a").attr("href")
             val recPoster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
             if (recHref.isNotEmpty()) {
                 newMovieSearchResponse(recTitle, recHref, TvType.Movie) { this.posterUrl = recPoster }
             } else null
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url 
            ) {
                this.posterUrl = posterUrl
                this.recommendations = recommendations
                this.plot = synopsis
                this.tags = tags
                this.year = year
            }
        } else {
            val episodes = arrayListOf<Episode>()
            val cleanSeriesTitle = title.split(" ").take(3).joinToString(" ") 
            
            // Layout 1: List style (.allepcont .row a)
            doc.select(".allepcont .row a, .EpisodesList .row a").forEach { episode ->
                val epLink = fixUrl(episode.attr("href"))
                val epRawName = episode.select(".ep-info h2").text().ifEmpty { episode.text() }
                var epName = epRawName.cleanTitle()
                if (epName.contains(cleanSeriesTitle, true)) {
                    epName = epName.replace(cleanSeriesTitle, "", true).trim()
                }
                val epNum = episode.select(".epnum").text().toIntOrNull() ?: Regex("\\d+").find(epRawName)?.value?.toIntOrNull()
                val epPoster = episode.select("img").attr("src").ifEmpty { posterUrl }
                
                episodes.add(newEpisode(epLink) {
                    this.name = epName.ifBlank { "الحلقة $epNum" }
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.season = 1 
                })
            }
            
            // Layout 2: Grid style (.Small--Box)
            if (episodes.isEmpty()) {
                doc.select(".Small--Box, .Block--Item, .GridItem").forEach { episode ->
                    val a = episode.selectFirst("a") ?: return@forEach
                    val epLink = fixUrl(a.attr("href"))
                    val epRawName = a.select("h3").text()
                    var epName = epRawName.cleanTitle()
                     if (epName.contains(cleanSeriesTitle, true)) {
                        epName = epName.replace(cleanSeriesTitle, "", true).trim()
                    }
                    val epNum = episode.select(".number em").text().toIntOrNull() ?: Regex("\\d+").find(epRawName)?.value?.toIntOrNull()
                    val epPoster = episode.select("img").attr("src").ifEmpty { posterUrl }
                    
                    if (epRawName.contains("حلقة|الحلقة".toRegex()) || epNum != null) {
                        episodes.add(newEpisode(epLink) {
                            this.name = epName.ifBlank { "الحلقة $epNum" }
                            this.episode = epNum
                            this.posterUrl = epPoster
                            this.season = 1
                        })
                    }
                }
            }

            // Layout 3: AJAX Loading (MasterDecode theme)
            if (episodes.isEmpty() || doc.select(".seasonslist").isNotEmpty()) {
                try {
                    val scripts = doc.select("script").joinToString("\n") { it.html() }
                    val ajaxtUrl = Regex("var AjaxtURL = \"(.*?)\";").find(scripts)?.groupValues?.get(1)
                        ?: "https://topcima.online/wp-content/themes/Master%20Decode-TopCinema-2-1/Ajaxt/"
                    val postId = doc.select("input#shortlink").attr("value").split("?p=").lastOrNull()
                        ?: Regex("post_id: '(\\d+)'").find(doc.html())?.groupValues?.get(1)
                        ?: Regex("\"post_id\",\"(\\d+)\"").find(doc.html())?.groupValues?.get(1)

                    if (postId != null) {
                        val seasonsList = doc.select(".seasonslist li").ifEmpty { listOf(null) }
                        seasonsList.forEachIndexed { index, seasonEl ->
                            val seasonNum = seasonEl?.attr("data-season")?.toIntOrNull() ?: (index + 1)
                            val response = app.post(
                                "${ajaxtUrl}Single/Episodes.php",
                                data = mapOf("season" to "$seasonNum", "post_id" to postId),
                                headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
                            ).text
                            
                            val seasonDoc = org.jsoup.Jsoup.parse(response)
                            seasonDoc.select(".allepcont .row a, .EpisodesList .row a, a").forEach { ep ->
                                val epLink = fixUrl(ep.attr("href"))
                                if (epLink.isEmpty() || epLink == mainUrl) return@forEach
                                
                                val epRawName = ep.select(".ep-info h2").text().ifEmpty { ep.text() }.trim()
                                if (epRawName.contains("المشاهدة الان|التحميل الان".toRegex())) return@forEach
                                
                                val epNum = ep.select(".epnum").text().toIntOrNull() 
                                    ?: Regex("\\d+").find(epRawName)?.value?.toIntOrNull()
                                
                                episodes.add(newEpisode(epLink) {
                                    this.name = epRawName.cleanTitle()
                                    this.episode = epNum
                                    this.season = seasonNum
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
                this.posterUrl = posterUrl
                this.tags = tags
                this.plot = synopsis
                this.recommendations = recommendations
                this.year = year
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.trimEnd('/')
        val watchUrl = "$pageUrl/watch/"
        val response = try {
            app.get(
                watchUrl,
                headers = headers + mapOf("Referer" to pageUrl)
            )
        } catch (_: Exception) {
            return false
        }
        if (response.code !in 200..299) return false

        val emittedUrls = linkedSetOf<String>()
        val unknownHlsMasters = mutableListOf<ExtractorLink>()
        fun accept(link: ExtractorLink) {
            if (MOOSAQualityPolicy.accepts(link)) {
                if (emittedUrls.add(link.url.trim())) callback(link)
            } else if (
                link.isM3u8 &&
                link.quality == Qualities.Unknown.value &&
                !MOOSAQualityPolicy.hasForbiddenLabel(link)
            ) {
                // The Vidara extractor emits an unknown-quality HLS master.
                // Read RESOLUTION from the actual playlist, never infer HD.
                unknownHlsMasters += link
            }
        }

        val doc = response.document
        val servers = doc.select(
            "ul#watch > li, .servers-list li, [data-watch]"
        ).distinctBy { element ->
            element.attr("data-watch").ifBlank {
                element.selectFirst("iframe[src]")?.attr("src").orEmpty()
            }
        }.sortedBy { element ->
            val url = element.attr("data-watch").ifBlank {
                element.selectFirst("iframe[src]")?.attr("src").orEmpty()
            }
            if (url.contains("vidara.to", ignoreCase = true)) 0 else 1
        }

        for (server in servers.take(5)) {
            val target = server.attr("data-watch").trim().ifBlank {
                server.selectFirst("iframe[src]")?.attr("src").orEmpty()
            }
            if (target.isBlank() || target == watchUrl || target == pageUrl) continue
            safeExtract(target, subtitleCallback, ::accept)
            // Don't delay the player with failing hosters after Vidara is found.
            if (unknownHlsMasters.isNotEmpty() || emittedUrls.isNotEmpty()) break
        }

        for (master in unknownHlsMasters.distinctBy { it.url }.take(3)) {
            val variants = try {
                M3u8Helper().m3u8Generation(
                    M3u8Helper.M3u8Stream(
                        streamUrl = master.url,
                        quality = null,
                        headers = master.getAllHeaders()
                    ),
                    false
                )
            } catch (_: Exception) {
                emptyList()
            }

            variants.sortedByDescending { it.quality ?: 0 }.forEach { variant ->
                val height = variant.quality
                if (!MOOSAQualityPolicy.acceptsHeight(height)) return@forEach
                val link = newExtractorLink(
                    source = name,
                    name = name + " • " + height + "p • " + master.source,
                    url = variant.streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = master.referer
                    quality = height!!
                    headers = master.headers
                }
                accept(link)
            }
        }
        return emittedUrls.isNotEmpty()
    }

    private suspend fun safeExtract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        } catch (e: Exception) {
            scrapeM3u8(url, "Direct", callback)
        }
    }

    private suspend fun scrapeM3u8(
        url: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val text = app.get(
                url,
                headers = headers + mapOf("Referer" to "$mainUrl/")
            ).text

            val m3u8 = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""")
                .find(text)?.groupValues?.get(1)

            if (m3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        sourceName, sourceName, m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val fileUrl = Regex("""(?:file|src|url|link)\s*[=:]\s*["']([^"']+)["']""")
                .findAll(text)
                .map { it.groupValues[1] }
                .firstOrNull { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4")) }

            if (fileUrl != null) {
                val type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(sourceName, sourceName, fileUrl, type = type) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            } 
        } catch (e: Exception) {
        }
    }
}