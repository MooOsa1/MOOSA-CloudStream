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

/** Direct MoviesDrive films, with exact IMDb matching and on-wire UHD proof.
 * No season packs, HTML download pages, mislabeled 4K, or torrent links.
 */
internal class MOOSAMoviesDriveProvider : MainAPI() {
    override var name = MOOSAMoviesDrivePolicy.NAME
    override var mainUrl = MOOSAMoviesDrivePolicy.BASE
    override var lang = "en"
    override val hasMainPage = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie)

    private data class Verified(
        val url: String, val quality: Int, val languages: Set<String>?
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = com.lagradost.cloudstream3.utils.AppUtils
            .tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data) ?: return false
        val imdb = MOOSAMoviesDrivePolicy.imdbId(request.imdbId) ?: return false
        if (request.isAnime || request.isTv) return false

        val verified = withTimeoutOrNull(23_000L) {
            searchAndVerify(imdb, request.originalLanguage)
        }.orEmpty().distinctBy { it.url }
        verified.forEach { file ->
            val audio = MOOSAAudioTrackPolicy.display(file.languages)
            callback(newExtractorLink(
                name,
                "MoviesDrive • Pixeldrain verified ${if (file.quality == 2160) "4K" else "1080p"} • $audio",
                file.url,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = file.quality
                this.headers = mapOf("User-Agent" to USER_AGENT)
            })
        }
        MOOSANativeSourceDiagnostics.mark(
            name,
            if (verified.isEmpty()) "No verified seekable direct video" else
                "${verified.size} seekable verified MKV(s); actual video dimensions"
        )
        return verified.isNotEmpty()
    }

    private suspend fun searchAndVerify(
        imdb: String, originalLanguage: String?
    ): List<Verified> = coroutineScope {
        val query = runCatching {
            app.get("$mainUrl/search.php?q=$imdb")
        }.getOrNull() ?: return@coroutineScope emptyList()
        if (!query.isSuccessful) return@coroutineScope emptyList()
        val json = runCatching { JSONObject(query.text) }.getOrNull()
            ?: return@coroutineScope emptyList()
        val hits = json.optJSONArray("hits") ?: return@coroutineScope emptyList()
        val page = (0 until hits.length()).firstNotNullOfOrNull { index ->
            val row = hits.optJSONObject(index)?.optJSONObject("document")
                ?: return@firstNotNullOfOrNull null
            if (row.optString("imdb_id") != imdb) return@firstNotNullOfOrNull null
            MOOSAMoviesDrivePolicy.moviePermalink(row.optString("permalink"))
        } ?: return@coroutineScope emptyList()

        val detail = runCatching { app.get(page) }.getOrNull()
            ?: return@coroutineScope emptyList()
        if (!detail.isSuccessful) return@coroutineScope emptyList()
        // Every candidate must be labeled with its release's own quality;
        // a site's top-level 4K category is never sufficient evidence.
        val candidates = detail.document.select("a[href]").mapNotNull { a ->
            val archive = MOOSAMoviesDrivePolicy.archive(a.attr("href"))
                ?: return@mapNotNull null
            val quality = MOOSAMoviesDrivePolicy.releaseQuality(a.text())
                ?: return@mapNotNull null
            archive to quality
        }.distinctBy { it.first }
        val selected = candidates.filter { it.second == 2160 }.take(2) +
            candidates.filter { it.second == 1080 }.take(1)
        val gate = Semaphore(3)
        selected.map { (archive, requiredQuality) ->
            async {
                gate.withPermit {
                    withTimeoutOrNull(13_000L) {
                        resolve(archive, requiredQuality, originalLanguage)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun resolve(
        archive: String, requiredQuality: Int, originalLanguage: String?
    ): Verified? {
        val post = runCatching { app.get(archive) }.getOrNull() ?: return null
        if (!post.isSuccessful) return null
        val hubs = post.document.select("a[href]")
            .mapNotNull { MOOSAMoviesDrivePolicy.hub(it.attr("href")) }
            .distinct().take(2)
        for (hub in hubs) {
            val page = runCatching { app.get(hub) }.getOrNull() ?: continue
            if (!page.isSuccessful) continue
            val terminal = Regex("""var\s+url\s*=\s*['\"]([^'\"]+)['\"]""")
                .find(page.text)?.groupValues?.get(1)
                ?.let(MOOSAMoviesDrivePolicy::terminal) ?: continue
            val links = runCatching {
                app.get(terminal, headers = mapOf("Referer" to hub))
            }.getOrNull() ?: continue
            if (!links.isSuccessful) continue
            // Prefer the real JS-selected PixelServer over a stale static href.
            val script = Regex("""var\s+pxl\s*=\s*['\"]([^'\"]+)['\"]""")
                .find(links.text)?.groupValues?.get(1)
                ?.let(MOOSAMoviesDrivePolicy::pixeldrainApi)
            val candidates = (listOfNotNull(script) +
                links.document.select("a[href]").mapNotNull {
                    MOOSAMoviesDrivePolicy.pixeldrainApi(it.attr("href"))
                }).distinct().take(2)
            for (url in candidates) {
                val video = verify(url, requiredQuality, originalLanguage)
                if (video != null) return video
            }
        }
        return null
    }

    private suspend fun verify(
        url: String, requiredQuality: Int, originalLanguage: String?
    ): Verified? {
        val response = runCatching {
            app.get(url, headers = mapOf(
                "Range" to "bytes=0-131071", "User-Agent" to USER_AGENT
            ))
        }.getOrNull() ?: return null
        if (response.code != 206 ||
            !response.headers["Content-Range"].orEmpty()
                .startsWith("bytes 0-", ignoreCase = true)
        ) return null
        val bytes = ByteArray(131072)
        val read = response.body.byteStream().use { stream ->
            var count = 0
            while (count < bytes.size) {
                val size = stream.read(bytes, count, bytes.size - count)
                if (size <= 0) break
                count += size
            }
            count
        }
        val video = MOOSA4KHDHubPolicy.compatibleVideo(bytes.copyOf(read))
            ?: return null
        val actual = MOOSA4KHDHubPolicy.actualQuality(video.width, video.height)
            ?: return null
        if (actual < requiredQuality) return null
        val languages = MOOSA4KHDHubPolicy.audioLanguages(bytes.copyOf(read))
        if (!MOOSAAudioTrackPolicy.accept(originalLanguage, languages)) return null
        return Verified(url, actual, languages)
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
