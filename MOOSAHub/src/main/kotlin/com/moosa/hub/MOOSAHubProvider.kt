package com.moosa.hub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MOOSAHubProvider : TmdbProvider() {
    override var name = "MOOSA Hub"
    override var lang = "ar"

    override val useMetaLoadResponse = true
    override val apiName = "MOOSA Hub"
    override val disableSeasonZero = true
    override val includeAdult = false

    data class ProviderTarget(
        val providerName: String,
        val mainUrl: String,
        val data: String
    )

    data class HubPlaybackRequest(
        val title: String,
        val year: Int? = null,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    private data class CachedTarget(
        val createdAt: Long,
        val target: ProviderTarget
    )

    private data class StremioSubtitleResponse(
        val subtitles: List<StremioSubtitle> = emptyList()
    )

    private data class StremioSubtitle(
        val id: String? = null,
        val url: String? = null,
        val lang: String? = null
    )

    private data class LegacySubtitleResult(
        val result: LegacySubtitleList? = null
    )

    private data class LegacySubtitleList(
        val all: List<StremioSubtitle> = emptyList()
    )

    private data class TmdbHomePage(
        @JsonProperty("results")
        val results: List<TmdbHomeItem> = emptyList()
    )

    private data class TmdbHomeItem(
        @JsonProperty("id")
        val id: Int? = null,
        @JsonProperty("title")
        val title: String? = null,
        @JsonProperty("original_title")
        val originalTitle: String? = null,
        @JsonProperty("name")
        val name: String? = null,
        @JsonProperty("original_name")
        val originalName: String? = null,
        @JsonProperty("poster_path")
        val posterPath: String? = null,
        @JsonProperty("release_date")
        val releaseDate: String? = null,
        @JsonProperty("first_air_date")
        val firstAirDate: String? = null,
        @JsonProperty("vote_average")
        val voteAverage: Double? = null
    ) {
        val displayTitle: String
            get() = title ?: name ?: originalTitle ?: originalName ?: ""

        val year: Int?
            get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    }

    private enum class PreferredSubtitleLanguage {
        ARABIC,
        ENGLISH,
        OTHER
    }

    private val targetCache = ConcurrentHashMap<String, CachedTarget>()
    private val providerHealth = ConcurrentHashMap<String, Int>()

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val providerResolveTimeoutMs = 8000L
    private val providerLinksTimeoutMs = 6500L
    private val subtitleTimeoutMs = 2200L

    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiUrl = "https://api.themoviedb.org/3"

    private suspend fun <T> timed(
        timeoutMs: Long,
        block: suspend () -> T
    ): T? = withTimeoutOrNull(timeoutMs) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logError(error)
            null
        }
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase()
            .replace("&", "and")
            .filter { it.isLetterOrDigit() }

    private fun resultYear(result: SearchResponse): Int? = when (result) {
        is MovieSearchResponse -> result.year
        is TvSeriesSearchResponse -> result.year
        else -> null
    }

    private fun matchScore(
        result: SearchResponse,
        wantedTitle: String,
        wantedYear: Int?
    ): Int {
        val normalized = normalizeTitle(result.name)
        if (normalized.isBlank() || wantedTitle.isBlank()) return 0

        val titleScore = when {
            normalized == wantedTitle -> 100
            wantedTitle.length >= 5 &&
                (normalized.contains(wantedTitle) || wantedTitle.contains(normalized)) -> 65
            else -> 0
        }
        if (titleScore == 0) return 0

        val year = resultYear(result)
        return when {
            wantedYear != null && year != null && wantedYear == year -> titleScore + 25
            wantedYear != null && year != null && wantedYear != year -> titleScore - 45
            else -> titleScore
        }
    }

    private suspend fun searchProvider(api: MainAPI, title: String): List<SearchResponse> {
        val paged = timed(2600L) {
            api.search(title, 1)?.items.orEmpty()
        }.orEmpty()

        if (paged.isNotEmpty()) return paged

        return timed(2600L) {
            api.search(title).orEmpty()
        }.orEmpty()
    }

    private suspend fun loadBestMatch(
        api: MainAPI,
        title: String,
        year: Int?,
        imdbId: String?,
        wantSeries: Boolean
    ): LoadResponse? {
        if (!imdbId.isNullOrBlank() && api.supportedSyncNames.contains(SyncIdName.Imdb)) {
            val directUrl = timed(1200L) {
                api.getLoadUrl(SyncIdName.Imdb, imdbId)
            }
            if (!directUrl.isNullOrBlank()) {
                val direct = timed(2200L) { api.load(directUrl) }
                if (
                    (!wantSeries && direct is MovieLoadResponse) ||
                    (wantSeries && direct is TvSeriesLoadResponse)
                ) {
                    return direct
                }
            }
        }

        val wantedTitle = normalizeTitle(title)
        val candidates = searchProvider(api, title)
            .asSequence()
            .filter { result ->
                if (wantSeries) {
                    result is TvSeriesSearchResponse
                } else {
                    result is MovieSearchResponse
                }
            }
            .map { it to matchScore(it, wantedTitle, year) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(5)
            .toList()

        for ((match, _) in candidates) {
            val loaded = timed(2200L) { api.load(match.url) } ?: continue
            if (
                (!wantSeries && loaded is MovieLoadResponse) ||
                (wantSeries && loaded is TvSeriesLoadResponse)
            ) {
                return loaded
            }
        }

        return null
    }

    private fun playbackProviders(request: HubPlaybackRequest): List<MainAPI> {
        val isSeries = request.season != null && request.episode != null

        val providers = apis.filter { api ->
            if (api === this || api.providerType != ProviderType.DirectProvider) {
                return@filter false
            }

            if (isSeries) {
                api.supportedTypes.any {
                    it == TvType.TvSeries ||
                        it == TvType.Anime ||
                        it == TvType.AsianDrama ||
                        it == TvType.Cartoon ||
                        it == TvType.OVA ||
                        it == TvType.Documentary
                }
            } else {
                api.supportedTypes.any {
                    it == TvType.Movie ||
                        it == TvType.AnimeMovie ||
                        it == TvType.Documentary ||
                        it == TvType.Video
                }
            }
        }

        return providers.sortedByDescending { providerHealth[it.name] ?: 0 }
    }

    private fun providerCacheKey(
        request: HubPlaybackRequest,
        api: MainAPI
    ): String = listOf(
        request.imdbId.orEmpty(),
        normalizeTitle(request.title),
        request.year?.toString().orEmpty(),
        request.season?.toString().orEmpty(),
        request.episode?.toString().orEmpty(),
        api.name,
        api.mainUrl
    ).joinToString("|")

    private suspend fun resolveTargetForProvider(
        request: HubPlaybackRequest,
        api: MainAPI
    ): ProviderTarget? {
        val cacheKey = providerCacheKey(request, api)
        targetCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < cacheLifetimeMs) {
                return cached.target
            }
            targetCache.remove(cacheKey)
        }

        val isSeries = request.season != null && request.episode != null
        val loaded = timed(providerResolveTimeoutMs) {
            loadBestMatch(
                api = api,
                title = request.title,
                year = request.year,
                imdbId = request.imdbId,
                wantSeries = isSeries
            )
        } ?: return null

        val data = if (!isSeries) {
            (loaded as? MovieLoadResponse)?.dataUrl
        } else {
            val series = loaded as? TvSeriesLoadResponse ?: return null
            val season = request.season ?: return null
            val episode = request.episode ?: return null

            val exact = series.episodes.firstOrNull {
                it.season == season && it.episode == episode
            }

            val tolerant = if (exact == null && season == 1) {
                series.episodes.firstOrNull {
                    it.episode == episode &&
                        (it.season == null || it.season == 1)
                }
            } else {
                null
            }

            (exact ?: tolerant)?.data
        }?.takeIf { it.isNotBlank() } ?: return null

        val target = ProviderTarget(
            providerName = api.name,
            mainUrl = api.mainUrl,
            data = data
        )

        targetCache[cacheKey] = CachedTarget(
            createdAt = System.currentTimeMillis(),
            target = target
        )

        return target
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = super.load(url) ?: return null
        val imdbId = base.getImdbId()

        return when (base) {
            is MovieLoadResponse -> {
                base.dataUrl = HubPlaybackRequest(
                    title = base.name,
                    year = base.year,
                    imdbId = imdbId
                ).toJson()
                base
            }

            is TvSeriesLoadResponse -> {
                base.episodes.forEach { episode ->
                    episode.data = HubPlaybackRequest(
                        title = base.name,
                        year = base.year,
                        imdbId = imdbId,
                        season = episode.season,
                        episode = episode.episode
                    ).toJson()
                }
                base
            }

            else -> base
        }
    }

    private fun subtitleLanguage(
        label: String?,
        langTag: String? = null
    ): PreferredSubtitleLanguage {
        val text = label.orEmpty().lowercase()
        val tag = langTag.orEmpty().lowercase()

        if (
            tag == "ar" ||
            tag.startsWith("ar-") ||
            text == "ar" ||
            text == "ara" ||
            text.contains("arab") ||
            text.contains("عرب")
        ) return PreferredSubtitleLanguage.ARABIC

        if (
            tag == "en" ||
            tag.startsWith("en-") ||
            text == "en" ||
            text == "eng" ||
            text.contains("english")
        ) return PreferredSubtitleLanguage.ENGLISH

        return PreferredSubtitleLanguage.OTHER
    }

    private fun isWeakSubtitleVariant(subtitle: SubtitleFile): Boolean {
        val text = subtitle.lang.lowercase()
        return "sdh" in text ||
            "cc" in text ||
            "hearing" in text ||
            "forced" in text ||
            "auto" in text ||
            "machine" in text
    }

    private fun subtitleScore(subtitle: SubtitleFile): Int {
        val language = subtitleLanguage(subtitle.lang, subtitle.langTag)
        val text = subtitle.lang.lowercase()
        val url = subtitle.url.lowercase()

        var score = when (language) {
            PreferredSubtitleLanguage.ARABIC -> 3000
            PreferredSubtitleLanguage.ENGLISH -> 1000
            PreferredSubtitleLanguage.OTHER -> 0
        }

        if (!isWeakSubtitleVariant(subtitle)) score += 150
        if ("srt" in url) score += 25
        if ("vtt" in url) score += 15
        if ("sdh" in text || "cc" in text || "hearing" in text) score -= 120
        if ("forced" in text) score -= 100
        if ("auto" in text || "machine" in text) score -= 180

        return score
    }

    private fun normalizeExternalSubtitle(
        sub: StremioSubtitle,
        source: String
    ): SubtitleFile? {
        val url = sub.url?.takeIf { it.startsWith("http") } ?: return null

        return when (subtitleLanguage(sub.lang)) {
            PreferredSubtitleLanguage.ARABIC ->
                SubtitleFile("Arabic • $source", url)
            PreferredSubtitleLanguage.ENGLISH ->
                SubtitleFile("English • $source", url)
            PreferredSubtitleLanguage.OTHER -> null
        }
    }

    private suspend fun fetchOpenSubtitlesV3(
        request: HubPlaybackRequest
    ): List<SubtitleFile> {
        val imdb = request.imdbId?.takeIf { it.startsWith("tt") } ?: return emptyList()
        val isSeries = request.season != null && request.episode != null
        val type = if (isSeries) "series" else "movie"
        val id = if (isSeries) {
            "$imdb:${request.season}:${request.episode}"
        } else {
            imdb
        }

        val response = timed(subtitleTimeoutMs) {
            app.get(
                "https://opensubtitles-v3.strem.io/subtitles/$type/$id.json",
                headers = mapOf("Accept" to "application/json")
            ).parsedSafe<StremioSubtitleResponse>()
        } ?: return emptyList()

        return response.subtitles
            .mapNotNull { normalizeExternalSubtitle(it, "OpenSubtitles") }
            .distinctBy { it.url }
    }

    private suspend fun fetchOpenSubtitlesLegacy(
        request: HubPlaybackRequest
    ): List<SubtitleFile> {
        val imdb = request.imdbId?.takeIf { it.startsWith("tt") } ?: return emptyList()
        val query = if (
            request.season != null &&
            request.episode != null
        ) {
            "$imdb ${request.season} ${request.episode}"
        } else {
            imdb
        }

        val payload = base64Encode(
            """{"id":1,"jsonrpc":"2.0","method":"subtitles.find","params":[null,{"query":{"itemHash":"$query"}}]}""".toByteArray()
        )

        val response = timed(subtitleTimeoutMs) {
            app.get(
                "https://opensubtitles.strem.io/stremio/v1/q.json?b=$payload"
            ).parsedSafe<LegacySubtitleResult>()
        } ?: return emptyList()

        return response.result
            ?.all
            .orEmpty()
            .mapNotNull { normalizeExternalSubtitle(it, "OpenSubtitles Backup") }
            .distinctBy { it.url }
    }

    private suspend fun fetchExternalSubtitles(
        request: HubPlaybackRequest
    ): List<SubtitleFile> = coroutineScope {
        val v3 = async { fetchOpenSubtitlesV3(request) }
        val legacy = async { fetchOpenSubtitlesLegacy(request) }

        (v3.await() + legacy.await())
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)
    }

    private fun normalizeNativeSubtitle(
        subtitle: SubtitleFile,
        source: String
    ): SubtitleFile? {
        return when (subtitleLanguage(subtitle.lang, subtitle.langTag)) {
            PreferredSubtitleLanguage.ARABIC ->
                subtitle.copy(lang = "Arabic • $source")
            PreferredSubtitleLanguage.ENGLISH ->
                subtitle.copy(lang = "English • $source")
            PreferredSubtitleLanguage.OTHER -> null
        }
    }

    private fun inferQuality(link: ExtractorLink): Int {
        if (link.quality >= 720) return link.quality

        val text = "${link.name} ${link.source} ${link.url}".lowercase()
        return when {
            "2160" in text || "4k" in text || "uhd" in text -> 2160
            "1440" in text || "qhd" in text || "2k" in text -> 1440
            "1080" in text || "fhd" in text || "fullhd" in text || "full hd" in text -> 1080
            "720" in text || Regex("""\bhd\b""").containsMatchIn(text) -> 720
            else -> link.quality
        }
    }

    private fun validStreamingUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)

    private fun linkKey(link: ExtractorLink): String =
        "${link.url}|${link.referer}|${link.headers.hashCode()}"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val request = tryParseJson<HubPlaybackRequest>(data) ?: return@coroutineScope false
        val providers = playbackProviders(request)

        if (providers.isEmpty()) return@coroutineScope false

        MOOSAProgress.start(providers.size)

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        val nativeSubtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val emittedLink = AtomicBoolean(false)
        val completedProviders = AtomicInteger(0)
        val workingProviders = AtomicInteger(0)

        // Start subtitle services immediately, before provider/server discovery.
        val externalSubtitleJob = async {
            val external = fetchExternalSubtitles(request)

            // Arabic is emitted immediately when it arrives. English waits until the end.
            external
                .filter {
                    subtitleLanguage(it.lang, it.langTag) == PreferredSubtitleLanguage.ARABIC
                }
                .sortedByDescending(::subtitleScore)
                .forEach { subtitle ->
                    if (seenSubtitles.add(subtitle.url)) {
                        subtitleCallback(subtitle)
                    }
                }

            external
        }

        providers.amap { api ->
            var providerWorked = false

            try {
                val target = resolveTargetForProvider(request, api)
                    ?: return@amap false

                val hasUsableLink = AtomicBoolean(false)

                timed(providerLinksTimeoutMs) {
                    api.loadLinks(
                        target.data,
                        isCasting,
                        { subtitle ->
                            normalizeNativeSubtitle(subtitle, api.name)?.let { normalized ->
                                if (normalized.url.isNotBlank()) {
                                    nativeSubtitles += normalized
                                }
                            }
                        },
                        { link ->
                            val quality = inferQuality(link)
                            val key = linkKey(link)

                            if (
                                quality >= 720 &&
                                validStreamingUrl(link.url) &&
                                seenLinks.add(key)
                            ) {
                                hasUsableLink.set(true)
                                emittedLink.set(true)
                                callback(link)
                            }
                        }
                    )
                }

                providerWorked = hasUsableLink.get()
                if (providerWorked) {
                    workingProviders.incrementAndGet()
                    providerHealth.merge(api.name, 2) { old, add ->
                        (old + add).coerceAtMost(20)
                    }
                } else {
                    providerHealth.merge(api.name, -1) { old, add ->
                        (old + add).coerceAtLeast(-10)
                    }
                }

                providerWorked
            } catch (error: Throwable) {
                logError(error)
                providerHealth.merge(api.name, -1) { old, add ->
                    (old + add).coerceAtLeast(-10)
                }
                false
            } finally {
                MOOSAProgress.providerProgress(
                    completedProviders.incrementAndGet(),
                    providers.size,
                    workingProviders.get()
                )
            }
        }

        MOOSAProgress.subtitles()

        val externalSubtitles = externalSubtitleJob.await()

        // Native Arabic is next, then English. Everything else is discarded.
        val remainingSubtitles = (
            nativeSubtitles +
                externalSubtitles.filter {
                    subtitleLanguage(it.lang, it.langTag) == PreferredSubtitleLanguage.ENGLISH
                }
            )
            .filter {
                subtitleLanguage(it.lang, it.langTag) != PreferredSubtitleLanguage.OTHER
            }
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)

        remainingSubtitles.forEach { subtitle ->
            if (seenSubtitles.add(subtitle.url)) {
                subtitleCallback(subtitle)
            }
        }

        MOOSAProgress.finish()
        emittedLink.get()
    }

    private suspend fun tmdbHome(
        path: String,
        page: Int,
        isTv: Boolean,
        language: String = "en-US",
        extra: Map<String, String> = emptyMap()
    ): List<SearchResponse> {
        val params = buildMap {
            put("api_key", tmdbApiKey)
            put("page", page.toString())
            put("language", language)
            put("include_adult", "false")
            putAll(extra)
        }

        val data = timed(3500L) {
            app.get(
                "$tmdbApiUrl$path",
                params = params
            ).parsedSafe<TmdbHomePage>()
        } ?: return emptyList()

        return data.results.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.displayTitle.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = if (isTv) {
                "https://www.themoviedb.org/tv/$id"
            } else {
                "https://www.themoviedb.org/movie/$id"
            }
            val poster = item.posterPath?.let {
                "https://image.tmdb.org/t/p/w500$it"
            }

            if (isTv) {
                newTvSeriesSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.TvSeries,
                    fix = false
                ) {
                    this.id = id
                    this.posterUrl = poster
                    this.year = item.year
                    this.score = item.voteAverage?.let { Score.from10(it) }
                }
            } else {
                newMovieSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.Movie,
                    fix = false
                ) {
                    this.id = id
                    this.posterUrl = poster
                    this.year = item.year
                    this.score = item.voteAverage?.let { Score.from10(it) }
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = coroutineScope {
        val trendingMovies = async {
            tmdbHome("/trending/movie/week", page, false)
        }
        val trendingSeries = async {
            tmdbHome("/trending/tv/week", page, true)
        }
        val topMovies = async {
            tmdbHome("/movie/top_rated", page, false)
        }
        val topSeries = async {
            tmdbHome("/tv/top_rated", page, true)
        }
        val arabicMovies = async {
            tmdbHome(
                "/discover/movie",
                page,
                false,
                language = "ar-SA",
                extra = mapOf(
                    "with_original_language" to "ar",
                    "sort_by" to "popularity.desc"
                )
            )
        }
        val arabicSeries = async {
            tmdbHome(
                "/discover/tv",
                page,
                true,
                language = "ar-SA",
                extra = mapOf(
                    "with_original_language" to "ar",
                    "sort_by" to "popularity.desc"
                )
            )
        }
        val indianMovies = async {
            tmdbHome(
                "/discover/movie",
                page,
                false,
                extra = mapOf(
                    "with_original_language" to "hi",
                    "sort_by" to "popularity.desc"
                )
            )
        }
        val indianSeries = async {
            tmdbHome(
                "/discover/tv",
                page,
                true,
                extra = mapOf(
                    "with_original_language" to "hi",
                    "sort_by" to "popularity.desc"
                )
            )
        }

        newHomePageResponse(
            listOf(
                HomePageList("🔥 أفلام رائجة", trendingMovies.await()),
                HomePageList("🔥 مسلسلات رائجة", trendingSeries.await()),
                HomePageList("🇸🇦 أفلام عربية", arabicMovies.await()),
                HomePageList("🇸🇦 مسلسلات عربية", arabicSeries.await()),
                HomePageList("🇮🇳 أفلام هندية", indianMovies.await()),
                HomePageList("🇮🇳 مسلسلات هندية", indianSeries.await()),
                HomePageList("⭐ أفضل الأفلام", topMovies.await()),
                HomePageList("⭐ أفضل المسلسلات", topSeries.await())
            ),
            hasNext = true
        )
    }
}
