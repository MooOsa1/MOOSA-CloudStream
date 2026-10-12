package com.moosa.distro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.UUID

class DistroTVProvider : MainAPI() {
    override var mainUrl = "https://www.distro.tv"
    override var name = "DistroTV On Demand"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val feedUrl = "https://tv.jsrdn.com/tv_v5/getfeed.php?type=vod"
    private val androidUa =
        "Dalvik/2.1.0 (Linux; U; Android 9; AFTT Build/STT9.221129.002) GTV/AFTT DistroTV/2.0.9"
    private val browserUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private var cachedFeed: DistroFeed? = null

    data class DistroFeed(
        val shows: Map<String, DistroShow> = emptyMap()
    )

    data class DistroShow(
        val title: String? = null,
        val description: String? = null,
        val type: String? = null,
        val img_thumbh: String? = null,
        val img_logo: String? = null,
        val seasons: List<DistroSeason> = emptyList()
    )

    data class DistroSeason(
        val episodes: List<DistroEpisode> = emptyList()
    )

    data class DistroEpisode(
        val id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val img_thumbh: String? = null,
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
            app.get(
                feedUrl,
                headers = mapOf(
                    "User-Agent" to androidUa,
                    "Accept" to "application/json,*/*"
                )
            ).text
        ) ?: DistroFeed()

        cachedFeed = parsed
        return parsed
    }

    private fun DistroShow.validEpisodes(): List<Pair<Int, DistroEpisode>> {
        val out = mutableListOf<Pair<Int, DistroEpisode>>()
        seasons.forEachIndexed { seasonIndex, season ->
            season.episodes.forEach { ep ->
                if (!ep.content?.url.isNullOrBlank()) {
                    out += (seasonIndex + 1) to ep
                }
            }
        }
        return out
    }

    private fun DistroShow.isOnDemand(): Boolean =
        !type.equals("live", ignoreCase = true) && validEpisodes().isNotEmpty()

    private fun DistroShow.toSearchResponse(key: String): SearchResponse? {
        val showTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val episodes = validEpisodes()
        if (episodes.isEmpty()) return null

        val payload = DistroPayload(key, showTitle, description).toJson()
        val poster = img_thumbh ?: img_logo ?: episodes.firstOrNull()?.second?.img_thumbh

        return if (episodes.size == 1) {
            newMovieSearchResponse(showTitle, payload, TvType.Movie) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(showTitle, payload, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = feed().shows.entries
            .asSequence()
            .filter { it.value.isOnDemand() }
            .mapNotNull { it.value.toSearchResponse(it.key) }
            .take(200)
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

        val poster = show.img_thumbh ?: show.img_logo ?: episodes.firstOrNull()?.second?.img_thumbh

        if (episodes.size == 1) {
            val ep = episodes.first().second
            val stream = ep.content?.url ?: return null

            return newMovieLoadResponse(
                payload.title,
                url,
                TvType.Movie,
                stream
            ) {
                posterUrl = poster
                plot = payload.description ?: ep.description
                duration = ep.content.duration?.let { (it / 60L).toInt() }
            }
        }

        val seasonCounters = mutableMapOf<Int, Int>()
        val mappedEpisodes = episodes.map { (seasonNumber, ep) ->
            val episodeNumber = (seasonCounters[seasonNumber] ?: 0) + 1
            seasonCounters[seasonNumber] = episodeNumber

            newEpisode(ep.content?.url.orEmpty()) {
                name = ep.title ?: "Episode $episodeNumber"
                season = seasonNumber
                episode = episodeNumber
                posterUrl = ep.img_thumbh ?: poster
                description = ep.description
                runTime = ep.content?.duration?.let { (it / 60L).toInt() }
            }
        }

        return newTvSeriesLoadResponse(
            payload.title,
            url,
            TvType.TvSeries,
            mappedEpisodes
        ) {
            posterUrl = poster
            plot = payload.description
        }
    }

    private fun sanitizeStreamUrl(rawUrl: String): String {
        var url = rawUrl

        val replacements = mapOf(
            "__CACHE_BUSTER__" to System.currentTimeMillis().toString(),
            "__DEVICE_ID__" to UUID.randomUUID().toString(),
            "__LIMIT_AD_TRACKING__" to "0",
            "__IS_GDPR__" to "0",
            "__IS_CCPA__" to "0",
            "__GEO_COUNTRY__" to "",
            "__LATITUDE__" to "",
            "__LONGITUDE__" to "",
            "__GEO_DMA__" to "",
            "__GEO_TYPE__" to "",
            "__PAGEURL_ESC__" to "https%3A%2F%2Fdistro.tv%2F",
            "__STORE_URL__" to "https%3A%2F%2Fdistro.tv%2F",
            "__APP_BUNDLE__" to "distro.tv",
            "__APP_VERSION__" to "2.0.9",
            "__APP_CATEGORY__" to "",
            "__WIDTH__" to "1920",
            "__HEIGHT__" to "1080",
            "__DEVICE__" to "AFTT",
            "__DEVICE_ID_TYPE__" to "uuid",
            "__DEVICE_CONNECTION_TYPE__" to "",
            "__DEVICE_CATEGORY__" to "tv",
            "__env.i__" to "app",
            "__env.u__" to "app",
            "__PALN__" to "",
            "__GDPR_CONSENT__" to "",
            "__ADVERTISING_ID__" to "",
            "__CLIENT_IP__" to ""
        )

        replacements.forEach { (macro, value) ->
            url = url.replace(macro, value)
        }

        url = Regex("__[^_].*?__").replace(url, "")
        return url
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val streamUrl = sanitizeStreamUrl(data)
        val type = if (
            streamUrl.contains(".m3u8", ignoreCase = true) ||
            streamUrl.contains("/hls/", ignoreCase = true)
        ) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                source = name,
                name = "DistroTV",
                url = streamUrl,
                type = type
            ) {
                referer = "https://distro.tv/"
                headers = mapOf(
                    "User-Agent" to browserUa,
                    "Referer" to "https://distro.tv/",
                    "Origin" to "https://distro.tv"
                )
            }
        )

        return true
    }
}
