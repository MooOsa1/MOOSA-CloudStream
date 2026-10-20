package com.moosa.hub

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Detect one exact stream (URL + referer + header identity) wrongly reused by
 * the same provider for two different works/episodes.
 *
 * This catches common fallback-to-first-episode extractor failures after the
 * first successful lookup. It does NOT prove content identity and cannot
 * detect two distinct URLs that happen to contain the same wrong video.
 */
internal object MOOSAEpisodeLinkReuseGuard {
    private data class Seen(val contentIdentity: String, val time: Long)
    private const val maxEntries = 768
    private const val maxAgeMs = 30 * 60 * 1000L
    private val seen = LinkedHashMap<String, Seen>(128, 0.75f, true)

    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun identity(claim: MOOSAMatchGuard.Claim): String {
        val id = claim.tmdbId?.let { "tmdb:$it" }
            ?: claim.imdbId?.let { "imdb:$it" }
            ?: "${MOOSAMatchGuard.titleKey(claim.title, claim.season != null)}|${claim.year}"
        return "$id|s${claim.season ?: 0}|e${claim.episode ?: 0}"
    }

    /**
     * Call ONLY when an eligible video has passed link reachability / quality
     * checks and is about to reach the player.
     */
    @Synchronized
    fun accepts(provider: String, claim: MOOSAMatchGuard.Claim, streamSignature: String): Boolean {
        if (provider.isBlank() || streamSignature.isBlank()) return true
        val key = digest("$provider|$streamSignature")
        val content = identity(claim)
        val now = System.currentTimeMillis()
        val previous = seen[key]
        if (previous != null && now - previous.time in 0 until maxAgeMs &&
            previous.contentIdentity != content) return false
        seen[key] = Seen(content, now)
        if (seen.size > maxEntries) {
            val iterator = seen.entries.iterator()
            while (seen.size > maxEntries && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    @Synchronized
    fun clearForTests() = seen.clear()
}
