package com.moosa.hub

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Exact TMDB-ID -> HTTPS HLS. A master playlist's 4K badge alone is not
 * evidence: confirm fMP4 initialization tracks and a real fragmented segment.
 */
internal object MOOSAReyaPolicy {
    const val NAME = "MOOSA Reya • Verified adaptive streams"
    const val BASE = "https://vidnst.reyamika.eu.cc"

    data class Variant(val url: String, val width: Int, val height: Int)
    data class InitVideo(val width: Int, val height: Int, val codec: String,
                         val audioPresent: Boolean, val audioLanguages: Set<String>?)

    fun apiUrl(tmdb: Int?, tv: Boolean, season: Int?, episode: Int?): String? {
        if (tmdb == null || tmdb !in 1..999_999_999) return null
        val suffix = if (!tv) "&type=movie" else {
            if (season == null || episode == null || season !in 1..100 ||
                episode !in 1..1000) return null
            "&type=tv&season=" + season + "&episode=" + episode
        }
        return BASE + "/api/stream?tmdbId=" + tmdb + suffix
    }

    fun identity(body: JSONObject, tmdb: Int?, tv: Boolean,
                 season: Int?, episode: Int?): Boolean {
        if (tmdb == null || body.optInt("tmdbId", -1) != tmdb) return false
        val type = body.optString("mediaType").lowercase(Locale.ROOT)
        if (tv) return type == "tv" &&
            body.optInt("season", -1) == season && body.optInt("episode", -1) == episode
        return type == "movie"
    }

    // Constrain both playlist and fragment hops to observed public video CDNs.
    // Never fetch private IPs, arbitrary redirect URLs, or javascript: endpoints.
    fun safeMedia(raw: String, relativeTo: String? = null): String? {
        val value = runCatching {
            if (relativeTo == null) URI(raw) else URI(relativeTo).resolve(raw)
        }.getOrNull() ?: return null
        val host = value.host?.lowercase(Locale.ROOT) ?: return null
        if (value.scheme != "https" || value.userInfo != null ||
            value.port != -1 || value.fragment != null) return null
        val permitted = host in setOf(
            "vidnst.reyamika.eu.cc", "pwcloud.animanga.fun",
            "akcloud.animanga.fun", "s2.streamflixserver.site",
            "noisy-king-5904.streamed-7.workers.dev",
            "sacdn.hakunaymatata.com"
        )
        return value.toASCIIString().takeIf { permitted }
    }

    /**
     * Reya API returns *episode-matched* WEBVTT subtitles alongside videos.
     * v146 ignored this array, so playback could use a mismatched external
     * WEB-DL/BluRay subtitle even though the matching source's own track exists.
     * Only the original CDN URL, exact episode path and Arabic/English tracks
     * are accepted. Caller emits tracks only if an actual video was verified.
     */
    data class Subtitle(val language: String, val url: String, val source: String)

