package com.moosa.hub

import org.junit.Assert.*
import org.junit.Test

class MOOSAAnimeStreamEpisodePolicyTest {
    private fun ep(number: Int, season: Int? = null, data: String = "ep-$number") =
        MOOSAAnimeStreamEpisodePolicy.Item(data, season, number)

    @Test fun nativeAnimeLoadsSubbedTrackBeforeDubbed() {
        val result = MOOSAAnimeStreamEpisodePolicy.select(
            season = 1, episode = 101, absolute = 101,
            sourceTitle = "Doraemon", pageProvesSeason = false,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(
                subbed = (1..110).map { ep(it, 1, "sub-$it") },
                dubbed = (1..110).map { ep(it, 1, "dub-$it") }
            )
        )
        assertEquals("sub-101", result)
    }

    @Test fun dubbedOnlyCanStillWorkWithoutSubbedTrack() {
        val result = MOOSAAnimeStreamEpisodePolicy.select(
            season = 1, episode = 101, absolute = null,
            sourceTitle = "Doraemon", pageProvesSeason = false,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(
                dubbed = listOf(ep(101, null, "dubbed-target"))
            )
        )
        assertEquals("dubbed-target", result)
    }

    @Test fun splitSeasonNativeEpisodeOneCanMatchVerifiedPage() {
        assertEquals("s2e1", MOOSAAnimeStreamEpisodePolicy.select(
            season = 2, episode = 1, absolute = 13,
            sourceTitle = "Show 2nd Season", pageProvesSeason = true,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(
                subbed = listOf(ep(1, 1, "s2e1"))
            )
        ))
    }

    @Test fun rejectSeasonOneWhenRequestedSeasonTwoAndNoSeasonProof() {
        assertNull(MOOSAAnimeStreamEpisodePolicy.select(
            season = 2, episode = 1, absolute = null,
            sourceTitle = "Show", pageProvesSeason = false,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(
                subbed = listOf(ep(1, 1, "wrong-season"))
            )
        ))
    }

    @Test fun absoluteEpisodeMustBeVerifiedByFullPrefix() {
        assertEquals("ep-13", MOOSAAnimeStreamEpisodePolicy.select(
            season = 2, episode = 1, absolute = 13,
            sourceTitle = "Show", pageProvesSeason = false,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(
                subbed = (1..24).map { ep(it, null) }
            )
        ))
        assertNull(MOOSAAnimeStreamEpisodePolicy.select(
            season = 2, episode = 1, absolute = 13,
            sourceTitle = "Show", pageProvesSeason = false,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(
                subbed = (1..24).filterNot { it == 7 }.map { ep(it, null) }
            )
        ))
    }

    @Test fun neverAcceptEmptyEpisodePayloads() {
        assertNull(MOOSAAnimeStreamEpisodePolicy.select(
            season = 1, episode = 1, absolute = 1,
            sourceTitle = "Show", pageProvesSeason = false,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(subbed = listOf(ep(1, 1, "")))
        ))
    }

    @Test fun neverAcceptExplicitlyWrongSequelSeason() {
        assertNull(MOOSAAnimeStreamEpisodePolicy.select(
            season = 1, episode = 1, absolute = 1,
            sourceTitle = "Show 2nd Season", pageProvesSeason = false,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(subbed = listOf(ep(1, 1)))
        ))
    }
}
