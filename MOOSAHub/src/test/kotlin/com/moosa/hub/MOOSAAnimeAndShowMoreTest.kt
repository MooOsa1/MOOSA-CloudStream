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
    @Test
    fun watchPlatformFiltersUseRealTmdbProvidersWithSubscriptionRegion() {
        val selection = MOOSABrowseFilters.Selection(
            isTv = true,
            platformNames = listOf("Netflix", "HBO Max"),
            watchRegion = "OM"
        )
        val data = MOOSABrowseFilters.toRequest(anime, selection).data
        assertTrue(data.startsWith("/discover/tv?"))
        assertTrue(data.contains("watch_region=OM"))
        assertTrue(data.contains("with_watch_providers=8|1899"))
        assertTrue(data.contains("with_watch_monetization_types=flatrate"))
    }

    @Test
    fun noPlatformSelectedDoesNotInventStreamingAvailability() {
        val data = MOOSABrowseFilters.toRequest(
            anime,
            MOOSABrowseFilters.Selection(isTv = false, watchRegion = "AE")
        ).data
        assertFalse(data.contains("with_watch_providers"))
        assertFalse(data.contains("watch_region"))
        assertTrue(data.startsWith("/discover/movie?"))
    }

    @Test
    fun invalidWatchRegionFallsBackToOmanAndUnknownProvidersAreIgnored() {
        val selection = MOOSABrowseFilters.Selection(
            isTv = false,
            platformNames = listOf("Netflix", "Imaginary service"),
            watchRegion = "XX"
        )
        val data = MOOSABrowseFilters.toRequest(anime, selection).data
        assertTrue(data.contains("watch_region=OM"))
        assertTrue(data.contains("with_watch_providers=8"))
        assertFalse(data.contains("Imaginary"))
    }

    @Test
    fun customYearRangeAndRatingProduceRealTmdbFilters() {
        val data = MOOSABrowseFilters.toRequest(
            anime,
            MOOSABrowseFilters.Selection(
                isTv = false,
                yearMode = "range",
                yearFrom = 2021,
                yearTo = 2024,
                minimumRating = 8
            )
        ).data
        assertTrue(data.startsWith("/discover/movie?"))
        assertTrue(data.contains("primary_release_date.gte=2021-01-01"))
        assertTrue(data.contains("primary_release_date.lte=2024-12-31"))
        assertTrue(data.contains("vote_average.gte=8"))
    }

    @Test
    fun rollingYearPresetTracksCurrentYearWithoutHardcoding() {
        val year = MOOSABrowseFilters.currentYear()
        val data = MOOSABrowseFilters.toRequest(
            anime,
            MOOSABrowseFilters.Selection(isTv = true, yearMode = "last5")
        ).data
        assertTrue(data.contains("first_air_date.gte=" + (year - 4) + "-01-01"))
        assertTrue(data.contains("first_air_date.lte="))
        assertTrue(data.contains("include_null_first_air_dates=false"))
    }

    @Test
    fun multipleGenresUseOrWithoutAccidentallyMixingAnime() {
        val start = MOOSABrowseFilters.Selection(isTv = true)
        val crime = MOOSABrowseFilters.withGenreToggle(start, "crime")
        val drama = MOOSABrowseFilters.withGenreToggle(crime, "drama")
        assertEquals(listOf("crime", "drama"),
            MOOSABrowseFilters.selectedGenres(drama))
        val data = MOOSABrowseFilters.toRequest(anime, drama).data
        assertTrue(data.contains("with_genres=80|18"))
        assertFalse(data.contains("with_original_language=ja"))
        val animeOnly = MOOSABrowseFilters.withGenreToggle(drama, "anime")
        assertEquals(listOf("anime"), MOOSABrowseFilters.selectedGenres(animeOnly))
        val japanese = MOOSABrowseFilters.toRequest(anime, animeOnly).data
        assertTrue(japanese.contains("with_original_language=ja"))
    }

    @Test
    fun defaultShelfOrderAndOldestOrderingAreNotFake() {
        val topSeries = MainPageRequest("Top Rated Series", "/tv/top_rated", false)
        val original = MOOSABrowseFilters.toRequest(
            topSeries, MOOSABrowseFilters.Selection(isTv = true)
        ).data
        assertTrue(original.contains("sort_by=vote_average.desc"))
        val oldest = MOOSABrowseFilters.toRequest(
            topSeries,
            MOOSABrowseFilters.Selection(isTv = true, sort = "date.asc")
        ).data
        assertTrue(oldest.contains("sort_by=first_air_date.asc"))
    }

}
