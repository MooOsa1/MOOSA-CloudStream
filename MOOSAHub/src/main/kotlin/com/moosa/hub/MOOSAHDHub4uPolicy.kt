package com.moosa.hub

import java.net.URI
import java.util.Locale

/** Exact film/episode identity and allowlisted public HDHub4u catalog paths. */
internal object MOOSAHDHub4uPolicy {
    const val SOURCE_NAME = "MOOSA HDHub4u • Direct"
    const val BASE = "https://new9.hdhub4u.cl"

    private fun normalized(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    fun slug(title: String): String = title.lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    fun exactCatalogHeading(
        title: String, year: Int?, season: Int?, pageTitle: String
    ): Boolean {
        val cleanTitle = normalized(title)
        if (cleanTitle.isBlank()) return false
        return if (season != null) {
            val heading = Regex("""(?i)^\s*(.+?)\s*\(\s*Season\s+(\d{1,3})\s*\)""")
                .find(pageTitle) ?: return false
            normalized(heading.groupValues[1]) == cleanTitle &&
                heading.groupValues[2].toIntOrNull() == season
        } else {
            val heading = Regex("""^\s*(.+?)\s*\(\s*((?:19|20)\d{2})\s*\)""")
                .find(pageTitle) ?: return false
            normalized(heading.groupValues[1]) == cleanTitle &&
                heading.groupValues[2].toIntOrNull() == year
        }
    }

    /** HubDrive and archive headings must confirm the exact requested item. */
    fun exactFileIdentity(
        title: String, year: Int?, season: Int?, episode: Int?, filename: String
    ): Boolean {
        if (normalized(title).isBlank()) return false
        if (season != null) {
            if (episode == null) return false
            val match = Regex("""(?i)^\s*(.+?)[ ._-]+S0*(\d{1,3})[ ._-]*E0*(\d{1,3})(?=[ ._-]|$)""")
                .find(filename) ?: return false
            return normalized(match.groupValues[1]) == normalized(title) &&
                match.groupValues[2].toIntOrNull() == season &&
                match.groupValues[3].toIntOrNull() == episode
        }
        val match = Regex("""^\s*(.+?)[ ._-]+\(?((?:19|20)\d{2})\)?(?=[ ._-]|$)""")
            .find(filename) ?: return false
        return normalized(match.groupValues[1]) == normalized(title) &&
            match.groupValues[2].toIntOrNull() == year
    }

    fun episodeLabel(text: String): Int? =
        Regex("""(?i)^\s*EPiSODE\s+0*(\d{1,3})\s*$""")
            .matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()

    fun releaseQuality(text: String): Int? = when {
        Regex("""(?i)\b(?:2160p|4k)\b""").containsMatchIn(text) -> 2160
        Regex("""(?i)\b1080p\b""").containsMatchIn(text) -> 1080
        else -> null
    }

    private fun uri(raw: String): URI? {
        val parsed = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.userInfo != null ||
            parsed.port != -1 || parsed.fragment != null || parsed.host.isNullOrBlank()
        ) return null
        return parsed
    }

    private fun isCatalogHost(host: String): Boolean =
        Regex("""(?:new\d+\.)?hdhub4u\.cl""").matches(host.lowercase(Locale.ROOT))

    fun safeCatalog(raw: String): String? {
        val parsed = uri(raw) ?: return null
        if (!isCatalogHost(parsed.host) || parsed.rawQuery != null ||
            !Regex("""/[a-z0-9-]+/""").matches(parsed.path)
        ) return null
        return parsed.toASCIIString()
    }

    fun safeSitemap(raw: String): String? {
        val parsed = uri(raw) ?: return null
        if (!isCatalogHost(parsed.host) || parsed.rawQuery != null ||
            !Regex("""/post-sitemap\d*\.xml""").matches(parsed.path)
        ) return null
        return parsed.toASCIIString()
    }

    fun matchingCatalogUrl(
        raw: String, title: String, year: Int?, season: Int?
    ): String? {
        val validated = safeCatalog(raw) ?: return null
        val path = URI(validated).path.lowercase(Locale.ROOT)
        val name = slug(title)
        if (name.isBlank() || !path.startsWith("/$name-")) return null
        if (season != null) {
            val markers = listOf("-season-$season-", "-season-${"%02d".format(season)}-")
            if (markers.none { it in path }) return null
        } else if (year == null || "-$year-" !in path) return null
        return validated
    }

    fun safeGreenmotors(raw: String): String? {
        val parsed = uri(raw) ?: return null
        if (parsed.host.lowercase(Locale.ROOT) !in
            setOf("greenmotors.club", "greenmotors.cc") ||
            parsed.path !in setOf("", "/") ||
            !Regex("""id=[A-Za-z0-9_-]{16,600}""").matches(parsed.rawQuery.orEmpty())
        ) return null
        return parsed.toASCIIString()
    }

    fun safeArchive(raw: String): String? {
        val parsed = uri(raw) ?: return null
        if (parsed.host.lowercase(Locale.ROOT) != "hblinks.lol" ||
            parsed.rawQuery != null ||
            !Regex("""/archives/\d{1,10}/?""").matches(parsed.path)
        ) return null
        return parsed.toASCIIString()
    }

    fun safeHubDrive(raw: String): String? {
        val parsed = uri(raw) ?: return null
        if (parsed.host.lowercase(Locale.ROOT) != "hubdrive.bar" ||
            parsed.rawQuery != null ||
            !Regex("""/file/[A-Za-z0-9]{5,40}/?""").matches(parsed.path)
        ) return null
        return parsed.toASCIIString()
    }

    /** Newer movie posts link straight to HubCloud, skipping HubDrive. */
    fun safeHubCloudDirect(raw: String): String? {
        val parsed = uri(raw) ?: return null
        if (parsed.host.lowercase(Locale.ROOT) != "hubcloud.ist" ||
            parsed.rawQuery != null ||
            !Regex("""/drive/[A-Za-z0-9_-]{5,50}/?""").matches(parsed.path)
        ) return null
        return parsed.toASCIIString()
    }
}