    fun subtitles(
        body: JSONObject, isTv: Boolean, season: Int?, episode: Int?
    ): List<Subtitle> {
        val rows = body.optJSONArray("subtitles") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val language = when (item.optString("label").trim().lowercase(Locale.ROOT)) {
                "arabic" -> "Arabic"
                "english" -> "English"
                else -> return@mapNotNull null
            }
            val raw = item.optString("url")
            val uri = runCatching { URI(raw) }.getOrNull()
                ?: return@mapNotNull null
            val path = uri.path ?: return@mapNotNull null
            if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) != "cache.vdrk.site" ||
                uri.userInfo != null || uri.port != -1 || uri.fragment != null ||
                uri.query != null || path.contains("..") ||
                !path.endsWith("/$language.vtt", true)) return@mapNotNull null
            if (isTv) {
                if (season == null || episode == null ||
                    !path.contains("/$season/$episode/")) return@mapNotNull null
            }
            val source = item.optString("source").trim().lowercase(Locale.ROOT)
            if (!source.matches(Regex("""[a-z0-9_-]{2,32}"""))) return@mapNotNull null
            Subtitle(language, uri.toASCIIString(), source)
        }.distinctBy { "${it.source}|${it.language}" }.take(8)
    }

    /** Measure a source's actual VTT timeline without guessing offsets. */
    fun finalCueSeconds(vtt: String): Double? {
        if (!vtt.trimStart('\uFEFF', '\n', '\r', ' ').startsWith("WEBVTT"))
            return null
        val cue = Regex(
            """(?m)^\s*(?:\d{2}:)?\d{2}:\d{2}\.\d{3}\s*-->\s*((?:\d{2}:)?\d{2}:\d{2}\.\d{3})"""
        )
        val all = cue.findAll(vtt).mapNotNull { m ->
            val pieces = m.groupValues[1].split(":").map { it.toDoubleOrNull() }
            if (pieces.any { it == null }) null else {
                val x = pieces.map { it!! }
                if (x.size == 3) x[0] * 3600 + x[1] * 60 + x[2]
                else x[0] * 60 + x[1]
            }
        }.toList()
        return all.takeIf { it.size >= 20 }?.maxOrNull()
            ?.takeIf { it >= 600.0 && it <= 28_800.0 }
    }

    /** Use full VIDEO-on-demand HLS rather than headers or title's guessed runtime. */
    fun playlistSeconds(media: String): Double? {
        if (!media.trimStart().startsWith("#EXTM3U") ||
            !media.lines().any { it.trim() == "#EXT-X-ENDLIST" }) return null
        val lengths = Regex("""(?m)^#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)""")
            .findAll(media).mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .toList()
        if (lengths.size < 30 || lengths.any { it <= 0 || it > 35 }) return null
        return lengths.sum().takeIf { it in 600.0..28_800.0 }
    }

    /**
     * Wrong-cut CDN mirrors have been seen presenting 35min of S02E05 while
     * the same episode's matched VTT runs to 45min. No constant subtitle
     * offset can repair a missing ~10min of video. Fail closed on mismatch.
     * Small credits gaps are allowed, including longer movie end credits.
     */
    fun runtimeCompatible(videoSeconds: Double?, subtitleLastCueSeconds: Double?): Boolean {
        if (subtitleLastCueSeconds == null) return true
        val length = videoSeconds ?: return false
        val extraCreditsAllowance = maxOf(300.0, length * 0.10)
        return length >= subtitleLastCueSeconds - 75.0 &&
            length <= subtitleLastCueSeconds + extraCreditsAllowance
    }

    fun variants(masterUrl: String, manifest: String): List<Variant> {
        if (!manifest.trimStart().startsWith("#EXTM3U")) return emptyList()
        val resolution = Regex("""(?i)\bRESOLUTION=(\d+)x(\d+)""")
        val lines = manifest.lines()
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:", true)) return@mapIndexedNotNull null
            val match = resolution.find(line) ?: return@mapIndexedNotNull null
            val width = match.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
            val height = match.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
            if (height !in 720..2160 || width !in 1200..4096) return@mapIndexedNotNull null
            val codecTag = Regex("""(?i)\bCODECS="([^"]+)"""")
                .find(line)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            if (codecTag != null &&
                (codecTag.contains("av01") || codecTag.contains("dvhe") ||
                    codecTag.contains("dvh1") || (codecTag.contains("mp4a") &&
                        !codecTag.contains("avc") && !codecTag.contains("hev") &&
                        !codecTag.contains("hvc")))) return@mapIndexedNotNull null
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() }
                ?.trim()?.takeIf { !it.startsWith("#") } ?: return@mapIndexedNotNull null
            val url = safeMedia(next, masterUrl) ?: return@mapIndexedNotNull null
            Variant(url, width, height)
        }.distinctBy { it.url }.sortedByDescending { it.height }
    }

    data class Media(val init: String, val segment: String)
    fun media(mediaUrl: String, manifest: String): Media? {
        if (!manifest.trimStart().startsWith("#EXTM3U") ||
            !manifest.contains("#EXTINF:") || manifest.contains("#EXT-X-KEY:"))
            return null
        val rawInit = Regex("""(?m)^#EXT-X-MAP:[^\r\n]*URI="([^"]+)"""")
            .find(manifest)?.groupValues?.get(1) ?: return null
        val first = manifest.lines().firstOrNull {
            it.isNotBlank() && !it.startsWith("#")
        }?.trim() ?: return null
        val init = safeMedia(rawInit, mediaUrl) ?: return null
        val segment = safeMedia(first, mediaUrl) ?: return null
        return Media(init, segment)
    }

    private data class Box(val name: String, val data: Int, val end: Int)
    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var at = start
        while (at >= 0 && at + 8 <= end && at + 8 <= bytes.size) {
            val n = ((bytes[at].toLong() and 255) shl 24) or
                ((bytes[at + 1].toLong() and 255) shl 16) or
                ((bytes[at + 2].toLong() and 255) shl 8) or
                (bytes[at + 3].toLong() and 255)
            if (n < 8 || n > end - at || n > bytes.size - at) break
            val size = n.toInt()
            val name = String(bytes, at + 4, 4, Charsets.US_ASCII)
            result += Box(name, at + 8, at + size)
            at += size
        }
        return result
    }

    private fun int32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 255) shl 24) or
            ((b[i + 1].toLong() and 255) shl 16) or
            ((b[i + 2].toLong() and 255) shl 8) or (b[i + 3].toLong() and 255)

    fun inspectInit(prefix: ByteArray): InitVideo? {
        if (prefix.size < 120) return null
        val top = boxes(prefix, 0, prefix.size)
        if (top.none { it.name == "ftyp" }) return null
        val moov = top.firstOrNull { it.name == "moov" } ?: return null
        val tracks = boxes(prefix, moov.data, moov.end).filter { it.name == "trak" }
        var width = 0
        var height = 0
        var codec = ""
        var audio = false
        for (track in tracks) {
            val children = boxes(prefix, track.data, track.end)
            val tkhd = children.firstOrNull { it.name == "tkhd" }
            val mdia = children.firstOrNull { it.name == "mdia" } ?: continue
            val mediaChildren = boxes(prefix, mdia.data, mdia.end)
            val handler = mediaChildren.firstOrNull { it.name == "hdlr" } ?: continue
            if (handler.data + 12 > handler.end) continue
            val kind = String(prefix, handler.data + 8, 4, Charsets.US_ASCII)
            if (kind == "soun") { audio = true; continue }
            if (kind != "vide" || tkhd == null || tkhd.end - tkhd.data < 8) continue
            val w = (int32(prefix, tkhd.end - 8) ushr 16).toInt()
            val h = (int32(prefix, tkhd.end - 4) ushr 16).toInt()
            if (w !in 1200..4096 || h !in 720..2160) continue
            val raw = String(prefix, track.data, track.end - track.data, Charsets.ISO_8859_1)
            val videoCodec = when {
                "hvc1" in raw || "hev1" in raw -> "HEVC"
                "avc1" in raw || "avc3" in raw -> "AVC"
                else -> ""
            }
            if (videoCodec.isEmpty() || "av01" in raw || "dvhe" in raw ||
                "dvh1" in raw) continue
            width = w
            height = h
            codec = videoCodec
        }
        if (width == 0 || height == 0 || !audio) return null
        // An "und"/unknown language never proves Korean is present.
        val detected = MOOSANetMirrorPolicy.audioLanguages(prefix)
            ?.mapNotNull(MOOSAAudioTrackPolicy::normalize)?.toSet()
            ?.takeIf { it.isNotEmpty() }
        return InitVideo(width, height, codec, true, detected)
    }

    fun isFmp4Fragment(data: ByteArray): Boolean {
        if (data.size < 6000) return false
        val text = String(data, Charsets.ISO_8859_1)
        return String(data, 4, 4, Charsets.US_ASCII) == "moof" &&
            "mdat" in text
    }
}
