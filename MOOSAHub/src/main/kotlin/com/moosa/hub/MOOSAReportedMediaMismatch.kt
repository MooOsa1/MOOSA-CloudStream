package com.moosa.hub

import java.util.Locale

/**
 * Conservative, evidence-backed quarantine of individually reported wrong-video
 * server/episode combinations. Do not block entire provider catalogs based on
 * one report: other servers and titles may still be correct.
 *
 * This is NOT a general video-identity validator. Provider page/episode metadata,
 * reachable URLs and advertised resolution do not prove what a video contains.
 */
internal object MOOSAReportedMediaMismatch {
    private val hdTwo = Regex("""(?<![a-z0-9])hd[ _-]*2(?![a-z0-9])""")

    fun rejects(
        claim: MOOSAMatchGuard.Claim,
        provider: String,
        serverName: String,
        serverSource: String
    ): Boolean {
        // Screenshot report: Doraemon (2005) S1E1 selected Anikoto HD-2 [SUB],
        // but the actual on-screen video was an unrelated school anime.
        // Restrict quarantine to the exact reported identity/server family.
        if (claim.season != 1 || claim.episode != 1) return false
        if (claim.year != null && claim.year != 2005) return false
        if (MOOSAMatchGuard.titleKey(claim.title, isSeries = true) != "doraemon") return false

        val origin = "$provider $serverName $serverSource".lowercase(Locale.ROOT)
        return "anikoto" in origin && hdTwo.containsMatchIn(origin)
    }
}
