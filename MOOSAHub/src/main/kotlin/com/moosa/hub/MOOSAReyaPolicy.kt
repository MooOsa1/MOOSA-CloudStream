package com.moosa.hub

import java.net.URI
import java.util.Locale

/**
 * Exact-ID Reya multi-source HLS. Every displayed resolution is verified
 * from the *real* ISO-BMFF video track of an HLS rendition init segment.
 * HLS variant labels and Content-Type alone are never trusted as proof.
 */
internal object MOOSAReyaPolicy {
    const val SOURCE_NAME = "MOOSA Reya • Direct UHD"
    const val BASE = "https://vidnst.reyamika.eu.cc"

    data class Variant(val url: String, val width: Int, val height: Int)
    data class TrackInfo(
        val videoWidth: Int, val videoHeight: Int, val videoCodec: String,
        val audioPresent: Boolean, val languages: Set<String>?
    )

    fun apiUrl(id: Int?, tv: Boolean, season: Int?, episode: Int?): String? {
        if (id == null || id !in 1..999_999_999) return null
        return if (tv) {
            if (season == null || episode == null ||
                season !in 1..99 || episode !in 1..1000
            ) null else "$BASE/api/stream?tmdbId=$id&type=tv&season=$season&episode=$episode"
        } else "$BASE/api/stream?tmdbId=$id&type=movie"
    }

