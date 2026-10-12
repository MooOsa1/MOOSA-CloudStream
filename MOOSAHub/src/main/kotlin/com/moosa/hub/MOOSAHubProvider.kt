package com.moosa.hub

import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
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

    private data class TimedMovieCache(
        val createdAt: Long,
        val targets: List<ProviderTarget>
    )

    private data class TimedSeriesCache(
        val createdAt: Long,
        val targets: Map<Pair<Int, Int>, List<ProviderTarget>>
    )

    private data class StremioSubtitleResponse(
        val subtitles: List<StremioSubtitle> = emptyList()
    )

    private data class StremioSubtitle(
        val id: String? = null,
        val url: String? = null,
        val lang: String? = null
    )

    private enum class PreferredSubtitleLanguage {
        ARABIC,
        ENGLISH,
        OTHER
    }

    private val movieCache = ConcurrentHashMap<String, TimedMovieCache>()
    private val seriesCache = ConcurrentHashMap<String, TimedSeriesCache>()

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val providerResolveTimeoutMs = 3500L
    private val providerLinksTimeoutMs = 5000L
    private val openSubtitlesTimeoutMs = 2500L

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
            .replace(Regex("[^a-z0-9]"), "")

    private fun cacheKey(title: String, year: Int?, imdbId: String?): String =
        listOf(normalizeTitle(title), year?.toString().orEmpty(), imdbId.orEmpty()).joinToString("|")

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
        return timed(2000L) {
            api.search(title, 1)?.items.orEmpty()
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
            val directUrl = timed(1000L) {
                api.getLoadUrl(SyncIdName.Imdb, imdbId)
            }
            if (!directUrl.isNullOrBlank()) {
                val direct = timed(1600L) { api.load(directUrl) }
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
            .take(3)
            .toList()

        for ((match, _) in candidates) {
            val loaded = timed(1600L) { api.load(match.url) } ?: continue
            if (
                (!wantSeries && loaded is MovieLoadResponse) ||
                (wantSeries && loaded is TvSeriesLoadResponse)
            ) {
                return loaded
            }
        }

        return null
    }

    private fun movieProviders(): List<MainAPI> = apis.filter { api ->
        api !== this &&
            api.providerType == ProviderType.DirectProvider &&
            api.supportedTypes.contains(TvType.Movie)
    }

    private fun seriesProviders(): List<MainAPI> = apis.filter { api ->
        api !== this &&
            api.providerType == ProviderType.DirectProvider &&
            api.supportedTypes.contains(TvType.TvSeries)
    }

    private suspend fun resolveMovieTargets(
        title: String,
        year: Int?,
        imdbId: String?,
        onProgress: (Int, Int) -> Unit
    ): List<ProviderTarget> {
        val key = cacheKey(title, year, imdbId)
        movieCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < cacheLifetimeMs) {
                onProgress(1, 1)
                return cached.targets
            }
            movieCache.remove(key)
        }

        val providers = movieProviders()
        val done = AtomicInteger(0)

        val targets = providers.amap { api ->
            try {
                timed(providerResolveTimeoutMs) {
                    val loaded = loadBestMatch(
                        api = api,
                        title = title,
                        year = year,
                        imdbId = imdbId,
                        wantSeries = false
                    ) as? MovieLoadResponse ?: return@timed null

                    if (loaded.dataUrl.isBlank()) return@timed null

                    ProviderTarget(
                        providerName = api.name,
                        mainUrl = api.mainUrl,
                        data = loaded.dataUrl
                    )
                }
            } finally {
                onProgress(done.incrementAndGet(), providers.size.coerceAtLeast(1))
            }
        }.filterNotNull().distinct()

        movieCache[key] = TimedMovieCache(System.currentTimeMillis(), targets)
        return targets
    }

    private suspend fun resolveSeriesTargets(
        title: String,
        year: Int?,
        imdbId: String?,
        onProgress: (Int, Int) -> Unit
    ): Map<Pair<Int, Int>, List<ProviderTarget>> {
        val key = cacheKey(title, year, imdbId)
        seriesCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < cacheLifetimeMs) {
                onProgress(1, 1)
                return cached.targets
            }
            seriesCache.remove(key)
        }

        val providers = seriesProviders()
        val done = AtomicInteger(0)

        val resolved = providers.amap { api ->
            try {
                timed(providerResolveTimeoutMs) {
                    val loaded = loadBestMatch(
                        api = api,
                        title = title,
                        year = year,
                        imdbId = imdbId,
                        wantSeries = true
                    ) as? TvSeriesLoadResponse ?: return@timed emptyList()

                    loaded.episodes.mapNotNull { episode ->
                        val season = episode.season ?: return@mapNotNull null
                        val number = episode.episode ?: return@mapNotNull null
                        if (episode.data.isBlank()) return@mapNotNull null

                        (season to number) to ProviderTarget(
                            providerName = api.name,
                            mainUrl = api.mainUrl,
                            data = episode.data
                        )
                    }
                }.orEmpty()
            } finally {
                onProgress(done.incrementAndGet(), providers.size.coerceAtLeast(1))
            }
        }.flatten()

        val targets = resolved
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.distinct() }

        seriesCache[key] = TimedSeriesCache(System.currentTimeMillis(), targets)
        return targets
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

    private fun subtitleLanguage(label: String?, langTag: String? = null): PreferredSubtitleLanguage {
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
            PreferredSubtitleLanguage.ARABIC -> 2000
            PreferredSubtitleLanguage.ENGLISH -> 1000
            PreferredSubtitleLanguage.OTHER -> 0
        }

        if (!isWeakSubtitleVariant(subtitle)) score += 120
        if ("srt" in url) score += 20
        if ("vtt" in url) score += 10
        if ("sdh" in text || "cc" in text || "hearing" in text) score -= 100
        if ("forced" in text) score -= 80
        if ("auto" in text || "machine" in text) score -= 150

        return score
    }

    private suspend fun fetchOpenSubtitles(request: HubPlaybackRequest): List<SubtitleFile> {
        val imdb = request.imdbId?.takeIf { it.startsWith("tt") } ?: return emptyList()
        val isSeries = request.season != null && request.episode != null
        val type = if (isSeries) "series" else "movie"
        val id = if (isSeries) {
            "$imdb:${request.season}:${request.episode}"
        } else {
            imdb
        }

        val response = timed(openSubtitlesTimeoutMs) {
            app.get(
                "https://opensubtitles-v3.strem.io/subtitles/$type/$id.json",
                headers = mapOf("Accept" to "application/json")
            ).parsedSafe<StremioSubtitleResponse>()
        } ?: return emptyList()

        return response.subtitles
            .asSequence()
            .mapNotNull { sub ->
                val url = sub.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                when (subtitleLanguage(sub.lang)) {
                    PreferredSubtitleLanguage.ARABIC ->
                        SubtitleFile("Arabic 1 • OpenSubtitles", url)
                    PreferredSubtitleLanguage.ENGLISH ->
                        SubtitleFile("English 1 • OpenSubtitles", url)
                    PreferredSubtitleLanguage.OTHER -> null
                }
            }
            .distinctBy { it.url }
            .take(12)
            .toList()
    }

    private fun normalizeNativeSubtitle(subtitle: SubtitleFile, source: String): SubtitleFile? {
        return when (subtitleLanguage(subtitle.lang, subtitle.langTag)) {
            PreferredSubtitleLanguage.ARABIC ->
                subtitle.copy(lang = "Arabic 2 • $source")
            PreferredSubtitleLanguage.ENGLISH ->
                subtitle.copy(lang = "English 2 • $source")
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

    private suspend fun targetsFor(
        request: HubPlaybackRequest,
        onProgress: (Int, Int) -> Unit
    ): List<ProviderTarget> {
        val season = request.season
        val episode = request.episode

        return if (season == null || episode == null) {
            resolveMovieTargets(request.title, request.year, request.imdbId, onProgress)
        } else {
            resolveSeriesTargets(
                request.title,
                request.year,
                request.imdbId,
                onProgress
            )[season to episode].orEmpty()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val request = tryParseJson<HubPlaybackRequest>(data) ?: return@coroutineScope false

        val providerTotal = if (request.season == null || request.episode == null) {
            movieProviders().size
        } else {
            seriesProviders().size
        }.coerceAtLeast(1)

        MOOSAProgress.start(providerTotal)

        val targets = targetsFor(request) { done, total ->
            MOOSAProgress.searchProgress(done, total)
        }

        if (targets.isEmpty()) {
            MOOSAProgress.finish()
            return@coroutineScope false
        }

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        val collectedSubtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())

        val openSubsJob = async {
            fetchOpenSubtitles(request)
        }

        val linksDone = AtomicInteger(0)

        targets.amap { target ->
            try {
                val api = apis.firstOrNull { candidate ->
                    candidate !== this &&
                        candidate.name == target.providerName &&
                        (target.mainUrl.isBlank() || candidate.mainUrl == target.mainUrl)
                } ?: return@amap false

                timed(providerLinksTimeoutMs) {
                    api.loadLinks(
                        target.data,
                        isCasting,
                        { subtitle ->
                            normalizeNativeSubtitle(subtitle, api.name)?.let { normalized ->
                                if (
                                    normalized.url.isNotBlank() &&
                                    seenSubtitles.add(normalized.url)
                                ) {
                                    collectedSubtitles += normalized
                                }
                            }
                        },
                        { link ->
                            val quality = inferQuality(link)
                            if (
                                quality >= 720 &&
                                link.url.isNotBlank() &&
                                seenLinks.add(link.url)
                            ) {
                                if (link.quality < 720) {
                                    link.quality = quality
                                }
                                callback(link)
                            }
                        }
                    )
                } ?: false
            } finally {
                MOOSAProgress.linksProgress(
                    linksDone.incrementAndGet(),
                    targets.size.coerceAtLeast(1)
                )
            }
        }

        MOOSAProgress.subtitles()

        openSubsJob.await().forEach { subtitle ->
            if (subtitle.url.isNotBlank() && seenSubtitles.add(subtitle.url)) {
                collectedSubtitles += subtitle
            }
        }

        collectedSubtitles
            .sortedWith(
                compareByDescending<SubtitleFile> { subtitleScore(it) }
                    .thenBy { it.lang }
            )
            .forEach(subtitleCallback)

        MOOSAProgress.finish()
        return@coroutineScope seenLinks.isNotEmpty()
    }
}
