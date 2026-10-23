package com.moosa.hub

/**
 * Known external CloudStream providers which MOOSA can consume when they are
 * already registered in the app's global provider registry. This does NOT
 * claim their code is bundled in MOOSA, and does not install a CS3 silently.
 * Exact names avoid collecting an unrelated third-party meta-provider.
 */
internal object MOOSARecognizedExternalProvider {
    private val names = setOf("cinestream", "streamplay")

    fun isRecognized(name: String): Boolean =
        name.trim().lowercase(java.util.Locale.ROOT) in names

    fun shouldIncludeFromGlobal(name: String, isAnime: Boolean): Boolean =
        isAnime || isRecognized(name)

    // When a meta-provider exposes P2P/Torrentio among its direct links,
    // exclude those candidates without disabling the entire provider.
    private val torrentLabel = Regex(
        "(?i)torrentio|torrentsdb|animetosho|bittorrent|\\bp2p\\b|\\btorrent\\b"
    )

    fun isTorrentCandidate(source: String, server: String, url: String): Boolean {
        val normalized = url.trim().lowercase(java.util.Locale.ROOT)
        val path = normalized.substringBefore('?').substringBefore('#')
        return normalized.startsWith("magnet:") ||
            path.endsWith(".torrent") ||
            torrentLabel.containsMatchIn(source) ||
            torrentLabel.containsMatchIn(server)
    }
}
