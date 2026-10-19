package com.moosa.hub

import com.lagradost.cloudstream3.MainPageRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAAnimeAndShowMoreTest {
    private val anime = MainPageRequest(
        "Anime",
        "/discover/tv?with_genres=16&with_original_language=ja&with_origin_country=JP&sort_by=popularity.desc",
        false
    )

    @Test
    fun animeHomeCategoryUsesJapaneseTvAnimation() {
        val shelf = MOOSACatalog.categories.first { it.name == "Anime" }
        assertTrue(shelf.data.startsWith("/discover/tv?"))
        assertTrue(shelf.data.contains("with_genres=16"))
        assertTrue(shelf.data.contains("with_original_language=ja"))
        assertTrue(shelf.data.contains("with_origin_country=JP"))
    }

    @Test
    fun animeShelfDefaultsToAnimeGenreAndJapan() {
        val initial = MOOSABrowseFilters.initial(anime)
        assertTrue(initial.isTv)
        assertEquals("anime", initial.genreKey)
        assertEquals("JP", initial.originCountry)
    }

    @Test
    fun animeFilterWorksForBothMoviesAndSeries() {
        assertTrue(MOOSABrowseFilters.availableGenres(true).any { it.key == "anime" })
        assertTrue(MOOSABrowseFilters.availableGenres(false).any { it.key == "anime" })
        for (isTv in listOf(true, false)) {
            val choice = MOOSABrowseFilters.Selection(
                isTv = isTv, genreKey = "anime", originCountry = "JP"
            )
            val data = MOOSABrowseFilters.toRequest(anime, choice).data
            assertTrue(data.startsWith("/discover/${if (isTv) "tv" else "movie"}?"))
            assertTrue(data.contains("with_genres=16"))
            assertTrue(data.contains("with_original_language=ja"))
            assertTrue(data.contains("with_origin_country=JP"))
        }
    }

    @Test
    fun regularAnimationDoesNotGetJapaneseAnimeLanguageRestriction() {
        val data = MOOSABrowseFilters.toRequest(
            anime,
            MOOSABrowseFilters.Selection(
                isTv = false,
                genreKey = "animation"
            )
        ).data
        assertTrue(data.contains("with_genres=16"))
        assertFalse(data.contains("with_original_language=ja"))
    }

    @Test
    fun showMoreIsVisibleOnlyOnMoosaAndKnownRows() {
        assertTrue(MOOSAShowMorePolicy.shouldShow("MOOSA Hub", true))
        assertFalse(MOOSAShowMorePolicy.shouldShow("MOOSA Arabic", true))
        assertFalse(MOOSAShowMorePolicy.shouldShow("Other Repo", true))
        assertFalse(MOOSAShowMorePolicy.shouldShow("MOOSA Hub", false))
        assertFalse(MOOSAShowMorePolicy.shouldShow(null, true))
        // Back to MOOSA must re-enable the button for its valid rows.
        assertTrue(MOOSAShowMorePolicy.shouldShow("MOOSA Hub", true))
    }
}
