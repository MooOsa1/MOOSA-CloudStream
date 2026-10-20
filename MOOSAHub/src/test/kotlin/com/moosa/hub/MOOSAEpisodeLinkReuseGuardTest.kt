package com.moosa.hub

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MOOSAEpisodeLinkReuseGuardTest {
    @Before fun reset() = MOOSAEpisodeLinkReuseGuard.clearForTests()

    private fun show(ep: Int) =
        MOOSAMatchGuard.Claim("The Office", 2005, tmdbId = 2316, season = 2, episode = ep)
    private fun movie(id: Int, title: String) =
        MOOSAMatchGuard.Claim(title, 2022, tmdbId = id)

    @Test fun acceptsOriginalAndRepeatedSameEpisode() {
        assertTrue(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", show(3),
            "https://cdn.example/episode3.m3u8|https://ref|headers1"))
        assertTrue(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", show(3),
            "https://cdn.example/episode3.m3u8|https://ref|headers1"))
    }

    @Test fun blocksFallbackReturningSameMediaForWrongEpisode() {
        val stream = "https://cdn.example/first.m3u8|https://ref|headers1"
        assertTrue(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", show(1), stream))
        assertFalse(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", show(2), stream))
        assertTrue(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", show(2),
            "https://cdn.example/second.m3u8|https://ref|headers1"))
    }

    @Test fun detectsSameStreamAcrossDifferentMoviesButNotProviders() {
        val sameStream = "https://cdn.example/static.m3u8|https://ref|headers1"
        assertTrue(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", movie(42, "Film A"), sameStream))
        assertFalse(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", movie(56, "Film B"), sameStream))
        assertTrue(MOOSAEpisodeLinkReuseGuard.accepts("Provider B", movie(56, "Film B"), sameStream))
    }

    @Test fun refererAndHeaderIdentityAvoidFalseCrossEpisodeCollisions() {
        assertTrue(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", show(1),
            "https://cdn.example/master.m3u8|https://show/e1|cookie-A"))
        assertTrue(MOOSAEpisodeLinkReuseGuard.accepts("Provider A", show(2),
            "https://cdn.example/master.m3u8|https://show/e2|cookie-B"))
    }
}
