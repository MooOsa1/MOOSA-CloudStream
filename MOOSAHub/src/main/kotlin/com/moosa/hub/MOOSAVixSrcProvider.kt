package com.moosa.hub

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Exact TMDB movie / season / episode -> VixSrc API -> expiring HLS token.
 * Requires a reachable HD rendition AND a readable media segment. The HLS
 * player selects its own bitrate, allowing seamless 1080p -> 720p fallback.
 */
internal class MOOSAVixSrcProvider : MainAPI() {
    override var name: String = MOOSAVixSrcPolicy.SOURCE_NAME
    override var mainUrl: String = MOOSAVixSrcPolicy.BASE
    override var lang: String = "en"
    override val hasMainPage: Boolean = false
    override val providerType: ProviderType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = com.lagradost.cloudstream3.utils.AppUtils
            .tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data) ?: return false
        if (request.isAnime) return false
        val originalAudio = MOOSAVixSrcPolicy.preferredAudioLanguage(request.originalLanguage)
        val endpoint = MOOSAVixSrcPolicy.apiUrl(
            request.tmdbId, request.isTv, request.season, request.episode
        ) ?: return false

        val result = withTimeoutOrNull(15_000L) {
            runCatching {
                val api = app.get(endpoint, headers = HEADERS)
                if (!api.isSuccessful) return@runCatching null
                val embedPath = JSONObject(api.text).optString("src")
                val embedUrl = MOOSAVixSrcPolicy.safeEmbed(embedPath)
                    ?: return@runCatching null
                val embed = app.get(embedUrl, headers = HEADERS)
                if (!embed.isSuccessful) return@runCatching null
                val tokenData = MOOSAVixSrcPolicy.tokenFromHtml(embed.text)
                    ?: return@runCatching null
                val master = MOOSAVixSrcPolicy.masterUrl(tokenData, originalAudio)
                val masterResponse = app.get(
                    master, headers = HEADERS + ("Referer" to endpoint)
                )
                if (!masterResponse.isSuccessful) return@runCatching null
                if (!MOOSAVixSrcPolicy.hasOriginalAudio(masterResponse.text, originalAudio))
                    return@runCatching null
                // First check 1080p, then 720p on a failed CDN fragment.
                // A broken top rendition must not hide an otherwise healthy episode.
                val variants = MOOSAVixSrcPolicy.hdVariants(
                    master, masterResponse.text
                )
                if (variants.isEmpty()) return@runCatching null
                var playableHeight: Int? = null
                for (variant in variants) {
                    val headers = HEADERS + ("Referer" to endpoint)
                    val media = runCatching {
                        app.get(variant.url, headers = headers)
                    }.getOrNull() ?: continue
                    if (!media.isSuccessful) continue
                    val aesKeyUrl = MOOSAVixSrcPolicy.aes128KeyUrl(
                        variant.url, media.text
                    )
                    val segment = MOOSAVixSrcPolicy.firstSegment(
                        variant.url, media.text
                    ) ?: continue
                    val readLimit = if (aesKeyUrl != null) 2048
                        else if (variant.declaredVideoCodec) 64 else 32768
                    val sample = runCatching {
                        app.get(
                            segment,
                            headers = headers + ("Range" to "bytes=0-${readLimit - 1}")
                        )
                    }.getOrNull() ?: continue
                    val contentType = sample.headers["Content-Type"].orEmpty()
                    if (!sample.isSuccessful ||
                        (aesKeyUrl == null &&
                            contentType.contains("html", ignoreCase = true)) ||
                        contentType.startsWith("audio/", ignoreCase = true)
                    ) continue
                    val prefix = ByteArray(readLimit)
                    val evidence = sample.body.byteStream().use { stream ->
                        var read = 0
                        while (read < prefix.size) {
                            val n = stream.read(prefix, read, prefix.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        read
                    }
                    if (evidence < 64) continue
                    if (aesKeyUrl != null) {
                        if (!MOOSAVixSrcPolicy.looksLikeEncryptedMedia(
                                prefix.copyOf(evidence)
                            )
                        ) continue
                        // A valid video fragment without its 16-byte AES key
                        // still cannot be played; don't emit it prematurely.
                        val keyResponse = runCatching {
                            app.get(aesKeyUrl, headers = headers)
                        }.getOrNull() ?: continue
                        if (!keyResponse.isSuccessful ||
                            keyResponse.body.bytes().size != 16
                        ) continue
                    } else if (prefix[0] == '<'.code.toByte() ||
                        prefix[0] == '{'.code.toByte()
                    ) continue
                    // No video CODECS? Prove H264 video exists in the TS.
                    if (!variant.declaredVideoCodec && aesKeyUrl == null) {
                        val measured = MOOSAHlsMediaQuality.h264HeightFromTs(
                            prefix.copyOf(evidence)
                        ) ?: continue
                        if (measured < 720) continue
                    }
                    playableHeight = variant.height
                    break
                }
                // Retain master HLS and its separate audio tracks. Returning
                // bare video-rendition URLs can lose the original audio.
                playableHeight?.let { Triple(master, it, masterResponse.text) }
            }.getOrNull()
        }
        if (result == null) {
            MOOSANativeSourceDiagnostics.mark(name, "VixSrc returned no verified HD segment")
            return false
        }

        callback(newExtractorLink(
            name,
            "VixSrc • HLS • Adaptive",
            result.first,
            ExtractorLinkType.M3U8
        ) {
            this.quality = result.second
            this.referer = endpoint
            this.headers = HEADERS + ("Referer" to endpoint)
        })
        // Unlike external full-series search results, these subtitle tracks
        // belong to the exact episode's HLS master and its actual video cut.
        // Emit video FIRST; slow caption hosts must not delay player startup.
        coroutineScope {
            MOOSAVixSrcPolicy.nativeCaptions(result.third).map { caption ->
                async {
                    withTimeoutOrNull(4_000L) {
                        val playlist = runCatching {
                            app.get(caption.playlistUrl, headers = HEADERS)
                        }.getOrNull() ?: return@withTimeoutOrNull
                        if (!playlist.isSuccessful) return@withTimeoutOrNull
                        val vtt = MOOSAVixSrcPolicy.nativeVttUrl(
                            caption.playlistUrl, playlist.text
                        ) ?: return@withTimeoutOrNull
                        val body = runCatching {
                            app.get(vtt, headers = HEADERS)
                        }.getOrNull() ?: return@withTimeoutOrNull
                        if (!body.isSuccessful ||
                            !body.text.trimStart().startsWith("WEBVTT") ||
                            !body.text.contains(" --> ")
                        ) return@withTimeoutOrNull
                        subtitleCallback(SubtitleFile(
                            "${caption.language} • VixSrc matched episode", vtt
                        ))
                    }
                }
            }.awaitAll()
        }
        MOOSANativeSourceDiagnostics.mark(
            name, "HLS video ${result.second}p rendition and segment reachable"
        )
        return true
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150 Safari/537.36"
        val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://vixsrc.to",
            "Origin" to "https://vixsrc.to"
        )
    }
}
