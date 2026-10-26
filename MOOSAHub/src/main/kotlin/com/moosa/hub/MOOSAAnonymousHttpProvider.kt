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

/** Separate no-login HTTP addons. Never convert P2P hashes to video links. */
internal class MOOSAAnonymousHttpProvider(
    private val sourceLabel: String,
    private val sourceBase: String
) : MainAPI() {
    override var name = "MOOSA $sourceLabel HTTP"
    override var mainUrl = sourceBase
    override var lang = "en"
    override val hasMainPage = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private data class Verified(
        val url: String, val height: Int, val hls: Boolean,
        val label: String, val audio: Set<String>?
    )

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = com.lagradost.cloudstream3.utils.AppUtils
            .tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data) ?: return false
        if (request.isAnime || request.originalLanguage.equals("ar", true)) return false
        val endpoint = MOOSAAnonymousHttpPolicy.streamApi(
            sourceBase, request.imdbId, request.season, request.episode, request.isTv
        ) ?: return false

        val found = withTimeoutOrNull(24_000L) {
            val response = runCatching { app.get(endpoint, headers = HEADERS) }
                .getOrNull() ?: return@withTimeoutOrNull emptyList<Verified>()
            if (!response.isSuccessful || response.text.length > 1_000_000)
                return@withTimeoutOrNull emptyList<Verified>()
            val array = runCatching { JSONObject(response.text).optJSONArray("streams") }
                .getOrNull() ?: return@withTimeoutOrNull emptyList<Verified>()
            val candidates = (0 until minOf(70, array.length())).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                if (!MOOSAAnonymousHttpPolicy.acceptedCandidate(
                    entry.optString("title"), entry.optString("name"),
                    entry.optString("infoHash").takeIf { it.isNotBlank() },
                    if (entry.has("fileIdx")) entry.optInt("fileIdx") else null
                )) return@mapNotNull null
                if (!MOOSASubtitleEpisodePolicy.accepts(
                    request.season, request.episode, null, null,
                    entry.optString("title"), entry.optString("name")
                )) return@mapNotNull null
                val url = MOOSAAnonymousHttpPolicy.safeMediaUrl(entry.optString("url"))
                    ?: return@mapNotNull null
                url to MOOSAAnonymousHttpPolicy.sourceName(entry.optString("name"))
            }.distinctBy { it.first }.take(12)
            coroutineScope {
                val gate = Semaphore(3)
                candidates.map { (url, title) ->
                    async {
                        gate.withPermit {
                            withTimeoutOrNull(8_000L) {
                                verify(url, title, request.originalLanguage)
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }.orEmpty()
        found.forEach { item ->
            callback(newExtractorLink(
                name,
                sourceLabel + " • " + item.label +
                    MOOSAServerTitlePolicy.audioSuffix(item.audio),
                item.url,
                if (item.hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = item.height
            })
        }
        MOOSANativeSourceDiagnostics.mark(name,
            if (found.isEmpty()) "No verified HTTPS HD stream"
            else "Verified HTTP candidates: " + found.size
        )
        return found.isNotEmpty()
    }

    private suspend fun verify(url: String, title: String, original: String?): Verified? {
        val response = runCatching {
            app.get(url, headers = HEADERS + ("Range" to "bytes=0-196607"))
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        val mime = response.headers["Content-Type"].orEmpty().lowercase()
        if (mime.contains("html") || mime.contains("json") ||
            mime.startsWith("audio/")) return null
        val prefix = ByteArray(196608)
        val length = response.body.byteStream().use { input ->
            var total = 0
            while (total < prefix.size) {
                val n = input.read(prefix, total, prefix.size - total)
                if (n <= 0) break
                total += n
            }
            total
        }
        if (length < 32) return null
        val bytes = prefix.copyOf(length)
        val first = String(bytes, 0, minOf(32, length), Charsets.UTF_8).trimStart()
        if (first.startsWith("<") || first.startsWith("{")) return null
        if (first.startsWith("#EXTM3U")) {
            val manifest = String(bytes, Charsets.UTF_8)
            val height = MOOSAAnonymousHttpPolicy.hlsHeight(manifest) ?: return null
            if (!MOOSAVixSrcPolicy.hasOriginalAudio(
                manifest, MOOSAVixSrcPolicy.preferredAudioLanguage(original)
            )) return null
            return Verified(url, height, true, title, null)
        }
        val video = MOOSA4KHDHubPolicy.compatibleVideo(bytes) ?: return null
        val languages = MOOSA4KHDHubPolicy.audioLanguages(bytes)
        if (!MOOSAAudioTrackPolicy.accept(original, languages)) return null
        val height = MOOSAAnonymousHttpPolicy.videoHeight(video.width, video.height)
            ?: return null
        return Verified(url, height, false, title, languages)
    }

    private companion object {
        val HEADERS = mapOf("User-Agent" to
            "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            "Accept" to "*/*")
    }
}
