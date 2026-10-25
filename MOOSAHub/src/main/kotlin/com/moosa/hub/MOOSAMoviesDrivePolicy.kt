package com.moosa.hub

import java.net.URI
import java.util.Locale

/** Independently checked MoviesDrive -> HubCloud -> Pixeldrain file route. */
internal object MOOSAMoviesDrivePolicy {
    const val NAME = "MOOSA MoviesDrive • Direct"
    const val BASE = "https://new4.moviesdrive.beer"

    fun imdbId(raw: String?): String? =
        raw?.takeIf { Regex("""tt\d{5,12}""").matches(it) }

    fun moviePath(raw: String): String? =
        safeUrl(raw, setOf("new4.moviesdrive.beer"))
            ?.takeIf { it.path.startsWith("/") &&
                !it.path.startsWith("//") && it.path.endsWith("/") &&
                !it.path.contains("..") && it.query == null }
            ?.toASCIIString()

    fun moviePermalink(raw: String): String? {
        if (!raw.startsWith("/") || raw.startsWith("//") ||
            raw.contains("..") || raw.contains('?') || raw.contains('#') ||
            raw.contains('\\')
        ) return null
        return moviePath(BASE + raw)
    }

    fun archive(raw: String): String? =
        safeUrl(raw, setOf("mdrive.lol"))
            ?.takeIf { Regex("""/archive/\d{1,12}/?""").matches(it.path) && it.query == null }
            ?.toASCIIString()

    fun hub(raw: String): String? =
        safeUrl(raw, setOf("hubcloud.ist"))
            ?.takeIf { Regex("""/drive/[A-Za-z0-9]+/?""").matches(it.path) && it.query == null }
            ?.toASCIIString()

    fun terminal(raw: String): String? =
        safeUrl(raw, setOf("gamerxyt.com"))
            ?.takeIf { it.path == "/hubcloud.php" &&
                it.query?.matches(Regex(
                    """host=hubcloud&id=[A-Za-z0-9]+&token=[A-Za-z0-9%+/=_-]+"""
                )) == true }
            ?.toASCIIString()

    /** A Pixeldrain /u/ landing page is HTML, never feed it to the player. */
    fun pixeldrainApi(raw: String): String? {
        val parsed = safeUrl(raw, setOf("pixeldrain.dev")) ?: return null
        if (parsed.query != null) return null
        val match = Regex("""/(?:u|api/file)/([A-Za-z0-9]{3,32})/?""")
            .matchEntire(parsed.path) ?: return null
        return "https://pixeldrain.dev/api/file/${match.groupValues[1]}"
    }

    fun releaseQuality(text: String): Int? = when {
        Regex("""(?i)(?:\b2160p\b|\b4k\b)""").containsMatchIn(text) -> 2160
        Regex("""(?i)\b1080p\b""").containsMatchIn(text) -> 1080
        else -> null
    }

    private fun safeUrl(raw: String, hosts: Set<String>): URI? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        return uri.takeIf {
            it.scheme?.lowercase(Locale.ROOT) == "https" &&
                it.host?.lowercase(Locale.ROOT) in hosts &&
                it.port == -1 && it.userInfo == null && it.fragment == null
        }
    }
}
