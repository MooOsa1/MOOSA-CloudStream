package com.moosa.distro

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class DistroTVProvider : MainAPI() {
    override var mainUrl = "https://www.distro.tv"
    override var name = "DistroTV On Demand"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val feedUrl = "https://tv.jsrdn.com/tv_v5/getfeed.php"
    private var cachedFeed: DistroFeed? = null

    data class DistroFeed(
        val shows: Map<String, DistroShow> = emptyMap()
    )

    data class DistroShow(
        val title: String? = null,
        val description: String? = null,
        val type: String? = null,
        val seasons: List<DistroSeason> = emptyList()
    )

    data class DistroSeason(
        val episodes: List<DistroEpisode> = emptyList()
    )

    data class DistroEpisode(
        val id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val content: DistroContent? = null
    )

    data class DistroContent(
        val url: String? = null,
        val duration: Long? = null
    )

    data class DistroPayload(
        val key: String,
        val title: String,
        val description: String? = null
    )

    private suspend fun feed(): DistroFeed {
        cachedFeed?.let { return it }

        val parsed = tryParseJson<DistroFeed>(
            app.get(feedUrl, headers = mapOf("Accept" to "application/json")).text
        ) ?: DistroFeed()

        cachedFeed = parsed
        return parsed
    }

    private fun DistroShow.validEpisodes(): List<DistroEpisode> =
        seasons.firstOrNull()?.episodes.orEmpty()
            .filter { !it.content?.url.isNullOrBlank() }

    private fun DistroShow.isOnDemand(): Boolean =
        !type.equals("live", ignoreCase = true) && validEpisodes().isNotEmpty()

    private fun DistroShow.toSearchResponse(key: String): SearchResponse? {
        val showTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val episodes = validEpisodes()
        if (episodes.isEmpty()) return null

        val payload = DistroPayload(key, showTitle, description).toJson()

        return if (episodes.size == 1) {
            newMovieSearchResponse(showTitle, payload, TvType.Movie)
        } else {
            newTvSeriesSearchResponse(showTitle, payload, TvType.TvSeries)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = feed().shows.entries
            .asSequence()
            .filter { it.value.isOnDemand() }
            .mapNotNull { it.value.toSearchResponse(it.key) }
            .take(150)
            .toList()

        return newHomePageResponse(
            listOf(HomePageList("Movies & Shows", items, true))
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        return feed().shows.entries
            .asSequence()
            .filter { it.value.isOnDemand() }
            .filter {
                it.value.title.orEmpty().lowercase().contains(q) ||
                    it.value.description.orEmpty().lowercase().contains(q)
            }
            .mapNotNull { it.value.toSearchResponse(it.key) }
            .take(100)
            .toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val payload = tryParseJson<DistroPayload>(url) ?: return null
        val show = feed().shows[payload.key] ?: return null
        val episodes = show.validEpisodes()
        if (episodes.isEmpty()) return null

        if (episodes.size == 1) {
            val ep = episodes.first()
            val stream = ep.content?.url ?: return null

            return newMovieLoadResponse(
                payload.title,
                url,
                TvType.Movie,
                stream
            ) {
                plot = payload.description ?: ep.description
                duration = ep.content.duration?.let { (it / 60L).toInt() }
            }
        }

        val mappedEpisodes = episodes.mapIndexed { index, ep ->
            val stream = ep.content?.url.orEmpty()
            newEpisode(stream) {
                name = ep.title ?: "Episode ${index + 1}"
                episode = index + 1
                description = ep.description
                runTime = ep.content?.duration?.toInt()
            }
        }

        return newTvSeriesLoadResponse(
            payload.title,
            url,
            TvType.TvSeries,
            mappedEpisodes
        ) {
            plot = payload.description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val type = if (data.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = type
            )
        )

        return true
    }
}
