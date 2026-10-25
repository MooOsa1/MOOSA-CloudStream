package com.moosa.hub

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document

/**
 * Independent HDHub4u catalog. Uses public post sitemaps, never the site's
 * Cloudflare-protected search service. Emits only checked, seekable MKVs.
 * Film: catalog -> HubDrive -> HubCloud -> signed R2.
 * TV: season page -> *exact* episode -> HUBLinks archive -> same file chain.
 */
internal class MOOSAHDHub4uProvider : MainAPI() {
    override var name: String = MOOSAHDHub4uPolicy.SOURCE_NAME
    override var mainUrl: String = MOOSAHDHub4uPolicy.BASE
    override var lang: String = "en"
    override val hasMainPage = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private data class Release(val url: String, val quality: Int)
    private data class Video(val url: String, val referer: String, val quality: Int,
                             val languages: Set<String>?)

    @Volatile private var sitemapLastFetched = 0L
    @Volatile private var cachedCatalog: List<String> = emptyList()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = com.lagradost.cloudstream3.utils.AppUtils
            .tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data) ?: return false
        if (request.isAnime || request.title.isBlank() ||
            (!request.isTv && request.year == null) ||
            (request.isTv && (request.season == null || request.episode == null))
        ) return false

        val verified = withTimeoutOrNull(39_000L) {
            findVerified(request.title, request.year,
                request.season.takeIf { request.isTv },
                request.episode.takeIf { request.isTv },
                request.originalLanguage, callback)
        } ?: 0
        MOOSANativeSourceDiagnostics.mark(
            name,
            if (verified > 0) "$verified HTTP 206 MKV file(s) with real video and audio tracks"
            else "No identity-checked, seekable 1080p or 4K episode/movie"
        )
        return verified > 0
    }

    private suspend fun findVerified(
        title: String, year: Int?, season: Int?, episode: Int?,
        originalLanguage: String?, callback: (ExtractorLink) -> Unit
    ): Int = coroutineScope {
        val documents = findCatalogPages(title, year, season)
        if (documents.isEmpty()) return@coroutineScope 0
        val releases = documents.flatMap { detail ->
            if (season != null && episode != null) {
                detail.select("a[href]").mapNotNull { link ->
                    if (MOOSAHDHub4uPolicy.episodeLabel(link.text()) != episode) null
                    else MOOSAHDHub4uPolicy.safeGreenmotors(link.attr("href"))
                        ?.let { Release(it, 1080) }
                }
            } else {
                detail.select("a[href]").mapNotNull { link ->
                    val file = MOOSAHDHub4uPolicy.safeHubDrive(link.attr("href"))
                        ?: return@mapNotNull null
                    val quality = MOOSAHDHub4uPolicy.releaseQuality(link.text())
                        ?: return@mapNotNull null
                    Release(file, quality)
                }
            }
        }.distinctBy { it.url }
        // Prefer real UHD, then keep one/two independent FHD fallbacks.
        val selected = releases.filter { it.quality == 2160 }.take(3) +
            releases.filter { it.quality == 1080 }.take(2)
        if (selected.isEmpty()) return@coroutineScope 0

        val gate = Semaphore(3)
        val delivered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        selected.map { release ->
            async {
                gate.withPermit {
                    val videos = withTimeoutOrNull(22_000L) {
                        resolve(release, title, year, season, episode,
                            originalLanguage)
                    }.orEmpty()
                    videos.count { video ->
                        if (!delivered.add(video.url)) false
                        else {
                            val qualityLabel = if (video.quality == 2160) "4K" else "1080p"
                            val host = if (video.url.contains("pixeldrain.dev")) "PixelServer"
                                else "R2 CDN"
                            callback(newExtractorLink(
                                name,
                                "HDHub4u $host • verified $qualityLabel • ${MOOSAAudioTrackPolicy.display(video.languages)}",
                                video.url,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = video.quality
                                this.referer = video.referer
                                this.headers = if (video.referer.isBlank())
                                    mapOf("User-Agent" to USER_AGENT)
                                else mapOf("Referer" to video.referer,
                                    "User-Agent" to USER_AGENT)
                            })
                            true
                        }
                    }
                }
            }
        }.awaitAll().sum()
    }

    private suspend fun findCatalogPages(
        title: String, year: Int?, season: Int?
    ): List<Document> = coroutineScope {
        val slug = MOOSAHDHub4uPolicy.slug(title)
        if (slug.isBlank()) return@coroutineScope emptyList()
        val guesses = if (season != null) listOf(
            "$slug-season-$season-hindi-bluray-all-episodes",
            "$slug-season-$season-hindi-webrip-all-episodes"
        ) else listOf(
            "$slug-$year-bluray-hindi-full-movie",
            "$slug-$year-webrip-hindi-full-movie"
        )
        val quick = guesses.map { guess ->
            async { fetchExactCatalog("$mainUrl/$guess/", title, year, season) }
        }.awaitAll().filterNotNull()
        if (quick.isNotEmpty()) return@coroutineScope quick

        val links = sitemapCatalog()
            .mapNotNull { MOOSAHDHub4uPolicy.matchingCatalogUrl(it, title, year, season) }
            .distinct().take(6)
        val gate = Semaphore(3)
        links.map { link ->
            async { gate.withPermit { fetchExactCatalog(link, title, year, season) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchExactCatalog(
        url: String, title: String, year: Int?, season: Int?
    ): Document? {
        val validated = MOOSAHDHub4uPolicy.safeCatalog(url) ?: return null
        val page = runCatching { app.get(validated) }.getOrNull()
            ?: return null
        if (!page.isSuccessful) return null
        val heading = page.document.selectFirst("h1.page-title")?.text().orEmpty()
        if (!MOOSAHDHub4uPolicy.exactCatalogHeading(title, year, season, heading))
            return null
        return page.document
    }

    private suspend fun sitemapCatalog(): List<String> = coroutineScope {
        val now = System.currentTimeMillis()
        if (cachedCatalog.isNotEmpty() && now - sitemapLastFetched < 6 * 60 * 60 * 1000L)
            return@coroutineScope cachedCatalog
        val links = withTimeoutOrNull(10_000L) {
            val index = runCatching { app.get("$mainUrl/sitemap.xml") }.getOrNull()
                ?: return@withTimeoutOrNull emptyList()
            val sitemapUrls = Regex("""<loc>(https://[^<]+)</loc>""")
                .findAll(index.text).mapNotNull { match ->
                    MOOSAHDHub4uPolicy.safeSitemap(match.groupValues[1])
                }.distinct().take(20).toList()
            val gate = Semaphore(5)
            sitemapUrls.map { sitemap ->
                async {
                    gate.withPermit {
                        val page = runCatching { app.get(sitemap) }.getOrNull()
                            ?: return@withPermit emptyList<String>()
                        Regex("""<loc>(https://[^<]+)</loc>""")
                            .findAll(page.text).mapNotNull { result ->
                                MOOSAHDHub4uPolicy.safeCatalog(result.groupValues[1])
                            }.take(1500).toList()
                    }
                }
            }.awaitAll().flatten()
        }.orEmpty().distinct()
        if (links.isNotEmpty()) {
            cachedCatalog = links
            sitemapLastFetched = now
        }
        links
    }

    private suspend fun resolve(
        release: Release, title: String, year: Int?,
        season: Int?, episode: Int?, originalLanguage: String?
    ): List<Video> {
        val destinations: List<Release> = if (season != null) {
            val shortener = runCatching { app.get(release.url) }.getOrNull()
                ?: return emptyList()
            val target = MOOSA4KHDHubPolicy.decodeGatewayDestination(shortener.text)
                ?.let(MOOSAHDHub4uPolicy::safeArchive) ?: return emptyList()
            val archive = runCatching { app.get(target) }.getOrNull()
                ?: return emptyList()
            if (!archive.isSuccessful || !MOOSAHDHub4uPolicy.exactFileIdentity(
                    title, year, season, episode, archive.document.title()
                )) return emptyList()
            archive.document.select("h3,h4,h5").mapNotNull { section ->
                val quality = MOOSAHDHub4uPolicy.releaseQuality(section.text())
                    ?: return@mapNotNull null
                val drive = section.select("a[href]").firstNotNullOfOrNull {
                    MOOSAHDHub4uPolicy.safeHubDrive(it.attr("href"))
                } ?: return@mapNotNull null
                Release(drive, quality)
            }.distinctBy { it.url }
                .sortedByDescending { it.quality }.take(3)
        } else listOf(release)

        val results = mutableListOf<Video>()
        for ((driveUrl, quality) in destinations) {
            val filePage = runCatching { app.get(driveUrl) }.getOrNull()
                ?: continue
            if (!filePage.isSuccessful) continue
            val filename = filePage.document.selectFirst("h6")?.text().orEmpty()
            if (!filename.endsWith(".mkv", ignoreCase = true) ||
                !MOOSAHDHub4uPolicy.exactFileIdentity(
                    title, year, season, episode, filename
                ) || !MOOSA4KHDHubPolicy.isCompatibleRelease(filename)
            ) continue
            val hub = filePage.document.select("a[href]")
                .firstNotNullOfOrNull { link ->
                    if (!link.text().contains("HubCloud", ignoreCase = true)) null
                    else MOOSA4KHDHubPolicy.safeHubPage(link.attr("href"))
                } ?: continue
            val hubPage = runCatching { app.get(hub) }.getOrNull()
                ?: continue
            if (!hubPage.isSuccessful) continue
            val next = Regex("""var\s+url\s*=\s*['\"]([^'\"]+)['\"]""")
                .find(hubPage.text)?.groupValues?.get(1)
                ?.let(MOOSA4KHDHubPolicy::safeHostPage) ?: continue
            val terminal = runCatching {
                app.get(next, headers = mapOf("Referer" to hub))
            }.getOrNull() ?: continue
            if (!terminal.isSuccessful) continue
            // GamerXyt renders the current PixelServer in its `var pxl`
            // script. The static PixelServer anchor can be stale/404.
            val pixel = Regex("""var\s+pxl\s*=\s*['\"]([^'\"]+)['\"]""")
                .find(terminal.text)?.groupValues?.get(1)
                ?.let(MOOSAMoviesDrivePolicy::pixeldrainApi)
            val r2 = terminal.document.select("a[href]")
                .mapNotNull {
                    MOOSA4KHDHubPolicy.safeDirectFile(it.attr("href"))
                }
            val pixelAnchors = terminal.document.select("a[href]")
                .mapNotNull {
                    MOOSAMoviesDrivePolicy.pixeldrainApi(it.attr("href"))
                }
            val files = (r2 + listOfNotNull(pixel) + pixelAnchors)
                .distinct().take(3)
            for (link in files) {
                val ref = if (link.startsWith("https://pixeldrain.dev/")) "" else next
                val verified = verifyVideo(link, ref, quality, originalLanguage)
                if (verified != null) results += verified
            }
            if (results.isNotEmpty()) return results.distinctBy { it.url }
        }
        return emptyList()
    }

    private suspend fun verifyVideo(
        url: String, referer: String, expectedQuality: Int,
        originalLanguage: String?
    ): Video? {
        val headers = mutableMapOf(
            "Range" to "bytes=0-131071",
            "User-Agent" to USER_AGENT
        )
        if (referer.isNotBlank()) headers["Referer"] = referer
        val response = runCatching {
            app.get(url, headers = headers, referer = referer)
        }.getOrNull() ?: return null
        if (response.code != 206 ||
            !response.headers["Content-Range"].orEmpty()
                .startsWith("bytes 0-", ignoreCase = true)
        ) return null
        val firstBytes = ByteArray(131072)
        val count = response.body.byteStream().use { input ->
            var read = 0
            while (read < firstBytes.size) {
                val size = input.read(firstBytes, read, firstBytes.size - read)
                if (size <= 0) break
                read += size
            }
            read
        }
        val bytes = firstBytes.copyOf(count)
        val video = MOOSA4KHDHubPolicy.compatibleVideo(bytes) ?: return null
        val actual = MOOSA4KHDHubPolicy.actualQuality(video.width, video.height)
            ?: return null
        if (actual < expectedQuality) return null
        val languages = MOOSA4KHDHubPolicy.audioLanguages(bytes)
        if (!MOOSAAudioTrackPolicy.accept(originalLanguage, languages)) return null
        return Video(url, referer, actual, languages)
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
