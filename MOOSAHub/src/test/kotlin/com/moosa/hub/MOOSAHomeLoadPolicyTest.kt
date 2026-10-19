package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAHomeLoadPolicyTest {
    @Test
    fun firstHomeLoadNeedsOneTmdbPageInsteadOfThree() {
        assertEquals(1, MOOSAHomeLoadPolicy.HOME_TMDB_PAGES_PER_REQUEST)
        assertEquals(1, MOOSAHomeLoadPolicy.tmdbPage(1))
    }

    @Test
    fun subsequentHomePagesNeverSkipTmdbPages() {
        assertEquals(2, MOOSAHomeLoadPolicy.tmdbPage(2))
        assertEquals(3, MOOSAHomeLoadPolicy.tmdbPage(3))
        assertEquals(1, MOOSAHomeLoadPolicy.tmdbPage(0))
    }

    @Test
    fun homePosterWorkingSetIsSmall() {
        assertTrue(MOOSAHomeLoadPolicy.MAX_HOME_CARDS in 8..20)
    }
}
