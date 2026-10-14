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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object MOOSACatalog {
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val TMDB_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private const val IMAGE = "https://image.tmdb.org/t/p/w500"
    private const val PAGES_PER_BATCH = 1
    private val artworkSemaphore = Semaphore(4)

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

    private data class TmdbImages(
        @JsonProperty("posters")
        val posters: List<TmdbImage> = emptyList(),
        @JsonProperty("backdrops")
        val backdrops: List<TmdbImage> = emptyList()
    )

    private data class TmdbImage(
        @JsonProperty("file_path")
        val filePath: String? = null,
        @JsonProperty("vote_average")
        val voteAverage: Double? = null,
        @JsonProperty("iso_639_1")
        val language: String? = null
    )

        data class Item(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("vote_count") val voteCount: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null
    ) {
        // Use familiar English catalog titles; keep Arabic productions in Arabic.
        // Original credited names are search aliases, not display names.
        val displayName: String
            get() = (if (originalLanguage == "ar") {
                sequenceOf(originalTitle, originalName, title, name)
            } else {
                sequenceOf(title, name, originalTitle, originalName)
            }).filterNotNull().map(String::trim)
                .firstOrNull(String::isNotBlank).orEmpty()

        val year: Int?
            get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    }

        data class AliasDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null
    )

    private data class AlternativeTitles(
        @JsonProperty("titles")
        val titles: List<AlternativeTitle> = emptyList(),
        @JsonProperty("results")
        val results: List<AlternativeTitle> = emptyList()
    )

    private data class AlternativeTitle(
        @JsonProperty("title")
        val title: String? = null
    )

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): String {
        return app.get(
            "$TMDB_API$path",
            params = mapOf("api_key" to TMDB_KEY) + params
        ).text
    }

    private fun Item.toResponse(
        api: MainAPI,
        forceTv: Boolean? = null
    ): SearchResponse? {
        val contentId = id ?: return null
        val titleText =
            displayName.trim().takeIf { it.isNotBlank() }
                ?: return null

        val isTv =
            forceTv ?: (
                mediaType == "tv" ||
                    (name != null && title == null)
                )

        val artworkPath =
            posterPath?.takeIf { it.isNotBlank() }
                ?: backdropPath?.takeIf { it.isNotBlank() }

        val poster =
            artworkPath?.let { "$IMAGE$it" }

        val reliableScore =
            voteAverage
                ?.takeIf {
                    it > 0.0 &&
                        it <= 10.0 &&
                        (voteCount ?: 0) > 0
                }
                ?.let(Score::from10)

        return if (isTv) {
            api.newTvSeriesSearchResponse(
                name = titleText,
                url =
                    "https://www.themoviedb.org/tv/$contentId",
                type = TvType.TvSeries,
                fix = false
            ) {
                this.id = contentId
                this.posterUrl = poster
                this.year = this@toResponse.year
                this.score = reliableScore
            }
        } else {
            api.newMovieSearchResponse(
                name = titleText,
                url =
                    "https://www.themoviedb.org/movie/$contentId",
                type = TvType.Movie,
                fix = false
            ) {
                this.id = contentId
                this.posterUrl = poster
                this.year = this@toResponse.year
                this.score = reliableScore
            }
        }
    }

    private suspend fun enrichArtwork(
        item: Item,
        forceTv: Boolean
    ): Item {
        val preferredLanguage =
            item.originalLanguage
                ?.lowercase()
                ?.takeIf {
                    it == "ar" ||
                        it == "en"
                }

        val shouldResolveImages =
            item.posterPath.isNullOrBlank() ||
                item.backdropPath.isNullOrBlank() ||
                preferredLanguage == "ar"

        if (!shouldResolveImages) {
            return item
        }

        val id = item.id ?: return item

        val isTv =
            forceTv ||
                item.mediaType == "tv" ||
                (
                    item.name != null &&
                        item.title == null
                    )

        val type =
            if (isTv) "tv" else "movie"

        return artworkSemaphore.withPermit {
            val imageLanguages =
                buildList {
                    preferredLanguage
                        ?.let(::add)
                    add("null")
                    if (
                        preferredLanguage != "en"
                    ) {
                        add("en")
                    }
                }
                    .distinct()
                    .joinToString(",")

            val images = try {
                tryParseJson<TmdbImages>(
                    get(
                        "/$type/$id/images",
                        mapOf(
                            "include_image_language" to
                                imageLanguages
                        )
                    )
                )
            } catch (_: Throwable) {
                null
            } ?: return@withPermit item

            fun languageRank(
                language: String?
            ): Int =
                when {
                    preferredLanguage != null &&
                        language.equals(
                            preferredLanguage,
                            ignoreCase = true
                        ) -> 4

                    language == null -> 3

                    language.equals(
                        "en",
                        ignoreCase = true
                    ) -> 2

                    else -> 1
                }

            val poster =
                images.posters
                    .asSequence()
                    .filter {
                        !it.filePath.isNullOrBlank()
                    }
                    .sortedWith(
                        compareByDescending<TmdbImage> {
                            languageRank(
                                it.language
                            )
                        }.thenByDescending {
                            it.voteAverage ?: 0.0
                        }
                    )
                    .mapNotNull {
                        it.filePath
                    }
                    .firstOrNull()
                    ?: item.posterPath

            val backdrop =
                images.backdrops
                    .asSequence()
                    .filter {
                        !it.filePath.isNullOrBlank()
                    }
                    .sortedWith(
                        compareByDescending<TmdbImage> {
                            languageRank(
                                it.language
                            )
                        }.thenByDescending {
                            it.voteAverage ?: 0.0
                        }
                    )
                    .mapNotNull {
                        it.filePath
                    }
                    .firstOrNull()
                    ?: item.backdropPath

            item.copy(
                posterPath = poster,
                backdropPath = backdrop
            )
        }
    }

    private suspend fun listPage(
        api: MainAPI,
        path: String,
        page: Int,
        forceTv: Boolean,
        extra: Map<String, String> = emptyMap()
    ): CatalogPage {
        val body = try {
            get(
                path,
                mapOf(
                    "page" to page.toString(),
                    "language" to "en-US",
                    "include_adult" to "false"
                ) + extra
            )
        } catch (_: Throwable) {
            return CatalogPage(emptyList(), 1)
        }

        val response =
            tryParseJson<Page>(body)
                ?: return CatalogPage(emptyList(), 1)

        val enriched = coroutineScope {
            response.results.map { item ->
                async {
                    enrichArtwork(item, forceTv)
                }
            }.awaitAll()
        }

        return CatalogPage(
            items = enriched.mapNotNull { item ->
                val hasArtwork =
                    !item.posterPath.isNullOrBlank() ||
                        !item.backdropPath.isNullOrBlank()

                if (hasArtwork) {
                    item.toResponse(api, forceTv)
                } else {
                    null
                }
            },
            totalPages =
                response.totalPages.coerceAtLeast(1)
        )
    }

    val categories = mainPageOf(
        "/trending/movie/week" to "Trending Movies",
        "/trending/tv/week" to "Trending Series",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated Series",
        "/discover/movie?with_original_language=ar&sort_by=popularity.desc" to "Arabic Movies",
        "/discover/tv?with_original_language=ar&sort_by=popularity.desc" to "Arabic Series",
        "/discover/movie?with_origin_country=IN&sort_by=vote_average.desc&vote_count.gte=200" to "Top Indian Movies"
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
            api is MOOSAHubProvider
        ) {
            MOOSASeeAll.register(
                api = api,
                request = request,
                items = allItems,
                hasNext = canLoadMore,
                horizontal = request.horizontalImages
            )
        }

        newHomePageResponse(
            request,
            allItems,
            hasNext = canLoadMore
        )
    }

    data class BrowseBatch(
        val items: List<SearchResponse>,
        val lastPage: Int,
        val hasNext: Boolean
    )

    suspend fun browseBatch(
        api: MainAPI,
        request: MainPageRequest,
        startPage: Int,
        pageCount: Int
    ): BrowseBatch = coroutineScope {
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

        val safeStart = startPage.coerceAtLeast(1)
        val first = listPage(api, path, safeStart, isTv, options)
        val maxPage = minOf(first.totalPages, 500)

        if (first.items.isEmpty() || safeStart > maxPage) {
            return@coroutineScope BrowseBatch(
                items = emptyList(),
                lastPage = safeStart,
                hasNext = false
            )
        }

        val lastPage = minOf(
            safeStart + pageCount.coerceAtLeast(1) - 1,
            maxPage
        )

        val rest = if (lastPage > safeStart) {
            ((safeStart + 1)..lastPage).map { tmdbPage ->
                async {
                    listPage(api, path, tmdbPage, isTv, options)
                }
            }
        } else {
            emptyList()
        }

        val items = buildList {
            addAll(first.items)
            rest.forEach { deferred ->
                addAll(deferred.await().items)
            }
        }.distinctBy { it.url }

        BrowseBatch(
            items = items,
            lastPage = lastPage,
            hasNext = lastPage < maxPage
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
    ): List<String> = coroutineScope {
        val id =
            tmdbId ?: return@coroutineScope listOf(
                fallback
            )

        val type =
            if (isTv) "tv" else "movie"

        val detailJobs =
            listOf(
                "en-US",
                "ar-SA",
                "ar",
                "en"
            ).map { language ->
                async {
                    try {
                        tryParseJson<AliasDetail>(
                            get(
                                "/$type/$id",
                                mapOf(
                                    "language" to language
                                )
                            )
                        )
                    } catch (_: Throwable) {
                        null
                    }
                }
            }

        val alternativeJob = async {
            try {
                tryParseJson<AlternativeTitles>(
                    get(
                        "/$type/$id/alternative_titles"
                    )
                )
            } catch (_: Throwable) {
                null
            }
        }

        val details =
            detailJobs.mapNotNull {
                it.await()
            }

        val alternatives =
            alternativeJob.await()

        buildList {
            // Put original credited names first. Localized titles are
            // secondary aliases strictly for finding pages on providers.
            details.forEach { detail ->
                listOfNotNull(
                    detail.originalTitle,
                    detail.originalName
                ).forEach(::add)
            }
            add(fallback)

            details.forEach { detail ->
                listOfNotNull(
                    detail.title,
                    detail.name
                ).forEach(::add)
            }

            alternatives
                ?.let {
                    it.titles + it.results
                }
                ?.mapNotNull {
                    it.title
                }
                ?.forEach(::add)
        }
            .map {
                it.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinctBy {
                it.lowercase()
            }
            .take(14)
    }
}
