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

    private val playbackProviderNames = setOf(
        "PlexFree",
        "Plex Free",
        "Filmzie",
        "DistroTV",
        "InternetArchive",
        "YouTube",
        "Dailymotion"
    )

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val meta = tryParseJson<TmdbLink>(data) ?: return false
        val title = meta.movieName?.takeIf { it.isNotBlank() } ?: return false
        val wantedTitle = normalizeTitle(title)
        var foundAny = false

        apis.filter { api ->
            api !== this && api.name in playbackProviderNames
        }.amap { api ->
            try {
                val match = api.search(title)?.firstOrNull { result ->
                    normalizeTitle(result.name) == wantedTitle &&
                        if (meta.season == null || meta.episode == null) {
                            result is MovieSearchResponse
                        } else {
                            result is TvSeriesSearchResponse
                        }
                } ?: return@amap

                val loaded = api.load(match.url) ?: return@amap

                val providerData = if (meta.season == null || meta.episode == null) {
                    (loaded as? MovieLoadResponse)?.dataUrl
                } else {
                    (loaded as? TvSeriesLoadResponse)?.episodes?.firstOrNull { episode ->
                        episode.season == meta.season && episode.episode == meta.episode
                    }?.data
                } ?: return@amap

                if (api.loadLinks(providerData, isCasting, subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (error: Exception) {
                logError(error)
            }
        }

        return foundAny
    }
}
