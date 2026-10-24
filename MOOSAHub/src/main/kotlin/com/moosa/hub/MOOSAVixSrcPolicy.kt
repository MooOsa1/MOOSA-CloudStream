package com.moosa.hub

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Strict TMDB-ID HLS resolver policy: no title guessing or quality invention. */
internal object MOOSAVixSrcPolicy {
    const val SOURCE_NAME = "MOOSA VixSrc • Adaptive FHD"
    const val BASE = "https://vixsrc.to"

    data class TokenData(val token: String, val expires: Long, val playlist: String)
    data class Variant(
        val url: String,
        val height: Int,
        val declaredVideoCodec: Boolean = false
    )

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

    fun preferredAudioLanguage(originalLanguage: String?): String =
        originalLanguage?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"

    fun masterUrl(data: TokenData, language: String = "en"): String {
        val separator = if ('?' in data.playlist) '&' else '?'
        return data.playlist + separator + "token=" + URLEncoder.encode(data.token, "UTF-8") +
            "&expires=" + data.expires + "&h=1&lang=" +
            URLEncoder.encode(preferredAudioLanguage(language), "UTF-8")
    }

    /** A video with the wrong dub is not a valid match for the requested title. */
    fun hasOriginalAudio(master: String, originalLanguage: String): Boolean {
        val requested = preferredAudioLanguage(originalLanguage)
        val codes = mapOf(
            "en" to "eng", "hi" to "hin", "ar" to "ara", "es" to "spa",
            "fr" to "fra", "de" to "deu", "it" to "ita", "ja" to "jpn",
            "ko" to "kor", "zh" to "zho", "pt" to "por", "ru" to "rus",
            "ta" to "tam", "te" to "tel", "ml" to "mal", "bn" to "ben"
        )
        val accepted = setOf(requested, codes[requested] ?: requested)
        val audioLines = master.lines().filter {
            it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")
        }
        if (audioLines.isEmpty()) return true // Audio may be muxed into the video track.
        val language = Regex("""(?:^|,)LANGUAGE=["']?([a-z]{2,3})["']?(?:,|$)""", RegexOption.IGNORE_CASE)
        return audioLines.any { line ->
            language.find(line)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                ?.let { it in accepted } == true
        }
    }

    /**
     * Retain multiple genuine HD renditions. The original implementation
     * checked ONLY the highest rendition: a blocked 1080p segment discarded
     * a potentially healthy 720p episode altogether.
     *
     * Reject a declared audio-only or AV1/Dolby-Vision rendition on Fire TV.
     * Missing CODECS is not trusted until the provider probes real H264 TS.
     */
    fun hdVariants(masterUrl: String, playlist: String): List<Variant> {
        if (!playlist.trimStart().startsWith("#EXTM3U")) return emptyList()
        val lines = playlist.lines()
        val resolution = Regex("""(?:^|[:,])RESOLUTION=(\d+)x(\d+)(?:,|$)""")
        return lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true))
                return@mapIndexedNotNull null
            val height = resolution.find(line)?.groupValues?.get(2)?.toIntOrNull()
                ?: return@mapIndexedNotNull null
            // This is the adaptive FHD source. 4K has a separate provider.
            if (height !in 720..1080) return@mapIndexedNotNull null
            val codecValue = Regex("""CODECS="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)
            val tokens = codecValue?.lowercase()?.split(',')?.map(String::trim).orEmpty()
            val video = tokens.any { token ->
                token.startsWith("avc1") || token.startsWith("avc3") ||
                    token.startsWith("hvc1") || token.startsWith("hev1")
            }
            if (codecValue != null && !video) return@mapIndexedNotNull null
            if (tokens.any { it.startsWith("av01") || it.startsWith("dvhe") ||
                    it.startsWith("dvh1") }) return@mapIndexedNotNull null
            val relative = lines.drop(index + 1).firstOrNull { value ->
                value.isNotBlank() && !value.startsWith('#')
            }?.trim() ?: return@mapIndexedNotNull null
            val resolved = runCatching {
                URI(masterUrl).resolve(relative).toASCIIString()
            }.getOrNull()?.let(::safePlaylist) ?: return@mapIndexedNotNull null
            Variant(resolved, height, video)
        }.distinctBy { it.url }.sortedByDescending { it.height }.take(3)
    }

    fun highestVariant(masterUrl: String, playlist: String): Variant? =
        hdVariants(masterUrl, playlist).firstOrNull()

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
