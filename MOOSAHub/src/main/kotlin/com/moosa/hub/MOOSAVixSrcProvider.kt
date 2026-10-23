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
                val variant = MOOSAVixSrcPolicy.highestVariant(
                    master, masterResponse.text
                ) ?: return@runCatching null
                val media = app.get(
                    variant.url, headers = HEADERS + ("Referer" to endpoint)
                )
                if (!media.isSuccessful) return@runCatching null
                val segment = MOOSAVixSrcPolicy.firstSegment(
                    variant.url, media.text
                ) ?: return@runCatching null
                val sample = app.get(
                    segment,
                    headers = HEADERS + mapOf(
                        "Referer" to endpoint,
                        "Range" to "bytes=0-2047"
                    )
                )
                if (!sample.isSuccessful ||
                    sample.headers["Content-Type"].orEmpty().contains("html", true)
                ) return@runCatching null
                val prefix = ByteArray(32)
                val evidence = sample.body.byteStream().use { stream ->
                    var read = 0
                    while (read < prefix.size) {
                        val n = stream.read(prefix, read, prefix.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    read
                }
                if (evidence < 12 || prefix[0] == '<'.code.toByte() ||
                    prefix[0] == '{'.code.toByte()
                ) return@runCatching null
                master to variant.height
            }.getOrNull()
        }
        if (result == null) {
            MOOSANativeSourceDiagnostics.mark(name, "VixSrc returned no verified HD segment")
            return false
        }

        callback(newExtractorLink(
            name,
            "VixSrc • Adaptive ${result.second}p",
            result.first,
            ExtractorLinkType.M3U8
        ) {
            this.quality = result.second
            this.referer = endpoint
            this.headers = HEADERS + ("Referer" to endpoint)
        })
        MOOSANativeSourceDiagnostics.mark(
            name, "HLS ${result.second}p variant and segment reachable"
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
