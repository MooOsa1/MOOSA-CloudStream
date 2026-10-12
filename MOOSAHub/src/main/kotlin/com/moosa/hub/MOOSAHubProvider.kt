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
import com.lagradost.cloudstream3.SearchResponseList
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
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MOOSAHubProvider : TmdbProvider() {
    override var name = "MOOSA Hub"
    override var lang = "en"

    override val useMetaLoadResponse = true
    override val mainPage = MOOSACatalog.categories
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
        val tmdbId: Int? = null,
        val isTv: Boolean = false,
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

    private data class TmdbOverview(val overview: String? = null)

    private data class TmdbExternalIds(
        @JsonProperty("imdb_id")
        val imdbId: String? = null
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
    private val synopsisCache = ConcurrentHashMap<String, String>()

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val providerResolveTimeoutMs = 8000L
    private val providerLinksTimeoutMs = 6500L
    private val subtitleTimeoutMs = 2200L
    private val linkProbeTimeoutMs = 2200L
    private val maxProvidersToTry = 18
    private val desiredVerifiedLinks = 10
    private val linksPerProviderToProbe = 4

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

    private fun titleMatchScore(
        candidate: String,
        aliases: List<String>
    ): Int {
        val normalized = normalizeTitle(candidate)
        if (normalized.isBlank()) return 0

        return aliases.maxOfOrNull { alias ->
            val wanted = normalizeTitle(alias)
            if (wanted.isBlank()) {
                0
            } else when {
                normalized == wanted -> 100
                wanted.length >= 5 &&
                    (normalized.contains(wanted) || wanted.contains(normalized)) -> 65
                else -> 0
            }
        } ?: 0
    }

    private fun contentMatchScore(
        candidateName: String,
        candidateYear: Int?,
        aliases: List<String>,
        wantedYear: Int?
    ): Int {
        val titleScore = titleMatchScore(candidateName, aliases)
        if (titleScore == 0) return 0

        return when {
            wantedYear != null && candidateYear != null && wantedYear == candidateYear ->
                titleScore + 25
            wantedYear != null && candidateYear != null && wantedYear != candidateYear ->
                titleScore - 60
            else -> titleScore
        }
    }

    private fun aliasScore(
        result: SearchResponse,
        aliases: List<String>,
        year: Int?
    ): Int = contentMatchScore(
        candidateName = result.name,
        candidateYear = resultYear(result),
        aliases = aliases,
        wantedYear = year
    )

    private fun loadedScore(
        result: LoadResponse,
        aliases: List<String>,
        year: Int?
    ): Int = contentMatchScore(
        candidateName = result.name,
        candidateYear = result.year,
        aliases = aliases,
        wantedYear = year
    )

    private suspend fun searchProvider(
        api: MainAPI,
        aliases: List<String>,
        year: Int?,
        wantSeries: Boolean
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        for (alias in aliases.take(3)) {
            val page = timed(1800L) {
                api.search(alias, 1)?.items.orEmpty()
            }.orEmpty()

            results += page

            val strongMatch = page.any { result ->
                (
                    (wantSeries && result is TvSeriesSearchResponse) ||
                        (!wantSeries && result is MovieSearchResponse)
                    ) && aliasScore(result, aliases, year) >= 90
            }

            if (strongMatch) break
        }

        return results.distinctBy { it.url }
    }

    private suspend fun loadBestMatch(
        api: MainAPI,
        request: HubPlaybackRequest,
        aliases: List<String>
    ): LoadResponse? {
        val wantSeries = request.season != null && request.episode != null

        if (
            !request.imdbId.isNullOrBlank() &&
            api.supportedSyncNames.contains(SyncIdName.Imdb)
        ) {
            val directUrl = timed(1200L) {
                api.getLoadUrl(SyncIdName.Imdb, request.imdbId)
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

        val candidates = searchProvider(
            api = api,
            aliases = aliases,
            year = request.year,
            wantSeries = wantSeries
        )
            .asSequence()
            .filter { result ->
                if (wantSeries) {
                    result is TvSeriesSearchResponse
                } else {
                    result is MovieSearchResponse
                }
            }
            .map { it to aliasScore(it, aliases, request.year) }
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

    private fun playbackProviders(request: HubPlaybackRequest): List<MainAPI> =
        apis
            .filter { api ->
                api !== this && api.providerType == ProviderType.DirectProvider
            }
            .sortedWith(
                compareByDescending<MainAPI> {
                    providerHealth[it.name] ?: 0
                }.thenByDescending {
                    if (
                        !request.imdbId.isNullOrBlank() &&
                        it.supportedSyncNames.contains(SyncIdName.Imdb)
                    ) 1 else 0
                }
            )

    private fun providerCacheKey(
        request: HubPlaybackRequest,
        api: MainAPI
    ): String = listOf(
        request.imdbId.orEmpty(),
        request.tmdbId?.toString().orEmpty(),
        normalizeTitle(request.title),
        request.year?.toString().orEmpty(),
        request.season?.toString().orEmpty(),
        request.episode?.toString().orEmpty(),
        api.name,
        api.mainUrl
    ).joinToString("|")

    private suspend fun resolveTargetForProvider(
        request: HubPlaybackRequest,
        api: MainAPI,
        aliases: List<String>
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
                request = request,
                aliases = aliases
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
        val tmdbMatch = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url)
        val isTv = tmdbMatch?.groupValues?.getOrNull(1) == "tv"
        val tmdbId = tmdbMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

        val arabicOverview = tmdbId?.let { id ->
            val type = if (isTv) "tv" else "movie"
            var overview: String? = null

            for (language in listOf("ar-SA", "ar")) {
                overview = timed(2200L) {
                    app.get(
                        "$tmdbApiUrl/$type/$id",
                        params = mapOf(
                            "api_key" to tmdbApiKey,
                            "language" to language
                        )
                    ).parsedSafe<TmdbOverview>()?.overview
                }?.takeIf { it.isNotBlank() }

                if (!overview.isNullOrBlank()) break
            }

            overview
        }
        if (!arabicOverview.isNullOrBlank()) base.plot = arabicOverview

        return when (base) {
            is MovieLoadResponse -> {
                base.dataUrl = HubPlaybackRequest(
                    title = base.name,
                    year = base.year,
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    isTv = false
                ).toJson()
                base
            }

            is TvSeriesLoadResponse -> {
                base.episodes.forEach { episode ->
                    episode.data = HubPlaybackRequest(
                        title = base.name,
                        year = base.year,
                        imdbId = imdbId,
                        tmdbId = tmdbId,
                        isTv = isTv,
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

    private suspend fun resolveSubtitleImdb(
        request: HubPlaybackRequest
    ): String? {
        request.imdbId
            ?.takeIf { it.startsWith("tt") }
            ?.let { return it }

        val tmdbId = request.tmdbId ?: return null
        val type = if (request.isTv || request.season != null) "tv" else "movie"

        return timed(1800L) {
            app.get(
                "$tmdbApiUrl/$type/$tmdbId/external_ids",
                params = mapOf("api_key" to tmdbApiKey)
            ).parsedSafe<TmdbExternalIds>()
                ?.imdbId
                ?.takeIf { it.startsWith("tt") }
        }
    }

    private suspend fun fetchOpenSubtitlesV3(
        request: HubPlaybackRequest,
        imdb: String
    ): List<SubtitleFile> {
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
        request: HubPlaybackRequest,
        imdb: String
    ): List<SubtitleFile> {
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
        request: HubPlaybackRequest,
        onArabic: (SubtitleFile) -> Unit
    ): List<SubtitleFile> = coroutineScope {
        val imdb = resolveSubtitleImdb(request) ?: return@coroutineScope emptyList()

        val v3 = async {
            fetchOpenSubtitlesV3(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) == PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }
        val legacy = async {
            fetchOpenSubtitlesLegacy(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) == PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }

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

        val aliasesJob = async {
            timed(1400L) {
                MOOSACatalog.aliases(
                    tmdbId = request.tmdbId,
                    isTv = request.isTv,
                    fallback = request.title
                )
            } ?: listOf(request.title)
        }

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        val nativeSubtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val emittedLink = AtomicBoolean(false)
        val completedProviders = AtomicInteger(0)
        val workingProviders = AtomicInteger(0)

        // Start subtitle services immediately, before provider/server discovery.
        // Each Arabic source is emitted as soon as that source finishes.
        val externalSubtitleJob = async {
            fetchExternalSubtitles(request) { subtitle ->
                if (seenSubtitles.add(subtitle.url)) {
                    subtitleCallback(subtitle)
                }
            }
        }

        val aliases = aliasesJob.await()
        val providerSemaphore = Semaphore(6)

        providers.amap { api ->
            providerSemaphore.withPermit {
                var providerWorked = false

                try {
                    val target = resolveTargetForProvider(request, api, aliases)
                        ?: return@withPermit false

                val hasUsableLink = AtomicBoolean(false)

                timed(providerLinksTimeoutMs) {
                    api.loadLinks(
                        target.data,
                        isCasting,
                        { subtitle ->
                            normalizeNativeSubtitle(subtitle, api.name)?.let { normalized ->
                                if (normalized.url.isBlank()) return@let

                                when (
                                    subtitleLanguage(
                                        normalized.lang,
                                        normalized.langTag
                                    )
                                ) {
                                    PreferredSubtitleLanguage.ARABIC -> {
                                        if (seenSubtitles.add(normalized.url)) {
                                            subtitleCallback(normalized)
                                        }
                                    }

                                    PreferredSubtitleLanguage.ENGLISH -> {
                                        nativeSubtitles += normalized
                                    }

                                    PreferredSubtitleLanguage.OTHER -> Unit
                                }
                            }
                        },
                        { link ->
                            val quality = inferQuality(link)
                            val key = linkKey(link)

                            val acceptableQuality = quality <= 0 || quality >= 720

                            if (
                                acceptableQuality &&
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
        }

        MOOSAProgress.subtitles()

        val externalSubtitles = externalSubtitleJob.await()

        // Arabic has already been emitted early. Emit English last; discard other languages.
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
    ): HomePageResponse = MOOSACatalog.home(this, page, request)

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? = MOOSACatalog.search(this, query, page)
}
