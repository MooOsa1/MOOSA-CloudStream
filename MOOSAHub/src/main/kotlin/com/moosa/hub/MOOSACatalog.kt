package com.moosa.hub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mainPageOf
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
    private const val PAGES_PER_BATCH = 1

        data class Page(
        @JsonProperty("results")
        val results: List<Item> = emptyList(),
        @JsonProperty("total_pages")
        val totalPages: Int = 1
    )

    private data class CatalogPage(
        val items: List<SearchResponse>,
        val totalPages: Int
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

    private suspend fun listPage(
        api: MainAPI,
        path: String,
        page: Int,
        forceTv: Boolean,
        extra: Map<String, String> = emptyMap()
    ): CatalogPage {
        val response = tryParseJson<Page>(
            get(
                path,
                mapOf(
                    "page" to page.toString(),
                    "language" to "en-US",
                    "include_adult" to "false"
                ) + extra
            )
        ) ?: return CatalogPage(emptyList(), 1)

        return CatalogPage(
            items = response.results.mapNotNull { it.toResponse(api, forceTv) },
            totalPages = response.totalPages.coerceAtLeast(1)
        )
    }

    val categories = mainPageOf(
        "/trending/movie/week" to "Trending Movies",
        "/trending/tv/week" to "Trending Series",
        "/discover/movie?with_original_language=ar&sort_by=popularity.desc" to "Arabic Movies",
        "/discover/tv?with_original_language=ar&sort_by=popularity.desc" to "Arabic Series",
        "/discover/movie?with_origin_country=IN&sort_by=vote_average.desc&vote_count.gte=200" to "Top Indian Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated Series"
    )

    suspend fun home(
        api: MainAPI,
        page: Int,
        request: MainPageRequest,
        showMoreButton: Boolean = true
    ): HomePageResponse = coroutineScope {
        val path = request.data.substringBefore("?")
        val query = request.data.substringAfter("?", "")
        val extras = query.split("&").filter { "=" in it }.associate {
            val parts = it.split("=", limit = 2)
            parts[0] to parts[1]
        }
        val isTv = path.contains("/tv/") || path == "/discover/tv"
        val options = if (path == "/movie/top_rated") {
            extras + ("region" to "US")
        } else {
            extras
        }

        val firstTmdbPage = ((page.coerceAtLeast(1) - 1) * PAGES_PER_BATCH) + 1
        val first = listPage(api, path, firstTmdbPage, isTv, options)
        val maxPage = minOf(first.totalPages, 500)

        if (first.items.isEmpty() || firstTmdbPage > maxPage) {
            return@coroutineScope newHomePageResponse(
                request,
                emptyList(),
                hasNext = false
            )
        }

        val extraPages = ((firstTmdbPage + 1)..minOf(
            firstTmdbPage + PAGES_PER_BATCH - 1,
            maxPage
        )).map { tmdbPage ->
            async {
                listPage(api, path, tmdbPage, isTv, options)
            }
        }

        val allItems = buildList {
            addAll(first.items)
            extraPages.forEach { deferred ->
                addAll(deferred.await().items)
            }
        }.distinctBy { it.url }

        val lastFetchedPage = minOf(
            firstTmdbPage + PAGES_PER_BATCH - 1,
            maxPage
        )

        val canLoadMore =
            allItems.isNotEmpty() && lastFetchedPage < maxPage

        if (
            showMoreButton &&
            page == 1 &&
            canLoadMore &&
            api is MOOSAHubProvider
        ) {
            MOOSASeeAll.register(
                api = api,
                request = request,
                items = allItems,
                hasNext = true,
                horizontal = false
            )
        }

        newHomePageResponse(
            request,
            allItems,
            hasNext = if (showMoreButton && page == 1) {
                false
            } else {
                canLoadMore
            }
        )
    }

    suspend fun search(api: MainAPI, query: String, page: Int): SearchResponseList? {
        val data = tryParseJson<Page>(
            get(
                "/search/multi",
                mapOf(
                    "query" to query,
                    "page" to page.toString(),
                    "language" to "en-US",
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

        val details = listOf("en-US", "ar-SA").mapNotNull { language ->
            try {
                tryParseJson<AliasDetail>(
                    get("/$type/$id", mapOf("language" to language))
                )
            } catch (_: Throwable) {
                null
            }
        }

        return buildList {
            add(fallback)
            details.forEach { detail ->
                listOfNotNull(
                    detail.title,
                    detail.name,
                    detail.originalTitle,
                    detail.originalName
                ).forEach(::add)
            }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(6)
    }
}
