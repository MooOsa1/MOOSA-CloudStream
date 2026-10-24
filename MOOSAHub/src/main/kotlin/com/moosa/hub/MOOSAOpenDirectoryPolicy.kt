package com.moosa.hub

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Independent read-only open-directory candidate source. A listed MKV isn't
 * advertised to the player until ranged bytes prove video and original audio.
 */
internal object MOOSAOpenDirectoryPolicy {
    const val NAME = "MOOSA OpenDirectory • verified UHD/FHD"
    const val BASE = "https://a.111477.xyz"
    const val RELAY = "https://p.111477.xyz/bulk?u="

    private fun encode(part: String): String =
        URLEncoder.encode(part, "UTF-8").replace("+", "%20")

    fun directory(title: String, year: Int?, season: Int?): String? {
        val cleaned = title.trim().replace(":", "")
        if (cleaned.isEmpty() || cleaned.length > 140 ||
            cleaned.contains('/') || cleaned.contains('\\') ||
            cleaned.contains("..") || year == null || year !in 1900..2100
        ) return null
        return if (season == null) {
            BASE + "/movies/" + encode(cleaned + " (" + year + ")") + "/"
        } else {
            if (season !in 1..100) return null
            BASE + "/tvs/" + encode(cleaned) + "/Season%20" +
                season.toString().padStart(2,'0') + "/"
        }
    }

    data class Candidate(
        val label: String,
        val fileUrl: String,
        val streamUrl: String,
        val hintedHeight: Int
    )

    fun candidate(directory: String, href: String, fileName: String,
                  season: Int?, episode: Int?): Candidate? {
        val name = fileName.trim()
        if (name.length !in 12..260 ||
            !name.endsWith(".mkv", true) ||
            Regex("""(?i)\b(?:AV1|AV01|DOVI|DOLBY.VISION)\b""").containsMatchIn(name)
        ) return null
        val height = when {
            Regex("""(?i)\b(?:2160p|4k)\b""").containsMatchIn(name) -> 2160
            Regex("""(?i)\b1080p\b""").containsMatchIn(name) -> 1080
            else -> return null
        }
        if (season != null) {
            val ep = episode ?: return null
            val seMatch = Regex("""(?i)\bS0*(\d{1,3})E0*(\d{1,3})\b""")
                .find(name)
            if (seMatch != null) {
                if (seMatch.groupValues[1].toIntOrNull() != season ||
                    seMatch.groupValues[2].toIntOrNull() != ep) return null
            } else {
                val onlyEp = Regex("""(?i)\bE0*(\d{1,3})\b""")
                    .find(name)?.groupValues?.get(1)?.toIntOrNull()
                if (onlyEp != ep) return null
            }
        }
        // Never follow an untrusted off-site URL or arbitrary traversal.
        if (href.isBlank() || href.contains('\\') || href.contains("..") ||
            href.startsWith("//") || href.startsWith("#")
        ) return null
        val uri = runCatching {
            URI(directory).resolve(href.replace(" ", "%20")
                .replace("[", "%5B").replace("]", "%5D"))
        }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.scheme != "https" || host != "a.111477.xyz" ||
            uri.userInfo != null || uri.port != -1 ||
            uri.fragment != null || !uri.path.endsWith(".mkv", true)
        ) return null
        val url = uri.toASCIIString()
        // Existing community extractor accesses the same public file via
        // its range-aware CDN worker. Revalidate from the user's Fire TV.
        return Candidate(name, url, RELAY + URLEncoder.encode(url, "UTF-8"), height)
    }
}
