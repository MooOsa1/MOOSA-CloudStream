package com.moosa.hub

import org.junit.Assert.*
import org.junit.Test

class MOOSAMediaIdentityPolicyTest {
    private val tv = MOOSAMatchGuard.Claim("The Office", 2005, season = 2, episode = 3)
    private val anime = MOOSAMatchGuard.Claim("Naruto", 2002, season = 2, episode = 1, isAnime = true)

    @Test fun rejectsWrongUpstreamEpisodeAcrossAllProviders() {
        assertTrue(MOOSAMediaIdentityPolicy.targetContradicts(tv,
            "https://site.example/watch/The.Office.S02E04"))
        assertTrue(MOOSAMediaIdentityPolicy.targetContradicts(tv,
            "https://site.example/play?season=2&episode=4&id=abc"))
        assertTrue(MOOSAMediaIdentityPolicy.targetContradicts(tv,
            "https://site.example/watch/season/3/episode/3"))
        assertFalse(MOOSAMediaIdentityPolicy.targetContradicts(tv,
            "https://site.example/watch/The.Office.S02E03"))
        assertFalse(MOOSAMediaIdentityPolicy.targetContradicts(tv,
            "https://site.example/play?season=2&episode=3&id=abc"))
    }

    @Test fun neverTreatBareEpisodeIdAsTvLocalIndex() {
        assertFalse(MOOSAMediaIdentityPolicy.targetContradicts(anime,
            "https://anime.example/watch?ep=13&embed=767876"))
        assertFalse(MOOSAMediaIdentityPolicy.targetContradicts(anime,
            "anikoto|https://anime.example/naruto|9982|sub"))
        assertFalse(MOOSAMediaIdentityPolicy.targetContradicts(anime,
            "https://cdn.example/opaque-id"))
    }

    @Test fun rejectExplicitWrongFinalMediaWithoutDestroyingGenericCdns() {
        assertTrue(MOOSAMediaIdentityPolicy.linkContradicts(tv,
            "https://cdn.example/The.Office.S02E04.1080p.m3u8", "HLS", "1080p"))
        assertTrue(MOOSAMediaIdentityPolicy.linkContradicts(tv,
            "https://cdn.example/season/2/episode/4/master.m3u8", "HLS", "1080p"))
        assertTrue(MOOSAMediaIdentityPolicy.linkContradicts(tv,
            "https://cdn.example/xx.mp4", "Provider", "The.Office.S02E04 1080p"))
        assertFalse(MOOSAMediaIdentityPolicy.linkContradicts(tv,
            "https://cdn.example/ab123/master.m3u8", "Anikoto", "HD-2 [SUB]"))
        assertFalse(MOOSAMediaIdentityPolicy.linkContradicts(tv,
            "https://cdn.example/The.Office.S02E03.1080p.m3u8", "HLS", "1080p"))
    }

    @Test fun preserveMoviesAndOtherTitlesWithoutRelevantIdentityHints() {
        val movie = MOOSAMatchGuard.Claim("Dune", 2021)
        assertFalse(MOOSAMediaIdentityPolicy.targetContradicts(movie,
            "https://cdn.example/foo?season=3&episode=1"))
        assertFalse(MOOSAMediaIdentityPolicy.linkContradicts(movie,
            "https://cdn.example/1917/master.m3u8", "Provider", "1080p"))
    }

    @Test fun rejectCrossTitleCatalogIdsForMoviesSeriesAnime() {
        val dune = MOOSAMatchGuard.Claim("Dune", 2021, imdbId = "tt1160419", tmdbId = 438631)
        assertTrue(MOOSAMediaIdentityPolicy.linkContradicts(dune,
            "https://cdn.example/movie/imdb/tt0087182/stream.m3u8", "Provider", "1080p"))
        assertFalse(MOOSAMediaIdentityPolicy.linkContradicts(dune,
            "https://cdn.example/movie/imdb/tt1160419/stream.m3u8", "Provider", "1080p"))
        assertTrue(MOOSAMediaIdentityPolicy.linkContradicts(dune,
            "https://cdn.example/stream.m3u8?tmdb=9999", "Provider", "1080p"))
        assertFalse(MOOSAMediaIdentityPolicy.linkContradicts(dune,
            "https://cdn.example/stream.m3u8?tmdb=438631", "Provider", "1080p"))
        assertTrue(MOOSAMediaIdentityPolicy.targetContradicts(dune,
            "https://source.example/load?tmdb_id=12345"))
        assertFalse(MOOSAMediaIdentityPolicy.targetContradicts(dune,
            """{"episodes":[{"season":1,"episode":1},{"season":2,"episode":3}]}"""))
    }

    @Test fun rejectContradictoryMetadataButNotServerOrdinalTwo() {
        val doraemon = MOOSAMatchGuard.Claim("Doraemon", 2005, season = 1, episode = 1, isAnime = true)
        assertTrue(MOOSAMediaIdentityPolicy.linkContradicts(doraemon,
            "https://cdn.example/a.mp4", "AniKoto", "Doraemon S01E02 HD-2"))
        assertFalse(MOOSAMediaIdentityPolicy.linkContradicts(doraemon,
            "https://cdn.example/a.mp4", "AniKoto", "HD-2 [SUB]"))
    }
}
