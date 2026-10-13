package com.moosa.plex

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.UUID

class PlexFreeProvider : MainAPI() {
    override var mainUrl = "https://watch.plex.tv"
    override var name = "Plex Free"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val vod = "https://vod.provider.plex.tv"
    private val clientId = UUID.randomUUID().toString()
    private var token: String? = null

    override val mainPage = mainPageOf(
        "movie:action" to "Movies — Action",
        "movie:comedy" to "Movies — Comedy",
        "movie:drama" to "Movies — Drama",
        "movie:sci-fi" to "Movies — Sci-Fi",
        "movie:horror" to "Movies — Horror",
        "movie:adventure" to "Movies — Adventure",
        "movie:documentary" to "Movies — Documentary",
        "show:binge-worthy-shows" to "TV — Binge-Worthy",
        "show:drama_tv" to "TV — Drama",
        "show:crime_tv" to "TV — Crime",
        "show:comedy-gold" to "TV — Comedy",
        "show:sci-fi_tv" to "TV — Sci-Fi",
        "show:reality-tv" to "TV — Reality",
        "show:true-crime" to "TV — True Crime"
    )

    private data class AnonymousUser(val authToken: String? = null)

    private suspend fun getToken(): String {
        token?.let { return it }
        val response = app.post(
            "https://plex.tv/api/v2/users/anonymous",
            headers = mapOf(
                "Accept" to "application/json",
                "X-Plex-Product" to "Plex Mediaverse",
                "X-Plex-Client-Identifier" to clientId
            )
        )
        val parsed = tryParseJson<AnonymousUser>(response.text)
            ?: throw ErrorLoadingException("Plex anonymous login failed")
        val value = parsed.authToken
            ?: throw ErrorLoadingException("Plex did not return an anonymous token")
        token = value
        return value
    }

    private fun authQuery(token: String) =
        "X-Plex-Token=${token}&X-Plex-Client-Identifier=${clientId}"

    private data class HubResponse(
        @JsonProperty("MediaContainer")
        val mediaContainer: HubContainer? = null
    )

    private data class HubContainer(
        @JsonProperty("Metadata")
        val metadata: List<PlexItem> = emptyList()
    )

    private data class PlexItem(
        val ratingKey: String? = null,
        val key: String? = null,
        val title: String? = null,
        val type: String? = null,
        val year: Int? = null,
        val thumb: String? = null,
        val art: String? = null,
        val summary: String? = null,
        val duration: Long? = null,
        @JsonProperty("Media")
        val media: List<PlexMedia> = emptyList()
    )

    private data class PlexMedia(
        val protocol: String? = null,
        val height: Int? = null,
        val width: Int? = null,
        val videoResolution: String? = null,
        @JsonProperty("Part")
        val parts: List<PlexPart> = emptyList()
    )

    private data class PlexPart(
        val key: String? = null,
        val license: String? = null
    )

    private data class ChildrenResponse(
        @JsonProperty("MediaContainer")
        val mediaContainer: ChildrenContainer? = null
    )

    private data class ChildrenContainer(
        @JsonProperty("Metadata")
        val metadata: List<PlexChild> = emptyList()
    )

    private data class PlexChild(
        val ratingKey: String? = null,
        val title: String? = null,
        val type: String? = null,
        val index: Int? = null,
        val parentIndex: Int? = null,
        val thumb: String? = null,
        val summary: String? = null
    )

    private data class PlexPayload(
        val id: String,
        val type: String,
        val title: String,
        val year: Int? = null,
        val thumb: String? = null
    )

