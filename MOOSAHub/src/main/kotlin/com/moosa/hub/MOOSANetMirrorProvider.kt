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
import org.json.JSONObject

/** Optional on-device fallback: reject signed CDN links blocked in the viewer's region. */
internal class MOOSANetMirrorProvider : MainAPI() {
    override var name: String = MOOSANetMirrorPolicy.SOURCE_NAME
    override var mainUrl: String = MOOSANetMirrorPolicy.BASE
    override var lang: String = "en"
    override val hasMainPage: Boolean = false
    override val providerType: ProviderType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private data class VerifiedCandidate(
        val candidate: MOOSANetMirrorPolicy.Candidate,
        val audioLanguages: Set<String>?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = com.lagradost.cloudstream3.utils.AppUtils
            .tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data) ?: return false
        if (request.isAnime || request.originalLanguage.equals("ar", true)) return false
        val endpoint = MOOSANetMirrorPolicy.apiUrl(
            request.tmdbId, request.isTv, request.season, request.episode
        ) ?: return false

        val playable = withTimeoutOrNull(13_000L) {
            runCatching {
                val result = app.get(endpoint, headers = API_HEADERS)
                if (!result.isSuccessful) return@runCatching emptyList()
                val body = JSONObject(result.text)
                if (!MOOSANetMirrorPolicy.validIdentity(body, request))
                    return@runCatching emptyList()
                coroutineScope {
                    MOOSANetMirrorPolicy.directCandidates(body).map { item ->
                        async {
                            withTimeoutOrNull(8_000L) {
                                runCatching {
                                    val response = app.get(
                                        item.url,
                                        headers = MEDIA_HEADERS + ("Range" to "bytes=0-131071"),
                                        referer = MOOSANetMirrorPolicy.MEDIA_REFERER
                                    )
                                    if (response.code != 206 ||
                                        !response.headers["Content-Range"].orEmpty()
                                            .startsWith("bytes 0-", true)
                                    ) return@runCatching null
                                    val prefix = ByteArray(131072)
                                    val bytes = response.body.byteStream().use { stream ->
                                        var count = 0
                                        while (count < prefix.size) {
                                            val n = stream.read(prefix, count, prefix.size - count)
                                            if (n <= 0) break
                                            count += n
                                        }
                                        count
                                    }
                                    val evidence = prefix.copyOf(bytes)
                                    if (!MOOSANetMirrorPolicy.compatibleMp4(evidence))
                                        return@runCatching null
                                    val languages = MOOSANetMirrorPolicy.audioLanguages(evidence)
                                    if (!MOOSAAudioTrackPolicy.accept(
                                            request.originalLanguage, languages
                                        )) return@runCatching null
                                    VerifiedCandidate(item, languages)
                                }.getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }.getOrNull()
        }.orEmpty()

        playable.forEach { verified ->
            val item = verified.candidate
            val audioLabel = MOOSAServerTitlePolicy.audio(verified.audioLanguages)
            callback(newExtractorLink(
                name,
                "NetMirror • $audioLabel",
                item.url,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = item.height
                this.referer = MOOSANetMirrorPolicy.MEDIA_REFERER
                this.headers = MEDIA_HEADERS
            })
        }
        MOOSANativeSourceDiagnostics.mark(
            name,
            if (playable.isNotEmpty()) "Compatible MP4 video + original audio policy + seeking verified"
            else "CDN unavailable or video compatibility not confirmed"
        )
        return playable.isNotEmpty()
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
        val API_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://net27.cc/"
        )
        val MEDIA_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to MOOSANetMirrorPolicy.MEDIA_REFERER
        )
    }
}
