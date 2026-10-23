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
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * On-demand, direct-HTTP 4K movie source, independent of any other plugin.
 *
 * Search -> exact title/year -> 2160p release -> HubCloud gateway ->
 * signed CDN -> 206 range + real Matroska video dimensions.
 *
 * A file page advertising "4K" is not enough. No link reaches the player
 * until its first bytes identify a genuine UHD video track.
 */
internal class MOOSA4KHDHubProvider : MainAPI() {
    override var name: String = MOOSA4KHDHubPolicy.SOURCE_NAME
    override var mainUrl: String = MOOSA4KHDHubPolicy.BASE
    override var lang: String = "en"
    override val hasMainPage: Boolean = false
    override val providerType: ProviderType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie)

    private data class VerifiedVideo(
        val url: String,
        val referer: String,
        val width: Int,
        val height: Int
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = com.lagradost.cloudstream3.utils.AppUtils
            .tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data) ?: return false
        if (request.isTv || request.isAnime || request.title.isBlank() ||
            request.year == null || request.year !in 1900..2100
        ) return false

        val discovered = withTimeoutOrNull(23_000L) {
            searchAndVerify(request.title, request.year)
        }.orEmpty().distinctBy { it.url }

        discovered.forEach { media ->
            callback(
                newExtractorLink(
                    name,
                    "HubCloud • verified 4K",
                    media.url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = 2160
                    this.referer = media.referer
                    this.headers = mapOf(
                        "Referer" to media.referer,
                        "User-Agent" to USER_AGENT
                    )
                }
            )
        }
        MOOSANativeSourceDiagnostics.mark(
            name,
            if (discovered.isNotEmpty()) {
                "Verified 3840-wide MKV • HTTP 206 seek • ${discovered.size} server(s)"
            } else {
                "No independently verified 4K direct file"
            }
        )
        return discovered.isNotEmpty()
    }

    private suspend fun searchAndVerify(
        title: String, year: Int
    ): List<VerifiedVideo> = coroutineScope {
        val search = "${mainUrl}/?s=${URLEncoder.encode(title, "UTF-8")}"
        val searchResponse = runCatching { app.get(search) }.getOrNull()
            ?: return@coroutineScope emptyList()
        if (!searchResponse.isSuccessful) return@coroutineScope emptyList()

        val match = searchResponse.document.select("a.movie-card")
            .firstOrNull { card ->
                val foundTitle = card.selectFirst(".movie-card-title")?.text().orEmpty()
                val foundYear = Regex("""\b(?:19|20)\d{2}\b""")
                    .find(card.selectFirst(".movie-card-meta")?.text().orEmpty())
                    ?.value?.toIntOrNull()
                MOOSA4KHDHubPolicy.exactCatalogMatch(
                    title, year, foundTitle, foundYear
                )
            } ?: return@coroutineScope emptyList()
        val detailsUrl = safeCatalogPath(match.attr("href"))
            ?: return@coroutineScope emptyList()
        val details = runCatching { app.get(detailsUrl) }.getOrNull()
            ?: return@coroutineScope emptyList()
        if (!details.isSuccessful) return@coroutineScope emptyList()

        // The quality comes from the actual stream, not this release text.
        // At most two UHD options; 1080p fallback is handled by other sources.
        val releases = details.document.select(".download-item")
            .filter { element ->
                MOOSA4KHDHubPolicy.isUhdRelease(
                    element.selectFirst(".file-title")?.text().orEmpty()
                )
            }
            .take(2)

        releases.map { release ->
            async {
                withTimeoutOrNull(13_000L) {
                    resolveRelease(release)
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun safeCatalogPath(path: String): String? {
        if (!path.startsWith("/") || path.startsWith("//") ||
            path.contains("\\") || path.contains("..")
        ) return null
        return "${MOOSA4KHDHubPolicy.BASE}$path"
    }

    private suspend fun resolveRelease(element: Element): VerifiedVideo? {
        val gateway = element.select("a[href]")
            .firstOrNull { it.text().contains("HubCloud", ignoreCase = true) }
            ?.attr("href") ?: return null
        if (!isAllowedGateway(gateway)) return null

        val gatewayResponse = runCatching { app.get(gateway) }.getOrNull()
            ?: return null
        if (!gatewayResponse.isSuccessful) return null
        val hub = MOOSA4KHDHubPolicy.decodeGateway(gatewayResponse.text)
            ?: return null

        val hubResponse = runCatching { app.get(hub) }.getOrNull()
            ?: return null
        if (!hubResponse.isSuccessful) return null
        val next = Regex("""var\s+url\s*=\s*['\"]([^'\"]+)['\"]""")
            .find(hubResponse.text)?.groupValues?.get(1) ?: return null
        val filePageUrl = MOOSA4KHDHubPolicy.safeHostPage(next)
            ?: return null

        val filePage = runCatching {
            app.get(filePageUrl, headers = mapOf("Referer" to hub))
        }.getOrNull() ?: return null
        if (!filePage.isSuccessful) return null
        val candidates = filePage.document.select("a[href]")
            .mapNotNull { link ->
                MOOSA4KHDHubPolicy.safeDirectFile(link.attr("href"))
            }
            .distinct()
            .take(2)

        for (video in candidates) {
            val verified = verifyReal4K(video, filePageUrl)
            if (verified != null) return verified
        }
        return null
    }

    private fun isAllowedGateway(value: String): Boolean {
        val url = runCatching { URI(value) }.getOrNull() ?: return false
        val host = url.host?.lowercase().orEmpty()
        return url.scheme == "https" && url.port == -1 &&
            url.userInfo == null &&
            (
                host == "greenmotors.club" ||
                    host == "greenmotors.cc" ||
                    host == "hubcloud.ist"
            )
    }

    private suspend fun verifyReal4K(
        url: String, referer: String
    ): VerifiedVideo? {
        val response = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "Range" to "bytes=0-131071",
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT
                ),
                referer = referer
            )
        }.getOrNull() ?: return null
        // 200 with an HTML redirect or an unseekable 65GB response is unsafe.
        if (response.code != 206 ||
            !response.headers["Content-Range"].orEmpty()
                .startsWith("bytes 0-", ignoreCase = true)
        ) return null

        val bytes = ByteArray(131072)
        val count = response.body.byteStream().use { input ->
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
            read
        }
        val dimensions = MOOSA4KHDHubPolicy.videoDimensions(bytes.copyOf(count))
            ?: return null
        // 3840x1600 is also genuine UHD cinematic framing (letterbox cropped).
        if (dimensions.first < 3800 || dimensions.second < 1600) return null
        return VerifiedVideo(url, referer, dimensions.first, dimensions.second)
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
