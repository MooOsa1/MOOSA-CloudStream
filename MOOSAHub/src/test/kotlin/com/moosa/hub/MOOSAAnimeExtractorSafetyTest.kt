package com.moosa.hub

import org.junit.Assert.*
import org.junit.Test

class MOOSAAnimeExtractorSafetyTest {
    private fun rejected(
        source: String,
        name: String = source,
        provider: String = "CineStream",
        className: String = "com.megix.CineStreamProvider",
        anime: Boolean = true
    ) = MOOSAAnimeExtractorSafety.unsafeEngine(
        anime, provider, className, source, name
    )

    @Test fun blockFirstResultAndFallbackExtractorsForEveryAnime() {
        assertEquals("castle", rejected("Castle", "Castle Auto (USE VLC)"))
        assertEquals("anikoto", rejected("Anikoto[Sub]", "Anikoto[Sub] - HD-2"))
        assertEquals("anikoto", rejected("Anikoto[Sub]", "Anikoto Vidstream-1 [SUB]"))
        assertEquals("animedao", rejected("Animedao[Sub]", "Animedao[Sub] HD"))
        assertEquals("anidb", rejected("Anidb", "Anidb [SUB]"))
        assertEquals("anizone", rejected("Anizone", "Anizone Multi Audio"))
        assertEquals("anineko", rejected("Anineko HD-1 [SUB]"))
        assertEquals("gramcinema", rejected("GramCinema", "1080p"))
    }

    @Test fun retainIndependentlyIdentifiedAnimeSources() {
        assertNull(rejected("Anikage[HD] Sub"))
        assertNull(rejected("Reanime[Sub]"))
        assertNull(rejected("Animekizz [HD]"))
        assertNull(rejected("TokyoInsider", "[TokyoInsider] - Anime 1080p"))
        assertNull(rejected("AniKoto OtherName", name = "Anikage", provider = "ExternalAnime",
            className = "com.anime.ExternalProvider"))
        assertNull(rejected("Castle", name = "Castle Auto (USE VLC)",
            provider = "DifferentProvider", className = "com.anikoto.Anime"))
    }

    @Test fun retainAllMovieAndTvServersIncludingCastleAndAnimeSources() {
        assertNull(rejected("Castle", "Castle Auto (USE VLC)", anime = false))
        assertNull(rejected("Anikoto[Sub]", "HD-2 [SUB]", anime = false))
        assertNull(rejected("GramCinema", anime = false))
    }

    @Test fun rejectByServerNameIfSourceIsGeneric() {
        assertEquals("castle", rejected("Generic", "Castle Auto (USE VLC)"))
        assertEquals("anikoto", rejected("Generic", "Anikoto[Sub] - HD-2"))
    }

    @Test fun preventIncidentalTitleWordsFromBlockingGoodExtractors() {
        assertNull(rejected("Anikage Castle In The Sky"))
        assertNull(rejected("My Anikoto Test"))
        assertNull(rejected("Something Anineko"))
        assertNull(rejected("Castled"))
    }

    @Test fun acceptCineStreamVariantsWithoutBlockingOtherProviderClasses() {
        assertEquals("castle", rejected("Castle", provider = "TMDB",
            className = "com.megix.CineTmdbProvider"))
        assertNull(rejected("Castle", provider = "OtherRepo",
            className = "org.demo.CastleProvider"))
    }
}
