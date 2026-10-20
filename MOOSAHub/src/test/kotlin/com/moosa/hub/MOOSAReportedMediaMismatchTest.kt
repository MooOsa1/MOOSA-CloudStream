package com.moosa.hub

import org.junit.Assert.*
import org.junit.Test

class MOOSAReportedMediaMismatchTest {
    private fun show(year: Int? = 2005, season: Int = 1, episode: Int = 1) =
        MOOSAMatchGuard.Claim("Doraemon", year, season = season, episode = episode, isAnime = true)

    @Test fun rejectsOnlyScreenshotConfirmedServerEpisode() {
        assertTrue(MOOSAReportedMediaMismatch.rejects(show(), "Anikoto", "HD-2 [SUB]", "Anikoto"))
        assertTrue(MOOSAReportedMediaMismatch.rejects(show(), "Anime Provider", "Anikoto HD-2 [SUB]", "External"))
        assertFalse(MOOSAReportedMediaMismatch.rejects(show(), "Anikoto", "HD-1 [SUB]", "Anikoto"))
        assertFalse(MOOSAReportedMediaMismatch.rejects(show(), "Anikoto", "HD-3 [SUB]", "Anikoto"))
        assertFalse(MOOSAReportedMediaMismatch.rejects(show(), "Other source", "HD-2 [SUB]", "Other"))
        assertFalse(MOOSAReportedMediaMismatch.rejects(show(season = 1, episode = 2), "Anikoto", "HD-2 [SUB]", "Anikoto"))
        assertFalse(MOOSAReportedMediaMismatch.rejects(show(season = 2, episode = 1), "Anikoto", "HD-2 [SUB]", "Anikoto"))
        assertFalse(MOOSAReportedMediaMismatch.rejects(show(year = 1979), "Anikoto", "HD-2 [SUB]", "Anikoto"))
    }

    @Test fun doesNotBlockOtherAnimeOrUnrelatedMovie() {
        val other = MOOSAMatchGuard.Claim("Naruto", 2002, season = 1, episode = 1, isAnime = true)
        assertFalse(MOOSAReportedMediaMismatch.rejects(other, "Anikoto", "HD-2 [SUB]", "Anikoto"))
        val movie = MOOSAMatchGuard.Claim("Doraemon", 2005, isAnime = true)
        assertFalse(MOOSAReportedMediaMismatch.rejects(movie, "Anikoto", "HD-2 [SUB]", "Anikoto"))
    }
}
