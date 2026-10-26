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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Completely independent direct-file source. No guessed 4K, audio language
 * or seasons: a file is emitted ONLY after a 206 range response exposes
 * compatible Matroska video dimensions AND original-language audio.
 */
internal class MOOSAOpenDirectoryProvider : MainAPI() {
    override var name = MOOSAOpenDirectoryPolicy.NAME
    override var mainUrl = MOOSAOpenDirectoryPolicy.BASE
    override var lang = "en"
    override val hasMainPage = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private data class Video(
        val url: String,
        val width: Int,
        val height: Int,
        val quality: Int,
        val languages: Set<String>?
    )

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = com.lagradost.cloudstream3.utils.AppUtils
            .tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data) ?: return false
        if (request.isAnime ||
            (request.isTv && (request.season == null || request.episode == null))
        ) return false
        val directory = MOOSAOpenDirectoryPolicy.directory(
            request.title, request.year, if (request.isTv) request.season else null
        ) ?: return false

        val available = withTimeoutOrNull(23_000L) {
            val index = runCatching {
                app.get(directory, headers = HEADERS)
            }.getOrNull() ?: return@withTimeoutOrNull emptyList<Video>()
            if (!index.isSuccessful) return@withTimeoutOrNull emptyList<Video>()
            val candidates = index.document.select("tr").mapNotNull { row ->
                val link = row.selectFirst("a[href]") ?: return@mapNotNull null
                MOOSAOpenDirectoryPolicy.candidate(
                    directory, link.attr("href"), link.text(),
                    if (request.isTv) request.season else null,
                    if (request.isTv) request.episode else null
                )
            }.distinctBy { it.fileUrl }
                .sortedByDescending { it.hintedHeight }
                .take(5)

            coroutineScope {
                val gate = kotlinx.coroutines.sync.Semaphore(2)
                candidates.map { file ->
                    async {
                        gate.withPermit {
                            withTimeoutOrNull(10_000L) {
                                verify(file, request.originalLanguage)
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }.orEmpty()

        available.distinctBy { it.url }.forEach { video ->
            val audio = MOOSAServerTitlePolicy.audio(video.languages)
            callback(
                newExtractorLink(
                    name,
                    "OpenDirectory • Direct • $audio",
                    video.url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = video.quality
                    this.referer = mainUrl + "/"
                    this.headers = HEADERS
                }
            )
        }
        MOOSANativeSourceDiagnostics.mark(
            name,
            if (available.isEmpty()) "No playable direct UHD/FHD file"
            else "MKV video and original audio verified: " +
                available.size + " link(s)"
        )
        return available.isNotEmpty()
    }

    private suspend fun verify(
        file: MOOSAOpenDirectoryPolicy.Candidate, originalLanguage: String?
    ): Video? {
        // Prefer the public range-aware worker; try the first-party file URL
        // only if the worker does not produce a real seekable media response.
        for (url in listOf(file.streamUrl, file.fileUrl)) {
            val response = runCatching {
                app.get(
                    url, headers = HEADERS + ("Range" to "bytes=0-131071"),
                    referer = mainUrl + "/"
                )
            }.getOrNull() ?: continue
            if (response.code != 206 ||
                !response.headers["Content-Range"].orEmpty().startsWith("bytes 0-")
            ) continue
            val bytes = ByteArray(131072)
            val count = response.body.byteStream().use { input ->
                var n = 0
                while (n < bytes.size) {
                    val read = input.read(bytes, n, bytes.size - n)
                    if (read <= 0) break
                    n += read
                }
                n
            }
            val evidence = bytes.copyOf(count)
            val track = MOOSA4KHDHubPolicy.compatibleVideo(evidence) ?: continue
            val quality = when {
                track.width >= 3800 && track.height >= 1600 -> 2160
                track.width >= 1700 && track.height >= 800 -> 1080
                else -> continue
            }
            if (quality < file.hintedHeight) continue
            val audio = MOOSA4KHDHubPolicy.audioLanguages(evidence)
            if (!MOOSAAudioTrackPolicy.accept(originalLanguage, audio)) continue
            return Video(url, track.width, track.height, quality, audio)
        }
        return null
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 7.1.2) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        val HEADERS = mapOf("User-Agent" to UA)
    }
}
