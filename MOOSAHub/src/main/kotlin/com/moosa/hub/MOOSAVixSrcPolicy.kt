package com.moosa.hub

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Strict TMDB-ID HLS resolver policy: no title guessing or quality invention. */
internal object MOOSAVixSrcPolicy {
    const val SOURCE_NAME = "MOOSA VixSrc • Adaptive FHD"
    const val BASE = "https://vixsrc.to"

    data class TokenData(val token: String, val expires: Long, val playlist: String)
    data class Variant(val url: String, val height: Int)

    fun apiUrl(tmdbId: Int?, isTv: Boolean, season: Int?, episode: Int?): String? {
        if (tmdbId == null || tmdbId !in 1..999_999_999) return null
        if (!isTv) return "$BASE/api/movie/$tmdbId"
        if (season == null || episode == null || season !in 1..100 || episode !in 1..1000)
            return null
        return "$BASE/api/tv/$tmdbId/$season/$episode"
    }

    fun safeEmbed(raw: String): String? {
        if (!raw.startsWith("/embed/") || raw.startsWith("//") ||
            raw.contains("\\") || raw.contains("..") || raw.contains("#")
        ) return null
        return runCatching { URI(BASE + raw).toASCIIString() }.getOrNull()
    }

    fun safePlaylist(raw: String): String? {
        // Embed pages have shipped both JS \/ and double-escaped \\/ forms.
        val value = raw.trim().replace("\\\\/", "/").replace("\\/", "/")
        val absolute = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$BASE$value"
            else -> value
        }
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
            uri.fragment != null || uri.host?.lowercase(Locale.ROOT) != "vixsrc.to"
        ) return null
        return uri.toASCIIString()
    }

    fun tokenFromHtml(html: String): TokenData? {
        fun field(key: String): String? = Regex(
            """["']?${Regex.escape(key)}["']\s*:\s*["']([^"']+)["']"""
        ).find(html)?.groupValues?.get(1)

        val token = field("token")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,256}")) }
            ?: return null
        val expires = field("expires")?.toLongOrNull() ?: return null
        val playlist = field("url")?.let(::safePlaylist) ?: return null
        if (expires <= System.currentTimeMillis() / 1000 + 60) return null
        return TokenData(token, expires, playlist)
    }

    fun masterUrl(data: TokenData): String {
        val separator = if ('?' in data.playlist) '&' else '?'
        return data.playlist + separator + "token=" + URLEncoder.encode(data.token, "UTF-8") +
            "&expires=" + data.expires + "&h=1"
    }

    fun highestVariant(masterUrl: String, playlist: String): Variant? {
        if (!playlist.trimStart().startsWith("#EXTM3U")) return null
        val lines = playlist.lines()
        val regex = Regex("""(?:^|,)RESOLUTION=(\d+)x(\d+)(?:,|$)""")
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@mapIndexedNotNull null
            val height = regex.find(line)?.groupValues?.get(2)?.toIntOrNull()
                ?: return@mapIndexedNotNull null
            if (height !in 720..2160) return@mapIndexedNotNull null
            val relative = lines.drop(index + 1).firstOrNull { value ->
                value.isNotBlank() && !value.startsWith('#')
            }?.trim() ?: return@mapIndexedNotNull null
            val resolved = runCatching {
                URI(masterUrl).resolve(relative).toASCIIString()
            }.getOrNull()?.let(::safePlaylist) ?: return@mapIndexedNotNull null
            Variant(resolved, height)
        }.maxByOrNull { it.height }
    }

    fun firstSegment(mediaUrl: String, playlist: String): String? {
        if (!playlist.trimStart().startsWith("#EXTM3U") ||
            !playlist.contains("#EXTINF:")
        ) return null
        val line = playlist.lines().firstOrNull { line ->
            line.isNotBlank() && !line.startsWith('#')
        } ?: return null
        return runCatching { URI(mediaUrl).resolve(line.trim()).toASCIIString() }
            .getOrNull()?.let(::safePlaylist)
    }
}
