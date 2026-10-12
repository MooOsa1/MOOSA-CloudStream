package com.moosa.hub

import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

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

    private fun normalizeTitle(value: String): String =
        value.lowercase()
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]"), "")

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
        val paged = try {
            api.search(title, 1)?.items.orEmpty()
        } catch (error: Exception) {
            logError(error)
            emptyList()
        }
        if (paged.isNotEmpty()) return paged

        return try {
            api.search(title).orEmpty()
        } catch (error: Exception) {
            logError(error)
            emptyList()
        }
    }

    private suspend fun resolveMovieTargets(
        title: String,
        year: Int?
    ): List<ProviderTarget> {
        return apis
            .filter { api ->
                api !== this && api.providerType == ProviderType.DirectProvider
            }
            .amap { api ->
                try {
                    val candidates = searchProvider(api, title)
                        .map { it to matchScore(it, normalizeTitle(title), year) }
                        .filter { (_, score) -> score > 0 }
                        .sortedByDescending { (_, score) -> score }
                        .take(5)

                    for ((match, _) in candidates) {
                        val loaded = try {
                            api.load(match.url)
                        } catch (error: Exception) {
                            logError(error)
                            null
                        }

                        if (loaded is MovieLoadResponse && loaded.dataUrl.isNotBlank()) {
                            return@amap ProviderTarget(
                                providerName = api.name,
                                mainUrl = api.mainUrl,
                                data = loaded.dataUrl
                            )
                        }
                    }
                    null
                } catch (error: Exception) {
                    logError(error)
                    null
                }
            }
            .filterNotNull()
    }

    private suspend fun resolveSeriesTargets(
        title: String,
        year: Int?
    ): Map<Pair<Int, Int>, List<ProviderTarget>> {
        val resolved = apis
            .filter { api ->
                api !== this && api.providerType == ProviderType.DirectProvider
            }
            .amap { api ->
                try {
                    val candidates = searchProvider(api, title)
                        .map { it to matchScore(it, normalizeTitle(title), year) }
                        .filter { (_, score) -> score > 0 }
                        .sortedByDescending { (_, score) -> score }
                        .take(5)

                    for ((match, _) in candidates) {
                        val loaded = try {
                            api.load(match.url)
                        } catch (error: Exception) {
                            logError(error)
                            null
                        }

                        if (loaded is TvSeriesLoadResponse && loaded.episodes.isNotEmpty()) {
                            return@amap loaded.episodes.mapNotNull { episode ->
                                val season = episode.season ?: return@mapNotNull null
                                val number = episode.episode ?: return@mapNotNull null
                                if (episode.data.isBlank()) return@mapNotNull null

                                (season to number) to ProviderTarget(
                                    providerName = api.name,
                                    mainUrl = api.mainUrl,
                                    data = episode.data
                                )
                            }
                        }
                    }
                    emptyList()
                } catch (error: Exception) {
                    logError(error)
                    emptyList()
                }
            }
            .flatten()

        return resolved
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, targets) -> targets.distinct() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val base = super.load(url) ?: return null

        return when (base) {
            is MovieLoadResponse -> {
                val targets = resolveMovieTargets(base.name, base.year)
                base.dataUrl = HubPlaybackData(targets).toJson()
                base
            }

            is TvSeriesLoadResponse -> {
                val targetsByEpisode = resolveSeriesTargets(base.name, base.year)

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = tryParseJson<HubPlaybackData>(data) ?: return false
        if (payload.targets.isEmpty()) return false

        val results = payload.targets.amap { target ->
            val api = apis.firstOrNull { candidate ->
                candidate !== this &&
                    candidate.name == target.providerName &&
                    (target.mainUrl.isBlank() || candidate.mainUrl == target.mainUrl)
            } ?: return@amap false

            try {
                api.loadLinks(
                    target.data,
                    isCasting,
                    subtitleCallback,
                    callback
                )
            } catch (error: Exception) {
                logError(error)
                false
            }
        }

        return results.any { it }
    }
}
