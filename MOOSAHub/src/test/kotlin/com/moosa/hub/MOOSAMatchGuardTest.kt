package com.moosa.hub

import org.junit.Assert.*
import org.junit.Test

class MOOSAMatchGuardTest {
    private fun movie(name: String, year: Int, imdb: String? = null) =
        MOOSAMatchGuard.Claim(name, year, imdbId = imdb)
    private fun show(name: String, year: Int, season: Int, episode: Int) =
        MOOSAMatchGuard.Claim(name, year, season = season, episode = episode)
    private fun check(
        request: MOOSAMatchGuard.Claim,
        candidate: MOOSAMatchGuard.Evidence,
        aliases: List<String> = emptyList()
    ) = MOOSAMatchGuard.verifiedIdentity(request, candidate, aliases)

    @Test fun rejectsDifferentTitlesEvenWithHighTokenOverlap() {
        assertFalse(check(movie("Dune", 2021),
            MOOSAMatchGuard.Evidence("Dune Part Two", 2024)))
        // Same-year near matches must fail purely on identity, not only year.
        assertFalse(check(movie("Dune", 2021),
            MOOSAMatchGuard.Evidence("Dune Part Two", 2021)))
        assertFalse(check(movie("Scary Movie", 2000),
            MOOSAMatchGuard.Evidence("Scary Movie 2", 2000)))
        assertFalse(check(movie("Scary Movie", 2000),
            MOOSAMatchGuard.Evidence("Scary Movie 2", 2001)))
        assertFalse(check(movie("The Lion King", 1994),
            MOOSAMatchGuard.Evidence("The Lion King 2", 1998)))
        assertFalse(check(show("Naruto", 2002, 1, 4),
            MOOSAMatchGuard.Evidence("Naruto Shippuden", 2007)))
        assertFalse(check(show("Doraemon", 2005, 1, 101),
            MOOSAMatchGuard.Evidence("Doraemon 1979", 1979)))
    }

    @Test fun forbidsSameNameWrongRemakeWhenKnownYearConflicts() {
        assertFalse(check(movie("The Lion King", 1994),
            MOOSAMatchGuard.Evidence("The Lion King", 2019)))
        assertFalse(check(movie("Halloween", 1978),
            MOOSAMatchGuard.Evidence("Halloween", 2007)))
        assertFalse(check(show("Doraemon", 2005, 1, 101),
            MOOSAMatchGuard.Evidence("Doraemon (1979)", null)))
        assertFalse(check(movie("Dune", 2021),
            MOOSAMatchGuard.Evidence("Dune", 1984)))
        assertFalse(check(movie("Dune", 2021),
            MOOSAMatchGuard.Evidence("Dune", 2022)))
    }

    @Test fun rejectsMissingYearWithoutCrossProviderIdentity() {
        assertFalse(check(movie("Dune", 2021),
            MOOSAMatchGuard.Evidence("Dune")))
        assertFalse(check(show("Doraemon", 2005, 1, 101),
            MOOSAMatchGuard.Evidence("Doraemon")))
        assertTrue(check(movie("Dune", 2021, imdb = "tt1160419"),
            MOOSAMatchGuard.Evidence("Dune", imdbId = "tt1160419")))
    }

    @Test fun rejectsContradictoryImdbOrTmdbEvenIfNamesMatch() {
        assertFalse(check(movie("Dune", 2021, "tt1160419"),
            MOOSAMatchGuard.Evidence("Dune", 2021, imdbId = "tt0087182")))
        assertFalse(check(MOOSAMatchGuard.Claim("Doraemon", 2005, tmdbId = 65733, season = 1, episode = 101),
            MOOSAMatchGuard.Evidence("Doraemon", 2005, tmdbId = 52725)))
        assertFalse(check(movie("Dune", 2021, "tt1160419"),
            MOOSAMatchGuard.Evidence("Dune", 1984, imdbId = "tt1160419")))
    }

    @Test fun acceptsVerifiedExactOfficialNamesAndLocalizedAliases() {
        assertTrue(check(movie("Parasite", 2019),
            MOOSAMatchGuard.Evidence("기생충", 2019),
            listOf("기생충")))
        assertTrue(check(movie("Scary Movie", 2000),
            MOOSAMatchGuard.Evidence("Scary Movie (2000)", 2000)))
        assertTrue(check(show("Doraemon", 2005, 1, 101),
            MOOSAMatchGuard.Evidence("Doraemon", 2005)))
        assertTrue(check(movie("Dune", 2021),
            MOOSAMatchGuard.Evidence("Dune", searchYear = 2021)))
    }

    @Test fun rejectsWrongSeasonAndEpisodeEvenWithMatchingYearAndName() {
        assertFalse(check(show("The Office", 2005, 2, 3),
            MOOSAMatchGuard.Evidence("The Office S2E4", 2005)))
        assertFalse(check(show("The Office", 2005, 2, 3),
            MOOSAMatchGuard.Evidence("The Office Season 3", 2005)))
        assertTrue(check(show("The Office", 2005, 2, 3),
            MOOSAMatchGuard.Evidence("The Office S2E3", 2005)))
    }

    @Test fun rejectsContradictoryLoadedAndSearchYears() {
        assertFalse(check(movie("Dune", 2021),
            MOOSAMatchGuard.Evidence("Dune", 2021, searchYear = 1984)))
        assertFalse(check(movie("Dune", 2021),
            MOOSAMatchGuard.Evidence("Dune", 1984, searchYear = 2021)))
    }

    @Test fun preservesMovieTitlesContainingNumbersAndParts() {
        assertEquals("1917", MOOSAMatchGuard.titleKey("1917", false))
        assertEquals("2046", MOOSAMatchGuard.titleKey("2046", false))
        assertEquals("scary movie 2", MOOSAMatchGuard.titleKey("Scary Movie 2", false))
        assertEquals("dune part two", MOOSAMatchGuard.titleKey("Dune Part Two", false))
        assertEquals("doraemon", MOOSAMatchGuard.titleKey("Doraemon (2005)", true))
        assertEquals("the office", MOOSAMatchGuard.titleKey("The Office Season 2 Episode 3", true))
    }

    @Test fun refusesAnimeAliasIfSourceExplicitlyAdvertisesOtherSeason() {
        val claim = MOOSAMatchGuard.Claim(
            "Attack on Titan", 2013, season = 1, episode = 1, isAnime = true)
        val item = MOOSAMatchGuard.Evidence(
            "Shingeki no Kyojin Season 2", 2013,
            additionalNames = listOf("Attack on Titan"))
        assertFalse(check(claim, item, listOf("Shingeki no Kyojin")))
    }

    @Test fun searchCandidateCanMatchTitleBeforeLoadedYearVerification() {
        val claim = movie("Dune", 2021)
        assertTrue(MOOSAMatchGuard.titleAndClaimCompatible(
            claim, MOOSAMatchGuard.Evidence("Dune"), emptyList()))
        assertFalse(check(claim, MOOSAMatchGuard.Evidence("Dune")))
    }
}
