package com.moosa.hub

/**
 * Keep only a screenful of cards per shelf on Fire TV. The regular paged
 * Show More view is deliberately not limited by these home-only settings.
 */
internal object MOOSAHomeLoadPolicy {
    const val MAX_HOME_CARDS = 16
    const val HOME_TMDB_PAGES_PER_REQUEST = 1

    fun tmdbPage(requestedHomePage: Int): Int =
        (requestedHomePage.coerceAtLeast(1) - 1) *
            HOME_TMDB_PAGES_PER_REQUEST + 1
}
