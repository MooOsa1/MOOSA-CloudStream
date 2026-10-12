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
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

    data class HubPlaybackData(
        val targets: List<ProviderTarget> = emptyList()
    )

    private data class TimedMovieCache(
        val createdAt: Long,
        val targets: List<ProviderTarget>
    )

    private data class TimedSeriesCache(
        val createdAt: Long,
        val targets: Map<Pair<Int, Int>, List<ProviderTarget>>
    )

    private val movieCache = ConcurrentHashMap<String, TimedMovieCache>()
    private val seriesCache = ConcurrentHashMap<String, TimedSeriesCache>()

    private val cacheLifetimeMs = 15 * 60 * 1000L
    private val providerResolveTimeoutMs = 4500L
    private val providerLinksTimeoutMs = 6000L

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
        return timed(2500L) {
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
        // Fast + exact path for providers that support IMDb IDs.
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
            .take(3)
            .toList()

        for ((match, _) in candidates) {
            val loaded = timed(1800L) { api.load(match.url) } ?: continue
            if (
                (!wantSeries && loaded is MovieLoadResponse) ||
                (wantSeries && loaded is TvSeriesLoadResponse)
            ) {
                return loaded
            }
        }

        return null
    }

    private suspend fun resolveMovieTargets(
        title: String,
        year: Int?,
        imdbId: String?
    ): List<ProviderTarget> {
        val key = cacheKey(title, year, imdbId)
        movieCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < cacheLifetimeMs) {
                return cached.targets
            }
            movieCache.remove(key)
        }

        val providers = apis.filter { api ->
            api !== this &&
                api.providerType == ProviderType.DirectProvider &&
                api.supportedTypes.contains(TvType.Movie)
        }

        val targets = providers.amap { api ->
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
        }.filterNotNull().distinct()

        movieCache[key] = TimedMovieCache(System.currentTimeMillis(), targets)
        return targets
    }

    private suspend fun resolveSeriesTargets(
        title: String,
        year: Int?,
        imdbId: String?
    ): Map<Pair<Int, Int>, List<ProviderTarget>> {
        val key = cacheKey(title, year, imdbId)
        seriesCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < cacheLifetimeMs) {
                return cached.targets
            }
            seriesCache.remove(key)
        }

        val providers = apis.filter { api ->
            api !== this &&
                api.providerType == ProviderType.DirectProvider &&
                api.supportedTypes.contains(TvType.TvSeries)
        }

        val resolved = providers.amap { api ->
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
                val targets = resolveMovieTargets(base.name, base.year, imdbId)
                base.dataUrl = HubPlaybackData(targets).toJson()
                base
            }

            is TvSeriesLoadResponse -> {
                val targetsByEpisode = resolveSeriesTargets(base.name, base.year, imdbId)

                base.episodes.forEach { episode ->
                    val season = episode.season
                    val number = episode.episode
                    val targets = if (season != null && number != null) {
                        targetsByEpisode[season to number].orEmpty()
                    } else {
                        emptyList()
                    }
                    episode.data = HubPlaybackData(targets).toJson()
                }
                base
            }

            else -> base
        }
    }

    private fun isArabicSubtitle(subtitle: SubtitleFile): Boolean {
        val text = subtitle.lang.lowercase()
        val tag = subtitle.langTag?.lowercase().orEmpty()
        return tag == "ar" ||
            tag.startsWith("ar-") ||
            text == "ar" ||
            text.contains("arab") ||
            text.contains("عرب")
    }

    private fun subtitleScore(subtitle: SubtitleFile): Int {
        val text = subtitle.lang.lowercase()
        var score = if (isArabicSubtitle(subtitle)) 100 else 0
        if ("sdh" in text || "cc" in text || "hearing" in text) score -= 20
        if ("forced" in text) score -= 10
        return score
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = tryParseJson<HubPlaybackData>(data) ?: return false
        if (payload.targets.isEmpty()) return false

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        val emittedLink = AtomicBoolean(false)

        payload.targets.amap { target ->
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
                        if (subtitle.url.isNotBlank() && seenSubtitles.add(subtitle.url)) {
                            subtitles += subtitle
                        }
                    },
                    { link ->
                        if (link.url.isNotBlank() && seenLinks.add(link.url)) {
                            emittedLink.set(true)
                            callback(link)
                        }
                    }
                )
            } ?: false
        }

        // Arabic subtitles first, with normal Arabic preferred over SDH/forced variants.
        subtitles
            .sortedByDescending(::subtitleScore)
            .forEach { subtitle ->
                val normalized = if (isArabicSubtitle(subtitle)) {
                    subtitle.copy(lang = "Arabic")
                } else {
                    subtitle
                }
                subtitleCallback(normalized)
            }

        return emittedLink.get()
    }
}
