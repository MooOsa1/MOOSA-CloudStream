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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

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

    private val movieCache = ConcurrentHashMap<String, TimedMovieCache>()
    private val seriesCache = ConcurrentHashMap<String, TimedSeriesCache>()

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val providerResolveTimeoutMs = 3500L
    private val providerLinksTimeoutMs = 5000L

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
        // Opening the details page stays fast. Provider searching starts only after Play.
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

    private fun isArabicSubtitle(subtitle: SubtitleFile): Boolean {
        val text = subtitle.lang.lowercase()
        val tag = subtitle.langTag?.lowercase().orEmpty()
        return tag == "ar" ||
            tag.startsWith("ar-") ||
            text == "ar" ||
            text.contains("arab") ||
            text.contains("عرب")
    }

    private fun isWeakArabicVariant(subtitle: SubtitleFile): Boolean {
        val text = subtitle.lang.lowercase()
        return "sdh" in text ||
            "cc" in text ||
            "hearing" in text ||
            "forced" in text ||
            "auto" in text ||
            "machine" in text
    }

    private fun subtitleScore(subtitle: SubtitleFile): Int {
        val text = subtitle.lang.lowercase()
        val url = subtitle.url.lowercase()
        var score = if (isArabicSubtitle(subtitle)) 1000 else 0

        if (!isWeakArabicVariant(subtitle)) score += 120
        if ("srt" in url) score += 20
        if ("vtt" in url) score += 10
        if ("sdh" in text || "cc" in text || "hearing" in text) score -= 80
        if ("forced" in text) score -= 60
        if ("auto" in text || "machine" in text) score -= 120

        return score
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

    private fun qualityRank(quality: Int): Int = when {
        quality >= 2160 -> 4000
        quality == 1080 -> 3000
        quality == 1440 -> 2000
        quality >= 720 -> 1000 + quality
        else -> 0
    }

    private suspend fun targetsFor(request: HubPlaybackRequest): List<ProviderTarget> {
        val season = request.season
        val episode = request.episode

        return if (season == null || episode == null) {
            resolveMovieTargets(request.title, request.year, request.imdbId)
        } else {
            resolveSeriesTargets(request.title, request.year, request.imdbId)[season to episode].orEmpty()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val request = tryParseJson<HubPlaybackRequest>(data) ?: return false
        val targets = targetsFor(request)
        if (targets.isEmpty()) return false

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        val links = Collections.synchronizedList(mutableListOf<Pair<ExtractorLink, Int>>())
        val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())

        targets.amap { target ->
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
                        val quality = inferQuality(link)
                        // Never expose anything below 720p.
                        if (quality >= 720 && link.url.isNotBlank() && seenLinks.add(link.url)) {
                            links += link to quality
                        }
                    }
                )
            } ?: false
        }

        // Arabic first. Normal Arabic beats SDH/CC/forced/auto variants.
        subtitles
            .sortedWith(
                compareByDescending<SubtitleFile> { isArabicSubtitle(it) }
                    .thenByDescending { subtitleScore(it) }
            )
            .forEach { subtitle ->
                val normalized = if (isArabicSubtitle(subtitle)) {
                    subtitle.copy(lang = "Arabic")
                } else {
                    subtitle
                }
                subtitleCallback(normalized)
            }

        // Requested priority inside MOOSA Hub: 4K, then FHD/1080p, then the rest (all >=720p).
        links
            .sortedWith(
                compareByDescending<Pair<ExtractorLink, Int>> { (_, quality) -> qualityRank(quality) }
                    .thenByDescending { (_, quality) -> quality }
            )
            .forEach { (link, _) -> callback(link) }

        return links.isNotEmpty()
    }
}
