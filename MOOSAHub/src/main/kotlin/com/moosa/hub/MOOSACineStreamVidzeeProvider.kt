package com.moosa.hub

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Second independent protocol family alongside VaPlayer / Vidrock:
 * CineStream-origin VidZee's direct TMDB movie/TV season-episode HLS API.
 *
 * Based on the documented formats in SaurabhKaperwan/CSX:
 * CineStreamExtractors.invokeVidzee and CineStreamUtils.decryptVidzeeUrl.
 * Upstream README: GPLv3-or-later. No CineStream.cs3 is needed.
 *
 * Safe bounded networking; no torrents; no manufactured quality labels.
 */
internal object MOOSACineStreamVidzeePolicy {
    const val SOURCE_NAME = "MOOSA CineStream • VidZee"
    const val BASE = "https://player.vidzee.wtf"
    val SERVERS = listOf(0, 1, 2, 4, 5, 6, 7)
    private const val CIPHER_KEY = "A7kP9xM2Qv8Lr4Nz1Ht6Yc3Bw5Jf0DsU"

    fun requestUrl(id: Int?, isTv: Boolean, season: Int?, episode: Int?, server: Int): String? {
        if (id == null || id !in 1..999999999 || server !in SERVERS) return null
        if (!isTv && season == null && episode == null)
            return "$BASE/api/server?id=$id&sr=$server"
        if (!isTv || season == null || episode == null ||
            season !in 1..200 || episode !in 1..3000) return null
        return "$BASE/api/server?id=$id&sr=$server&ss=$season&ep=$episode"
    }

    // Pure JVM/Kotlin Base64 implementation supports Fire OS 6 (API 25),
    // unlike java.util.Base64 (Android 26+). Invalid encodings fail closed.
    fun base64(data: String?): ByteArray? {
        if (data.isNullOrBlank() || data.length > 32768) return null
        val trimmed = data.trim()
        val unpadded = trimmed.trimEnd('=')
        if (trimmed.length - unpadded.length > 2 || unpadded.length % 4 == 1)
            return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = ByteArray(unpadded.length * 3 / 4 + 1)
        var carry = 0
        var bits = 0
        var n = 0
        for (char in unpadded) {
            val six = alphabet.indexOf(char)
            if (six < 0) return null
            carry = (carry shl 6) or six
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = ((carry ushr bits) and 255).toByte()
                carry = carry and ((1 shl bits) - 1)
            }
        }
        if (carry != 0) return null
        return out.copyOf(n)
    }

    fun secureHls(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val candidate = raw.trim()
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.fragment != null) return null
        if (!uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8")) return null
        return candidate
    }

    fun decryptHls(payload: String?): String? {
        val wrapperBytes = base64(payload) ?: return null
        val wrapper = runCatching {
            String(wrapperBytes, Charsets.UTF_8)
        }.getOrNull() ?: return null
        val parts = wrapper.split(':', limit = 2)
        if (parts.size != 2) return null
        val iv = base64(parts[0])?.takeIf { it.size == 16 } ?: return null
        val data = base64(parts[1])?.takeIf {
            it.size in 16..16384 && it.size % 16 == 0
        } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(CIPHER_KEY.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(iv)
            )
            secureHls(String(cipher.doFinal(data), Charsets.UTF_8))
        }.getOrNull()
    }
}

internal class MOOSACineStreamVidzeeProvider : MainAPI() {
    override var name = MOOSACineStreamVidzeePolicy.SOURCE_NAME
    override var mainUrl = MOOSACineStreamVidzeePolicy.BASE
    override var lang = "en"
    override val hasMainPage = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val request = tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data)
            ?: return@coroutineScope false
        if (MOOSACineStreamVidzeePolicy.requestUrl(
                request.tmdbId, request.isTv, request.season, request.episode, 0
            ) == null) return@coroutineScope false

        // Seven genuinely independent server IDs, bounded to 3 concurrent
        // requests to avoid adding large search latency or stressing Fire TV.
        val gate = Semaphore(3)
        val links = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val fetched = withTimeoutOrNull(14000L) {
            MOOSACineStreamVidzeePolicy.SERVERS.map { server ->
                async {
                    gate.withPermit {
                        val url = MOOSACineStreamVidzeePolicy.requestUrl(
                            request.tmdbId, request.isTv,
                            request.season, request.episode, server
                        ) ?: return@withPermit
                        val result = runCatching {
                            app.get(url, headers = mapOf(
                                "Referer" to MOOSACineStreamVidzeePolicy.BASE + "/"
                            ))
                        }.getOrNull() ?: return@withPermit
                        if (!result.isSuccessful) return@withPermit
                        val json = runCatching { JSONObject(result.text) }.getOrNull()
                            ?: return@withPermit
                        val provided = json.optJSONObject("headers")
                        val acceptedHeaders = mutableMapOf(
                            "Referer" to (MOOSACineStreamVidzeePolicy.BASE + "/")
                        )
                        if (provided != null) {
                            for (key in listOf("Referer", "referer", "Origin", "origin",
                                                "User-Agent", "user-agent", "Accept")) {
                                val value = provided.optString(key).takeIf {
                                    it.isNotBlank() && it.length <= 600 &&
                                        !it.contains('\n') && !it.contains('\r')
                                } ?: continue
                                val canonical = when {
                                    key.equals("referer", true) -> "Referer"
                                    key.equals("origin", true) -> "Origin"
                                    key.equals("user-agent", true) -> "User-Agent"
                                    else -> "Accept"
                                }
                                acceptedHeaders[canonical] = value
                            }
                        }
                        val sources = json.optJSONArray("url") ?: return@withPermit
                        for (i in 0 until minOf(sources.length(), 12)) {
                            val entry = sources.optJSONObject(i) ?: continue
                            val hls = MOOSACineStreamVidzeePolicy.decryptHls(
                                entry.optString("link")
                            ) ?: continue
                            if (!links.add(hls)) continue
                            val tag = entry.optString("name").take(20)
                            callback(newExtractorLink(
                                MOOSACineStreamVidzeePolicy.SOURCE_NAME,
                                "VidZee [$server] " + tag,
                                hls,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = acceptedHeaders["Referer"].orEmpty()
                                this.headers = acceptedHeaders.toMap()
                                this.quality = Qualities.Unknown.value
                            })
                        }
                    }
                }
            }.awaitAll()
            true
        }
        links.isNotEmpty() && fetched == true
    }
}
