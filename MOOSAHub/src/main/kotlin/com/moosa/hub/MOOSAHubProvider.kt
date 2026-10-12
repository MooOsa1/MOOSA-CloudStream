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

    private data class WyzieSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("display") val display: String? = null,
        @JsonProperty("language") val language: String? = null
    )

    private data class CachedSubtitleList(
        val createdAt: Long,
        val items: List<SubtitleFile>
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
    private val subtitleCache = ConcurrentHashMap<String, CachedSubtitleList>()

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val subtitleCacheLifetimeMs = 15 * 60 * 1000L
    private val providerResolveTimeoutMs = 10000L
    private val providerLinksTimeoutMs = 12000L
    private val streamPlayLinksTimeoutMs = 24000L
    private val subtitleTimeoutMs = 2200L
    private val fastSubtitleTimeoutMs = 1350L
    private val linkProbeTimeoutMs = 2800L
    private val linkProbeBatchSize = 10

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
            val page = timed(2300L) {
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

        fun correctType(response: LoadResponse?): Boolean =
            (!wantSeries && response is MovieLoadResponse) ||
                (wantSeries && response is TvSeriesLoadResponse)

        if (
            !request.imdbId.isNullOrBlank() &&
            api.supportedSyncNames.contains(SyncIdName.Imdb)
        ) {
            val directUrl = timed(1200L) {
                api.getLoadUrl(SyncIdName.Imdb, request.imdbId)
            }
            if (!directUrl.isNullOrBlank()) {
                val direct = timed(3000L) { api.load(directUrl) }
                if (correctType(direct) && direct != null) {
                    val loadedImdb = direct.getImdbId()
                    val imdbMatches =
                        !loadedImdb.isNullOrBlank() &&
                            loadedImdb.equals(request.imdbId, ignoreCase = true)
                    val titleMatches =
                        loadedScore(direct, aliases, request.year) >= 90

                    if (imdbMatches || (loadedImdb.isNullOrBlank() && titleMatches)) {
                        return direct
                    }
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
            .filter { (_, score) -> score >= 90 }
            .sortedByDescending { (_, score) -> score }
            .take(3)
            .toList()

        for ((match, _) in candidates) {
            val loaded = timed(3000L) { api.load(match.url) } ?: continue
            if (!correctType(loaded)) continue

            if (loadedScore(loaded, aliases, request.year) >= 90) {
                return loaded
            }
        }

        return null
    }

    private fun playbackProviders(request: HubPlaybackRequest): List<MainAPI> {
        val disabled = MOOSASettingsStore.disabledSources()
        return apis
            .filter { api ->
                api !== this &&
                    api.providerType == ProviderType.DirectProvider &&
                    disabled.none { blocked ->
                        blocked.equals(api.name, ignoreCase = true)
                    }
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
    }

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

    private fun decodeJsonString(raw: String): String {
        return tryParseJson<String>("\"$raw\"")
            ?: raw.replace("\\n", "\n").replace("\\\"", "\"")
    }

    private suspend fun translateSynopsisToArabic(text: String): String? {
        if (text.isBlank()) return null

        val response = timed(2600L) {
            app.get(
                "https://translate.googleapis.com/translate_a/single",
                params = mapOf(
                    "client" to "gtx",
                    "sl" to "auto",
                    "tl" to "ar",
                    "dt" to "t",
                    "q" to text
                )
            ).text
        } ?: return null

        val translatedSection = response.substringBefore("]],null")
        val translated = Regex("""\["((?:\\.|[^"\\])*)",""")
            .findAll(translatedSection)
            .map { decodeJsonString(it.groupValues[1]) }
            .joinToString("")
            .trim()

        return translated.takeIf {
            it.isNotBlank() && it.any { ch -> ch in '\u0600'..'\u06FF' }
        }
    }

    private suspend fun resolveArabicSynopsis(
        tmdbId: Int?,
        isTv: Boolean,
        englishFallback: String?
    ): String? {
        val id = tmdbId ?: return null
        val type = if (isTv) "tv" else "movie"
        val cacheKey = "$type:$id"

        synopsisCache[cacheKey]?.let { return it }

        for (language in listOf("ar-SA", "ar-AE", "ar-EG", "ar")) {
            val overview = timed(1800L) {
                app.get(
                    "$tmdbApiUrl/$type/$id",
                    params = mapOf(
                        "api_key" to tmdbApiKey,
                        "language" to language
                    )
                ).parsedSafe<TmdbOverview>()?.overview
            }?.takeIf { it.isNotBlank() }

            if (!overview.isNullOrBlank()) {
                synopsisCache[cacheKey] = overview
                return overview
            }
        }

        val translated = englishFallback
            ?.takeIf { it.isNotBlank() }
            ?.let { translateSynopsisToArabic(it) }

        if (!translated.isNullOrBlank()) {
            synopsisCache[cacheKey] = translated
        }

        return translated
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = super.load(url) ?: return null
        val imdbId = base.getImdbId()
        val tmdbMatch = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url)
        val isTv = tmdbMatch?.groupValues?.getOrNull(1) == "tv"
        val tmdbId = tmdbMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

        val arabicOverview = resolveArabicSynopsis(
            tmdbId = tmdbId,
            isTv = isTv,
            englishFallback = base.plot
        )
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

    private fun currentSubtitleTimeoutMs(): Long =
        if (MOOSASettingsStore.arabicFastMode()) {
            fastSubtitleTimeoutMs
        } else {
            subtitleTimeoutMs
        }

    private fun subtitleCacheKey(
        request: HubPlaybackRequest,
        imdb: String
    ): String = listOf(
        imdb,
        request.season?.toString().orEmpty(),
        request.episode?.toString().orEmpty()
    ).joinToString("|")

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

        val response = timed(currentSubtitleTimeoutMs()) {
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

        val response = timed(currentSubtitleTimeoutMs()) {
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

    private suspend fun fetchWyzieSubtitles(
        request: HubPlaybackRequest,
        imdb: String
    ): List<SubtitleFile> {
        val key = MOOSASettingsStore.wyzieKey() ?: return emptyList()
        val params = buildMap {
            put("id", imdb)
            put("source", "all")
            put("key", key)
            request.season?.let { put("season", it.toString()) }
            request.episode?.let { put("episode", it.toString()) }
        }

        val response = timed(currentSubtitleTimeoutMs()) {
            app.get(
                "https://sub.wyzie.ru/search",
                params = params,
                headers = mapOf("Accept" to "application/json")
            ).parsedSafe<Array<WyzieSubtitle>>()
        } ?: return emptyList()

        return response.mapNotNull { item ->
            val url = item.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val lang = item.language.orEmpty()
            when (subtitleLanguage(lang, lang)) {
                PreferredSubtitleLanguage.ARABIC ->
                    SubtitleFile(item.display ?: "Arabic • Wyzie", url)
                PreferredSubtitleLanguage.ENGLISH ->
                    SubtitleFile(item.display ?: "English • Wyzie", url)
                PreferredSubtitleLanguage.OTHER -> null
            }
        }.distinctBy { it.url }
    }

    private suspend fun fetchExternalSubtitles(
        request: HubPlaybackRequest,
        onArabic: (SubtitleFile) -> Unit
    ): List<SubtitleFile> = coroutineScope {
        val imdb = resolveSubtitleImdb(request) ?: return@coroutineScope emptyList()
        val cacheKey = subtitleCacheKey(request, imdb)

        subtitleCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < subtitleCacheLifetimeMs) {
                cached.items
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
                return@coroutineScope cached.items
            }
            subtitleCache.remove(cacheKey)
        }

        val v3 = async {
            fetchOpenSubtitlesV3(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }

        val legacy = async {
            fetchOpenSubtitlesLegacy(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }

        val wyzie = async {
            fetchWyzieSubtitles(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }

        val all = (v3.await() + legacy.await() + wyzie.await())
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)

        if (all.isNotEmpty()) {
            subtitleCache[cacheKey] = CachedSubtitleList(
                createdAt = System.currentTimeMillis(),
                items = all
            )
        }

        all
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

    private fun isDownloadLabeled(link: ExtractorLink): Boolean {
        val label = "${link.name} ${link.source}".lowercase()
        return "download" in label || "تحميل" in label
    }

    private fun validStreamingUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)

    private fun linkKey(link: ExtractorLink): String =
        "${link.url}|${link.referer}|${link.headers.hashCode()}"


    private fun linksTimeoutFor(api: MainAPI): Long {
        val label = "${api.name} ${api.mainUrl}".lowercase()
        return if ("streamplay" in label || "stream play" in label) {
            streamPlayLinksTimeoutMs
        } else {
            providerLinksTimeoutMs
        }
    }


    private suspend fun verifyStreamingLink(link: ExtractorLink): Boolean {
        if (!validStreamingUrl(link.url)) return false

        return timed(linkProbeTimeoutMs) {
            val headers = link.getAllHeaders()

            when {
                link.isM3u8 -> {
                    val response = app.get(
                        link.url,
                        headers = headers,
                        referer = link.referer,
                        verify = false
                    )
                    response.isSuccessful &&
                        response.text.contains("#EXTM3U", ignoreCase = true)
                }

                link.isDash -> {
                    val response = app.get(
                        link.url,
                        headers = headers,
                        referer = link.referer,
                        verify = false
                    )
                    response.isSuccessful &&
                        response.text.contains("<MPD", ignoreCase = true)
                }

                else -> {
                    val head = app.head(
                        link.url,
                        headers = headers,
                        referer = link.referer,
                        verify = false
                    )
                    if (head.isSuccessful) {
                        val contentType =
                            head.headers["Content-Type"].orEmpty().lowercase()
                        contentType.isBlank() ||
                            (
                                !contentType.contains("text/html") &&
                                    !contentType.contains("application/json")
                                )
                    } else {
                        val ranged = app.get(
                            link.url,
                            headers = headers + mapOf("Range" to "bytes=0-1023"),
                            referer = link.referer,
                            verify = false
                        )
                        val contentType =
                            ranged.headers["Content-Type"].orEmpty().lowercase()
                        ranged.isSuccessful &&
                            !contentType.contains("text/html") &&
                            !contentType.contains("application/json")
                    }
                }
            }
        } ?: false
    }

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
            timed(1800L) {
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
        val verifiedLinks = AtomicInteger(0)

        val externalSubtitleJob = async {
            fetchExternalSubtitles(request) { subtitle ->
                if (seenSubtitles.add(subtitle.url)) {
                    subtitleCallback(subtitle)
                }
            }
        }

        val aliases = aliasesJob.await()

        val providerBatchSize = MOOSASettingsStore.providerConcurrency()

        for (batch in providers.chunked(providerBatchSize)) {
            batch.amap { api ->
                var providerWorked = false

                try {
                    val target = resolveTargetForProvider(request, api, aliases)

                    if (target != null) {
                        val candidateLinks =
                            Collections.synchronizedList(mutableListOf<ExtractorLink>())

                        timed(linksTimeoutFor(api)) {
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
                                    val acceptableQuality =
                                        quality <= 0 || quality >= 720

                                    if (
                                        acceptableQuality &&
                                        validStreamingUrl(link.url) &&
                                        !isDownloadLabeled(link)
                                    ) {
                                        candidateLinks += link
                                    }
                                }
                            )
                        }

                        val candidates = candidateLinks
                            .distinctBy(::linkKey)
                            .sortedByDescending(::inferQuality)

                        for (linkBatch in candidates.chunked(linkProbeBatchSize)) {
                            linkBatch.amap { link ->
                                val key = linkKey(link)
                                if (seenLinks.contains(key)) {
                                    return@amap false
                                }

                                val verified =
                                    !MOOSASettingsStore.verifyLinks() ||
                                        verifyStreamingLink(link)

                                if (verified && seenLinks.add(key)) {
                                    providerWorked = true
                                    emittedLink.set(true)
                                    verifiedLinks.incrementAndGet()
                                    callback(link)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }

                    if (providerWorked) {
                        providerHealth.merge(api.name, 3) { old, add ->
                            (old + add).coerceAtMost(24)
                        }
                    } else {
                        providerHealth.merge(api.name, -2) { old, add ->
                            (old + add).coerceAtLeast(-12)
                        }
                    }
                } catch (error: Throwable) {
                    logError(error)
                    providerHealth.merge(api.name, -2) { old, add ->
                        (old + add).coerceAtLeast(-12)
                    }
                } finally {
                    MOOSAProgress.providerProgress(
                        completedProviders.incrementAndGet(),
                        providers.size,
                        verifiedLinks.get()
                    )
                }

                providerWorked
            }
        }

        MOOSAProgress.serversReady(verifiedLinks.get())
        MOOSAProgress.subtitles()

        val externalSubtitles = externalSubtitleJob.await()

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

        MOOSAProgress.finish(verifiedLinks.get())
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
