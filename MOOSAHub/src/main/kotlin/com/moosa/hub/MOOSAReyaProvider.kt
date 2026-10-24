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
 * Reya exact-TMDB independent stream source. Never trusts an upstream 4K tag:
 * video dimensions, compatible codec, and a muxed audio track must be read
 * from the real MP4 init section of the selected playable HLS rendition.
 *
 * For non-English movies with unlabelled audio, fail closed rather than play
 * an unrelated Hindi or other dub. English with unknown metadata is labelled
 * clearly as "audio language unverified", not falsely "English verified".
 */
internal class MOOSAReyaProvider : MainAPI() {
    override var name = MOOSAReyaPolicy.SOURCE_NAME
    override var mainUrl = MOOSAReyaPolicy.BASE
    override var lang = "en"
    override val hasMainPage = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private data class Verified(
        val master: String,
        val rendition: String,
        val height: Int,
        val width: Int,
        val source: String,
        val headers: Map<String, String>,
        val originalLanguage: String?,
        val languages: Set<String>?
    )

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

        val playable = withTimeoutOrNull(26_000L) {
            val api = runCatching { app.get(endpoint, headers = BASE_HEADERS) }
                .getOrNull() ?: return@withTimeoutOrNull emptyList<Verified>()
            if (!api.isSuccessful) return@withTimeoutOrNull emptyList<Verified>()
            val obj = runCatching { JSONObject(api.text) }.getOrNull()
                ?: return@withTimeoutOrNull emptyList<Verified>()
            if (obj.optInt("tmdbId") != request.tmdbId ||
                obj.optString("mediaType") != if (request.isTv) "tv" else "movie"
            ) return@withTimeoutOrNull emptyList<Verified>()
            if (request.isTv &&
                (obj.optInt("season") != request.season ||
                    obj.optInt("episode") != request.episode)
            ) return@withTimeoutOrNull emptyList<Verified>()

            val sources = obj.optJSONArray("streams")
                ?: return@withTimeoutOrNull emptyList<Verified>()
            val options = (0 until minOf(10, sources.length()))
                .mapNotNull { sources.optJSONObject(it) }
                .filter { MOOSAReyaPolicy.allowedUrl(it.optString("url")) }
                .distinctBy { it.optString("url") }
            coroutineScope {
                val gate = Semaphore(3)
                options.map { source ->
                    async {
                        gate.withPermit {
                            withTimeoutOrNull(10_000L) {
                                verifySource(source, request.originalLanguage)
                            }.orEmpty()
                        }
                    }
                }.awaitAll().flatten()
            }
        }.orEmpty().distinctBy { it.rendition }.sortedByDescending { it.height }

        playable.take(8).forEach { video ->
            val tag = if (video.height >= 2160) "4K" else
                if (video.height >= 1080) "1080p" else "720p"
            val audio = MOOSAAudioTrackPolicy.display(video.languages)
            val streamName = "Reya " + video.source
            callback(newExtractorLink(
                name,
                "$streamName • fixed $tag • $audio",
                video.rendition,
                ExtractorLinkType.M3U8
            ) {
                quality = video.height
                headers = video.headers
                referer = video.headers["Referer"].orEmpty()
            })
            // Preserve a second adaptive option for 4K streams: Fire TV can
            // switch to lower available HD rendition when the 4K CDN stalls.
            if (video.height >= 2160) {
                callback(newExtractorLink(
                    name,
                    "$streamName • 4K adaptive • $audio",
                    video.master,
                    ExtractorLinkType.M3U8
                ) {
                    quality = video.height
                    headers = video.headers
                    referer = video.headers["Referer"].orEmpty()
                })
            }
        }
        MOOSANativeSourceDiagnostics.mark(
            name, if (playable.isNotEmpty())
                "Verified real MP4 video dimensions + audio • " +
                    playable.size + " HD/UHD rendition(s)"
            else "No verified playable original-audio HD/UHD source"
        )
        return playable.isNotEmpty()
    }

    private suspend fun verifySource(
        source: JSONObject, originalLanguage: String?
    ): List<Verified> {
        val master = source.optString("url")
        if (!MOOSAReyaPolicy.allowedUrl(master)) return emptyList()
        val userHeaders = source.optJSONObject("headers")
        // Never forward provider Cookie, Authorization or arbitrary headers
        // into third-party media URLs.
        val headers = BASE_HEADERS.toMutableMap()
        listOf("Referer", "Origin", "User-Agent").forEach { name ->
            val value = userHeaders?.optString(name).orEmpty()
            if (value.length in 4..300 &&
                !value.contains('\n') && !value.contains('\r')
            ) headers[name] = value
        }
        val referer = source.optString("referer")
        if (referer.startsWith("https://") && referer.length < 300 &&
            "Referer" !in headers
        ) headers["Referer"] = referer

        val response = runCatching { app.get(master, headers = headers) }
            .getOrNull() ?: return emptyList()
        if (!response.isSuccessful) return emptyList()
        val variants = MOOSAReyaPolicy.variants(master, response.text)
        if (variants.isEmpty()) return emptyList()
        val found = mutableListOf<Verified>()
        for (variant in variants) {
            if (found.size >= 2) break
            val media = runCatching { app.get(variant.url, headers = headers) }
                .getOrNull() ?: continue
            if (!media.isSuccessful) continue
            val parts = MOOSAReyaPolicy.mediaParts(variant.url, media.text)
                ?: continue
            val init = readPrefix(parts.init, headers, 65536) ?: continue
            val tracks = MOOSAReyaPolicy.trackInfo(init) ?: continue
            val measuredHeight = when {
                tracks.videoWidth >= 3800 && tracks.videoHeight >= 1600 -> 2160
                tracks.videoWidth >= 1700 && tracks.videoHeight >= 800 -> 1080
                tracks.videoHeight >= 720 -> 720
                else -> continue
            }
            // A bad 1080 video must not be falsely advertised as 4K by HLS.
            if (measuredHeight < variant.height || measuredHeight < 720) continue
            if (!MOOSAAudioTrackPolicy.accept(originalLanguage, tracks.languages))
                continue
            val fragment = readPrefix(parts.segment, headers, 16384) ?: continue
            if (!MOOSAReyaPolicy.fragmentLooksPlayable(fragment)) continue
            val sourceName = source.optString("source", "HLS")
                .replace(Regex("""[^A-Za-z0-9_-]+"""), "")
                .take(18).ifBlank { "HLS" }
            found += Verified(
                master, variant.url, measuredHeight,
                tracks.videoWidth, sourceName,
                headers, originalLanguage, tracks.languages
            )
        }
        return found
    }

    /** The CDN may report an incorrect MIME type: validate real bytes. */
    private suspend fun readPrefix(
        url: String, headers: Map<String, String>, limit: Int
    ): ByteArray? {
        if (!MOOSAReyaPolicy.allowedUrl(url)) return null
        val result = runCatching {
            app.get(url, headers = headers +
                ("Range" to "bytes=0-" + (limit - 1)))
        }.getOrNull() ?: return null
        if (!result.isSuccessful) return null
        val buffer = ByteArray(limit)
        val read = result.body.byteStream().use { stream ->
            var count = 0
            while (count < buffer.size) {
                val current = stream.read(buffer, count, buffer.size - count)
                if (current <= 0) break
                count += current
            }
            count
        }
        return buffer.copyOf(read).takeIf { it.size >= 96 }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 7.1.2) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        val BASE_HEADERS = mapOf("User-Agent" to UA)
    }
}
