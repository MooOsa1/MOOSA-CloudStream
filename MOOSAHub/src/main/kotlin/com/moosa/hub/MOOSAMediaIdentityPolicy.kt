package com.moosa.hub

import java.net.URI
import java.net.URLDecoder

/**
 * Provider-independent negative identity evidence for movies, TV, and anime.
 *
 * Deliberately conservative: known contradictions are actionable; opaque
 * CDN identifiers and available 1080p streams do NOT prove content identity.
 * No guessed title, hash or provider popularity is treated as a match.
 */
internal object MOOSAMediaIdentityPolicy {
    private val pairedSeasonEpisode = Regex(
        """(?i)(?<![a-z0-9])s0*(\d{1,3})[ ._\-]*e0*(\d{1,4})(?!\d)"""
    )
    private val segmentSeasonEpisode = Regex(
        """(?i)(?:^|[/ ?&#])season[=/\-_ ]+(\d{1,3})[/&? ._\-]+(?:episode|ep)[=/\-_ ]+(\d{1,4})(?!\d)"""
    )
    private val querySeason = Regex("""(?i)(?:[?&])(?:season|season_number)=(\d{1,3})(?:&|$)""")
    private val queryEpisode = Regex("""(?i)(?:[?&])(?:episode|episode_number)=(\d{1,4})(?:&|$)""")
    private val releaseFile = Regex("""(?i)\.(?:mp4|mkv|avi|webm|m3u8)(?:\?.*)?$""")

    private fun decode(input: String): String =
        runCatching { URLDecoder.decode(input, "UTF-8") }.getOrDefault(input)
            .take(2048)

    private fun pairs(value: String): List<Pair<Int, Int>> {
        val decoded = decode(value)
        val explicit = (pairedSeasonEpisode.findAll(decoded) +
            segmentSeasonEpisode.findAll(decoded)).mapNotNull { m ->
                val season = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val episode = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (season in 1..200 && episode in 1..2000) season to episode else null
            }.toList()
        val queryS = querySeason.find(decoded)?.groupValues?.get(1)?.toIntOrNull()
        val queryE = queryEpisode.find(decoded)?.groupValues?.get(1)?.toIntOrNull()
        return explicit + if (queryS != null && queryE != null &&
            queryS in 1..200 && queryE in 1..2000) listOf(queryS to queryE)
        else emptyList()
    }

    /**
     * Reject when a provider explicitly marks a different season+episode
     * in its per-episode data. Do not infer from bare ?ep=13: many anime
     * providers use continuous numbering rather than TMDB's local index.
     */
    fun targetContradicts(claim: MOOSAMatchGuard.Claim, data: String): Boolean {
        val expectedS = claim.season ?: return false
        val expectedE = claim.episode ?: return false
        // A third-party plugin may serialize a complete JSON episode table
        // as its target. Scanning that table for other episode numbers would
        // falsely hide perfectly valid servers. Only interpret one direct
        // episode URL here; opaque/provider-specific payloads remain eligible.
        val target = data.trim()
        if (!target.startsWith("https://", true) &&
            !target.startsWith("http://", true)) return false
        return pairs(target).any { (s, e) -> s != expectedS || e != expectedE }
    }

    /**
     * Inspect readable HLS/MP4 links and server labels for *explicit*
     * negative episode evidence. Unlabeled /master.m3u8 and opaque CDN links
     * remain eligible (but are never described as content-verified).
     */
    fun linkContradicts(
        claim: MOOSAMatchGuard.Claim,
        url: String,
        source: String,
        name: String
    ): Boolean {
        val season = claim.season
        val episode = claim.episode
        if (season != null && episode != null) {
            if (pairs(url).any { (s, e) -> s != season || e != episode }) return true
            // Server labels may contain a release name with S01E02, but a
            // simple 'HD-2' means server number 2, NOT episode 2.
            if (pairs(source).any { (s, e) -> s != season || e != episode } ||
                pairs(name).any { (s, e) -> s != season || e != episode }
            ) return true
        }
        val filename = runCatching {
            URI(url).rawPath.orEmpty().substringAfterLast('/')
        }.getOrDefault("")
        // Existing year/name checks, extended to readable HLS playlist
        // filenames. Do not scan arbitrary CDN folder timestamps.
        if (!releaseFile.containsMatchIn(filename)) return false
        return MOOSAMatchGuard.mediaFilenameContradicts(
            claim, url
        )
    }
}