    private fun imageUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return "${vod}${path}"
    }

    private fun PlexItem.idOrNull(): String? =
        ratingKey?.takeIf { it.isNotBlank() }
            ?: key?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    private fun PlexItem.toSearchResponse(): SearchResponse? {
        val id = idOrNull() ?: return null
        val titleValue = title?.takeIf { it.isNotBlank() } ?: return null
        val mediaType = if (type == "show") TvType.TvSeries else TvType.Movie
        val payload = PlexPayload(id, if (type == "show") "show" else "movie", titleValue, year, thumb).toJson()
        val itemYear = year

        return if (mediaType == TvType.TvSeries) {
            newTvSeriesSearchResponse(titleValue, payload, TvType.TvSeries) {
                posterUrl = imageUrl(thumb)
                this.year = itemYear
            }
        } else {
            newMovieSearchResponse(titleValue, payload, TvType.Movie) {
                posterUrl = imageUrl(thumb)
                this.year = itemYear
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val token = getToken()
        val kind = request.data.substringBefore(":")
        val slug = request.data.substringAfter(":")
        val url = "${vod}/hubs/sections/movies/${slug}?count=60&${authQuery(token)}"
        val response = app.get(url, headers = mapOf("Accept" to "application/json"))
            .parsedSafe<HubResponse>()

        val wantedType = if (kind == "show") "show" else "movie"
        val items = response?.mediaContainer?.metadata.orEmpty()
            .filter { it.type == wantedType }
            .mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, items)
    }

    private suspend fun getMetadata(id: String): PlexItem? {
        val token = getToken()
        val url = "${vod}/library/metadata/${id}?${authQuery(token)}"
        return app.get(url, headers = mapOf("Accept" to "application/json"))
            .parsedSafe<HubResponse>()
            ?.mediaContainer?.metadata?.firstOrNull()
    }

    private suspend fun getChildren(id: String): List<PlexChild> {
        val token = getToken()
        val url = "${vod}/library/metadata/${id}/children?${authQuery(token)}"
        return app.get(url, headers = mapOf("Accept" to "application/json"))
            .parsedSafe<ChildrenResponse>()
            ?.mediaContainer?.metadata.orEmpty()
    }

    override suspend fun load(url: String): LoadResponse? {
        val payload = tryParseJson<PlexPayload>(url) ?: return null
        val meta = getMetadata(payload.id)

        if (payload.type == "show") {
            val seasons = getChildren(payload.id).filter { it.type == "season" }
            val episodes = mutableListOf<Episode>()

            for (season in seasons) {
                val seasonId = season.ratingKey ?: continue
                val seasonNumber = season.index ?: 1
                val eps = getChildren(seasonId).filter { it.type == "episode" }
                for (ep in eps) {
                    val epId = ep.ratingKey ?: continue
                    episodes += newEpisode(PlexPayload(epId, "episode", ep.title ?: "Episode").toJson()) {
                        name = ep.title
                        this.season = ep.parentIndex ?: seasonNumber
                        episode = ep.index
                        posterUrl = imageUrl(ep.thumb)
                        description = ep.summary
                    }
                }
            }

            return newTvSeriesLoadResponse(payload.title, url, TvType.TvSeries, episodes) {
                posterUrl = imageUrl(meta?.thumb ?: payload.thumb)
                backgroundPosterUrl = imageUrl(meta?.art)
                year = meta?.year ?: payload.year
                plot = meta?.summary
            }
        }

        return newMovieLoadResponse(
            payload.title,
            url,
            TvType.Movie,
            PlexPayload(payload.id, "movie", payload.title).toJson()
        ) {
            posterUrl = imageUrl(meta?.thumb ?: payload.thumb)
            backgroundPosterUrl = imageUrl(meta?.art)
            year = meta?.year ?: payload.year
            plot = meta?.summary
            duration = meta?.duration?.let { (it / 60000L).toInt() }
        }
    }

    private fun PlexMedia.qualityValue(): Int {
        val resolution = height?.takeIf { it > 0 }
            ?: videoResolution
                ?.lowercase()
                ?.let { value ->
                    when {
                        "4k" in value || "2160" in value -> 2160
                        else -> Regex("\\d{3,4}").find(value)?.value?.toIntOrNull()
                    }
                }
        return when {
            resolution == null -> Qualities.Unknown.value
            resolution >= 2000 -> Qualities.P2160.value
            resolution >= 1300 -> Qualities.P1440.value
            resolution >= 950 -> Qualities.P1080.value
            resolution >= 650 -> Qualities.P720.value
            resolution >= 420 -> Qualities.P480.value
            resolution >= 300 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = tryParseJson<PlexPayload>(data) ?: return false
        val token = getToken()
        val meta = getMetadata(payload.id) ?: return false
        val headers = mapOf(
            "X-Plex-Token" to token,
            "X-Plex-Client-Identifier" to clientId
        )
        val emittedUrls = mutableSetOf<String>()
        var found = false

        suspend fun emitLink(url: String, quality: Int, type: ExtractorLinkType) {
            if (type == ExtractorLinkType.M3U8) {
                val variants = try {
                    M3u8Helper().m3u8Generation(
                        M3u8Helper.M3u8Stream(url, quality, headers),
                        false
                    )
                } catch (_: Exception) {
                    emptyList()
                }

                if (variants.isNotEmpty()) {
                    variants.forEach { variant ->
                        if (emittedUrls.add(variant.streamUrl)) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "Plex",
                                    url = variant.streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = variant.quality ?: quality
                                    this.headers = headers
                                }
                            )
                            found = true
                        }
                    }
                    return
                }
            }

            if (emittedUrls.add(url)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Plex",
                        url = url,
                        type = type
                    ) {
                        this.quality = quality
                        this.headers = headers
                    }
                )
                found = true
            }
        }

        for (media in meta.media) {
            for (part in media.parts) {
                val key = part.key ?: continue
                val streamUrl = if (key.startsWith("http")) key else "${vod}${key}"
                val joiner = if (streamUrl.contains("?")) "&" else "?"
                val fullUrl = "${streamUrl}${joiner}${authQuery(token)}"
                val protocol = media.protocol.orEmpty()
                val type = when {
                    key.contains(".m3u8", ignoreCase = true) ||
                        protocol.equals("hls", ignoreCase = true) -> ExtractorLinkType.M3U8
                    key.contains(".mpd", ignoreCase = true) ||
                        protocol.equals("dash", ignoreCase = true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                emitLink(fullUrl, media.qualityValue(), type)
            }
        }
        return found
    }
}