    /** Bound untrusted URLs to Reya's observed, independent HLS CDN families. */
    fun allowedUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        val host = u.host?.lowercase(Locale.ROOT) ?: return@runCatching false
        u.scheme == "https" && u.port == -1 && u.userInfo == null &&
            u.fragment == null && (
                host == "vidnst.reyamika.eu.cc" ||
                host == "pwcloud.animanga.fun" ||
                host == "akcloud.animanga.fun" ||
                host == "s2.streamflixserver.site" ||
                host == "noisy-king-5904.streamed-7.workers.dev"
            )
    }.getOrDefault(false)

    /** Child URL must stay on the verified media host, not point into a LAN. */
    fun childUrl(parent: String, path: String): String? = runCatching {
        val p = URI(parent)
        val child = p.resolve(path.trim())
        if (allowedUrl(parent) && child.scheme == "https" &&
            child.host.equals(p.host, true) && child.port == -1 &&
            child.userInfo == null && child.fragment == null
        ) child.toASCIIString() else null
    }.getOrNull()

    fun variants(master: String, manifest: String): List<Variant> {
        if (!manifest.trimStart().startsWith("#EXTM3U")) return emptyList()
        val lines = manifest.lines().map(String::trim)
        val resolution = Regex("""(?:^|[:,])\s*RESOLUTION=(\d+)x(\d+)(?:,|$)""",
            RegexOption.IGNORE_CASE)
        return lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:", true)) return@mapIndexedNotNull null
            val m = resolution.find(line) ?: return@mapIndexedNotNull null
            val width = m.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
            val height = m.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
            if (height !in 720..2160 || width !in 1200..4096) return@mapIndexedNotNull null
            val codecMatch = Regex("""CODECS="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            if (codecMatch != null &&
                (codecMatch.contains("av01") || codecMatch.contains("dvhe") ||
                    codecMatch.contains("dvh1") ||
                    !Regex("""(?:avc1|avc3|hvc1|hev1)""").containsMatchIn(codecMatch))
            ) return@mapIndexedNotNull null
            val child = lines.getOrNull(i + 1)
                ?.takeUnless { it.startsWith("#") || it.isBlank() }
                ?.let { childUrl(master, it) } ?: return@mapIndexedNotNull null
            Variant(child, width, height)
        }.distinctBy { it.url }.sortedByDescending { it.height }.take(4)
    }

    data class MediaParts(val init: String, val segment: String)

    fun mediaParts(mediaUrl: String, playlist: String): MediaParts? {
        if (!playlist.trimStart().startsWith("#EXTM3U")) return null
        if (playlist.lines().any { it.startsWith("#EXT-X-KEY:") &&
                !it.contains("METHOD=NONE", ignoreCase = true)
            }) return null
        val mapLine = playlist.lines().firstOrNull {
            it.trimStart().startsWith("#EXT-X-MAP:")
        } ?: return null
        val init = Regex("""URI="([^"]+)"""").find(mapLine)?.groupValues?.get(1)
            ?.let { childUrl(mediaUrl, it) } ?: return null
        val segment = playlist.lines().map(String::trim)
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.let { childUrl(mediaUrl, it) } ?: return null
        return MediaParts(init, segment)
    }

    private data class Box(val type: String, val start: Int, val end: Int)
    private fun boxes(data: ByteArray, from: Int, to: Int): List<Box> {
        val result = mutableListOf<Box>()
        var i = from
        val end = minOf(data.size, to)
        while (i + 8 <= end) {
            val size = (0..3).fold(0L) { n, offset ->
                (n shl 8) or (data[i + offset].toLong() and 255L)
            }
            if (size !in 8..(end - i).toLong()) break
            val type = String(data, i + 4, 4, Charsets.ISO_8859_1)
            val next = i + size.toInt()
            result += Box(type, i + 8, next)
            i = next
        }
        return result
    }

    /**
     * Reads real MP4 moov/trak/tkhd and stsd. 4K HLS quality is invalid if
     * the actual video track is 1080p, unsupported AV1 or Dolby Vision.
     */
    fun trackInfo(data: ByteArray): TrackInfo? {
        val moov = boxes(data, 0, data.size).firstOrNull { it.type == "moov" }
            ?: return null
        val tracks = boxes(data, moov.start, moov.end).filter { it.type == "trak" }
        var width = 0
        var height = 0
        var codec = ""
        var sound = false
        val languages = mutableSetOf<String>()
        tracks.forEach { track ->
            val children = boxes(data, track.start, track.end)
            val media = children.firstOrNull { it.type == "mdia" }
                ?: return@forEach
            val mediaChildren = boxes(data, media.start, media.end)
            val handler = mediaChildren.firstOrNull { it.type == "hdlr" }
                ?: return@forEach
            if (handler.start + 12 > handler.end) return@forEach
            val kind = String(data, handler.start + 8, 4, Charsets.US_ASCII)
            if (kind == "soun") {
                sound = true
                val mdhd = mediaChildren.firstOrNull { it.type == "mdhd" }
                if (mdhd != null && mdhd.start < mdhd.end) {
                    val version = data[mdhd.start].toInt() and 255
                    val position = mdhd.start + (if (version == 1) 32 else 20)
                    if (position + 2 <= mdhd.end) {
                        val number = ((data[position].toInt() and 255) shl 8) or
                            (data[position + 1].toInt() and 255)
                        val lang = listOf(10, 5, 0).map { shift ->
                            ((number shr shift and 31) + 96).toChar()
                        }.joinToString("")
                        if (lang.all { it in 'a'..'z' }) {
                            MOOSAAudioTrackPolicy.normalize(lang)?.let(languages::add)
                        }
                    }
                }
            } else if (kind == "vide") {
                val tkhd = children.firstOrNull { it.type == "tkhd" }
                if (tkhd != null && tkhd.end - tkhd.start >= 8) {
                    fun fix16(start: Int): Int {
                        var n = 0
                        for (k in 0..3) n = (n shl 8) or (data[start + k].toInt() and 255)
                        return n ushr 16
                    }
                    width = fix16(tkhd.end - 8)
                    height = fix16(tkhd.end - 4)
                }
                val minf = mediaChildren.firstOrNull { it.type == "minf" }
                val stbl = minf?.let { boxes(data, it.start, it.end) }
                    ?.firstOrNull { it.type == "stbl" }
                val stsd = stbl?.let { boxes(data, it.start, it.end) }
                    ?.firstOrNull { it.type == "stsd" }
                if (stsd != null && stsd.start + 16 <= stsd.end) {
                    codec = String(data, stsd.start + 12, 4, Charsets.US_ASCII)
                        .lowercase(Locale.ROOT)
                }
            }
        }
        if (width < 1280 || height < 720 || !sound || codec.isEmpty()) return null
        if (codec !in setOf("hvc1", "hev1", "avc1", "avc3")) return null
        if ((width >= 3800 || height > 1080) &&
            codec !in setOf("hvc1", "hev1")
        ) return null // Mantis Fire Stick 4K: 4K HEVC required.
        return TrackInfo(width, height, codec, true, languages.takeIf { it.isNotEmpty() })
    }

    fun fragmentLooksPlayable(data: ByteArray): Boolean {
        if (data.size < 96 || data[0] == '<'.code.toByte()) return false
        val head = data.copyOf(minOf(data.size, 10000)).toString(Charsets.ISO_8859_1)
        return head.contains("moof") && head.contains("mdat")
    }
}
