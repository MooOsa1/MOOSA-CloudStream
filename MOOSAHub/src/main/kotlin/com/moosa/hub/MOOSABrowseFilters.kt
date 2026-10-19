package com.moosa.hub

import com.lagradost.cloudstream3.MainPageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Source-backed Show More filters. Never filter the already-loaded cards:
 * each selection becomes a real TMDB /discover/movie or /discover/tv query.
 * Keep the original home shelf until the viewer changes a filter.
 */
internal object MOOSABrowseFilters {
    data class GenreChoice(
        val key: String,
        val label: String,
        val movieId: Int? = null,
        val tvId: Int? = null
    ) {
        fun id(isTv: Boolean): Int? = if (isTv) tvId else movieId
    }

    data class CountryChoice(val name: String, val code: String)
    data class SortChoice(val name: String, val apiSort: String)

    val genres = listOf(
        GenreChoice("action", "Action", movieId = 28),
        GenreChoice("adventure", "Adventure", movieId = 12),
        GenreChoice("action_adventure", "Action & Adventure", tvId = 10759),
        GenreChoice("animation", "Animation", 16, 16),
        // Anime is Japanese-language animation, not generic worldwide cartoons.
        GenreChoice("anime", "Anime", 16, 16),
        GenreChoice("comedy", "Comedy", 35, 35),
        GenreChoice("crime", "Crime", 80, 80),
        GenreChoice("documentary", "Documentary", 99, 99),
        GenreChoice("drama", "Drama", 18, 18),
        GenreChoice("family", "Family", 10751, 10751),
        GenreChoice("fantasy", "Fantasy", movieId = 14),
        GenreChoice("horror", "Horror", movieId = 27),
        GenreChoice("mystery", "Mystery", 9648, 9648),
        GenreChoice("romance", "Romance", movieId = 10749),
        GenreChoice("science_fiction", "Science Fiction", movieId = 878),
        GenreChoice("scifi_fantasy", "Sci-Fi & Fantasy", tvId = 10765),
        GenreChoice("thriller", "Thriller", movieId = 53),
        GenreChoice("war", "War", movieId = 10752),
        GenreChoice("war_politics", "War & Politics", tvId = 10768),
        GenreChoice("western", "Western", 37, 37)
    )

    val countries = listOf(
        CountryChoice("All countries", ""),
        CountryChoice("India", "IN"),
        CountryChoice("United States", "US"),
        CountryChoice("United Kingdom", "GB"),
        CountryChoice("South Korea", "KR"),
        CountryChoice("Japan", "JP"),
        CountryChoice("Turkey", "TR"),
        CountryChoice("Spain", "ES"),
        CountryChoice("France", "FR")
    )

    val sorts = listOf(
        SortChoice("Most popular", "popularity.desc"),
        SortChoice("Highest rated", "vote_average.desc"),
        SortChoice("Most votes", "vote_count.desc"),
        SortChoice("Newest", "date.desc")
    )

    data class Selection(
        val isTv: Boolean,
        val genreKey: String? = null,
        val sort: String = "popularity.desc",
        val originCountry: String = ""
    )

    fun initial(original: MainPageRequest): Selection {
        val raw = original.data
        val isTv = raw.substringBefore("?").let {
            it.startsWith("/tv/") || it == "/discover/tv" ||
                it.startsWith("/trending/tv/")
        }
        val country = when {
            raw.contains("with_origin_country=IN") -> "IN"
            raw.contains("with_origin_country=JP") -> "JP"
            else -> ""
        }
        val anime = raw.contains("with_genres=16") &&
            raw.contains("with_original_language=ja")
        return Selection(
            isTv = isTv,
            originCountry = country,
            genreKey = if (anime) "anime" else null
        )
    }

    fun availableGenres(isTv: Boolean): List<GenreChoice> =
        genres.filter { it.id(isTv) != null }

    fun withType(selection: Selection, isTv: Boolean): Selection =
        selection.copy(
            isTv = isTv,
            genreKey = selection.genreKey?.takeIf { key ->
                availableGenres(isTv).any { it.key == key }
            }
        )

    fun toRequest(original: MainPageRequest, choice: Selection): MainPageRequest {
        val genreId = genres.firstOrNull { it.key == choice.genreKey }
            ?.id(choice.isTv)
        val selectedSort = sorts.firstOrNull { it.apiSort == choice.sort }
            ?: sorts.first()
        val params = linkedMapOf(
            "sort_by" to if (selectedSort.apiSort == "date.desc") {
                if (choice.isTv) "first_air_date.desc"
                else "primary_release_date.desc"
            } else selectedSort.apiSort
        )
        genreId?.let { params["with_genres"] = it.toString() }
        // TMDB has no native Anime genre: animation + Japanese original
        // language separates anime from non-Japanese cartoons.
        if (choice.genreKey == "anime") {
            params["with_original_language"] = "ja"
        }
        if (choice.originCountry in countries.map { it.code } &&
            choice.originCountry.isNotEmpty()
        ) params["with_origin_country"] = choice.originCountry

        // Without a vote floor, TMDB's top-rated sort puts obscure
        // one-vote titles first instead of meaningful highest-rated results.
        if (choice.sort == "vote_average.desc") {
            params["vote_count.gte"] = if (choice.isTv) "50" else "100"
        }

        if (choice.sort == "date.desc") {
            val utc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
            params[if (choice.isTv) "first_air_date.lte"
                   else "primary_release_date.lte"] = utc
            if (choice.isTv) {
                params["include_null_first_air_dates"] = "false"
            }
        }

        val type = if (choice.isTv) "tv" else "movie"
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=$value"
        }
        return MainPageRequest(
            name = original.name,
            data = "/discover/$type?$query",
            horizontalImages = original.horizontalImages
        )
    }
}
