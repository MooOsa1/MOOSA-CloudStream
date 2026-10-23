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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native Vidrock HTTP/HLS invoker, based on the documented CineStream protocol.
 * Attribution: SaurabhKaperwan/CSX, CineStreamExtractors.invokeVidrock and
 * CineStreamUtils.decryptVidrockUrl; upstream GPLv3-or-later.
 *
 * Independent of installed CineStream/StreamPlay CS3 files. Exact TMDB ID and
 * season/episode addressing (no title search). Torrent-free and no user keys.
 * Server CDN origin differs from VaPlayer; this does not guarantee uptime.
 */
internal object MOOSACineStreamVidrockPolicy {
    const val SOURCE_NAME = "MOOSA CineStream • Vidrock"
    const val BASE = "https://vidrock.ru"
    private const val KEY_HEX =
        "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    fun requestUrl(tmdb: Int?, isTv: Boolean, season: Int?, episode: Int?): String? {
        if (tmdb == null || tmdb !in 1..999999999) return null
        if (!isTv && season == null && episode == null) {
            return "$BASE/api/movie/$tmdb/"
        }
        if (!isTv || season == null || episode == null ||
            season !in 1..200 || episode !in 1..3000) return null
        return "$BASE/api/tv/" + tmdb + "_" + season + "_" + episode + "/"
    }

    fun hlsUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val clean = value.trim()
        if (!clean.startsWith("https://", true)) return null
        val uri = runCatching { java.net.URI(clean) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) ||
            uri.host.isNullOrBlank() || uri.userInfo != null) return null
        if (!uri.path.orEmpty().lowercase(java.util.Locale.ROOT).endsWith(".m3u8"))
            return null
        return clean
    }

    // Small pure-Kotlin strict decoder, compatible with Fire OS 6 / Android 7
    // where java.util.Base64 is not part of the public API.
    private fun fromBase64Url(encoded: String): ByteArray? {
        if (encoded.length !in 38..8192) return null
        val raw = encoded.trimEnd('=')
        if (encoded.drop(raw.length).length > 2 || raw.length % 4 == 1) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val result = ByteArray(raw.length * 3 / 4)
        var acc = 0
        var bits = 0
        var pos = 0
        for (char in raw) {
            val index = alphabet.indexOf(char)
            if (index == -1) return null
            acc = (acc shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                result[pos++] = ((acc ushr bits) and 255).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        if (acc != 0) return null
        return result.copyOf(pos)
    }

    private fun key(): ByteArray =
        KEY_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun decryptUrl(ciphertext: String?): String? {
        if (ciphertext.isNullOrBlank()) return null
        val bytes = fromBase64Url(ciphertext) ?: return null
        // 12 bytes AES-GCM IV + 16 byte authentication tag minimum.
        if (bytes.size <= 28) return null
        return runCatching {
            val nonce = bytes.copyOfRange(0, 12)
            val data = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"),
                GCMParameterSpec(128, nonce)
            )
            val plaintext = String(cipher.doFinal(data), Charsets.UTF_8)
            hlsUrl(plaintext)
        }.getOrNull()
    }
}

internal class MOOSACineStreamVidrockProvider : MainAPI() {
    override var name = MOOSACineStreamVidrockPolicy.SOURCE_NAME
    override var mainUrl = MOOSACineStreamVidrockPolicy.BASE
    override var lang = "en"
    override val hasMainPage = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = tryParseJson<MOOSAHubProvider.HubPlaybackRequest>(data)
            ?: return false
        val address = MOOSACineStreamVidrockPolicy.requestUrl(
            request.tmdbId, request.isTv, request.season, request.episode
        ) ?: return false
        val headers = mapOf(
            "Origin" to MOOSACineStreamVidrockPolicy.BASE,
            "Referer" to MOOSACineStreamVidrockPolicy.BASE + "/",
            "User-Agent" to "Mozilla/5.0"
        )
        MOOSANativeSourceDiagnostics.mark(name, "Requesting S02E01 • Vidrock API")
        val response = withTimeoutOrNull(8000L) {
            runCatching { app.get(address, headers = headers) }.getOrNull()
        } ?: run {
            MOOSANativeSourceDiagnostics.mark(name, "API unreachable / 8s timeout")
            return false
        }
        if (!response.isSuccessful) {
            MOOSANativeSourceDiagnostics.mark(name, "API HTTP " + response.code)
            return false
        }
        val document = runCatching { JSONObject(response.text) }.getOrNull()
            ?: run {
                MOOSANativeSourceDiagnostics.mark(name, "API returned invalid JSON")
                return false
            }
        var emitted = false
        var count = 0
        var encryptedCount = 0
        val names = document.keys()
        while (names.hasNext() && count < 12) {
            val server = names.next()
            val encrypted = document.optJSONObject(server)
                ?.optString("url").orEmpty()
            if (encrypted.isNotBlank()) encryptedCount++
            val hls = MOOSACineStreamVidrockPolicy.decryptUrl(encrypted) ?: continue
            count++
            callback(newExtractorLink(
                MOOSACineStreamVidrockPolicy.SOURCE_NAME,
                "Vidrock • " + server.take(42),
                hls,
                ExtractorLinkType.M3U8
            ) {
                this.referer = MOOSACineStreamVidrockPolicy.BASE + "/"
                this.headers = headers
                // Rely on MOOSA master playlist inspection, not made-up 1080p.
                this.quality = Qualities.Unknown.value
            })
            emitted = true
        }
        MOOSANativeSourceDiagnostics.mark(
            name,
            when {
                emitted -> "HLS found • verifying playback / 720p+"
                encryptedCount > 0 -> "Encrypted links rejected / decrypt failed"
                else -> "No S02E01 streams in Vidrock response"
            }
        )
        return emitted
    }
}
