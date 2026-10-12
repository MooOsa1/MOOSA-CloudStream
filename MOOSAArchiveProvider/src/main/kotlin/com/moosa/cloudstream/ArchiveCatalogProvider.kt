package com.moosa.cloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class ArchiveCatalogProvider(
    private val providerName: String,
    private val providerLang: String,
    private val languageFilter: String
) : MainAPI() {

    override var mainUrl = "https://archive.org"
    override var name = providerName
    override var lang = providerLang
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true

    private data class SearchResult(val response: DocsResponse?)
    private data class DocsResponse(val docs: List<SearchEntry> = emptyList())
    private data class SearchEntry(
        val identifier: String = "",
        val title: String? = null
    )
    private data class MetadataResult(val metadata: MetadataEntry?)
    private data class MetadataEntry(
        val identifier: String = "",
        val title: String? = null,
        val date: String? = null
    )

    private fun buildQuery(term: String? = null): String {
        val parts = mutableListOf("mediatype:(movies)", languageFilter)
        if (!term.isNullOrBlank()) parts.add("(" + term.trim() + ")")
        return parts.joinToString(" AND ")
    }

    private fun SearchEntry.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(
            title ?: identifier,
            "$mainUrl/details/$identifier",
            TvType.Movie
        ) {
            posterUrl = "$mainUrl/services/img/$identifier"
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val q = buildQuery().encodeUri()
        val url = "$mainUrl/advancedsearch.php?q=$q&fl[]=identifier&fl[]=title&rows=30&page=$page&output=json"
        val parsed = tryParseJson<SearchResult>(app.get(url).text)
        val items = parsed?.response?.docs.orEmpty()
            .filter { it.identifier.isNotBlank() }
            .map { it.toSearchResponse() }

        return newHomePageResponse(
            listOf(HomePageList("Movies", items, true))
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = buildQuery(query).encodeUri()
        val url = "$mainUrl/advancedsearch.php?q=$q&fl[]=identifier&fl[]=title&rows=30&page=$page&output=json"
        val parsed = tryParseJson<SearchResult>(app.get(url).text)
        return parsed?.response?.docs.orEmpty()
            .filter { it.identifier.isNotBlank() }
            .map { it.toSearchResponse() }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val identifier = url.substringAfterLast("/")
        val metaUrl = "$mainUrl/metadata/$identifier"
        val meta = tryParseJson<MetadataResult>(app.get(metaUrl).text)?.metadata
        val title = meta?.title ?: identifier.replace('_', ' ')
        val year = meta?.date?.let { Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        return newMovieLoadResponse(
            title,
            "$mainUrl/details/$identifier",
            TvType.Movie,
            identifier
        ) {
            posterUrl = "$mainUrl/services/img/$identifier"
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(
            "$mainUrl/details/$data",
            subtitleCallback,
            callback
        )
        return true
    }
}
