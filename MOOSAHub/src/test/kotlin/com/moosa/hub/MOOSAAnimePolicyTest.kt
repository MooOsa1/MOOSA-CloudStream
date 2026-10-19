package com.moosa.hub

import org.junit.Assert.*
import org.junit.Test

class MOOSAAnimePolicyTest {
    @Test fun animeRequiresJapaneseOriginalAndAnimationGenre() {
        assertTrue(MOOSAAnimePolicy.isAnime("ja", listOf(16, 10765)))
        assertFalse(MOOSAAnimePolicy.isAnime("ja", listOf(18)))
        assertFalse(MOOSAAnimePolicy.isAnime("en", listOf(16)))
        assertFalse(MOOSAAnimePolicy.isAnime(null, emptyList()))
    }

    @Test fun seasonalEpisodesReceiveSafeContinuousNumbers() {
        val episodes = (1..12).map { MOOSAAnimePolicy.Index(1, it) } +
            (1..12).map { MOOSAAnimePolicy.Index(2, it) }
        val mapped = MOOSAAnimePolicy.absoluteEpisodes(episodes)
        assertEquals(1, mapped[MOOSAAnimePolicy.Index(1, 1)])
        assertEquals(12, mapped[MOOSAAnimePolicy.Index(1, 12)])
        assertEquals(13, mapped[MOOSAAnimePolicy.Index(2, 1)])
        assertEquals(24, mapped[MOOSAAnimePolicy.Index(2, 12)])
    }

    @Test fun missingEpisodesMustNeverProduceFalseAbsoluteIndex() {
        val episodes = listOf(
            MOOSAAnimePolicy.Index(1, 1),
            MOOSAAnimePolicy.Index(1, 3),
            MOOSAAnimePolicy.Index(2, 1)
        )
        assertNull(MOOSAAnimePolicy.absoluteEpisodes(episodes)[MOOSAAnimePolicy.Index(2, 1)])
    }

    @Test fun episodeDisplayKeepsSeasonSpecificIdentity() {
        assertEquals("EP 13 • The Journey",
            MOOSAAnimePolicy.displayEpisodeName("The Journey", 2, 1, 13))
        assertEquals("EP 13",
            MOOSAAnimePolicy.displayEpisodeName("Episode 1", 2, 1, 13))
        assertEquals("The Journey",
            MOOSAAnimePolicy.displayEpisodeName("The Journey", 1, 1, 1))
    }

    @Test fun sameTitleDifferentDoraemonGenerationsAreNotInterchangeable() {
        assertFalse(MOOSAAnimePolicy.sameFirstSeasonGeneration(2005, 1979, 1))
        assertFalse(MOOSAAnimePolicy.sameFirstSeasonGeneration(1979, 2005, 1))
        assertTrue(MOOSAAnimePolicy.sameFirstSeasonGeneration(2005, 2006, 1))
        assertTrue(MOOSAAnimePolicy.sameFirstSeasonGeneration(2005, 1979, 2))
        assertTrue(MOOSAAnimePolicy.sameFirstSeasonGeneration(2005, null, 1))
        assertTrue(MOOSAAnimePolicy.sameFirstSeasonGeneration(null, 1979, 1))
        assertEquals(1979, MOOSAAnimePolicy.yearInTitle("Doraemon (1979)"))
        assertEquals(2005, MOOSAAnimePolicy.yearInTitle("Doraemon 2005"))
        assertNull(MOOSAAnimePolicy.yearInTitle("Doraemon S1E101"))
    }

    @Test fun animeSeasonNamesCoverSequelNamingAndPreventWrongSeason() {
        assertEquals(2, MOOSAAnimePolicy.explicitAnimeSeason("Show 2nd Season"))
        assertEquals(3, MOOSAAnimePolicy.explicitAnimeSeason("Show 3rd Season"))
        assertEquals(4, MOOSAAnimePolicy.explicitAnimeSeason("Show Season 4"))
        assertNull(MOOSAAnimePolicy.explicitAnimeSeason("Show"))
        assertTrue("Show 2nd Season" in MOOSAAnimePolicy.seasonQueries("Show", 2))
    }

    @Test fun onlyProvenContinuousSourceCanMatchAbsoluteEpisode() {
        val full = (1..24).map { MOOSAAnimePolicy.SourceIndex(1, it) }
        assertTrue(MOOSAAnimePolicy.allowsContinuousNumbering(2, 1, 13, "Show", full))
        assertFalse(MOOSAAnimePolicy.allowsContinuousNumbering(2, 1, 13,
            "Show Season 2", full))
        assertFalse(MOOSAAnimePolicy.allowsContinuousNumbering(2, 1, 13, "Show",
            full.filterNot { it.episode == 7 }))
        assertFalse(MOOSAAnimePolicy.allowsContinuousNumbering(2, 1, 13, "Show",
            full.map { it.copy(season = 2) }))
        assertFalse(MOOSAAnimePolicy.allowsContinuousNumbering(1, 1, 13, "Show", full))
    }
}
