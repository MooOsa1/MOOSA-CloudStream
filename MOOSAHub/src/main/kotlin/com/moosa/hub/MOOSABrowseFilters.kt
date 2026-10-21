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
    data class PlatformChoice(val name: String, val providerIds: String)

    // TMDB streaming provider IDs; regional availability can change.
    val platforms = listOf(
        PlatformChoice("Netflix", "8"),
        PlatformChoice("HBO Max", "1899"),
        PlatformChoice("Disney+", "337"),
        PlatformChoice("Prime Video", "9"),
        PlatformChoice("Apple TV+", "350"),
        PlatformChoice("Hulu", "15"),
        PlatformChoice("Crunchyroll", "283"),
        PlatformChoice("Paramount+", "2303|2616")
    )
    val watchRegions = listOf(
        CountryChoice("Oman", "OM"),
        CountryChoice("UAE", "AE"),
        CountryChoice("United States", "US"),
        CountryChoice("United Kingdom", "GB")
    )
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
        SortChoice("Shelf order", "shelf"),
        SortChoice("Highest rated", "vote_average.desc"),
        SortChoice("Most popular", "popularity.desc"),
        SortChoice("Newest", "date.desc"),
        SortChoice("Oldest", "date.asc")
    )

    data class Selection(
        val isTv: Boolean,
        val genreKey: String? = null,
        val sort: String = "shelf",
        val originCountry: String = "",
        val platformNames: List<String> = emptyList(),
        // Keep the default Oman region internal; no unnecessary region control.
        val watchRegion: String = "OM",
        val genreKeys: List<String> = emptyList(),
        val yearMode: String = "any",
        val yearFrom: Int = 2018,
        val yearTo: Int = 2026,
        val minimumRating: Int = 0
    )

    fun currentYear(): Int = java.util.Calendar.getInstance(
        TimeZone.getTimeZone("UTC")
    ).get(java.util.Calendar.YEAR)

    fun selectedGenres(selection: Selection): List<String> =
        if (selection.genreKeys.isNotEmpty()) selection.genreKeys.distinct()
        else listOfNotNull(selection.genreKey)

    // Anime requires Japanese animation metadata: it cannot be OR-combined
    // with worldwide animation, which would silently mislabel results.
    fun withGenreToggle(selection: Selection, key: String?): Selection {
        if (key == null) return selection.copy(genreKey = null, genreKeys = emptyList())
        val selected = selectedGenres(selection)
        val next = when {
            key in selected -> selected - key
            key == "anime" -> listOf("anime")
            else -> (selected - "anime") + key
        }
        return selection.copy(
            genreKey = null, genreKeys = next,
            originCountry = if (key == "anime" && "anime" in next) "JP"
                else selection.originCountry
        )
    }

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
            },
            genreKeys = selection.genreKeys.filter { key ->
                availableGenres(isTv).any { it.key == key }
            }
        )

    fun toRequest(original: MainPageRequest, choice: Selection): MainPageRequest {
        val keys = selectedGenres(choice)
        val genreIds = genres.filter { it.key in keys }
            .mapNotNull { it.id(choice.isTv) }.distinct()
        val shelfOrder = when {
            "sort_by=vote_average.desc" in original.data ||
                original.data.startsWith("/movie/top_rated") ||
                original.data.startsWith("/tv/top_rated") -> "vote_average.desc"
            "sort_by=vote_count.desc" in original.data -> "vote_count.desc"
            "sort_by=primary_release_date.desc" in original.data ||
                "sort_by=first_air_date.desc" in original.data -> "date.desc"
            else -> "popularity.desc"
        }
        val selectedSort = if (choice.sort == "shelf") shelfOrder else choice.sort
        val tmdbSort = when (selectedSort) {
            "date.desc" -> if (choice.isTv) "first_air_date.desc"
                else "primary_release_date.desc"
            "date.asc" -> if (choice.isTv) "first_air_date.asc"
                else "primary_release_date.asc"
            else -> selectedSort
        }
        val params = linkedMapOf("sort_by" to tmdbSort)
        if (genreIds.isNotEmpty()) {
            params["with_genres"] = genreIds.joinToString("|")
        }
        // TMDB has no native Anime genre: animation + Japanese original
        // language separates anime from non-Japanese cartoons.
        if ("anime" in keys) {
            params["with_original_language"] = "ja"
        }
        if (choice.originCountry in countries.map { it.code } &&
            choice.originCountry.isNotEmpty()
        ) params["with_origin_country"] = choice.originCountry
        val providerIds = platforms
            .filter { it.name in choice.platformNames }
            .flatMap { it.providerIds.split("|") }
            .distinct()
        if (providerIds.isNotEmpty()) {
            params["watch_region"] = choice.watchRegion.takeIf { region ->
                watchRegions.any { it.code == region }
            } ?: "OM"
            params["with_watch_providers"] = providerIds.joinToString("|")
            params["with_watch_monetization_types"] = "flatrate"
        }

        // Without a vote floor, TMDB's top-rated sort puts obscure
        // one-vote titles first instead of meaningful highest-rated results.
        if (selectedSort == "vote_average.desc") {
            params["vote_count.gte"] = if (choice.isTv) "50" else "100"
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val nowYear = currentYear()
        val safeFrom = choice.yearFrom.coerceIn(1900, nowYear)
        val safeTo = choice.yearTo.coerceIn(safeFrom, nowYear)
        val years = when (choice.yearMode) {
            "current" -> nowYear to nowYear
            "last3" -> (nowYear - 2) to nowYear
            "last5" -> (nowYear - 4) to nowYear
            "last10" -> (nowYear - 9) to nowYear
            "range" -> safeFrom to safeTo
            else -> null
        }
        val datePrefix = if (choice.isTv) "first_air_date" else "primary_release_date"
        if (years != null) {
            params["$datePrefix.gte"] = "${years.first}-01-01"
            params["$datePrefix.lte"] =
                minOf("${years.second}-12-31", today)
            if (choice.isTv) params["include_null_first_air_dates"] = "false"
        } else if (selectedSort == "date.desc") {
            params["$datePrefix.lte"] = today
            if (choice.isTv) params["include_null_first_air_dates"] = "false"
        }
        if (choice.minimumRating in 1..10) {
            params["vote_average.gte"] = choice.minimumRating.toString()
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
