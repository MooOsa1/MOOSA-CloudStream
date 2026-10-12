package com.moosa.movies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.UUID

class MOOSAMoviesProvider : TmdbProvider() {
    override var name = "MOOSA Movies"
    override var lang = "en"

    override val useMetaLoadResponse = true
    override val apiName = "TMDB"
    override val disableSeasonZero = true
    override val includeAdult = false

    private val plexVod = "https://vod.provider.plex.tv"
    private val plexDiscover = "https://discover.provider.plex.tv"
    private val plexClientId = UUID.randomUUID().toString()
    private var plexToken: String? = null

    private data class AnonymousUser(val authToken: String? = null)

    private data class SearchEnvelope(
        @JsonProperty("MediaContainer")
        val mediaContainer: SearchContainer? = null
    )

    private data class SearchContainer(
        @JsonProperty("SearchResults")
        val searchResults: List<SearchBucket> = emptyList()
    )

    private data class SearchBucket(
        val id: String? = null,
        val title: String? = null,
        @JsonProperty("SearchResult")
        val searchResult: List<SearchEntry> = emptyList()
    )

    private data class SearchEntry(
        @JsonProperty("Metadata")
        val metadata: SearchMetadata? = null
    )

    private data class SearchMetadata(
        val slug: String? = null,
        val title: String? = null,
        val type: String? = null,
        val ratingKey: String? = null,
        val key: String? = null
    )

    private data class MetadataEnvelope(
        @JsonProperty("MediaContainer")
        val mediaContainer: MetadataContainer? = null
    )

    private data class MetadataContainer(
        @JsonProperty("Metadata")
        val metadata: List<PlexMetadata> = emptyList()
    )

    private data class PlexMetadata(
        val ratingKey: String? = null,
        val key: String? = null,
        val type: String? = null,
        val index: Int? = null,
        val parentIndex: Int? = null,
        @JsonProperty("Media")
        val media: List<PlexMedia> = emptyList()
    )

    private data class PlexMedia(
        val protocol: String? = null,
        @JsonProperty("Part")
        val parts: List<PlexPart> = emptyList()
    )

    private data class PlexPart(
        val key: String? = null,
        val license: String? = null
    )

    private suspend fun getPlexToken(): String? {
        plexToken?.let { return it }

        val response = runCatching {
            app.post(
                "https://plex.tv/api/v2/users/anonymous",
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Plex-Product" to "Plex Mediaverse",
                    "X-Plex-Client-Identifier" to plexClientId
                )
            )
        }.getOrNull() ?: return null

        val token = tryParseJson<AnonymousUser>(response.text)?.authToken ?: return null
        plexToken = token
        return token
    }

    private fun plexHeaders(token: String) = mapOf(
        "Accept" to "application/json",
        "X-Plex-Token" to token,
        "X-Plex-Client-Identifier" to plexClientId,
        "X-Plex-Product" to "Plex Mediaverse",
        "X-Plex-Language" to "en"
    )

    private suspend fun findFreePlexTitle(title: String, isSeries: Boolean): String? {
        val token = getPlexToken() ?: return null
        val response = runCatching {
            app.get(
                "$plexDiscover/library/search",
                headers = plexHeaders(token),
                params = mapOf(
                    "query" to title,
                    "limit" to "20",
                    "searchTypes" to if (isSeries) "tv" else "movies",
                    "searchProviders" to "discover,plexAVOD",
                    "includeMetadata" to "1"
                )
            )
        }.getOrNull() ?: return null

        val parsed = tryParseJson<SearchEnvelope>(response.text) ?: return null
        val buckets = parsed.mediaContainer?.searchResults.orEmpty()

        val freeBuckets = buckets.filter {
            it.title?.contains("free on demand", ignoreCase = true) == true ||
                it.id?.contains("plex", ignoreCase = true) == true
        }

        val candidates = (if (freeBuckets.isNotEmpty()) freeBuckets else buckets)
            .flatMap { it.searchResult }
            .mapNotNull { it.metadata }
            .filter { if (isSeries) it.type == "show" else it.type == "movie" }

        val normalizedWanted = normalizeTitle(title)
        val exact = candidates.firstOrNull { normalizeTitle(it.title.orEmpty()) == normalizedWanted }
        val chosen = exact ?: candidates.firstOrNull() ?: return null

        val slug = chosen.slug
        if (!slug.isNullOrBlank()) {
            return (if (isSeries) "show:" else "movie:") + slug
        }

        chosen.ratingKey?.takeIf { it.isNotBlank() }?.let { return it }
        return chosen.key?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "").trim()

    private suspend fun getPlexMetadata(id: String): PlexMetadata? {
        val token = getPlexToken() ?: return null
        val response = runCatching {
            app.get(
                "$plexVod/library/metadata/$id",
                headers = plexHeaders(token)
            )
        }.getOrNull() ?: return null

        return tryParseJson<MetadataEnvelope>(response.text)
            ?.mediaContainer?.metadata?.firstOrNull()
    }

    private suspend fun getPlexChildren(id: String): List<PlexMetadata> {
        val token = getPlexToken() ?: return emptyList()
        val response = runCatching {
            app.get(
                "$plexVod/library/metadata/$id/children",
                headers = plexHeaders(token)
            )
        }.getOrNull() ?: return emptyList()

        return tryParseJson<MetadataEnvelope>(response.text)
            ?.mediaContainer?.metadata.orEmpty()
    }

    private suspend fun resolvePlexMedia(link: TmdbLink): PlexMetadata? {
        val title = link.movieName?.takeIf { it.isNotBlank() } ?: return null
        val isSeries = link.season != null && link.episode != null
        val selector = findFreePlexTitle(title, isSeries) ?: return null

        if (!isSeries) return getPlexMetadata(selector)

        val show = getPlexMetadata(selector) ?: return null
        val showId = show.ratingKey
            ?: show.key?.substringAfterLast('/')
            ?: selector.substringAfter(':')

        val seasonNumber = link.season ?: return null
        val episodeNumber = link.episode ?: return null

        val season = getPlexChildren(showId)
            .firstOrNull { it.type == "season" && it.index == seasonNumber }
            ?: return null

        val seasonId = season.ratingKey
            ?: season.key?.substringAfterLast('/')
            ?: return null

        val episode = getPlexChildren(seasonId)
            .firstOrNull { it.type == "episode" && it.index == episodeNumber }
            ?: return null

        val episodeId = episode.ratingKey
            ?: episode.key?.substringAfterLast('/')
            ?: return null

        return getPlexMetadata(episodeId)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdb = tryParseJson<TmdbLink>(data) ?: return false
        val token = getPlexToken() ?: return false
        val meta = resolvePlexMedia(tmdb) ?: return false

        var found = false

        meta.media.forEach { media ->
            media.parts.forEach { part ->
                val key = part.key ?: return@forEach
                val streamUrl = if (key.startsWith("http")) key else "$plexVod$key"
                val joiner = if (streamUrl.contains("?")) "&" else "?"
                val finalUrl =
                    "$streamUrl${joiner}X-Plex-Token=$token&X-Plex-Client-Identifier=$plexClientId"

                val type = when {
                    media.protocol.equals("hls", ignoreCase = true) ||
                        key.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                    media.protocol.equals("dash", ignoreCase = true) ||
                        key.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                callback(
                    newExtractorLink(
                        source = "Plex Free",
                        name = "Plex Free",
                        url = finalUrl,
                        type = type
                    ) {
                        headers = plexHeaders(token)
                    }
                )
                found = true
            }
        }

        return found
    }
}
