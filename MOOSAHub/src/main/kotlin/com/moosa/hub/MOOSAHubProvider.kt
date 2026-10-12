package com.moosa.hub

import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

class MOOSAHubProvider : TmdbProvider() {
    override var name = "MOOSA Hub"
    override var lang = "ar"

    override val useMetaLoadResponse = true
    override val apiName = "MOOSA Hub"
    override val disableSeasonZero = true
    override val includeAdult = false

    private fun normalizeTitle(value: String): String =
        value.lowercase()
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]"), "")

    private fun isTitleMatch(candidate: String, wanted: String): Boolean {
        val normalized = normalizeTitle(candidate)
        if (normalized.isBlank() || wanted.isBlank()) return false
        return normalized == wanted ||
            normalized.contains(wanted) ||
            wanted.contains(normalized)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val meta = tryParseJson<TmdbLink>(data) ?: return false
        val title = meta.movieName?.takeIf { it.isNotBlank() } ?: return false
        val wantedTitle = normalizeTitle(title)
        val isEpisode = meta.season != null && meta.episode != null
        var foundAny = false

        // Search every provider currently loaded in CloudStream, regardless of repository.
        apis.filter { api -> api !== this }.amap { api ->
            try {
                val results = api.search(title, 1)?.items.orEmpty()

                val matchingResults = results.filter { result ->
                    isTitleMatch(result.name, wantedTitle) &&
                        if (isEpisode) {
                            result is TvSeriesSearchResponse
                        } else {
                            result is MovieSearchResponse
                        }
                }

                // Exact title first, then close title matches.
                val orderedMatches = matchingResults.sortedByDescending { result ->
                    normalizeTitle(result.name) == wantedTitle
                }

                for (match in orderedMatches.take(3)) {
                    val loaded = try {
                        api.load(match.url)
                    } catch (error: Exception) {
                        logError(error)
                        null
                    } ?: continue

                    val providerData = if (!isEpisode) {
                        (loaded as? MovieLoadResponse)?.dataUrl
                    } else {
                        (loaded as? TvSeriesLoadResponse)?.episodes
                            ?.firstOrNull { episode ->
                                episode.season == meta.season &&
                                    episode.episode == meta.episode
                            }
                            ?.data
                    } ?: continue

                    try {
                        if (api.loadLinks(providerData, isCasting, subtitleCallback, callback)) {
                            foundAny = true
                            break
                        }
                    } catch (error: Exception) {
                        logError(error)
                    }
                }
            } catch (error: Exception) {
                logError(error)
            }
        }

        return foundAny
    }
}
