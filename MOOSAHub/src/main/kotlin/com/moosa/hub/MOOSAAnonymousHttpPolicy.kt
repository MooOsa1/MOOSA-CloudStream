package com.moosa.hub

import java.net.URI
import java.util.Locale

/** Public, no-credential Stremio HTTP source checks; never open P2P links. */
internal object MOOSAAnonymousHttpPolicy {
    private val hostPattern = Regex("""[a-z0-9-]+(?:\.[a-z0-9-]+)+""")
    private val prohibited = Regex("""(?i)\b(torrent|torrentio|p2p|debrid|magnet|premium|login|subscription)\b""")
    private val dimensions = Regex("""(?i)\bRESOLUTION=(\d+)x(\d+)(?:,|$)""")

    fun streamApi(base: String, imdbId: String?, season: Int?, episode: Int?, isTv: Boolean): String? {
        if (!imdbId.orEmpty().matches(Regex("""tt[0-9]{7,10}"""))) return null
        if (isTv && (season == null || episode == null ||
                    season !in 1..100 || episode !in 1..1000)) return null
        val tail = if (isTv) "$imdbId:$season:$episode" else imdbId
        return "$base/stream/" + (if (isTv) "series" else "movie") + "/$tail.json"
    }

    fun safeMediaUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 3000) return null
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
            uri.fragment != null || !hostPattern.matches(host) ||
            host.endsWith(".local") || host.endsWith(".internal") ||
            host.endsWith(".localhost") || host.endsWith(".test") ||
            host.contains("..")) return null
        if (uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".torrent")) return null
        return uri.toASCIIString()
    }

    fun acceptedCandidate(title: String?, name: String?, infoHash: String?, fileIdx: Int?): Boolean {
        if (!infoHash.isNullOrBlank() || fileIdx != null) return false
        return !prohibited.containsMatchIn(title.orEmpty() + " " + name.orEmpty())
    }

    /** The HLS manifest can attest its declared renditions, not 4K decoding or stability. */
    fun hlsHeight(body: String): Int? {
        if (!body.trimStart().startsWith("#EXTM3U")) return null
        val values = body.lineSequence().filter { it.startsWith("#EXT-X-STREAM-INF:", true) }
            .mapNotNull { line ->
                dimensions.find(line)?.let {
                    val w = it.groupValues[1].toIntOrNull()
                    val h = it.groupValues[2].toIntOrNull()
                    if (w != null && h != null && w in 1200..4096 && h in 720..2160) h else null
                }
            }.toList()
        return values.maxOrNull()
    }

    fun videoHeight(width: Int, height: Int): Int? = when {
        width >= 3800 && height >= 1500 -> 2160
        width >= 1700 && height >= 800 -> 1080
        width >= 1200 && height >= 650 -> 720
        else -> null
    }

    fun sourceName(name: String?): String = name.orEmpty()
        .replace(Regex("""[^\p{L}\p{N} ._-]"""), " ")
        .replace(Regex("""(?i)\b(2160p|1080p|720p|4k|uhd|hdr|download|verified)\b"""), " ")
        .replace(Regex("""\s+"""), " ").trim().take(26).ifBlank { "HTTP" }
}
