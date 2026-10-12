package com.moosa.hub

import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        val tmdbId: Int? = null,
        val isTv: Boolean = false,
        val season: Int? = null,
        val episode: Int? = null
    )

    private data class TimedTarget(
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

    private enum class PreferredSubtitleLanguage {
        ARABIC,
        ENGLISH,
        OTHER
    }

    private enum class LinkHealth {
        HEALTHY,
        UNKNOWN,
        DEAD
    }

    private val targetCache = ConcurrentHashMap<String, TimedTarget>()
    private val missCache = ConcurrentHashMap<String, Long>()
    private val providerHealth = ConcurrentHashMap<String, Int>()

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val missLifetimeMs = 3 * 60 * 1000L
    private val providerResolveTimeoutMs = 3200L
    private val providerLinksTimeoutMs = 4200L
    private val openSubtitlesTimeoutMs = 2200L
    private val linkProbeTimeoutMs = 1200L
    private val parallelProviders = 6

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = MOOSACatalog.home(this, page)

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? = MOOSACatalog.search(this, query, page)

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
        aliases: List<String>,
        wantedYear: Int?
    ): Int {
        val normalized = normalizeTitle(result.name)
        if (normalized.isBlank()) return 0

        val bestTitleScore = aliases.maxOfOrNull { alias ->
            val wanted = normalizeTitle(alias)
            when {
                wanted.isBlank() -> 0
                normalized == wanted -> 100
                wanted.length >= 4 &&
                    (normalized.contains(wanted) || wanted.contains(normalized)) -> 70
                else -> 0
            }
        } ?: 0

        if (bestTitleScore == 0) return 0

        val year = resultYear(result)
        return when {
            wantedYear != null && year != null && wantedYear == year -> bestTitleScore + 25
            wantedYear != null && year != null && wantedYear != year -> bestTitleScore - 35
            else -> bestTitleScore
        }
    }

    private suspend fun searchProvider(
        api: MainAPI,
        aliases: List<String>,
        year: Int?,
        wantSeries: Boolean
    ): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        for (alias in aliases.take(3)) {
            val page = timed(1100L) {
                api.search(alias, 1)?.items.orEmpty()
            }.orEmpty()

            results += page

            val hasStrongMatch = page.any { result ->
                (
                    (wantSeries && result is TvSeriesSearchResponse) ||
                    (!wantSeries && result is MovieSearchResponse)
                ) && matchScore(result, aliases, year) >= 90
            }

            if (hasStrongMatch) break
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
            val directUrl = timed(900L) {
                api.getLoadUrl(SyncIdName.Imdb, request.imdbId)
            }

            if (!directUrl.isNullOrBlank()) {
                val direct = timed(1500L) { api.load(directUrl) }
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
            .map { it to matchScore(it, aliases, request.year) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(4)
            .toList()

        for ((match, _) in candidates) {
            val loaded = timed(1500L) { api.load(match.url) } ?: continue
            if (
                (!wantSeries && loaded is MovieLoadResponse) ||
                (wantSeries && loaded is TvSeriesLoadResponse)
            ) {
                return loaded
            }
        }

        return null
    }

    private fun requestKey(request: HubPlaybackRequest): String =
        listOf(
            request.imdbId.orEmpty(),
            request.tmdbId?.toString().orEmpty(),
            request.season?.toString().orEmpty(),
            request.episode?.toString().orEmpty(),
            normalizeTitle(request.title)
        ).joinToString("|")

    private fun providerKey(api: MainAPI, request: HubPlaybackRequest): String =
        "${requestKey(request)}|${api.name}|${api.mainUrl}"

    private suspend fun resolveProviderTarget(
        api: MainAPI,
        request: HubPlaybackRequest,
        aliases: List<String>
    ): ProviderTarget? {
        val key = providerKey(api, request)
        val now = System.currentTimeMillis()

        targetCache[key]?.let { cached ->
            if (now - cached.createdAt < cacheLifetimeMs) return cached.target
            targetCache.remove(key)
        }

        missCache[key]?.let { missAt ->
            if (now - missAt < missLifetimeMs) return null
            missCache.remove(key)
        }

        val target = timed(providerResolveTimeoutMs) {
            val loaded = loadBestMatch(api, request, aliases) ?: return@timed null

            val data = if (request.season == null || request.episode == null) {
                (loaded as? MovieLoadResponse)?.dataUrl
            } else {
                (loaded as? TvSeriesLoadResponse)?.episodes
                    ?.firstOrNull { episode ->
                        episode.season == request.season &&
                            episode.episode == request.episode
                    }
                    ?.data
            } ?: return@timed null

            if (data.isBlank()) return@timed null

            ProviderTarget(
                providerName = api.name,
                mainUrl = api.mainUrl,
                data = data
            )
        }

        if (target == null) {
            missCache[key] = now
            return null
        }

        targetCache[key] = TimedTarget(now, target)
        return target
    }

    private fun candidateProviders(request: HubPlaybackRequest): List<MainAPI> {
        return apis
            .filter { api ->
                api !== this && api.providerType == ProviderType.DirectProvider
            }
            .sortedWith(
                compareByDescending<MainAPI> {
                    providerHealth["${it.name}|${it.mainUrl}"] ?: 0
                }.thenByDescending {
                    if (
                        !request.imdbId.isNullOrBlank() &&
                        it.supportedSyncNames.contains(SyncIdName.Imdb)
                    ) 1 else 0
                }
            )
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = super.load(url) ?: return null
        val imdbId = base.getImdbId()
        val match = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url)
        val isTv = match?.groupValues?.getOrNull(1) == "tv"
        val tmdbId = match?.groupValues?.getOrNull(2)?.toIntOrNull()

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

    private fun weakSubtitle(subtitle: SubtitleFile): Boolean {
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

        if (!weakSubtitle(subtitle)) score += 150
        if (url.contains(".srt")) score += 30
        if (url.contains(".vtt")) score += 15
        if ("sdh" in text || "cc" in text || "hearing" in text) score -= 120
        if ("forced" in text) score -= 100
        if ("auto" in text || "machine" in text) score -= 180
        return score
    }

    private suspend fun fetchOpenSubtitles(
        request: HubPlaybackRequest
    ): List<SubtitleFile> {
        val imdb = request.imdbId?.takeIf { it.startsWith("tt") }
            ?: return emptyList()

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

        var arIndex = 0
        var enIndex = 0

        return response.subtitles.mapNotNull { sub ->
            val url = sub.url?.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null

            when (subtitleLanguage(sub.lang)) {
                PreferredSubtitleLanguage.ARABIC -> {
                    arIndex += 1
                    SubtitleFile(
                        lang = "Arabic • OpenSubtitles #$arIndex",
                        url = url
                    )
                }

                PreferredSubtitleLanguage.ENGLISH -> {
                    enIndex += 1
                    SubtitleFile(
                        lang = "English • OpenSubtitles #$enIndex",
                        url = url
                    )
                }

                PreferredSubtitleLanguage.OTHER -> null
            }
        }.distinctBy { it.url }
            .take(16)
    }

    private fun normalizeNativeSubtitle(
        subtitle: SubtitleFile,
        source: String
    ): SubtitleFile? {
        return when (subtitleLanguage(subtitle.lang, subtitle.langTag)) {
            PreferredSubtitleLanguage.ARABIC ->
                subtitle.copy(
                    lang = "Arabic • $source"
                )

            PreferredSubtitleLanguage.ENGLISH ->
                subtitle.copy(
                    lang = "English • $source"
                )

            PreferredSubtitleLanguage.OTHER -> null
        }
    }

    private fun inferQuality(link: ExtractorLink): Int {
        if (link.quality >= 720) return link.quality

        val text = "${link.name} ${link.source} ${link.url}".lowercase()
        return when {
            "2160" in text || "4k" in text || "uhd" in text -> 2160
            "1440" in text || "qhd" in text || "2k" in text -> 1440
            "1080" in text || "fhd" in text ||
                "fullhd" in text || "full hd" in text -> 1080
            "720" in text || Regex("""\bhd\b""").containsMatchIn(text) -> 720
            else -> link.quality
        }
    }

    private suspend fun linkHealth(link: ExtractorLink): LinkHealth {
        if (
            link.type == ExtractorLinkType.TORRENT ||
            link.type == ExtractorLinkType.MAGNET
        ) return LinkHealth.DEAD

        if (!link.url.startsWith("http")) return LinkHealth.UNKNOWN

        val response = timed(linkProbeTimeoutMs) {
            app.head(
                url = link.url,
                headers = link.headers,
                referer = link.referer,
                allowRedirects = true
            )
        } ?: return LinkHealth.UNKNOWN

        return when {
            response.code in 200..399 -> LinkHealth.HEALTHY
            response.code == 403 || response.code == 405 || response.code == 429 ->
                LinkHealth.UNKNOWN
            response.code == 404 || response.code == 410 ||
                response.code in 500..599 -> LinkHealth.DEAD
            else -> LinkHealth.UNKNOWN
        }
    }

    private fun linkKey(link: ExtractorLink): String =
        "${link.url}|${link.referer}|${link.headers.hashCode()}"

    private fun providerHealthKey(api: MainAPI): String =
        "${api.name}|${api.mainUrl}"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val request = tryParseJson<HubPlaybackRequest>(data)
            ?: return@coroutineScope false

        val providers = candidateProviders(request)
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

        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        val englishSubtitles =
            Collections.synchronizedList(mutableListOf<SubtitleFile>())

        val subtitleJob = async {
            val subs = fetchOpenSubtitles(request)
                .sortedByDescending(::subtitleScore)

            subs.forEach { subtitle ->
                if (!seenSubtitles.add(subtitle.url)) return@forEach

                when (subtitleLanguage(subtitle.lang, subtitle.langTag)) {
                    PreferredSubtitleLanguage.ARABIC -> subtitleCallback(subtitle)
                    PreferredSubtitleLanguage.ENGLISH -> englishSubtitles += subtitle
                    PreferredSubtitleLanguage.OTHER -> Unit
                }
            }
        }

        val aliases = aliasesJob.await()
        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val unknownLinks =
            Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val emittedHealthyLink = AtomicBoolean(false)
        val done = AtomicInteger(0)
        val semaphore = Semaphore(parallelProviders)

        providers.amap { api ->
            semaphore.withPermit {
                try {
                    val target = resolveProviderTarget(api, request, aliases)
                        ?: return@withPermit false

                    val rawLinks =
                        Collections.synchronizedList(mutableListOf<ExtractorLink>())

                    val extracted = timed(providerLinksTimeoutMs) {
                        api.loadLinks(
                            target.data,
                            isCasting,
                            { subtitle ->
                                normalizeNativeSubtitle(subtitle, api.name)?.let { normalized ->
                                    if (!seenSubtitles.add(normalized.url)) return@let

                                    when (
                                        subtitleLanguage(
                                            normalized.lang,
                                            normalized.langTag
                                        )
                                    ) {
                                        PreferredSubtitleLanguage.ARABIC ->
                                            subtitleCallback(normalized)

                                        PreferredSubtitleLanguage.ENGLISH ->
                                            englishSubtitles += normalized

                                        PreferredSubtitleLanguage.OTHER -> Unit
                                    }
                                }
                            },
                            { link ->
                                val quality = inferQuality(link)
                                if (quality >= 720 && link.url.isNotBlank()) {
                                    if (link.quality < 720) link.quality = quality
                                    rawLinks += link
                                }
                            }
                        )
                    } ?: false

                    if (!extracted && rawLinks.isEmpty()) {
                        providerHealth.compute(providerHealthKey(api)) { _, old ->
                            (old ?: 0) - 1
                        }
                        return@withPermit false
                    }

                    var healthyFromProvider = false

                    rawLinks
                        .distinctBy(::linkKey)
                        .sortedByDescending { inferQuality(it) }
                        .amap { link ->
                            when (linkHealth(link)) {
                                LinkHealth.HEALTHY -> {
                                    if (seenLinks.add(linkKey(link))) {
                                        healthyFromProvider = true
                                        emittedHealthyLink.set(true)
                                        callback(link)
                                    }
                                }

                                LinkHealth.UNKNOWN -> {
                                    if (seenLinks.add(linkKey(link))) {
                                        unknownLinks += link
                                    }
                                }

                                LinkHealth.DEAD -> Unit
                            }
                        }

                    providerHealth.compute(providerHealthKey(api)) { _, old ->
                        val current = old ?: 0
                        if (healthyFromProvider) {
                            (current + 2).coerceAtMost(20)
                        } else {
                            (current - 1).coerceAtLeast(-20)
                        }
                    }

                    healthyFromProvider
                } finally {
                    MOOSAProgress.providerProgress(
                        done.incrementAndGet(),
                        providers.size.coerceAtLeast(1)
                    )
                }
            }
        }

        MOOSAProgress.subtitles()
        subtitleJob.await()

        if (!emittedHealthyLink.get()) {
            unknownLinks
                .distinctBy(::linkKey)
                .sortedByDescending { inferQuality(it) }
                .forEach(callback)
        }

        englishSubtitles
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)
            .forEach(subtitleCallback)

        MOOSAProgress.finish()
        return@coroutineScope emittedHealthyLink.get() || unknownLinks.isNotEmpty()
    }
}
