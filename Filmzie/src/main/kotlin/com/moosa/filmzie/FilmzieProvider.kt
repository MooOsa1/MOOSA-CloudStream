package com.moosa.filmzie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class FilmzieProvider : MainAPI() {
    override var mainUrl = "https://filmzie.com"
    override var name = "Filmzie"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val apiBase = "$mainUrl/api/v1"

    private data class CatalogEnvelope(val data: CatalogPage? = null)

    private data class CatalogPage(
        val data: List<CatalogItem> = emptyList(),
        val paging: Paging? = null
    )

    private data class Paging(
        val total: Int? = null,
        val limit: Int? = null,
        val offset: Int? = null
    )

    private data class CatalogItem(
        val id: Any? = null,
        val title: String? = null,
        val sefName: String? = null,
        val description: String? = null,
        val type: String? = null,
        val released: String? = null,
        val category: Any? = null
    )

    private data class FilmziePayload(
        val id: String,
        val title: String,
        val sefName: String,
        val description: String? = null,
        val type: String? = null,
        val released: String? = null
    )

    private data class AppState(val contentPage: ContentPage? = null)
    private data class ContentPage(val content: ContentDetails? = null)

    private data class ContentDetails(
        val title: String? = null,
        val type: String? = null,
        val description: String? = null,
        val videos: List<VideoEntry> = emptyList(),
        val seasons: List<SeasonEntry> = emptyList(),
        val seoTags: SeoTags? = null
    )

    private data class SeoTags(
        val title: String? = null,
        val description: String? = null
    )

    private data class VideoEntry(
        val id: Any? = null,
        val type: String? = null,
        val title: String? = null
    )

    private data class SeasonEntry(
        val seasonNumber: Int? = null,
        val title: String? = null,
        val episodes: List<EpisodeEntry> = emptyList()
    )

    private data class EpisodeEntry(
        val title: String? = null,
        val description: String? = null,
        val videoId: Any? = null
    )

    private data class StreamEnvelope(val data: StreamData? = null)
    private data class StreamData(val source: StreamSource? = null)

    private data class StreamSource(
        val sources: List<StreamFile> = emptyList(),
        val hlsV2: String? = null
    )

    private data class StreamFile(
        val file: String? = null,
        val label: String? = null,
        val type: String? = null
    )

    private fun CatalogItem.toSearchResponse(): SearchResponse? {
        val itemId = id?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val itemTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val slug = sefName?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val payload = FilmziePayload(
            id = itemId,
            title = itemTitle,
            sefName = slug,
            description = description,
            type = type,
            released = released
        ).toJson()

        val itemYear = released
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

        return if (type.equals("tv_show", ignoreCase = true)) {
            newTvSeriesSearchResponse(itemTitle, payload, TvType.TvSeries) {
                year = itemYear
            }
        } else {
            newMovieSearchResponse(itemTitle, payload, TvType.Movie) {
                year = itemYear
            }
        }
    }

    private suspend fun catalogPage(offset: Int, limit: Int = 60): CatalogPage {
        return app.get(
            "$apiBase/content",
            params = mapOf(
                "limit" to limit.toString(),
                "offset" to offset.toString()
            ),
            headers = mapOf(
                "Accept" to "application/json",
                "Referer" to "$mainUrl/"
            )
        ).parsedSafe<CatalogEnvelope>()?.data ?: CatalogPage()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val limit = 60
        val offset = ((page - 1).coerceAtLeast(0)) * limit
        val items = catalogPage(offset, limit).data.mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(
            listOf(HomePageList("Movies & TV Shows", items, true))
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val wanted = query.trim().lowercase()
        if (wanted.isBlank()) return emptyList()

        val found = mutableListOf<SearchResponse>()
        var offset = 0
        val limit = 100
        var total = Int.MAX_VALUE

        while (offset < total && offset < 600 && found.size < 100) {
            val page = catalogPage(offset, limit)
            total = page.paging?.total ?: (offset + page.data.size)

            page.data.asSequence()
                .filter {
                    it.title.orEmpty().lowercase().contains(wanted) ||
                        it.description.orEmpty().lowercase().contains(wanted)
                }
                .mapNotNull { it.toSearchResponse() }
                .forEach { found += it }

            if (page.data.isEmpty()) break
            offset += limit
        }

        return found.take(100)
    }

    private fun extractState(html: String): AppState? {
        val regex = Regex(
            """window\['APP_STATE'\][^=]*=[^{](\{.*\});""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val json = regex.find(html)?.groupValues?.getOrNull(1) ?: return null
        return tryParseJson<AppState>(json)
    }

    private suspend fun getDetails(slug: String): ContentDetails? {
        val html = app.get(
            "$mainUrl/content/$slug",
            headers = mapOf("Referer" to "$mainUrl/")
        ).text
        return extractState(html)?.contentPage?.content
    }

    private fun videoId(value: Any?): String? =
        value?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }

    override suspend fun load(url: String): LoadResponse? {
        val payload = tryParseJson<FilmziePayload>(url) ?: return null
        val details = getDetails(payload.sefName)

        val title = details?.title
            ?: details?.seoTags?.title
            ?: payload.title
        val description = details?.description
            ?: details?.seoTags?.description
            ?: payload.description
        val isSeries =
            details?.type.equals("tv_show", ignoreCase = true) ||
                payload.type.equals("tv_show", ignoreCase = true)

        if (isSeries) {
            val episodes = details?.seasons.orEmpty().flatMap { season ->
                val seasonNumber = season.seasonNumber ?: 1
                season.episodes.mapIndexedNotNull { index, episode ->
                    val streamId = videoId(episode.videoId) ?: return@mapIndexedNotNull null
                    newEpisode(streamId) {
                        name = episode.title ?: "Episode ${index + 1}"
                        this.season = seasonNumber
                        this.episode = index + 1
                        this.description = episode.description
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title,
                "$mainUrl/content/${payload.sefName}",
                TvType.TvSeries,
                episodes
            ) {
                plot = description
            }
        }

        val streamId = details?.videos.orEmpty()
            .firstOrNull { !it.type.equals("TRAILER", ignoreCase = true) }
            ?.id
            ?.let(::videoId)
            ?: return null

        return newMovieLoadResponse(
            title,
            "$mainUrl/content/${payload.sefName}",
            TvType.Movie,
            streamId
        ) {
            plot = description
            year = payload.released
                ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val response = app.get(
            "$apiBase/video/stream/$data",
            headers = mapOf(
                "Accept" to "application/json",
                "Referer" to "$mainUrl/"
            )
        ).parsedSafe<StreamEnvelope>()
            ?.data?.source ?: return false

        val links = buildList {
            response.sources.mapNotNullTo(this) { it.file?.takeIf(String::isNotBlank) }
            response.hlsV2?.takeIf(String::isNotBlank)?.let { add(it) }
        }.distinct()

        links.forEachIndexed { index, link ->
            val type = if (link.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = if (links.size > 1) "Filmzie ${index + 1}" else "Filmzie",
                    url = link,
                    type = type
                ) {
                    referer = "$mainUrl/"
                }
            )
        }

        return links.isNotEmpty()
    }
}
