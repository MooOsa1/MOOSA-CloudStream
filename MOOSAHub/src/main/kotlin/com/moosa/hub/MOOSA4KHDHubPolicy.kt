package com.moosa.hub

import java.net.URI
import java.util.Locale

/**
 * Strict matching and media evidence for 4KHDHub. The page's "2160p" badge is
 * only a candidate hint; the emitted quality comes from the MKV video track.
 */
internal object MOOSA4KHDHubPolicy {
    const val SOURCE_NAME = "MOOSA 4KHDHub • Direct"
    const val BASE = "https://4khdhub.one"

    private fun canonicalTitle(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), "")

    fun exactCatalogMatch(
        expectedTitle: String,
        expectedYear: Int?,
        foundTitle: String,
        foundYear: Int?
    ): Boolean =
        expectedYear != null && expectedYear == foundYear &&
            canonicalTitle(expectedTitle).isNotBlank() &&
            canonicalTitle(expectedTitle) == canonicalTitle(foundTitle)

    fun isUhdRelease(title: String): Boolean =
        Regex("""(?i)(?:\b2160p\b|\b4k\s+uhd\b)""").containsMatchIn(title)

    /** Fire TV Stick 4K (mantis) cannot decode AV1 or some Dolby Vision profiles. */
    fun isCompatibleRelease(title: String): Boolean =
        !Regex("""(?i)(?:\bav1\b|\bav01\b|\bdovi\b|\bdv\b|\bdolby[ ._-]*vision\b)""")
            .containsMatchIn(title)

    /** Reverses the public page's nested encoding, without executing its ad JS. */
    fun decodeGateway(html: String): String? {
        val payload = Regex("""s\(['\"]o['\"],\s*['\"]([A-Za-z0-9+/=]+)['\"]""")
            .find(html)?.groupValues?.get(1) ?: return null
        val decoded = base64(payload)
            ?.let(::base64)
            ?.let(::rot13)
            ?.let(::base64) ?: return null
        val nested = Regex("""\"o\"\s*:\s*\"([^\"]+)\"""")
            .find(decoded)?.groupValues?.get(1) ?: return null
        return base64(nested)?.let(::safeHubPage)
    }

    private fun rot13(value: String): String = value.map { c ->
        when (c) {
            in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 13) % 26).toChar()
            in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 13) % 26).toChar()
            else -> c
        }
    }.joinToString("")

    private fun base64(value: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = java.io.ByteArrayOutputStream()
        var bits = 0
        var pending = 0
        for (c in value) {
            if (c == '=') break
            if (c.isWhitespace()) continue
            val digit = alphabet.indexOf(c)
            if (digit < 0) return null
            bits = (bits shl 6) or digit
            pending += 6
            if (pending >= 8) {
                pending -= 8
                out.write((bits shr pending) and 255)
                bits = bits and ((1 shl pending) - 1)
            }
        }
        return out.toString("UTF-8")
    }

    fun safeHubPage(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null ||
            uri.host.isNullOrBlank() || uri.port != -1
        ) return null
        val host = uri.host.lowercase(Locale.ROOT)
        if (host !in setOf("hubcloud.ist", "hubdrive.ist")) {
            return null
        }
        return uri.toASCIIString()
    }

    fun safeHostPage(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null ||
            uri.host.isNullOrBlank() || uri.port != -1
        ) return null
        val host = uri.host.lowercase(Locale.ROOT)
        if (!(
                host == "gamerxyt.com" || host.endsWith(".gamerxyt.com") ||
                    host == "hubcloud.ist" || host == "hubdrive.ist"
            )
        ) return null
        return uri.toASCIIString()
    }

    /** Only signed, direct CDN files; reject external HTML/ads/P2P. */
    fun safeDirectFile(raw: String): String? {
        // The file name can contain spaces in the upstream HTML href.
        val encoded = raw.trim()
            .replace(" ", "%20")
            .replace("[", "%5B")
            .replace("]", "%5D")
        val uri = runCatching { URI(encoded) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null ||
            uri.host.isNullOrBlank() || uri.port != -1 ||
            uri.fragment != null
        ) return null
        val host = uri.host.lowercase(Locale.ROOT)
        if (!(
                host.endsWith(".workers.dev") ||
                    host.endsWith(".r2.dev") ||
                    host.endsWith(".cloudflarestorage.com")
            )
        ) return null
        return uri.toASCIIString()
    }

    /** Matroska EBML element header. Children are walked, not byte-grepped. */
    private data class Element(val id: Long, val data: Int, val end: Int)

    private fun element(bytes: ByteArray, at: Int, limit: Int): Element? {
        if (at >= limit) return null
        val firstId = bytes[at].toInt() and 255
        val idWidth = (1..4).firstOrNull { firstId and (1 shl (8 - it)) != 0 }
            ?: return null
        if (at + idWidth >= limit) return null
        var id = 0L
        repeat(idWidth) { i -> id = (id shl 8) or (bytes[at + i].toLong() and 255) }

        val sizeAt = at + idWidth
        val firstSize = bytes[sizeAt].toInt() and 255
        val sizeWidth = (1..8).firstOrNull { firstSize and (1 shl (8 - it)) != 0 }
            ?: return null
        if (sizeAt + sizeWidth > limit) return null
        val mask = if (sizeWidth == 8) 0 else (1 shl (8 - sizeWidth)) - 1
        var size = (firstSize and mask).toLong()
        repeat(sizeWidth - 1) { i ->
            size = (size shl 8) or (bytes[sizeAt + 1 + i].toLong() and 255)
        }
        val data = sizeAt + sizeWidth
        val unknownSize = size == (1L shl (7 * sizeWidth)) - 1
        val end = if (unknownSize || size > limit - data) limit
            else data + size.toInt()
        if (end < data) return null
        return Element(id, data, end)
    }

    private fun findElements(
        bytes: ByteArray, from: Int, until: Int, id: Long
    ): List<Element> {
        val matches = mutableListOf<Element>()
        var cursor = from
        while (cursor < until) {
            val item = element(bytes, cursor, until) ?: break
            if (item.id == id) matches += item
            if (item.end <= cursor) break
            cursor = item.end
        }
        return matches
    }

    private fun unsigned(bytes: ByteArray, element: Element): Int? {
        val size = element.end - element.data
        if (size !in 1..4) return null
        var result = 0
        for (i in element.data until element.end) {
            result = (result shl 8) or (bytes[i].toInt() and 255)
        }
        return result
    }

    data class CompatibleVideo(val width: Int, val height: Int, val codecId: String)

    /** Only compatible real Matroska video tracks, never audio-only or AV1. */
    fun compatibleVideo(prefix: ByteArray): CompatibleVideo? {
        if (prefix.size < 24 || !prefix.copyOfRange(0, 4)
                .contentEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
        ) return null
        val segment = findElements(prefix, 0, prefix.size, 0x18538067L)
            .firstOrNull() ?: return null
        val tracks = findElements(prefix, segment.data, segment.end, 0x1654AE6BL)
            .firstOrNull() ?: return null
        val entries = findElements(prefix, tracks.data, tracks.end, 0xAEL)
        for (entry in entries) {
            val type = findElements(prefix, entry.data, entry.end, 0x83L)
                .firstOrNull()?.let { unsigned(prefix, it) }
            if (type != 1) continue
            val codec = findElements(prefix, entry.data, entry.end, 0x86L)
                .firstOrNull()?.let { value ->
                    String(prefix, value.data, value.end - value.data, Charsets.US_ASCII)
                } ?: continue
            if (codec !in setOf("V_MPEGH/ISO/HEVC", "V_MPEG4/ISO/AVC")) continue
            val video = findElements(prefix, entry.data, entry.end, 0xE0L)
                .firstOrNull() ?: continue
            val width = findElements(prefix, video.data, video.end, 0xB0L)
                .firstOrNull()?.let { unsigned(prefix, it) }
            val height = findElements(prefix, video.data, video.end, 0xBAL)
                .firstOrNull()?.let { unsigned(prefix, it) }
            if (width != null && height != null &&
                width in 640..8192 && height in 360..4320
            ) return CompatibleVideo(width, height, codec)
        }
        return null
    }

    /** Convenience accessor for tests and real encoded resolution labels. */
    fun videoDimensions(prefix: ByteArray): Pair<Int, Int>? =
        compatibleVideo(prefix)?.let { it.width to it.height }
}
