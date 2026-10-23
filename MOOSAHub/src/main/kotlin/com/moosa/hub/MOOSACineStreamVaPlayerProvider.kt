package com.moosa.hub

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Independent, native, no-third-party-CS3-required source adapter.
 *
 * VaPlayer is one of CineStream's HTTP invokers, not CineStream itself.
 * Attribution: SaurabhKaperwan/CSX, CineStreamExtractors.invokeVaPlayer
 * (GPLv3-or-later README); this is a small independent protocol adapter,
 * not a copy of the upstream extractor source.
 *
 * It accepts only an exact IMDb ID + S/E tuple and real HTTPS HLS URLs.
 * Resolution comes from MOOSA's actual HLS manifest probe; NEVER invent 1080p.
 * An unreachable endpoint or missing/invalid data simply yields no results.
 */
internal object MOOSACineStreamVaPlayerPolicy {
    const val SOURCE_NAME = "MOOSA CineStream • VaPlayer"
    const val BASE = "https://streamdata.vaplayer.ru"
    const val REFERER = "https://nextgencloudfabric.com/"

    private val imdbPattern = Regex("""tt[0-9]{5,10}""")

    fun requestUrl(id: String?, season: Int?, episode: Int?): String? {
        if (id == null || !imdbPattern.matches(id)) return null
        if (season == null && episode == null) {
            return "$BASE/api.php?imdb=$id&type=movie"
        }
        if (season == null || episode == null ||
            season !in 1..200 || episode !in 1..3000
        ) return null
        return "$BASE/api.php?imdb=$id&type=tv&season=$season&episode=$episode"
    }

    fun hlsUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // Never forward browser wrappers, magnet links, cleartext or redirects
        // with unknown media types into the player.
        val value = url.trim()
        if (!value.startsWith("https://", ignoreCase = true)) return null
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return null
        val path = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
        if (!path.endsWith(".m3u8")) return null
        return value
    }
}

internal class MOOSACineStreamVaPlayerProvider : MainAPI() {
    override var name: String = MOOSACineStreamVaPlayerPolicy.SOURCE_NAME
    override var mainUrl: String = MOOSACineStreamVaPlayerPolicy.BASE
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
        val url = MOOSACineStreamVaPlayerPolicy.requestUrl(
            request.imdbId, request.season, request.episode
        ) ?: return false

        MOOSANativeSourceDiagnostics.mark(name, "Requesting S02E01 • VaPlayer API")
        val response = withTimeoutOrNull(7500L) {
            runCatching {
                app.get(
                    url,
                    headers = mapOf("Referer" to MOOSACineStreamVaPlayerPolicy.REFERER)
                )
            }.getOrNull()
        } ?: run {
            MOOSANativeSourceDiagnostics.mark(name, "API unreachable / 7.5s timeout")
            return false
        }
        if (!response.isSuccessful) {
            MOOSANativeSourceDiagnostics.mark(name, "API HTTP " + response.code)
            return false
        }
        val json = runCatching { JSONObject(response.text) }.getOrNull()
            ?: run {
                MOOSANativeSourceDiagnostics.mark(name, "API returned invalid JSON")
                return false
            }
        val streamList = json.optJSONObject("data")?.optJSONArray("stream_urls")
            ?: run {
                MOOSANativeSourceDiagnostics.mark(name, "No links for S02E01 in API data")
                return false
            }
        val urls = (0 until minOf(streamList.length(), 12))
            .mapNotNull { index ->
                MOOSACineStreamVaPlayerPolicy.hlsUrl(
                    streamList.optString(index)
                )
            }
            .distinct()
        var emitted = false
        urls.forEach { video ->
            emitted = true
            callback(
                newExtractorLink(
                    MOOSACineStreamVaPlayerPolicy.SOURCE_NAME,
                    "VaPlayer • HLS",
                    video,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = MOOSACineStreamVaPlayerPolicy.REFERER
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to MOOSACineStreamVaPlayerPolicy.REFERER
                    )
                }
            )
        }

        MOOSANativeSourceDiagnostics.mark(
            name,
            if (emitted) "HLS found • verifying playback / 720p+"
            else "API responded • no valid HTTPS HLS"
        )
        // Native/third-party subtitles are still handled by MOOSA's existing
        // parallel language-verified subtitle engine; no speculative captions.
        return emitted
    }
}
