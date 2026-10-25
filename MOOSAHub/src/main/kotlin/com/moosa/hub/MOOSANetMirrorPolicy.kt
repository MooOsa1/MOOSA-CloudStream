package com.moosa.hub

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** A second exact-ID catalog; a signed MP4 is not a server until its VIDEO is verified. */
internal object MOOSANetMirrorPolicy {
    const val SOURCE_NAME = "MOOSA NetMirror • Direct FHD"
    const val BASE = "https://net27.cc"
    const val MEDIA_REFERER = "https://videodownloader.site/"

    data class Candidate(val url: String, val height: Int, val sizeBytes: Long)

    fun apiUrl(tmdbId: Int?, isTv: Boolean, season: Int?, episode: Int?): String? {
        if (tmdbId == null || tmdbId !in 1..999_999_999) return null
        if (!isTv) return "$BASE/api/embed-tmdb/$tmdbId"
        if (season == null || episode == null || season !in 1..100 || episode !in 1..1000)
            return null
        return "$BASE/api/embed-tmdb/$tmdbId?type=tv&se=$season&ep=$episode"
    }

    private fun canonical(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), "")

    fun validIdentity(body: JSONObject, request: MOOSAHubProvider.HubPlaybackRequest): Boolean {
        if (!body.optBoolean("ok") || body.optInt("tmdbId") != request.tmdbId ||
            canonical(body.optString("title")) != canonical(request.title) ||
            canonical(request.title).isEmpty()
        ) return false
        if (request.year != null && body.optString("year")
                .toIntOrNull()?.let { it != request.year } == true
        ) return false
        if (request.isTv) {
            if (body.optString("type") != "tv" ||
                body.optInt("currentSeason") != request.season ||
                body.optInt("currentEpisode") != request.episode
            ) return false
        } else if (body.optString("type") != "movie") return false
        return true
    }

    fun directCandidates(body: JSONObject): List<Candidate> {
        val items = body.optJSONArray("streams") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val height = item.optInt("resolution")
            if (height !in listOf(1080, 720)) return@mapNotNull null
            val url = item.optString("url")
            val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            val hostname = uri.host?.lowercase(Locale.ROOT).orEmpty()
            if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
                uri.fragment != null ||
                !(hostname == "hakunaymatata.com" || hostname.endsWith(".hakunaymatata.com"))
            ) return@mapNotNull null
            Candidate(uri.toASCIIString(), height, item.optLong("size"))
        }.distinctBy { it.url }.sortedByDescending { it.height }.take(2)
    }

    private data class Mp4Box(val type: String, val data: Int, val end: Int)

    private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Mp4Box> {
        val result = ArrayList<Mp4Box>()
        var at = start
        while (at + 8 <= end) {
            val size = (0..3).fold(0L) { n, i ->
                (n shl 8) or (bytes[at + i].toLong() and 255)
            }
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            // Extended-size and out-of-sample boxes cannot be safely parsed.
            if (size < 8 || size > end - at) break
            val next = at + size.toInt()
            result += Mp4Box(type, at + 8, next)
            at = next
        }
        return result
    }

    /**
     * ISO-BMFF moov/trak/mdia/mdhd has the real audio track language.
     * "und" and a moov outside the bounded prefix are UNKNOWN, not "en".
     * Allows correct filtering of Indian dubs on Korean/other original films.
     */
    fun audioLanguages(prefix: ByteArray): Set<String>? {
        val moov = boxes(prefix, 0, prefix.size)
            .firstOrNull { it.type == "moov" } ?: return null
        val tracks = boxes(prefix, moov.data, moov.end)
            .filter { it.type == "trak" }
        val audio = tracks.mapNotNull { track ->
            val media = boxes(prefix, track.data, track.end)
                .firstOrNull { it.type == "mdia" } ?: return@mapNotNull null
            val mediaBoxes = boxes(prefix, media.data, media.end)
            val handler = mediaBoxes.firstOrNull { it.type == "hdlr" }
                ?: return@mapNotNull null
            if (handler.data + 12 > handler.end ||
                String(prefix, handler.data + 8, 4, Charsets.US_ASCII) != "soun"
            ) return@mapNotNull null
            val mdhd = mediaBoxes.firstOrNull { it.type == "mdhd" }
                ?: return@mapNotNull "und"
            if (mdhd.data >= mdhd.end) return@mapNotNull "und"
            val offset = mdhd.data + (if (prefix[mdhd.data].toInt() == 1) 32 else 20)
            if (offset + 2 > mdhd.end) return@mapNotNull "und"
            val code = ((prefix[offset].toInt() and 255) shl 8) or
                (prefix[offset + 1].toInt() and 255)
            val lang = buildString {
                append(((code shr 10 and 31) + 96).toChar())
                append(((code shr 5 and 31) + 96).toChar())
                append(((code and 31) + 96).toChar())
            }
            // Keep every unlabeled track. Discarding "und" from a Hindi +
            // unknown mux makes the entire source look Hindi-only and hides
            // a potentially correct Korean/Japanese original soundtrack.
            MOOSAAudioTrackPolicy.normalize(lang) ?: "und"
        }.toSet()
        return audio.takeIf { it.isNotEmpty() }
    }

    /** Fast-start MP4 metadata must show a playable video track, not just AAC audio. */
    fun compatibleMp4(prefix: ByteArray): Boolean {
        if (prefix.size < 64 || !String(prefix, 4, 4, Charsets.US_ASCII).equals("ftyp"))
            return false
        val text = String(prefix, Charsets.ISO_8859_1)
        return "vide" in text &&
            ("avc1" in text || "hvc1" in text || "hev1" in text)
    }
}
