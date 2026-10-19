package com.moosa.hub

/**
 * Keep only a screenful of cards per shelf on Fire TV. The regular paged
 * Show More view is deliberately not limited by these home-only settings.
 */
internal object MOOSAHomeLoadPolicy {
    const val MAX_HOME_CARDS = 16
    const val HOME_TMDB_PAGES_PER_REQUEST = 1
    // Avoid re-downloading every shelf during short returns from playback.
    const val FRESH_HOME_CACHE_MS = 8 * 60 * 1000L
    const val STALE_HOME_CACHE_MS = 30 * 60 * 1000L
    const val HOME_NETWORK_TIMEOUT_MS = 5_000L

    fun isFresh(ageMs: Long): Boolean = ageMs in 0 until FRESH_HOME_CACHE_MS
    fun mayUseStale(ageMs: Long): Boolean = ageMs in 0 until STALE_HOME_CACHE_MS

    fun tmdbPage(requestedHomePage: Int): Int =
        (requestedHomePage.coerceAtLeast(1) - 1) *
            HOME_TMDB_PAGES_PER_REQUEST + 1
}
