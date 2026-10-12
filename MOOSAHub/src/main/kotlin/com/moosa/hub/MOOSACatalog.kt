package com.moosa.hub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object MOOSACatalog {
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val TMDB_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val IMAGE = "https://image.tmdb.org/t/p/w500"

        data class Page(
        @JsonProperty("results")
        val results: List<Item> = emptyList()
    )

        data class Item(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("media_type") val mediaType: String? = null
    ) {
        val displayName: String
            get() = title ?: name ?: originalTitle ?: originalName ?: ""

        val year: Int?
            get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    }

        data class AliasDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null
    )

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): String {
        return app.get(
            "$TMDB_API$path",
            params = mapOf("api_key" to TMDB_KEY) + params
        ).text
    }

    private fun Item.toResponse(api: MainAPI, forceTv: Boolean? = null): SearchResponse? {
        val contentId = id ?: return null
        val isTv = forceTv ?: (mediaType == "tv" || (name != null && title == null))
        val poster = posterPath?.let { "$IMAGE$it" }

        return if (isTv) {
            api.newTvSeriesSearchResponse(
                name = displayName,
                url = "https://www.themoviedb.org/tv/$contentId",
                type = TvType.TvSeries,
                fix = false
            ) {
                this.id = contentId
                this.posterUrl = poster
                this.year = this@toResponse.year
                this.score = Score.from10(voteAverage)
            }
        } else {
            api.newMovieSearchResponse(
                name = displayName,
                url = "https://www.themoviedb.org/movie/$contentId",
                type = TvType.Movie,
                fix = false
            ) {
                this.id = contentId
                this.posterUrl = poster
                this.year = this@toResponse.year
                this.score = Score.from10(voteAverage)
            }
        }
    }

    private suspend fun list(
        api: MainAPI,
        path: String,
        page: Int,
        forceTv: Boolean,
        extra: Map<String, String> = emptyMap()
    ): List<SearchResponse> {
        val response = tryParseJson<Page>(
            get(
                path,
                mapOf(
                    "page" to page.toString(),
                    "language" to "ar-SA",
                    "include_adult" to "false"
                ) + extra
            )
        ) ?: return emptyList()

        return response.results.mapNotNull { it.toResponse(api, forceTv) }
    }

    suspend fun home(api: MainAPI, page: Int): HomePageResponse = coroutineScope {
        val trendingMovies = async {
            list(api, "/trending/movie/week", page, false)
        }
        val trendingSeries = async {
            list(api, "/trending/tv/week", page, true)
        }
        val arabicMovies = async {
            list(
                api,
                "/discover/movie",
                page,
                false,
                mapOf(
                    "with_original_language" to "ar",
                    "sort_by" to "popularity.desc"
                )
            )
        }
        val arabicSeries = async {
            list(
                api,
                "/discover/tv",
                page,
                true,
                mapOf(
                    "with_original_language" to "ar",
                    "sort_by" to "popularity.desc"
                )
            )
        }
        val indianMovies = async {
            list(
                api,
                "/discover/movie",
                page,
                false,
                mapOf(
                    "with_origin_country" to "IN",
                    "sort_by" to "popularity.desc"
                )
            )
        }
        val indianSeries = async {
            list(
                api,
                "/discover/tv",
                page,
                true,
                mapOf(
                    "with_origin_country" to "IN",
                    "sort_by" to "popularity.desc"
                )
            )
        }
        val topMovies = async {
            list(api, "/movie/top_rated", page, false, mapOf("region" to "US"))
        }
        val topSeries = async {
            list(api, "/tv/top_rated", page, true)
        }

        newHomePageResponse(
            listOf(
                HomePageList("أفلام رائجة", trendingMovies.await()),
                HomePageList("مسلسلات رائجة", trendingSeries.await()),
                HomePageList("أفلام عربية", arabicMovies.await()),
                HomePageList("مسلسلات عربية", arabicSeries.await()),
                HomePageList("أفلام هندية", indianMovies.await()),
                HomePageList("مسلسلات هندية", indianSeries.await()),
                HomePageList("أفضل الأفلام", topMovies.await()),
                HomePageList("أفضل المسلسلات", topSeries.await())
            ).filter { it.list.isNotEmpty() }
        )
    }

    suspend fun search(api: MainAPI, query: String, page: Int): SearchResponseList? {
        val data = tryParseJson<Page>(
            get(
                "/search/multi",
                mapOf(
                    "query" to query,
                    "page" to page.toString(),
                    "language" to "ar-SA",
                    "include_adult" to "false"
                )
            )
        ) ?: return null

        return data.results
            .mapNotNull { item ->
                if (item.mediaType == "person") null else item.toResponse(api)
            }
            .toNewSearchResponseList()
    }

    suspend fun aliases(
        tmdbId: Int?,
        isTv: Boolean,
        fallback: String
    ): List<String> {
        val id = tmdbId ?: return listOf(fallback)
        val type = if (isTv) "tv" else "movie"

        val detail = try {
            tryParseJson<AliasDetail>(
                get("/$type/$id", mapOf("language" to "ar-SA"))
            )
        } catch (_: Throwable) {
            null
        }

        return listOfNotNull(
            fallback,
            detail?.title,
            detail?.name,
            detail?.originalTitle,
            detail?.originalName
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(4)
    }
}
