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
import org.json.JSONObject

/**
 * On-device, exact-TMDB-id Reya streams. The live source's BCS S02E03
 * fMP4 initialization measured 3840x2160 AVC video and a muxed audio track.
 * A 2160p AVC Level 5.1 source may not decode on 2018 Fire TV 4K; label it
 * EXPERIMENTAL and always seek FHD backup rather than claiming compatibility.
 */
internal class MOOSAReyaProvider : MainAPI() {
    override var name = MOOSAReyaPolicy.NAME
    override var mainUrl = MOOSAReyaPolicy.BASE
    override var lang = "en"
    override val hasMainPage = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private data class Source(val url: String, val label: String,
                              val headers: Map<String, String>)
    private data class Link(val url: String, val height: Int, val codec: String,
                            val source: String, val headers: Map<String, String>,
                            val audioLanguages: Set<String>?)

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = com.lagradost.cloudstream3.utils.AppUtils
            .tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data) ?: return false
        if (request.isAnime || request.originalLanguage.equals("ar", true))
            return false
        val endpoint = MOOSAReyaPolicy.apiUrl(
            request.tmdbId, request.isTv, request.season, request.episode
        ) ?: return false

        val found = withTimeoutOrNull(21_000L) {
            val response = runCatching {
                app.get(endpoint, headers = DEFAULT_HEADERS)
            }.getOrNull() ?: return@withTimeoutOrNull emptyList<Link>()
            if (!response.isSuccessful) return@withTimeoutOrNull emptyList<Link>()
            val json = runCatching { JSONObject(response.text) }.getOrNull()
                ?: return@withTimeoutOrNull emptyList<Link>()
            if (!MOOSAReyaPolicy.identity(json, request.tmdbId,
                    request.isTv, request.season, request.episode))
                return@withTimeoutOrNull emptyList<Link>()
            val items = json.optJSONArray("streams")
                ?: return@withTimeoutOrNull emptyList<Link>()
            val sources = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val url = MOOSAReyaPolicy.safeMedia(item.optString("url"))
                    ?: return@mapNotNull null
                val label = item.optString("source")
                    .take(55).ifBlank { "Reya" }
                val rawHeaders = item.optJSONObject("headers")
                val extra = listOf("User-Agent", "Referer", "Origin", "Accept")
                    .mapNotNull { key ->
                        rawHeaders?.optString(key)?.takeIf {
                            it.isNotBlank() && it.length < 512 &&
                                !it.contains('\r') && !it.contains('\n')
                        }?.let { key to it }
                    }.toMap()
                Source(url, label, DEFAULT_HEADERS + extra)
            }.distinctBy { it.url }.sortedBy {
                if (it.url.contains("streamed-7.workers.dev") ||
                    it.label.contains("vidnest", true)) 0 else 1
            }.take(4)
            coroutineScope {
                val gate = Semaphore(3)
                sources.map { source ->
                    async {
                        gate.withPermit {
                            withTimeoutOrNull(12_000L) {
                                checkSource(source, request.originalLanguage)
                            }.orEmpty()
                        }
                    }
                }.awaitAll().flatten()
            }
        }.orEmpty().distinctBy { it.url }.sortedByDescending { it.height }

        found.forEach { link ->
            val qualityName = if (link.height == 2160) "4K" else
                link.height.toString() + "p"
            val warning = if (link.height == 2160 && link.codec == "AVC")
                " • AVC (experimental on Fire TV 2018)" else ""
            val audio = " • " + MOOSAAudioTrackPolicy.display(link.audioLanguages)
            callback(
                newExtractorLink(
                    name,
                    "Reya " + link.source.take(26) + " • " +
                        qualityName + " " + link.codec + warning + audio,
                    link.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = link.height
                    this.headers = link.headers
                    this.referer = link.headers["Referer"].orEmpty()
                }
            )
        }
        MOOSANativeSourceDiagnostics.mark(
            name,
            if (found.isEmpty()) "No validated HLS video + original audio"
            else "Validated fMP4 streams: " + found.size + " (4K AVC may be device limited)"
        )
        return found.isNotEmpty()
    }

    private suspend fun checkSource(
        source: Source, originalLanguage: String?
    ): List<Link> {
        val masterResponse = runCatching {
            app.get(source.url, headers = source.headers)
        }.getOrNull() ?: return emptyList()
        if (!masterResponse.isSuccessful) return emptyList()
        val manifest = masterResponse.text
        if (!manifest.trimStart().startsWith("#EXTM3U")) return emptyList()
        val variations = MOOSAReyaPolicy.variants(source.url, manifest)
            .filter { it.height >= 720 }
        if (variations.isEmpty()) return emptyList()
        val chosen = mutableListOf<Link>()
        var foundUhd = false
        var foundFhd = false
        for (variant in variations) {
            if (variant.height >= 2160 && foundUhd) continue
            if (variant.height < 2160 && foundFhd) continue
            val response = runCatching {
                app.get(variant.url, headers = source.headers)
            }.getOrNull() ?: continue
            if (!response.isSuccessful) continue
            val stream = MOOSAReyaPolicy.media(variant.url, response.text) ?: continue
            val initResponse = runCatching {
                app.get(stream.init, headers = source.headers)
            }.getOrNull() ?: continue
            if (!initResponse.isSuccessful) continue
            val init = ByteArray(65536)
            val initSize = initResponse.body.byteStream().use { input ->
                var total = 0
                while (total < init.size) {
                    val n = input.read(init, total, init.size - total)
                    if (n <= 0) break
                    total += n
                }
                total
            }
            val track = MOOSAReyaPolicy.inspectInit(init.copyOf(initSize)) ?: continue
            if (track.width != variant.width || track.height != variant.height ||
                !MOOSAAudioTrackPolicy.accept(originalLanguage, track.audioLanguages))
                continue

            val firstSegment = runCatching {
                app.get(
                    stream.segment,
                    headers = source.headers + ("Range" to "bytes=0-32767")
                )
            }.getOrNull() ?: continue
            if (!firstSegment.isSuccessful) continue
            val prefix = ByteArray(32768)
            val read = firstSegment.body.byteStream().use { input ->
                var total = 0
                while (total < prefix.size) {
                    val n = input.read(prefix, total, prefix.size - total)
                    if (n <= 0) break
                    total += n
                }
                total
            }
            if (!MOOSAReyaPolicy.isFmp4Fragment(prefix.copyOf(read))) continue
            chosen += Link(variant.url, variant.height, track.codec,
                source.label, source.headers, track.audioLanguages)
            if (variant.height >= 2160) foundUhd = true
            else foundFhd = true
            // One independently verified UHD and one HD safety fallback.
            if (chosen.size >= 2) break
        }
        return chosen
    }

    private companion object {
        val DEFAULT_HEADERS = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            "Accept" to "*/*"
        )
    }
}
