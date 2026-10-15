package com.moosa.hub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import kotlin.math.abs

class MOOSAHubProvider : TmdbProvider() {
    override var name = "MOOSA Hub"
    override var lang = "en"

    override val useMetaLoadResponse = true
    override val mainPage = MOOSACatalog.categories
    override val apiName = "MOOSA Hub"
    override val disableSeasonZero = true
    override val includeAdult = false

    data class ProviderTarget(
        val providerName: String,
        val mainUrl: String,
        val data: String
    )

    private data class DirectTmdbRequest(
        val id: Int,
        val type: String
    )

    data class HubPlaybackRequest(
        val title: String,
        val year: Int? = null,
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val isTv: Boolean = false,
        val originalLanguage: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val titleAliases: List<String> = emptyList()
    )

    private data class CachedTarget(
        val createdAt: Long,
        val target: ProviderTarget
    )

    private data class StremioSubtitleResponse(
        val subtitles: List<StremioSubtitle> = emptyList()
    )

    private data class StremioSubtitle(
        val id: String? = null,
        val url: String? = null,
        val lang: String? = null,
        // Stremio v3 publishes the original release metadata. Without this
        // CloudStream cannot distinguish a CAM subtitle from a WEB-DL one.
        @JsonProperty("subtitleFileName") val subtitleFileName: String? = null,
        @JsonProperty("movieReleaseName") val movieReleaseName: String? = null,
        @JsonProperty("releaseFormat") val releaseFormat: String? = null,
        @JsonProperty("fpsMilli") val fpsMilli: Int? = null
    )

    private data class WyzieSubtitle(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("display") val display: String? = null,
        @JsonProperty("language") val language: String? = null
    )

    private data class SubDlResponse(
        @JsonProperty("status") val status: Boolean = false,
        @JsonProperty("subtitles") val subtitles: List<SubDlSubtitle> = emptyList()
    )

    private data class SubDlSubtitle(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("release_name") val releaseName: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("unpack_files") val unpackFiles: List<SubDlFile> = emptyList()
    )

    private data class SubDlFile(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("release_name") val releaseName: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("format") val format: String? = null
    )

    private data class CachedSubtitleList(
        val createdAt: Long,
        val items: List<SubtitleFile>
    )

    private data class CachedSubtitleCheck(
        val checkedAt: Long,
        val readable: Boolean
    )

    private data class LegacySubtitleResult(
        val result: LegacySubtitleList? = null
    )

    private data class LegacySubtitleList(
        val all: List<StremioSubtitle> = emptyList()
    )

    private data class TmdbOverview(
        @JsonProperty("overview")
        val overview: String? = null
    )

    private data class TmdbTranslationCollection(
        @JsonProperty("translations")
        val translations: List<TmdbTranslationEntry> = emptyList()
    )
    private data class TmdbTranslationEntry(
        @JsonProperty("iso_639_1")
        val language: String? = null,
        @JsonProperty("iso_3166_1")
        val region: String? = null,
        @JsonProperty("data")
        val data: TmdbTranslationData? = null
    )
    private data class TmdbTranslationData(
        @JsonProperty("overview")
        val overview: String? = null
    )

    private data class TmdbSeasonDetail(
        @JsonProperty("name")
        val name: String? = null,
        @JsonProperty("season_number")
        val seasonNumber: Int? = null
    )

    private data class TmdbDetail(
        @JsonProperty("overview")
        val overview: String? = null,
        @JsonProperty("title")
        val title: String? = null,
        @JsonProperty("name")
        val name: String? = null,
        @JsonProperty("original_title")
        val originalTitle: String? = null,
        @JsonProperty("original_name")
        val originalName: String? = null,
        @JsonProperty("original_language")
        val originalLanguage: String? = null,
        @JsonProperty("poster_path")
        val posterPath: String? = null,
        @JsonProperty("backdrop_path")
        val backdropPath: String? = null,
        @JsonProperty("vote_average")
        val voteAverage: Double? = null,
        @JsonProperty("vote_count")
        val voteCount: Int? = null
    )

    private data class TmdbImageResponse(
        @JsonProperty("posters")
        val posters: List<TmdbImage> = emptyList(),
        @JsonProperty("backdrops")
        val backdrops: List<TmdbImage> = emptyList()
    )

    private data class TmdbImage(
        @JsonProperty("file_path")
        val filePath: String? = null,
        @JsonProperty("vote_average")
        val voteAverage: Double? = null,
        @JsonProperty("iso_639_1")
        val language: String? = null
    )

    private data class TmdbExternalIds(
        @JsonProperty("imdb_id")
        val imdbId: String? = null
    )

    private data class TmdbHomePage(
        @JsonProperty("results")
        val results: List<TmdbHomeItem> = emptyList()
    )

    private data class TmdbHomeItem(
        @JsonProperty("id")
        val id: Int? = null,
        @JsonProperty("title")
        val title: String? = null,
        @JsonProperty("original_title")
        val originalTitle: String? = null,
        @JsonProperty("name")
        val name: String? = null,
        @JsonProperty("original_name")
        val originalName: String? = null,
        @JsonProperty("original_language")
        val originalLanguage: String? = null,
        @JsonProperty("poster_path")
        val posterPath: String? = null,
        @JsonProperty("release_date")
        val releaseDate: String? = null,
        @JsonProperty("first_air_date")
        val firstAirDate: String? = null,
        @JsonProperty("vote_average")
        val voteAverage: Double? = null
    ) {
        val displayTitle: String
            get() = (if (originalLanguage == "ar") sequenceOf(title, name, originalTitle, originalName).filterNotNull().filter { Regex("""[\u0600-\u06FF]""").containsMatchIn(it) } + sequenceOf(title, name, originalTitle, originalName).filterNotNull() else sequenceOf(title, name, originalTitle, originalName))
                .filterNotNull().map(String::trim)
                .firstOrNull(String::isNotBlank).orEmpty()

        val year: Int?
            get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    }

    private enum class PreferredSubtitleLanguage {
        ARABIC,
        ENGLISH,
        OTHER
    }

    private val targetCache = ConcurrentHashMap<String, CachedTarget>()
    private val providerHealth = ConcurrentHashMap<String, Int>()
    private val synopsisCache = ConcurrentHashMap<String, String>()
    private val subtitleCache = ConcurrentHashMap<String, CachedSubtitleList>()
    // Cache *verified* subtitle bodies, not just index metadata. Reopening the
    // same film should not download every SRT again on a low-memory Fire TV.
    private val subtitleCheckCache = ConcurrentHashMap<String, CachedSubtitleCheck>()

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val subtitleCacheLifetimeMs = 15 * 60 * 1000L
    private val successfulSubtitleCheckLifetimeMs = 6 * 60 * 1000L
    private val failedSubtitleCheckLifetimeMs = 30 * 1000L
    private val providerResolveTimeoutMs = 30000L
    private val providerLinksTimeoutMs = 22000L
    private val streamPlayLinksTimeoutMs = 60000L
    // Subtitle discovery is not controlled by the Arabic video fast-mode setting.
    // Short network deadlines previously caused all tracks to silently disappear.
    private val subtitleTimeoutMs = 5200L
    private val linkProbeTimeoutMs = 10500L
    private val linkProbeBatchSize = 10

    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiUrl = "https://api.themoviedb.org/3"

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

    private fun normalizeTitle(value: String): String =
        value.lowercase()
            .replace("&", "and")
            .replace(Regex("""[’'\u2018\u2019]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun normalizeArabicOrthography(
        value: String
    ): String = buildString {
        for (ch in value) {
            when (ch) {
                '٠', '۰' -> append('0')
                '١', '۱' -> append('1')
                '٢', '۲' -> append('2')
                '٣', '۳' -> append('3')
                '٤', '۴' -> append('4')
                '٥', '۵' -> append('5')
                '٦', '۶' -> append('6')
                '٧', '۷' -> append('7')
                '٨', '۸' -> append('8')
                '٩', '۹' -> append('9')
                'أ', 'إ', 'آ', 'ٱ' -> append('ا')
                'ى' -> append('ي')
                'ؤ' -> append('و')
                'ئ' -> append('ي')
                'ة' -> append('ه')
                'ـ' -> Unit
                in '\u064B'..'\u065F' -> Unit
                '\u0670' -> Unit
                else -> append(ch)
            }
        }
    }

    private fun canonicalTitle(value: String): String {
        var text =
            normalizeArabicOrthography(
                normalizeTitle(value)
            )

        text = text
            .replace(
                Regex("""\b(?:19|20)\d{2}\b"""),
                " "
            )
            .replace(
                Regex(
                    """\b(?:2160p|1440p|1080p|720p|480p|4k|uhd|fhd|hdr|hevc|x264|x265|web[- ]?dl|webrip|bluray|blu[- ]?ray|dvdrip|hdtv)\b"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\bseason\s*\d{1,2}\b"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\b(?:episode|ep)\s*\d{1,4}\b"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\be\d{1,4}\b"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\bs\d{1,2}\b"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\b(?:movie|series|show|watch|download|subbed|dubbed|translated|complete|all episodes|full series|online)\b"""
                ),
                " "
            )
            .replace(
                Regex(
                    """(^|\s)(?:فيلم|مسلسل|مسلسلات|برنامج|مشاهدة|تحميل|مترجم|مدبلج|اونلاين|اون لاين|كامل)(?=\s|$)"""
                ),
                " "
            )
            .replace(
                Regex(
                    """(^|\s)الموسم\s*(?:\d{1,2}|الاول|الأول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر)(?=\s|$)"""
                ),
                " "
            )
            .replace(
                Regex("""(^|\s)(?:ج|جزء|الجزء)\s*\d{1,2}(?=\s|$)"""),
                " "
            )
            .replace(
                Regex("""(^|\s)(?:الحلقه|حلقه)\s*\d{1,4}(?:\s+(?:الاولى|الاول|الثانيه|الثاني|الثالثه|الثالث|الرابعه|الرابع|الخامسه|الخامس))?(?=\s|$)"""),
                " "
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()

        return text
    }

    private fun titleTokens(value: String): Set<String> =
        canonicalTitle(value)
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()

    private fun titleSimilarity(
        candidate: String,
        aliases: List<String>
    ): Double {
        val candidateCanonical =
            canonicalTitle(candidate)

        if (candidateCanonical.isBlank()) {
            return 0.0
        }

        var best = 0.0

        for (alias in aliases) {
            val wanted =
                canonicalTitle(alias)

            if (wanted.isBlank()) continue

            if (candidateCanonical == wanted) {
                return 1.0
            }

            val a = titleTokens(candidateCanonical)
            val b = titleTokens(wanted)

            if (a.isEmpty() || b.isEmpty()) continue

            val common =
                a.intersect(b).size.toDouble()

            val union =
                a.union(b).size.toDouble()

            val jaccard =
                if (union > 0.0) {
                    common / union
                } else {
                    0.0
                }

            val containment =
                common /
                    minOf(
                        a.size,
                        b.size
                    ).coerceAtLeast(1)

            val lengthRatio =
                minOf(
                    candidateCanonical.length,
                    wanted.length
                ).toDouble() /
                    maxOf(
                        candidateCanonical.length,
                        wanted.length
                    ).coerceAtLeast(1).toDouble()

            val score =
                maxOf(
                    jaccard,
                    containment * lengthRatio
                )

            if (score > best) {
                best = score
            }
        }

        return best
    }

    private fun resultYear(
        result: SearchResponse
    ): Int? = when (result) {
        is MovieSearchResponse -> result.year
        is TvSeriesSearchResponse -> result.year
        else -> null
    }

    private fun exactTitleMatch(
        candidate: String,
        aliases: List<String>
    ): Boolean =
        titleSimilarity(
            candidate,
            aliases
        ) >= 0.999

    private fun safeTitleMatch(
        candidate: String,
        aliases: List<String>,
        candidateYear: Int?,
        wantedYear: Int?
    ): Boolean {
        val similarity =
            titleSimilarity(
                candidate,
                aliases
            )

        if (similarity >= 0.999) {
            return yearCompatible(
                candidateYear,
                wantedYear
            )
        }

        if (
            candidateYear != null &&
            wantedYear != null
        ) {
            return abs(
                candidateYear - wantedYear
            ) <= 1 &&
                similarity >= 0.82
        }

        return similarity >= 0.94
    }

    private fun yearCompatible(
        candidateYear: Int?,
        wantedYear: Int?
    ): Boolean {
        if (
            candidateYear == null ||
            wantedYear == null
        ) {
            return true
        }

        return abs(
            candidateYear - wantedYear
        ) <= 1
    }

    private fun searchTypeMatches(
        result: SearchResponse,
        wantSeries: Boolean
    ): Boolean {
        if (wantSeries) {
            if (
                result is TvSeriesSearchResponse
            ) {
                return true
            }

            return result.type in setOf(
                TvType.TvSeries,
                TvType.Cartoon,
                TvType.Anime,
                TvType.OVA,
                TvType.AsianDrama
            )
        }

        if (
            result is MovieSearchResponse
        ) {
            return true
        }

        return result.type in setOf(
            TvType.Movie,
            TvType.AnimeMovie,
            TvType.Documentary
        )
    }

    private fun tokenSet(
        value: String
    ): Set<String> =
        canonicalTitle(value)
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun explicitSeasonNumber(value: String): Int? {
        val text = normalizeArabicOrthography(normalizeTitle(value))
        val markers = Regex("""(?:^|\s)(?:الموسم|موسم|season|الجزء|جزء|part|ج|s)\s*([\p{L}\d]+)""", RegexOption.IGNORE_CASE)
        val ordinals = mapOf(
            "الاول" to 1, "الاولى" to 1, "اول" to 1,
            "الثاني" to 2, "الثانيه" to 2, "ثاني" to 2,
            "الثالث" to 3, "الثالثه" to 3, "ثالث" to 3,
            "الرابع" to 4, "الرابعه" to 4, "رابع" to 4,
            "الخامس" to 5, "الخامسه" to 5, "خامس" to 5,
            "السادس" to 6, "السادسه" to 6, "سادس" to 6,
            "السابع" to 7, "السابعه" to 7, "سابع" to 7,
            "الثامن" to 8, "الثامنه" to 8, "ثامن" to 8,
            "التاسع" to 9, "التاسعه" to 9, "تاسع" to 9,
            "العاشر" to 10, "العاشره" to 10, "عاشر" to 10
        )
        return markers.findAll(text).mapNotNull { match ->
            val token = match.groupValues[1]
            token.toIntOrNull() ?: ordinals[token]
        }.firstOrNull()
    }

    private fun seasonSpecificTitleMatch(
        candidate: String,
        aliases: List<String>,
        season: Int
    ): Boolean {
        val explicitSeason = explicitSeasonNumber(candidate)
        if (explicitSeason != null && explicitSeason != season) return false

        val candidateCanonical = canonicalTitle(candidate)
        if (candidateCanonical.isBlank()) return false
        val candidateTokens = tokenSet(candidateCanonical)
        val candidateHasSeason = explicitSeason == season || season.toString() in candidateTokens

        for (alias in aliases) {
            val wanted = canonicalTitle(alias)
            if (wanted.isBlank()) continue
            if (candidateCanonical == wanted) return true
            val wantedTokens = tokenSet(wanted)
            if (candidateHasSeason && wantedTokens.isNotEmpty() && wantedTokens.all { it in candidateTokens }) {
                return true
            }
            val wantedSeason = explicitSeasonNumber(alias)
            if (wantedSeason == season && wantedTokens.all { it in candidateTokens }) {
                return true
            }
        }
        return false
    }

    private fun strictArabicTitleMatch(
        candidate: String,
        aliases: List<String>,
        candidateYear: Int?,
        wantedYear: Int?,
        wantSeries: Boolean,
        season: Int?
    ): Boolean {
        val candidateCanonical =
            canonicalTitle(candidate)

        val arabicAliases =
            aliases.filter {
                Regex("""[\u0600-\u06FF]""")
                    .containsMatchIn(it)
            }

        val usableAliases =
            if (arabicAliases.isNotEmpty()) {
                arabicAliases
            } else {
                aliases
            }

        val canonicalAliases =
            usableAliases
                .map(::canonicalTitle)
                .filter { it.isNotBlank() }
                .distinct()

        // Even an exact series-title match cannot override an explicit,
        // contradictory season in the provider result.
        if (wantSeries && season != null) {
            val explicit = explicitSeasonNumber(candidate)
            if (explicit != null && explicit != season) return false
        }

        if (
            candidateCanonical in
                canonicalAliases
        ) {
            return if (wantSeries) {
                true
            } else {
                yearCompatible(
                    candidateYear,
                    wantedYear
                )
            }
        }

        if (
            wantSeries &&
            season != null &&
            seasonSpecificTitleMatch(
                candidate,
                usableAliases,
                season
            )
        ) {
            return true
        }

        val candidateTokens =
            tokenSet(candidateCanonical)

        if (candidateTokens.size < 2) {
            return false
        }

        val similarity =
            titleSimilarity(
                candidate,
                usableAliases
            )

        val enoughSharedTokens =
            canonicalAliases.any { alias ->
                val wantedTokens =
                    tokenSet(alias)

                candidateTokens
                    .intersect(wantedTokens)
                    .size >= 2
            }

        if (!enoughSharedTokens) {
            return false
        }

        return if (wantSeries) {
            similarity >= 0.96
        } else {
            yearCompatible(
                candidateYear,
                wantedYear
            ) &&
                similarity >= 0.94
        }
    }

    private fun strictSearchScore(
        result: SearchResponse,
        aliases: List<String>,
        year: Int?,
        wantSeries: Boolean,
        originalLanguage: String?,
        season: Int?
    ): Int {
        val candidateYear =
            resultYear(result)

        val wantedYear =
            if (wantSeries) null else year

        val isArabicOriginal =
            originalLanguage.equals(
                "ar",
                ignoreCase = true
            )

        val titleMatches =
            if (isArabicOriginal) {
                strictArabicTitleMatch(
                    candidate = result.name,
                    aliases = aliases,
                    candidateYear = candidateYear,
                    wantedYear = wantedYear,
                    wantSeries = wantSeries,
                    season = season
                )
            } else {
                safeTitleMatch(
                    result.name,
                    aliases,
                    candidateYear,
                    wantedYear
                )
            }

        if (!titleMatches) {
            return 0
        }

        val similarity =
            titleSimilarity(
                result.name,
                aliases
            )

        val typeBonus =
            if (
                searchTypeMatches(
                    result,
                    wantSeries
                )
            ) {
                10
            } else {
                -10
            }

        val seasonBonus =
            if (
                wantSeries &&
                season != null &&
                seasonSpecificTitleMatch(
                    result.name,
                    aliases,
                    season
                )
            ) {
                30
            } else {
                0
            }

        val base = when {
            similarity >= 0.999 &&
                wantedYear != null &&
                candidateYear == wantedYear -> 140

            similarity >= 0.999 -> 125

            wantedYear != null &&
                candidateYear != null &&
                candidateYear == wantedYear -> 112

            else -> 100
        }

        return base +
            typeBonus +
            seasonBonus
    }

    private fun loadedIdentityMatches(
        result: LoadResponse,
        request: HubPlaybackRequest,
        aliases: List<String>
    ): Boolean {
        val loadedImdb =
            result.getImdbId()

        val loadedTmdb =
            result.getTMDbId()
                ?.toIntOrNull()

        if (
            !request.imdbId.isNullOrBlank() &&
            !loadedImdb.isNullOrBlank() &&
            !loadedImdb.equals(
                request.imdbId,
                ignoreCase = true
            )
        ) {
            return false
        }

        if (
            request.tmdbId != null &&
            loadedTmdb != null &&
            loadedTmdb != request.tmdbId
        ) {
            return false
        }

        val idMatched =
            (
                !request.imdbId.isNullOrBlank() &&
                    !loadedImdb.isNullOrBlank() &&
                    loadedImdb.equals(
                        request.imdbId,
                        ignoreCase = true
                    )
                ) ||
                (
                    request.tmdbId != null &&
                        loadedTmdb != null &&
                        loadedTmdb == request.tmdbId
                    )

        if (idMatched) {
            return true
        }

        // Series premiere dates vary by season across Arabic providers.
        // Match the series identity and exact requested episode instead.
        val candidateHasArabic =
            Regex("""[\u0600-\u06FF]""")
                .containsMatchIn(result.name)

        val identityAliases =
            if (
                request.originalLanguage.equals(
                    "ar",
                    ignoreCase = true
                ) &&
                candidateHasArabic
            ) {
                (
                    listOf(request.title) +
                        aliases.filter {
                            Regex("""[\u0600-\u06FF]""")
                                .containsMatchIn(it)
                        }
                    )
                    .distinctBy(::canonicalTitle)
                    .take(10)
            } else {
                aliases
            }

        val wantSeries =
            request.season != null &&
                request.episode != null

        val wantedYear =
            if (wantSeries) {
                null
            } else {
                request.year
            }

        return if (
            request.originalLanguage.equals(
                "ar",
                ignoreCase = true
            )
        ) {
            strictArabicTitleMatch(
                candidate = result.name,
                aliases = identityAliases,
                candidateYear = result.year,
                wantedYear = wantedYear,
                wantSeries = wantSeries,
                season = request.season
            )
        } else {
            safeTitleMatch(
                candidate = result.name,
                aliases = identityAliases,
                candidateYear = result.year,
                wantedYear = wantedYear
            )
        }
    }

    private fun arabicSeasonWord(
        season: Int
    ): String? = when (season) {
        1 -> "الأول"
        2 -> "الثاني"
        3 -> "الثالث"
        4 -> "الرابع"
        5 -> "الخامس"
        6 -> "السادس"
        7 -> "السابع"
        8 -> "الثامن"
        9 -> "التاسع"
        10 -> "العاشر"
        else -> null
    }

    private suspend fun tmdbSeasonNames(
        request: HubPlaybackRequest
    ): List<String> {
        val id = request.tmdbId ?: return emptyList()
        val season = request.season ?: return emptyList()
        if (!request.isTv) return emptyList()

        return coroutineScope {
            listOf(
                "ar-SA",
                "ar",
                "en-US"
            ).map { language ->
                async {
                    timed(2200L) {
                        app.get(
                            "$tmdbApiUrl/tv/$id/season/$season",
                            params = mapOf(
                                "api_key" to tmdbApiKey,
                                "language" to language
                            )
                        ).parsedSafe<TmdbSeasonDetail>()
                            ?.name
                            ?.trim()
                            ?.takeIf {
                                it.isNotBlank()
                            }
                    }
                }
            }.mapNotNull { it.await() }
                .distinctBy(::canonicalTitle)
        }
    }

    private suspend fun playbackAliases(
        request: HubPlaybackRequest
    ): List<String> {
        val base = timed(5200L) {
            MOOSACatalog.aliases(
                tmdbId = request.tmdbId,
                isTv = request.isTv,
                fallback = request.title
            )
        } ?: listOf(request.title)

        val season = request.season
        if (
            !request.isTv ||
            season == null
        ) {
            return base
                .distinctBy(::canonicalTitle)
                .take(16)
        }

        val seasonNames =
            tmdbSeasonNames(request)

        val arabicBase =
            base.filter {
                Regex("""[\u0600-\u06FF]""")
                    .containsMatchIn(it)
            }

        val preferredBase =
            if (
                request.originalLanguage.equals(
                    "ar",
                    ignoreCase = true
                ) &&
                arabicBase.isNotEmpty()
            ) {
                arabicBase + base
            } else {
                base
            }

        // A title such as "اللعبة" must be searched first.  Previously the
        // generated "اللعبة الموسم 5" occupied the same canonical-title key
        // and removed the plain query before it reached any provider.
        val generated = buildList {
            // Keep a few primary names first, then their season-specific forms.
            // Otherwise a long TMDB alias list consumes the entire search
            // budget before season 5 variants can run.
            addAll(preferredBase.take(2))
            preferredBase
                .take(2)
                .forEach { title ->
                    if (
                        Regex("""[\u0600-\u06FF]""")
                            .containsMatchIn(title)
                    ) {
                        add("$title الموسم $season")
                        add("$title الجزء $season")
                        arabicSeasonWord(season)?.let { word ->
                            add("$title الموسم $word")
                            add("$title الجزء $word")
                        }
                        add("$title ج$season")
                        add("$title $season")
                    } else {
                        add("$title Season $season")
                    }
                }
            addAll(seasonNames)
            addAll(preferredBase.drop(2))
        }

        return generated
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeArabicOrthography(normalizeTitle(it)) }
            .take(24)
    }

    private fun isStreamPlayProvider(
        api: MainAPI
    ): Boolean {
        val label =
            "${api.name} ${api.mainUrl} ${api.javaClass.name}"
                .lowercase()

        return "streamplay" in label ||
            "stream play" in label
    }

    private fun isArabicProvider(
        api: MainAPI
    ): Boolean {
        if (
            api.lang.equals(
                "ar",
                ignoreCase = true
            )
        ) {
            return true
        }

        val label =
            "${api.name} ${api.mainUrl} ${api.javaClass.name}"
                .lowercase()

        return listOf(
            "akwam",
            "اكوام",
            "fasel",
            "فاصل",
            "topcinema",
            "top cinema",
            "wecima",
            "cima",
            "arabseed",
            "arab seed",
            "shahid",
            "egy",
            "mycima"
        ).any { it in label }
    }

    private fun searchTimeoutFor(
        api: MainAPI,
        request: HubPlaybackRequest
    ): Long =
        when {
            // Akwam S1 discovery uses a search page plus an older-season
            // page, so it needs enough time for both network requests.
            isAkwamProvider(api) &&
                request.isTv -> 13500L

            isArabicProvider(api) &&
                request.isTv -> 8500L

            isArabicProvider(api) -> 6500L

            isStreamPlayProvider(api) -> 5000L

            else -> 3600L
        }

    private fun providerLoadTimeoutFor(
        api: MainAPI,
        request: HubPlaybackRequest
    ): Long =
        when {
            request.originalLanguage.equals("ar", true) &&
                MOOSASettingsStore.arabicFastMode() &&
                isAkwamProvider(api) -> 12000L

            request.originalLanguage.equals("ar", true) &&
                MOOSASettingsStore.arabicFastMode() &&
                isArabicProvider(api) -> 8000L

            isArabicProvider(api) &&
                request.isTv -> 30000L

            isArabicProvider(api) -> 14000L

            isStreamPlayProvider(api) -> 22000L

            else -> 7500L
        }

    private fun providerResolveTimeoutFor(
        api: MainAPI,
        request: HubPlaybackRequest
    ): Long =
        when {
            request.originalLanguage.equals("ar", true) &&
                MOOSASettingsStore.arabicFastMode() &&
                isAkwamProvider(api) -> 35000L

            request.originalLanguage.equals("ar", true) &&
                MOOSASettingsStore.arabicFastMode() &&
                isArabicProvider(api) -> 13000L

            isArabicProvider(api) &&
                request.isTv -> 100000L

            isArabicProvider(api) -> 38000L

            isStreamPlayProvider(api) -> 35000L

            else -> providerResolveTimeoutMs
        }

    private fun isAkwamProvider(api: MainAPI): Boolean =
        api.name.equals("Akwam", ignoreCase = true) ||
            api.javaClass.name.endsWith(".AkwamProvider")

    private fun isArabSeedProvider(api: MainAPI): Boolean =
        api.name.equals("ArabSeed", ignoreCase = true) &&
            isBundledArabicProvider(api)

    private fun arabSeedEpisodeConfirmed(
        api: MainAPI,
        result: SearchResponse,
        request: HubPlaybackRequest,
        wantSeries: Boolean
    ): Boolean {
        if (!isArabSeedProvider(api) || !wantSeries) return true

        // MySeed lists individual episodes as separate search entries.
        // A matching show/season but the wrong EP must never stop search.
        val season = request.season ?: return false
        val episode = request.episode ?: return false
        // A source may also provide a genuine series collection page
        // without an episode number. Let its load() provide verified episodes.
        val listedEpisode = explicitArabicEpisodeNumber(result.name)
            ?: return true
        val explicit = explicitSeasonNumber(result.name)

        // MySeed titles sometimes use "اللعبة 4 الحلقة 19" instead of
        // "اللعبة الموسم الرابع الحلقة 19". Require the numeric suffix
        // to follow an exact title alias; never guess from an arbitrary
        // number within the name.
        val normalized = normalizeArabicOrthography(normalizeTitle(result.name))
        val priorToEpisode = normalized.substringBefore(" الحلقه")
            .substringBefore(" حلقه")
            .substringBefore(" episode")
            .trim()
        val lastToken = priorToEpisode.substringAfterLast(' ').toIntOrNull()
        val numberedSeason = lastToken
            ?.takeIf { it in 1..30 }
            ?.takeIf {
                val prefix = priorToEpisode.substringBeforeLast(' ').trim()
                val allowed = (listOf(request.title) + request.titleAliases)
                    .any { alias -> canonicalTitle(prefix) == canonicalTitle(alias) }
                allowed
            }
        val listedSeason = explicit ?: numberedSeason

        return listedEpisode == episode &&
            (listedSeason == season || (season == 1 && listedSeason == null))
    }

    // Akwam lists each season as a separate series page. A plain base-title
    // hit is season 1, not evidence that seasons 2/3 were found. Keep searching
    // until a result explicitly identifies the requested season.
    private fun akwamSeasonConfirmed(
        api: MainAPI,
        result: SearchResponse,
        request: HubPlaybackRequest,
        wantSeries: Boolean
    ): Boolean {
        if (
            !isAkwamProvider(api) ||
            !wantSeries ||
            !request.originalLanguage.equals("ar", ignoreCase = true)
        ) return true

        val season = request.season ?: return true
        val explicit = explicitSeasonNumber(result.name)
        // Akwam also names seasons as "العتاولة 2", not only
        // "العتاولة الموسم الثاني". Require the prefix to match the
        // requested series exactly before treating a suffix as a season.
        val suffix = Regex("""\s+(\d{1,2})\s*$""")
            .find(normalizeArabicOrthography(result.name))
        val numberedSeason = suffix?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 1..30 }
            ?.takeIf {
                val prefix = result.name.substring(0, result.name.length - suffix.value.length)
                canonicalTitle(prefix) == canonicalTitle(request.title)
            }
        val foundSeason = explicit ?: numberedSeason
        return if (season == 1) {
            foundSeason == 1 ||
                (foundSeason == null &&
                    canonicalTitle(result.name) == canonicalTitle(request.title))
        } else {
            foundSeason == season
        }
    }

    private suspend fun searchProvider(
        api: MainAPI,
        request: HubPlaybackRequest,
        aliases: List<String>,
        year: Int?,
        wantSeries: Boolean,
        originalLanguage: String?,
        season: Int?
    ): List<SearchResponse> {
        val cleanAliases = aliases
            .map { it.trim() }
            .filter { it.isNotBlank() }
            // Keep "اللعبة" and "اللعبة الموسم 5" as separate search queries.
            .distinctBy { normalizeArabicOrthography(normalizeTitle(it)) }
            .take(if (originalLanguage.equals("ar", true)) 12 else 16)

        // Akwam indexes every season as a different series. Searching the
        // bare title first can return only the newest seasons or S1 and
        // waste most of the resolver budget. Prioritize the requested
        // season's Arabic WORD alias, which matches Akwam's own titles.
        val orderedAliases =
            if (
                isAkwamProvider(api) &&
                wantSeries &&
                season != null &&
                season > 1 &&
                originalLanguage.equals("ar", true)
            ) {
                val ordinal = arabicSeasonWord(season)
                val seasonAliases = cleanAliases
                    .filter { explicitSeasonNumber(it) == season }
                    .sortedBy { alias ->
                        when {
                            ordinal != null &&
                                alias.contains("الموسم $ordinal") -> 0
                            Regex("""(?:^|\s)ج\s*$season(?:\s|$)""")
                                .containsMatchIn(alias) -> 1
                            else -> 2
                        }
                    }
                seasonAliases + cleanAliases.filterNot { it in seasonAliases }
            } else {
                cleanAliases
            }

        val isEpisodeIndex = isArabSeedProvider(api) ||
            api.name.equals("TopCinema", ignoreCase = true)
        // Episode-indexed Arabic sites need the requested episode, not just
        // the newest result sharing the show's name.
        val episodeQueries = if (
            wantSeries && isEpisodeIndex && originalLanguage.equals("ar", true) &&
            request.episode != null
        ) {
            orderedAliases.filter {
                Regex("""[\u0600-\u06FF]""").containsMatchIn(it) &&
                    explicitSeasonNumber(it) == null
            }.take(2).flatMap { title ->
                listOf("$title الحلقة ${request.episode}",
                    "$title الموسم $season الحلقة ${request.episode}")
            }
        } else emptyList()
        val queryVariants = buildList {
            addAll(episodeQueries)
            addAll(orderedAliases)
            if (year != null && !wantSeries) {
                orderedAliases.take(2).forEach { alias ->
                    add("$alias $year")
                }
            }
        }.distinct().take(if (isArabSeedProvider(api)) 6 else if (isEpisodeIndex) 8 else 12)

        val results = mutableListOf<SearchResponse>()

        for ((index, query) in queryVariants.withIndex()) {
            val maxPages = when {
                // Akwam ignores pagination; its search already finds seasons.
                isAkwamProvider(api) -> 1
                // MySeed's public search has actual WordPress-style pages;
                // older episodes can appear on pages 3-5.
                isArabSeedProvider(api) && wantSeries && index < 2 -> 3
                isEpisodeIndex && wantSeries && index < 3 -> 3
                index < 2 -> 2
                else -> 1
            }

            for (page in 1..maxPages) {
                val found = timed(searchTimeoutFor(api, request)) {
                    api.search(query, page)?.items.orEmpty()
                }.orEmpty()
                results += found

                val matchingEpisode = found.any { item ->
                    akwamSeasonConfirmed(api, item, request, wantSeries) &&
                        arabSeedEpisodeConfirmed(api, item, request, wantSeries) &&
                        strictSearchScore(
                            item,
                            aliases,
                            year,
                            wantSeries,
                            originalLanguage,
                            season
                        ) >= 90
                }
                val confirmedEpisode = if (isArabSeedProvider(api) && wantSeries) {
                    found.any { item ->
                        explicitArabicEpisodeNumber(item.name) == request.episode &&
                            arabSeedEpisodeConfirmed(api, item, request, true) &&
                            strictSearchScore(item, aliases, year, true, originalLanguage, season) >= 90
                    }
                } else matchingEpisode
                if (confirmedEpisode) {
                    return results.distinctBy { it.url }
                }
                if (found.isEmpty()) break
            }
        }

        return results.distinctBy { it.url }
    }

    private data class MatchedLoad(
        val response: LoadResponse,
        val searchTitle: String
    )

    private fun explicitArabicEpisodeNumber(value: String): Int? {
        val normalized = normalizeArabicOrthography(normalizeTitle(value))
        val ep = Regex(
            """(?:الحلقه|حلقه|episode|ep)\s*[:#-]?\s*(\d{1,4}|الاولى|الاول|الثانيه|الثاني|الثالثه|الثالث|الرابعه|الرابع|الخامسه|الخامس)""",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1)
            ?: return null

        return ep.toIntOrNull() ?: mapOf(
            "الاولى" to 1, "الاول" to 1,
            "الثانيه" to 2, "الثاني" to 2,
            "الثالثه" to 3, "الثالث" to 3,
            "الرابعه" to 4, "الرابع" to 4,
            "الخامسه" to 5, "الخامس" to 5
        )[ep]
    }

    private fun explicitArabicEpisodeMovieMatches(
        movie: MovieLoadResponse,
        searchTitle: String,
        request: HubPlaybackRequest,
        aliases: List<String>
    ): Boolean {
        if (!request.originalLanguage.equals("ar", ignoreCase = true)) return false

        val season = request.season ?: return false
        val episode = request.episode ?: return false
        val titles = listOf(searchTitle, movie.name)

        if (titles.any {
                val s = explicitSeasonNumber(it)
                s != null && s != season
            }) return false

        if (titles.any {
                val ep = explicitArabicEpisodeNumber(it)
                ep != null && ep != episode
            }) return false

        // Never use movie-typed search results as arbitrary episodes.  A
        // genuine episode page must prove both season and episode itself.
        if (!titles.any {
                explicitSeasonNumber(it) == season &&
                    explicitArabicEpisodeNumber(it) == episode
            }) return false

        return !movie.dataUrl.isNullOrBlank() &&
            loadedIdentityMatches(movie, request, aliases)
    }

    private suspend fun loadBestMatch(
        api: MainAPI,
        request: HubPlaybackRequest,
        aliases: List<String>
    ): MatchedLoad? {
        val wantSeries =
            request.season != null &&
                request.episode != null

        fun correctType(
            response: LoadResponse?
        ): Boolean =
            if (wantSeries) {
                response is TvSeriesLoadResponse
            } else {
                response is MovieLoadResponse
            }

        val isStreamPlay =
            isStreamPlayProvider(api)

        if (
            isStreamPlay &&
            request.tmdbId != null
        ) {
            val directPayload =
                DirectTmdbRequest(
                    id = request.tmdbId,
                    type =
                        if (wantSeries) {
                            "tv"
                        } else {
                            "movie"
                        }
                ).toJson()

            val direct = timed(
                providerLoadTimeoutFor(
                    api,
                    request
                )
            ) {
                api.load(directPayload)
            }

            if (
                direct != null &&
                correctType(direct)
            ) {
                return MatchedLoad(direct, direct.name)
            }
        }

        if (
            !request.imdbId.isNullOrBlank() &&
            api.supportedSyncNames.contains(
                SyncIdName.Imdb
            )
        ) {
            val directUrl = timed(1800L) {
                api.getLoadUrl(
                    SyncIdName.Imdb,
                    request.imdbId
                )
            }

            if (!directUrl.isNullOrBlank()) {
                val direct = timed(
                    providerLoadTimeoutFor(
                        api,
                        request
                    )
                ) {
                    api.load(directUrl)
                }

                if (
                    correctType(direct) &&
                    direct != null &&
                    loadedIdentityMatches(
                        direct,
                        request,
                        aliases
                    )
                ) {
                    return MatchedLoad(direct, direct.name)
                }
            }
        }

        val searchResults = searchProvider(
            api = api,
            request = request,
            aliases = aliases,
            year = request.year,
            wantSeries = wantSeries,
            originalLanguage = request.originalLanguage,
            season = request.season
        )

        if (searchResults.isEmpty()) {
            MOOSAPlaybackDiagnostics.update(api, "Search returned no results")
        }

        val candidates = searchResults
            .asSequence()
            .filter {
                akwamSeasonConfirmed(api, it, request, wantSeries) &&
                    arabSeedEpisodeConfirmed(api, it, request, wantSeries)
            }
            .map {
                it to strictSearchScore(
                    it,
                    aliases,
                    request.year,
                    wantSeries,
                    request.originalLanguage,
                    request.season
                )
            }
            .filter { (_, score) ->
                score >= 90
            }
            .sortedByDescending {
                (_, score) -> score
            }
            .take(6)
            .toList()

        if (searchResults.isNotEmpty() && candidates.isEmpty()) {
            MOOSAPlaybackDiagnostics.update(
                api,
                "Search found ${searchResults.size} results, but no matching title/season"
            )
        }

        for ((match, _) in candidates) {
            val loaded = timed(
                providerLoadTimeoutFor(
                    api,
                    request
                )
            ) {
                api.load(match.url)
            } ?: continue

            if (
                wantSeries &&
                loaded is MovieLoadResponse &&
                explicitArabicEpisodeMovieMatches(
                    movie = loaded,
                    searchTitle = match.name,
                    request = request,
                    aliases = aliases
                )
            ) {
                return MatchedLoad(loaded, match.name)
            }

            if (!correctType(loaded)) continue

            if (
                loadedIdentityMatches(
                    loaded,
                    request,
                    aliases
                )
            ) {
                return MatchedLoad(loaded, match.name)
            }
        }

        if (candidates.isNotEmpty()) {
            MOOSAPlaybackDiagnostics.update(
                api,
                "Title matched, but loading/series identity failed"
            )
        }

        return null
    }

    private fun isBundledArabicProvider(api: MainAPI): Boolean =
        api.javaClass.name.startsWith("com.moosa.hub.bundled.")

    private fun playbackProviders(
        request: HubPlaybackRequest
    ): List<MainAPI> {
        val disabled =
            MOOSASettingsStore.disabledSources()

        fun hasOwnLoadLinks(api: MainAPI): Boolean =
            try {
                api.javaClass.declaredMethods.any {
                    it.name == "loadLinks"
                }
            } catch (_: Throwable) {
                false
            }

        val available = apis
            .filter { api ->
                // Arabic sites belong to the independent native provider.
                !isArabicProvider(api) &&
                api !== this &&
                    (
                        api.providerType ==
                            ProviderType.DirectProvider ||
                            hasOwnLoadLinks(api)
                        ) &&
                    disabled.none { blocked ->
                        blocked.equals(
                            api.name,
                            ignoreCase = true
                        )
                    }
            }

        // Prefer the copies shipped inside MOOSA Hub and avoid running two
        // implementations with the same provider name.
        val bundledNames = available
            .filter(::isBundledArabicProvider)
            .map { it.name.lowercase() }
            .toSet()

        val arabicRequest = request.originalLanguage.equals("ar", true)
        // Only bundled Akwam has user-confirmed Arabic playback.
        // Leave failed candidates available as standalone providers for
        // testing, but don't waste the Hub's Arabic lookup budget on them.
        // This is a temporary quarantine, not a claim that they never work.
        // TopCinema now has independently probed 1080p/720p HLS segment
        // delivery on Vidara (2026-10-14). Use it after confirmed Akwam
        // when the Akwam target is missing or fails, not as a fake mirror.
        // ArabSeed now parses individual Arabic episode posts and exact
        // season/episode matches. Re-enable as a strictly verified FALLBACK
        // after Akwam, not as a simultaneous slow candidate.
        val experimentalBundled = setOf(
            "WeCima", "CimaClub", "ShahidWBas"
        )

        return available
            .filter { api ->
                (isBundledArabicProvider(api) ||
                    api.name.lowercase() !in bundledNames) &&
                    !(arabicRequest && isBundledArabicProvider(api) &&
                        api.name in experimentalBundled)
            }
            .sortedWith(
                compareByDescending<MainAPI> {
                    when {
                        arabicRequest && isAkwamProvider(it) -> 6
                        arabicRequest && it.name.equals("ShahidWBas", true) -> 5
                        arabicRequest && isArabSeedProvider(it) -> 4
                        isBundledArabicProvider(it) -> 3
                        arabicRequest && MOOSAArabicBridge.supports(it) -> 2
                        arabicRequest && it.lang.equals("ar", true) -> 1
                        else -> 0
                    }
                }.thenByDescending {
                    providerHealth[it.name] ?: 0
                }.thenByDescending {
                    if (
                        !request.imdbId.isNullOrBlank() &&
                        it.supportedSyncNames.contains(
                            SyncIdName.Imdb
                        )
                    ) {
                        1
                    } else {
                        0
                    }
                }
            )
    }

    private fun providerCacheKey(
        request: HubPlaybackRequest,
        api: MainAPI
    ): String = listOf(
        "strict-v15",
        request.imdbId.orEmpty(),
        request.tmdbId?.toString().orEmpty(),
        request.originalLanguage.orEmpty(),
        normalizeTitle(request.title),
        request.year?.toString().orEmpty(),
        request.season?.toString().orEmpty(),
        request.episode?.toString().orEmpty(),
        api.name,
        api.mainUrl
    ).joinToString("|")

    private suspend fun resolveTargetForProvider(
        request: HubPlaybackRequest,
        api: MainAPI,
        aliases: List<String>,
        forceRefresh: Boolean = false
    ): ProviderTarget? {
        val cacheKey = providerCacheKey(request, api)

        if (forceRefresh) {
            targetCache.remove(cacheKey)
        }

        targetCache[cacheKey]?.let { cached ->
            if (
                System.currentTimeMillis() -
                    cached.createdAt <
                    if (request.originalLanguage.equals("ar", true)) {
                        3 * 60 * 1000L
                    } else {
                        cacheLifetimeMs
                    }
            ) {
                return cached.target
            }
            targetCache.remove(cacheKey)
        }

        if (
            request.originalLanguage.equals(
                "ar",
                ignoreCase = true
            ) &&
            !isBundledArabicProvider(api) &&
            MOOSAArabicBridge.supports(api)
        ) {
            // External repositories are unpredictable. Cap the whole bridge,
            // not just each HTTP call, or six failed searches can stall the
            // CloudStream loading dialog for over a minute.
            val bridged = timed(
                if (MOOSASettingsStore.arabicFastMode()) 14_000L else 25_000L
            ) {
                MOOSAArabicBridge.resolve(
                    api = api,
                    request = request,
                    aliases = aliases
                )
            }

            if (bridged != null) {
                targetCache[cacheKey] =
                    CachedTarget(
                        createdAt =
                            System.currentTimeMillis(),
                        target = bridged
                    )

                return bridged
            }
            // In fast mode the bridge has already tried the relevant Arabic
            // aliases. Don't repeat the whole search through a second resolver.
            if (MOOSASettingsStore.arabicFastMode()) return null
        }

        val isSeries =
            request.season != null &&
                request.episode != null
        val matched = timed(
            providerResolveTimeoutFor(
                api,
                request
            )
        ) {
            loadBestMatch(
                api = api,
                request = request,
                aliases = aliases
            )
        } ?: return null
        val loaded = matched.response

        val data = if (!isSeries || loaded is MovieLoadResponse) {
            (loaded as? MovieLoadResponse)?.dataUrl
        } else {
            val series = loaded as? TvSeriesLoadResponse ?: return null
            val season = request.season ?: return null
            val episode = request.episode ?: return null

            fun normalizeDigits(
                value: String
            ): String = buildString {
                value.forEach { ch ->
                    append(
                        when (ch) {
                            '٠', '۰' -> '0'
                            '١', '۱' -> '1'
                            '٢', '۲' -> '2'
                            '٣', '۳' -> '3'
                            '٤', '۴' -> '4'
                            '٥', '۵' -> '5'
                            '٦', '۶' -> '6'
                            '٧', '۷' -> '7'
                            '٨', '۸' -> '8'
                            '٩', '۹' -> '9'
                            else -> ch
                        }
                    )
                }
            }

            fun explicitEpisodeNumber(
                value: String?
            ): Int? {
                if (value.isNullOrBlank()) {
                    return null
                }

                val normalized =
                    normalizeDigits(value)

                val patterns = listOf(
                    Regex(
                        """(?:الحلقة|الحلقه|حلقة|حلقه)\s*[:#\-]?\s*(\d{1,4})"""
                    ),
                    Regex(
                        """(\d{1,4})\s*(?:الحلقة|الحلقه|حلقة|حلقه)"""
                    ),
                    Regex(
                        """\b(?:episode|ep)\s*[:#\-]?\s*(\d{1,4})\b""",
                        RegexOption.IGNORE_CASE
                    ),
                    Regex(
                        """\bs\d{1,3}\s*e(\d{1,4})\b""",
                        RegexOption.IGNORE_CASE
                    ),
                    Regex(
                        """[?&](?:episode|ep)=(\d{1,4})(?:&|$)""",
                        RegexOption.IGNORE_CASE
                    )
                )

                patterns.forEach { pattern ->
                    pattern.find(normalized)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.let { return it }
                }

                return normalized
                    .trim()
                    .takeIf {
                        it.matches(
                            Regex("""\d{1,4}""")
                        )
                    }
                    ?.toIntOrNull()
            }

            fun explicitSeasonNumber(
                value: String?
            ): Int? {
                if (value.isNullOrBlank()) {
                    return null
                }

                val normalized =
                    normalizeDigits(value)

                val patterns = listOf(
                    Regex(
                        """(?:الموسم|موسم)\s*[:#\-]?\s*(\d{1,3})"""
                    ),
                    Regex(
                        """\bs(\d{1,3})\s*e\d{1,4}\b""",
                        RegexOption.IGNORE_CASE
                    ),
                    Regex(
                        """[?&](?:season|s)=(\d{1,3})(?:&|$)""",
                        RegexOption.IGNORE_CASE
                    )
                )

                patterns.forEach { pattern ->
                    pattern.find(normalized)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.let { return it }
                }

                return null
            }

            fun providerSeason(
                item: Episode
            ): Int? =
                item.season
                    ?.takeIf {
                        it in 1..200
                    }
                    ?: explicitSeasonNumber(
                        item.name
                    )
                    ?: explicitSeasonNumber(
                        item.data
                    )

            fun episodeNumber(
                item: Episode
            ): Int? =
                item.episode
                    ?.takeIf { it > 0 }
                    ?: explicitEpisodeNumber(
                        item.name
                    )
                    ?: explicitEpisodeNumber(
                        item.data
                    )

            val exact =
                series.episodes.firstOrNull {
                    providerSeason(it) == season &&
                        episodeNumber(it) == episode
                }

            val explicitSeasons =
                series.episodes
                    .mapNotNull(::providerSeason)
                    .distinct()

            // A provider can number episodes as season 1 inside a
            // dedicated S5 page.  Remap ONLY if its page/search title
            // explicitly identifies this requested season.
            val pageProvesSeason =
                listOf(series.name, matched.searchTitle).any { title ->
                    explicitSeasonNumber(title) == season &&
                        seasonSpecificTitleMatch(title, aliases, season)
                }

            val seasonless =
                if (
                    exact == null &&
                    (
                        explicitSeasons.isEmpty() ||
                            explicitSeasons == listOf(1)
                    ) &&
                    (season == 1 || pageProvesSeason)
                ) {
                    series.episodes.firstOrNull { item ->
                        episodeNumber(item) == episode &&
                            (
                                providerSeason(item) == null ||
                                    providerSeason(item) == 1
                                )
                    }
                } else {
                    null
                }

            val firstSeasonFallback =
                if (
                    exact == null &&
                    season == 1
                ) {
                    series.episodes.firstOrNull {
                        episodeNumber(it) == episode &&
                            (
                                providerSeason(it) == null ||
                                    providerSeason(it) == 1
                                )
                    }
                } else {
                    null
                }

            val episodeData = (
                exact
                    ?: seasonless
                    ?: firstSeasonFallback
                )?.data

            if (episodeData.isNullOrBlank()) {
                MOOSAPlaybackDiagnostics.update(
                    api,
                    "Series matched, but S${season}E${episode} not confirmed"
                )
            }
            episodeData
        }?.takeIf { it.isNotBlank() } ?: return null

        val target = ProviderTarget(
            providerName = api.name,
            mainUrl = api.mainUrl,
            data = data
        )

        targetCache[cacheKey] = CachedTarget(
            createdAt = System.currentTimeMillis(),
            target = target
        )

        return target
    }

    private fun decodeJsonString(raw: String): String {
        return tryParseJson<String>("\"$raw\"")
            ?: raw.replace("\\n", "\n").replace("\\\"", "\"")
    }

    private fun cleanSynopsis(value: String?): String? {
        val raw = value
            ?.replace(Regex("""https?://\S+"""), " ")
            ?.replace(
                Regex("""\b[a-fA-F0-9]{16,}\b"""),
                " "
            )
            ?.replace(
                Regex("""\b[A-Za-z0-9_-]{24,}\b"""),
                " "
            )
            ?.replace("\uFFFD", " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()

        if (raw.length < 20) return null

        val visible = raw.count {
            it.isLetterOrDigit() ||
                it in '\u0600'..'\u06FF'
        }

        val ratio =
            visible.toDouble() /
                raw.length.coerceAtLeast(1).toDouble()

        return raw.takeIf {
            ratio >= 0.45 &&
                !it.contains("undefined", true) &&
                !it.contains("null null", true)
        }
    }

    private fun verifiedArabicSynopsis(raw: String?): String? =
        cleanSynopsis(raw)?.takeIf { text ->
            text.count { it in '\u0600'..'\u06FF' } >= 10 &&
                text.count { it in '\u0600'..'\u06FF' } * 4 >= text.count { it.isLetter() }
        }

    private suspend fun translateSynopsisExternally(source: String?): String? {
        // Only translate the plot, NEVER titles, search aliases, or names.
        // No user/account data is sent to the translation provider.
        val clean = cleanSynopsis(source) ?: return null
        verifiedArabicSynopsis(clean)?.let { return it }
        // A public, unofficial Google Translate web endpoint may be rate
        // limited. Keep it optional with a short timeout and validate output.
        val google = timed(3500L) {
            val raw = app.get(
                "https://translate.googleapis.com/translate_a/single",
                params = mapOf(
                    "client" to "gtx",
                    "sl" to "auto",
                    "tl" to "ar",
                    "dt" to "t",
                    "q" to clean.take(3500)
                )
            ).text
            val parts = JSONArray(raw).optJSONArray(0) ?: return@timed null
            buildString {
                for (index in 0 until parts.length()) {
                    append(parts.optJSONArray(index)?.optString(0).orEmpty())
                }
            }
        }
        verifiedArabicSynopsis(google)?.let { return it }

        // MyMemory's documented anonymous API allows at most 500 bytes
        // per query and a low shared daily quota. It is only a backup for
        // SHORT synopses; never silently truncate a long story.
        if (clean.toByteArray(Charsets.UTF_8).size > 450) return null
        val myMemory = timed(2700L) {
            val response = app.get(
                "https://api.mymemory.translated.net/get",
                params = mapOf("q" to clean, "langpair" to "en|ar")
            ).text
            val json = org.json.JSONObject(response)
            if (json.optInt("responseStatus", 0) != 200) return@timed null
            json.optJSONObject("responseData")
                ?.optString("translatedText")
                ?.replace("&quot;", "\"")
                ?.replace("&#39;", "'")
                ?.replace("&amp;", "&")
        }
        return verifiedArabicSynopsis(myMemory)
    }

    private suspend fun resolveArabicSynopsis(
        tmdbId: Int?,
        isTv: Boolean,
        englishFallback: String?
    ): String? {
        val source = cleanSynopsis(englishFallback)
        verifiedArabicSynopsis(source)?.let { return it }
        val id = tmdbId ?: return translateSynopsisExternally(source)
        val type = if (isTv) "tv" else "movie"
        val cacheKey = "$type:$id"
        // Do not cache missing-plot placeholders. Retry on the next visit
        // if a translation service was temporarily blocked.
        synopsisCache[cacheKey]?.let(::verifiedArabicSynopsis)?.let { return it }

        // Prioritize authentic Arabic TMDB descriptions. Parallel locale
        // lookups reduce the delay relative to four sequential requests.
        val locales = listOf("ar-SA", "ar-EG", "ar-AE", "ar")
        val localized = coroutineScope {
            locales.map { language ->
                async {
                    timed(1800L) {
                        app.get(
                            "$tmdbApiUrl/$type/$id",
                            params = mapOf(
                                "api_key" to tmdbApiKey,
                                "language" to language
                            )
                        ).parsedSafe<TmdbOverview>()?.overview
                    }
                }
            }.awaitAll()
        }
        localized.firstNotNullOfOrNull(::verifiedArabicSynopsis)?.let {
            synopsisCache[cacheKey] = it
            return it
        }

        // Official Arabic translation entries can exist even when a locale
        // detail API has no plot.
        val translations = timed(2500L) {
            app.get(
                "$tmdbApiUrl/$type/$id/translations",
                params = mapOf("api_key" to tmdbApiKey)
            ).parsedSafe<TmdbTranslationCollection>()
        }
        translations?.translations.orEmpty()
            .asSequence()
            .filter { it.language.equals("ar", ignoreCase = true) }
            .sortedBy { entry ->
                when (entry.region?.uppercase()) {
                    "SA" -> 0
                    "EG" -> 1
                    "AE" -> 2
                    else -> 3
                }
            }
            .mapNotNull { verifiedArabicSynopsis(it.data?.overview) }
            .firstOrNull()?.let {
                synopsisCache[cacheKey] = it
                return it
            }

        // The English description is fetched only when there is nothing
        // worth translating in the base metadata.
        val english = source ?: timed(1800L) {
            app.get(
                "$tmdbApiUrl/$type/$id",
                params = mapOf(
                    "api_key" to tmdbApiKey,
                    "language" to "en-US"
                )
            ).parsedSafe<TmdbOverview>()?.overview
        }
        translateSynopsisExternally(english)?.let {
            synopsisCache[cacheKey] = it
            return it
        }
        // No made-up description and no misleading untranslated English
        // text labelled Arabic. A temporary outage remains retryable.
        return null
    }

    private suspend fun tmdbDetail(
        tmdbId: Int?,
        isTv: Boolean
    ): TmdbDetail? {
        val id = tmdbId ?: return null
        val type = if (isTv) "tv" else "movie"

        return timed(2200L) {
            app.get(
                "$tmdbApiUrl/$type/$id",
                params = mapOf(
                    "api_key" to tmdbApiKey,
                    "language" to "en-US"
                )
            ).parsedSafe<TmdbDetail>()
        }
    }

    private suspend fun tmdbFallbackPoster(
        tmdbId: Int?,
        isTv: Boolean,
        preferredLanguage: String? = null
    ): String? {
        val id = tmdbId ?: return null
        val type = if (isTv) "tv" else "movie"

        val preferred =
            preferredLanguage
                ?.lowercase()
                ?.takeIf {
                    it == "ar" ||
                        it == "en"
                }

        val includeLanguages =
            buildList {
                preferred?.let(::add)
                add("null")
                if (preferred != "en") {
                    add("en")
                }
            }
                .distinct()
                .joinToString(",")

        val images = timed(2600L) {
            app.get(
                "$tmdbApiUrl/$type/$id/images",
                params = mapOf(
                    "api_key" to tmdbApiKey,
                    "include_image_language" to
                        includeLanguages
                )
            ).parsedSafe<TmdbImageResponse>()
        } ?: return null

        fun rank(language: String?): Int =
            when {
                preferred != null &&
                    language.equals(
                        preferred,
                        ignoreCase = true
                    ) -> 4

                language == null -> 3

                language.equals(
                    "en",
                    ignoreCase = true
                ) -> 2

                else -> 1
            }

        return images.posters
            .asSequence()
            .filter {
                !it.filePath.isNullOrBlank()
            }
            .sortedWith(
                compareByDescending<TmdbImage> {
                    rank(it.language)
                }.thenByDescending {
                    it.voteAverage ?: 0.0
                }
            )
            .mapNotNull { it.filePath }
            .firstOrNull()
            ?.let {
                "https://image.tmdb.org/t/p/w500$it"
            }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        val base =
            super.load(url)
                ?: return null

        return coroutineScope {

        val inheritedImdbId = base.getImdbId()
        val tmdbMatch =
            Regex(
                """themoviedb\.org/(movie|tv)/(\d+)"""
            ).find(url)

        val isTv =
            tmdbMatch
                ?.groupValues
                ?.getOrNull(1) == "tv"

        val tmdbId =
            tmdbMatch
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()

        val detailJob = async {
            tmdbDetail(
                tmdbId = tmdbId,
                isTv = isTv
            )
        }

        val imdbJob = async {
            inheritedImdbId
                ?: resolveTmdbImdb(
                    tmdbId = tmdbId,
                    isTv = isTv
                )
        }

        val synopsisJob = async {
            resolveArabicSynopsis(
                tmdbId = tmdbId,
                isTv = isTv,
                englishFallback = base.plot
            )
        }

        val detail = detailJob.await()
        if (detail?.originalLanguage.equals("ar", ignoreCase = true)) {
            return@coroutineScope null
        }
        val effectiveImdbId = imdbJob.await()
        val arabicOverview = synopsisJob.await()

        // The production-language original can be Japanese/Hindi, and an
        // Arabic production can have a romanized original_name. Preserve
        // familiar English display names and authentic Arabic script.
        val arDetail = if (
            detail?.originalLanguage.equals("ar", ignoreCase = true) &&
            tmdbId != null
        ) {
            timed(2100L) {
                app.get(
                    "$tmdbApiUrl/${if (isTv) "tv" else "movie"}/$tmdbId",
                    params = mapOf(
                        "api_key" to tmdbApiKey,
                        "language" to "ar-SA"
                    )
                ).parsedSafe<TmdbDetail>()
            }
        } else null

        detail?.let { meta ->
            val preferredName = if (meta.originalLanguage.equals("ar", true)) {
                listOfNotNull(
                    arDetail?.title, arDetail?.name,
                    meta.originalTitle, meta.originalName,
                    base.name, meta.title, meta.name
                ).firstOrNull {
                    Regex("""[\u0600-\u06FF]""").containsMatchIn(it)
                } ?: listOfNotNull(meta.title, meta.name, base.name,
                    meta.originalTitle, meta.originalName).firstOrNull()
            } else {
                listOfNotNull(meta.title, meta.name, base.name,
                    meta.originalTitle, meta.originalName).firstOrNull()
            }
            if (!preferredName.isNullOrBlank()) base.name = preferredName
        }

        // Never silently restore an English plot after Arabic translation
        // fails. Only description metadata is translated; names remain TMDB.
        base.plot = arabicOverview

        val reliableScore =
            detail?.voteAverage
                ?.takeIf {
                    it > 0.0 &&
                        it <= 10.0 &&
                        (detail.voteCount ?: 0) > 0
                }
                ?.let(Score::from10)

        if (reliableScore != null) {
            base.score = reliableScore
        }

        val originalPoster =
            if (
                detail?.originalLanguage.equals(
                    "ar",
                    ignoreCase = true
                )
            ) {
                tmdbFallbackPoster(
                    tmdbId = tmdbId,
                    isTv = isTv,
                    preferredLanguage =
                        detail?.originalLanguage
                )
            } else {
                null
            }

        val poster =
            originalPoster
                ?: detail?.posterPath
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        "https://image.tmdb.org/t/p/w500$it"
                    }
                ?: base.posterUrl
                ?: tmdbFallbackPoster(
                    tmdbId = tmdbId,
                    isTv = isTv,
                    preferredLanguage =
                        detail?.originalLanguage
                )

        if (!poster.isNullOrBlank()) {
            base.posterUrl = poster
        }

        val background =
            detail?.backdropPath
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    "https://image.tmdb.org/t/p/w1280$it"
                }

        if (!background.isNullOrBlank()) {
            base.backgroundPosterUrl = background
        }

        val detectedOriginalLanguage =
            detail?.originalLanguage?.takeIf { it.isNotBlank() }
                ?: if (Regex("""[\u0600-\u06FF]""").containsMatchIn(base.name)) {
                    "ar"
                } else null

        // TMDB provides both the official original and official localized
        // names. Carry them as aliases instead of machine-translating.
        val sourceTitleAliases = listOfNotNull(
            detail?.originalTitle, detail?.originalName,
            detail?.title, detail?.name,
            arDetail?.title, arDetail?.name,
            base.name
        ).filter(String::isNotBlank).distinct()

        when (base) {
            is MovieLoadResponse -> {
                base.dataUrl = HubPlaybackRequest(
                    title = base.name,
                    year = base.year,
                    imdbId = effectiveImdbId,
                    tmdbId = tmdbId,
                    isTv = false,
                    originalLanguage = detectedOriginalLanguage,
                    titleAliases = sourceTitleAliases
                ).toJson()
                base
            }

            is TvSeriesLoadResponse -> {
                base.episodes.forEach { episode ->
                    episode.data = HubPlaybackRequest(
                        title = base.name,
                        year = base.year,
                        imdbId = effectiveImdbId,
                        tmdbId = tmdbId,
                        isTv = isTv,
                        originalLanguage = detectedOriginalLanguage,
                        titleAliases = sourceTitleAliases,
                        season = episode.season,
                        episode = episode.episode
                    ).toJson()
                }
                base
            }

            else -> base
        }
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

    private fun isWeakSubtitleVariant(subtitle: SubtitleFile): Boolean {
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
            PreferredSubtitleLanguage.ARABIC -> 3000
            PreferredSubtitleLanguage.ENGLISH -> 1000
            PreferredSubtitleLanguage.OTHER -> 0
        }

        if (!isWeakSubtitleVariant(subtitle)) score += 150
        if ("srt" in url) score += 25
        if ("vtt" in url) score += 15
        if ("sdh" in text || "cc" in text || "hearing" in text) score -= 120
        if ("forced" in text) score -= 100
        if ("auto" in text || "machine" in text) score -= 180
        if ("WEB-DL" in subtitle.lang || "WEBRip" in subtitle.lang) score += 100
        if ("BluRay" in subtitle.lang) score += 70
        // Poor matches for common WEB-DL/WEBRip source videos. Retain as
        // explicit last-resort variants, never pretend they are synchronized.
        if (" • CAM" in subtitle.lang || " • TS" in subtitle.lang) score -= 250

        return score
    }

    // Only short, known release tags are used in CloudStream labels. Raw
    // release filenames often contain other language names (e.g. ENG/Hindi),
    // which can fool CloudStream's fuzzy language detection.
    private fun subtitleReleaseHint(raw: String): String {
        val text = raw.lowercase()
        return when {
            Regex("""web[ ._-]?dl""").containsMatchIn(text) -> "WEB-DL"
            Regex("""web[ ._-]?rip""").containsMatchIn(text) -> "WEBRip"
            Regex("""blu[ ._-]?ray|bdrip|brrip""").containsMatchIn(text) -> "BluRay"
            Regex("""hdtv""").containsMatchIn(text) -> "HDTV"
            Regex("""dvd[ ._-]?rip""").containsMatchIn(text) -> "DVDRip"
            Regex("""hd[ ._-]?rip""").containsMatchIn(text) -> "HDRip"
            Regex("""(?:hdcam|\bcam\b)""").containsMatchIn(text) -> "CAM"
            Regex("""(?:telesync|\bhdts\b|\bts\b)""").containsMatchIn(text) -> "TS"
            else -> ""
        }
    }

    private fun normalizeExternalSubtitle(
        sub: StremioSubtitle,
        source: String
    ): SubtitleFile? {
        val url = sub.url?.takeIf { validStreamingUrl(it) } ?: return null
        val language = when (subtitleLanguage(sub.lang)) {
            PreferredSubtitleLanguage.ARABIC -> "Arabic"
            PreferredSubtitleLanguage.ENGLISH -> "English"
            PreferredSubtitleLanguage.OTHER -> return null
        }
        val release = listOfNotNull(
            sub.movieReleaseName, sub.releaseFormat, sub.subtitleFileName
        ).joinToString(" ")
        val format = subtitleReleaseHint(release).takeIf { it.isNotBlank() }
        val fps = sub.fpsMilli?.takeIf { it in 23000..60000 }?.let {
            val rate = it / 1000.0
            String.format(java.util.Locale.US, "%.2ffps", rate)
        }
        val hint = listOfNotNull(format, fps).joinToString(" ")
        return SubtitleFile(
            listOf(language, source, hint)
                .filter { it.isNotBlank() }.joinToString(" • "),
            url
        )
    }

    private suspend fun resolveTmdbImdb(
        tmdbId: Int?,
        isTv: Boolean
    ): String? {
        val id = tmdbId ?: return null
        val type = if (isTv) "tv" else "movie"

        return timed(1800L) {
            app.get(
                "$tmdbApiUrl/$type/$id/external_ids",
                params = mapOf(
                    "api_key" to tmdbApiKey
                )
            ).parsedSafe<TmdbExternalIds>()
                ?.imdbId
                ?.takeIf {
                    it.startsWith("tt")
                }
        }
    }

    private suspend fun resolveSubtitleImdb(
        request: HubPlaybackRequest
    ): String? {
        request.imdbId
            ?.takeIf { it.startsWith("tt") }
            ?.let { return it }

        val tmdbId = request.tmdbId ?: return null
        val type = if (request.isTv || request.season != null) "tv" else "movie"

        // IMDb lookup is needed for external subtitles, even with fast video mode.
        return timed(4500L) {
            app.get(
                "$tmdbApiUrl/$type/$tmdbId/external_ids",
                params = mapOf("api_key" to tmdbApiKey)
            ).parsedSafe<TmdbExternalIds>()
                ?.imdbId
                ?.takeIf { it.startsWith("tt") }
        }
    }

    private fun currentSubtitleTimeoutMs(): Long = subtitleTimeoutMs

    private fun subtitleCacheKey(
        request: HubPlaybackRequest,
        imdb: String
    ): String = listOf(
        imdb,
        request.season?.toString().orEmpty(),
        request.episode?.toString().orEmpty()
    ).joinToString("|")

    private suspend fun fetchOpenSubtitlesV3(
        request: HubPlaybackRequest,
        imdb: String
    ): List<SubtitleFile> {
        val isSeries = request.season != null && request.episode != null
        val type = if (isSeries) "series" else "movie"
        val id = if (isSeries) {
            "$imdb:${request.season}:${request.episode}"
        } else {
            imdb
        }

        val response = timed(currentSubtitleTimeoutMs()) {
            app.get(
                "https://opensubtitles-v3.strem.io/subtitles/$type/$id.json",
                headers = mapOf("Accept" to "application/json")
            ).parsedSafe<StremioSubtitleResponse>()
        } ?: return emptyList()

        return response.subtitles
            .mapNotNull { normalizeExternalSubtitle(it, "OpenSubtitles") }
            .distinctBy { it.url }
    }

    private suspend fun fetchOpenSubtitlesLegacy(
        request: HubPlaybackRequest,
        imdb: String
    ): List<SubtitleFile> {
        val query = if (
            request.season != null &&
            request.episode != null
        ) {
            "$imdb ${request.season} ${request.episode}"
        } else {
            imdb
        }

        val payload = base64Encode(
            """{"id":1,"jsonrpc":"2.0","method":"subtitles.find","params":[null,{"query":{"itemHash":"$query"}}]}""".toByteArray()
        )

        val response = timed(currentSubtitleTimeoutMs()) {
            app.get(
                "https://opensubtitles.strem.io/stremio/v1/q.json?b=$payload"
            ).parsedSafe<LegacySubtitleResult>()
        } ?: return emptyList()

        return response.result
            ?.all
            .orEmpty()
            .mapNotNull { normalizeExternalSubtitle(it, "OpenSubtitles Backup") }
            .distinctBy { it.url }
    }

    private suspend fun fetchWyzieSubtitles(
        request: HubPlaybackRequest,
        imdb: String
    ): List<SubtitleFile> {
        val key = MOOSASettingsStore.wyzieKey() ?: return emptyList()
        val params = buildMap {
            put("id", imdb)
            put("source", "all")
            put("key", key)
            request.season?.let { put("season", it.toString()) }
            request.episode?.let { put("episode", it.toString()) }
        }

        val response = timed(currentSubtitleTimeoutMs()) {
            app.get(
                "https://sub.wyzie.io/search",
                params = params,
                headers = mapOf("Accept" to "application/json")
            ).parsedSafe<Array<WyzieSubtitle>>()
        } ?: return emptyList()

        return response.mapNotNull { item ->
            val url = item.url?.takeIf { validStreamingUrl(it) } ?: return@mapNotNull null
            // Wyzie display names are release titles and may not contain any
            // language name. Trust its structured language field instead.
            val language = when (subtitleLanguage(item.language, item.language)) {
                PreferredSubtitleLanguage.ARABIC -> "Arabic"
                PreferredSubtitleLanguage.ENGLISH -> "English"
                PreferredSubtitleLanguage.OTHER -> return@mapNotNull null
            }
            val format = subtitleReleaseHint(item.display.orEmpty())
            SubtitleFile(
                listOf(language, "Wyzie", format)
                    .filter { it.isNotBlank() }.joinToString(" • "), url
            )
        }.distinctBy { it.url }
    }

    private suspend fun fetchSubDlSubtitles(
        request: HubPlaybackRequest,
        imdb: String
    ): List<SubtitleFile> {
        val key = MOOSASettingsStore.subdlKey() ?: return emptyList()
        val isSeries = request.season != null && request.episode != null

        val params = buildMap {
            put("api_key", key)
            put("imdb_id", imdb)
            put("type", if (isSeries) "tv" else "movie")
            put("languages", "AR,EN")
            put("unpack", "1")
            put("subs_per_page", "30")
            put("client", "custom_integration")
            request.season?.let { put("season_number", it.toString()) }
            request.episode?.let { put("episode_number", it.toString()) }
        }

        val response = timed(currentSubtitleTimeoutMs()) {
            app.get(
                "https://api.subdl.com/api/v1/subtitles",
                params = params,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer $key"
                )
            ).parsedSafe<SubDlResponse>()
        } ?: return emptyList()

        if (!response.status) return emptyList()

        return response.subtitles.flatMap { subtitle ->
            val parentLang = subtitle.language.orEmpty()

            if (subtitle.unpackFiles.isNotEmpty()) {
                subtitle.unpackFiles.mapNotNull { file ->
                    if (
                        isSeries &&
                        request.season != null &&
                        request.episode != null &&
                        file.season != null &&
                        file.episode != null &&
                        (file.season != request.season || file.episode != request.episode)
                    ) return@mapNotNull null

                    val raw = file.url ?: return@mapNotNull null
                    val url = if (raw.startsWith("http")) raw
                    else "https://dl.subdl.com$raw"

                    val language = file.language?.takeIf { it.isNotBlank() } ?: parentLang
                    val label = when (subtitleLanguage(language, language)) {
                        PreferredSubtitleLanguage.ARABIC -> "Arabic • SubDL"
                        PreferredSubtitleLanguage.ENGLISH -> "English • SubDL"
                        PreferredSubtitleLanguage.OTHER -> return@mapNotNull null
                    }

                    val detail = file.releaseName
                        ?: subtitle.releaseName
                        ?: file.format
                        ?: "subtitle"

                    SubtitleFile(
                        lang = "${label} • ${detail}",
                        url = url
                    )
                }
            } else {
                val raw = subtitle.url ?: return@flatMap emptyList()
                if (
                    raw.endsWith(".zip", ignoreCase = true) ||
                    raw.endsWith(".rar", ignoreCase = true)
                ) return@flatMap emptyList()

                val url = if (raw.startsWith("http")) raw
                else "https://dl.subdl.com$raw"

                val label = when (subtitleLanguage(parentLang, parentLang)) {
                    PreferredSubtitleLanguage.ARABIC -> "Arabic • SubDL"
                    PreferredSubtitleLanguage.ENGLISH -> "English • SubDL"
                    PreferredSubtitleLanguage.OTHER -> return@flatMap emptyList()
                }

                listOf(
                    SubtitleFile(
                        lang = "${label} • ${subtitle.releaseName ?: "subtitle"}",
                        url = url
                    )
                )
            }
        }.distinctBy { it.url }
    }

    private suspend fun fetchExternalSubtitles(
        request: HubPlaybackRequest,
        onEarlyCandidates: suspend (List<SubtitleFile>) -> Unit
    ): List<SubtitleFile> = coroutineScope {
        val imdb = resolveSubtitleImdb(request) ?: return@coroutineScope emptyList()
        val cacheKey = subtitleCacheKey(request, imdb)

        subtitleCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < subtitleCacheLifetimeMs) {
                onEarlyCandidates(cached.items)
                return@coroutineScope emptyList()
            }
            subtitleCache.remove(cacheKey)
        }

        // List endpoints only: never publish links until their actual text
        // has been checked. Some index APIs return empty, zipped, 404 or
        // mislabelled legacy-encoding files under valid Arabic labels.
        // The legacy Stremio OpenSubtitles RPC returns empty for verified
        // movie samples. Avoid querying it on *every* playback; reserve it
        // for an empty primary index to keep working sources responsive.
        val v3 = async { fetchOpenSubtitlesV3(request, imdb) }
        val wyzie = async { fetchWyzieSubtitles(request, imdb) }
        val subDl = async { fetchSubDlSubtitles(request, imdb) }
        // Deliver the first working OpenSubtitles v3 tracks before awaiting
        // slower backup sources; don't block the player's subtitle menu.
        val primary = v3.await()
            .filter { validStreamingUrl(it.url) }
            .distinctBy { it.url }
        if (primary.isNotEmpty()) onEarlyCandidates(primary)

        val backup = if (primary.isEmpty()) {
            fetchOpenSubtitlesLegacy(request, imdb)
        } else emptyList()
        val all = (primary + backup + wyzie.await() + subDl.await())
            .filter { it.url.startsWith("http", ignoreCase = true) }
            .filterNot {
                val url = it.url.substringBefore("?").lowercase()
                url.endsWith(".zip") || url.endsWith(".rar") ||
                    url.endsWith(".7z") || url.endsWith(".gz")
            }
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)

        if (all.isNotEmpty()) {
            subtitleCache[cacheKey] = CachedSubtitleList(
                createdAt = System.currentTimeMillis(), items = all
            )
        }
        val primaryUrls = primary.map { it.url }.toHashSet()
        all.filterNot { it.url in primaryUrls }
    }

    private val subtitleTiming = Regex(
        """(?m)(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3}"""
    )

    private suspend fun isReadableSubtitle(sub: SubtitleFile): Boolean {
        // A cache hit avoids a network request, text decoding and an I/O burst.
        // Different Referer/headers and different claimed languages are NOT
        // interchangeable. Expired/failed checks are retried quickly.
        val cacheKey = sub.url + "|" + (sub.headers?.hashCode() ?: 0) +
            "|" + subtitleLanguage(sub.lang, sub.langTag)
        val now = System.currentTimeMillis()
        subtitleCheckCache[cacheKey]?.let { entry ->
            val lifetime = if (entry.readable) {
                successfulSubtitleCheckLifetimeMs
            } else failedSubtitleCheckLifetimeMs
            if (now - entry.checkedAt in 0 until lifetime) return entry.readable
            subtitleCheckCache.remove(cacheKey, entry)
        }
        val result = inspectSubtitleContent(sub)
        if (subtitleCheckCache.size >= 256) subtitleCheckCache.clear()
        subtitleCheckCache[cacheKey] = CachedSubtitleCheck(
            System.currentTimeMillis(), result
        )
        return result
    }

    private suspend fun inspectSubtitleContent(sub: SubtitleFile): Boolean {
        val url = sub.url
        if (!validStreamingUrl(url)) return false
        if (Regex("""(?i)\.(?:zip|rar|7z|gz)(?:[?#]|$)""").containsMatchIn(url)) return false
        val body = timed(4200L) {
            app.get(
                url,
                // Native providers may require Referer, User-Agent or cookies
                // to download an otherwise valid subtitle track.
                headers = (sub.headers ?: emptyMap()) + mapOf(
                    "Accept" to "text/plain,text/vtt,*/*",
                    "Range" to "bytes=0-32767"
                )
            ).text.take(32_768)
        } ?: return false

        // One broken glyph should not disqualify an otherwise readable file;
        // reject heavily damaged encodings, HTML error pages and binary data.
        if (body.isBlank() || body.count { it == '\uFFFD' } > 4 ||
            body.contains("\u0000") ||
            body.contains("<html", ignoreCase = true) ||
            body.contains("<!DOCTYPE", ignoreCase = true)
        ) return false

        if (!subtitleTiming.containsMatchIn(body)) return false
        val content = body.substringAfter("-->", body)
        val arabicCount = content.count { it in '\u0600'..'\u06FF' }
        val latinCount = content.count { it in 'A'..'Z' || it in 'a'..'z' }
        return when (subtitleLanguage(sub.lang, sub.langTag)) {
            PreferredSubtitleLanguage.ARABIC -> arabicCount >= 8
            PreferredSubtitleLanguage.ENGLISH -> latinCount >= 12
            PreferredSubtitleLanguage.OTHER -> false
        }
    }

    private suspend fun filterReadableSubtitles(
        subtitles: List<SubtitleFile>,
        onVerified: (SubtitleFile) -> Unit,
        gate: kotlinx.coroutines.sync.Semaphore
    ): List<SubtitleFile> = coroutineScope {
        // Inspect more candidates when index servers list multiple versions;
        // discarded/broken links should not starve Arabic or English results.
        // Keep a bounded list so verification does not block playback forever.
        val shortlist = subtitles
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)
            .groupBy { subtitleLanguage(it.lang, it.langTag) }
            .let { byLanguage ->
                byLanguage[PreferredSubtitleLanguage.ARABIC].orEmpty().take(10) +
                    byLanguage[PreferredSubtitleLanguage.ENGLISH].orEmpty().take(5)
            }
        // Reading 36 subtitle files while video sources are active caused
        // unnecessary I/O bursts on low-memory Android TV devices.
        // Emit each verified result immediately instead of waiting for all.
        shortlist.map { subtitle ->
            async {
                gate.withPermit {
                    if (isReadableSubtitle(subtitle)) {
                        onVerified(subtitle)
                        subtitle
                    } else null
                }
            }
        }.awaitAll().filterNotNull().sortedByDescending(::subtitleScore)
    }

    private fun normalizeNativeSubtitle(
        subtitle: SubtitleFile,
        source: String
    ): SubtitleFile? {
        return when (subtitleLanguage(subtitle.lang, subtitle.langTag)) {
            PreferredSubtitleLanguage.ARABIC ->
                subtitle.copy(lang = "Arabic • $source")
            PreferredSubtitleLanguage.ENGLISH ->
                subtitle.copy(lang = "English • $source")
            PreferredSubtitleLanguage.OTHER -> null
        }
    }

    private fun inferQuality(link: ExtractorLink): Int =
        MOOSAQualityPolicy.height(link) ?: 0

    private fun validStreamingUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)

    private fun linkKey(link: ExtractorLink): String =
        "${link.url}|${link.referer}|${link.headers.hashCode()}"


    private fun linksTimeoutFor(
        api: MainAPI,
        request: HubPlaybackRequest
    ): Long {
        val label =
            "${api.name} ${api.mainUrl} ${api.javaClass.name}"
                .lowercase()

        return when {
            request.originalLanguage.equals("ar", true) &&
                MOOSASettingsStore.arabicFastMode() &&
                isAkwamProvider(api) -> 23000L

            request.originalLanguage.equals("ar", true) &&
                MOOSASettingsStore.arabicFastMode() &&
                isArabicProvider(api) -> 11000L

            isStreamPlayProvider(api) ->
                streamPlayLinksTimeoutMs

            "fasel" in label ||
                "فاصل" in label ->
                75_000L

            "topcinema" in label ||
                "top cinema" in label ->
                55_000L

            "akwam" in label ||
                "اكوام" in label ->
                45_000L

            "arabseed" in label ||
                "arab seed" in label ->
                60_000L

            "wecima" in label ||
                "we cima" in label ||
                "cimaclub" in label ||
                "cima club" in label ->
                45_000L

            isArabicProvider(api) &&
                request.isTv ->
                40_000L

            isArabicProvider(api) ->
                30_000L

            else ->
                providerLinksTimeoutMs
        }
    }


    private suspend fun verifyStreamingLink(link: ExtractorLink): Boolean {
        val result = timed(linkProbeTimeoutMs) {
            MOOSAServerRegistry.testLink(link)
        } ?: false

        MOOSAServerRegistry.recordResult(link, result)
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val request = tryParseJson<HubPlaybackRequest>(data) ?: return@coroutineScope false
        if (request.originalLanguage.equals("ar", ignoreCase = true)) return@coroutineScope false
        val allProviders = playbackProviders(request)
        val fastArabic = request.originalLanguage.equals("ar", true) &&
            MOOSASettingsStore.arabicFastMode()

        // Collect independent Arabic alternatives in one bounded parallel batch.
        // Stopping immediately at the first Akwam result hid ArabSeed/TopCinema,
        // even when a different host would stream more smoothly.
        val providers = if (fastArabic) {
            val priority = allProviders.filter { api ->
                isAkwamProvider(api) || isArabSeedProvider(api) ||
                    (isBundledArabicProvider(api) && api.name.equals("TopCinema", true))
            }
            val extra = allProviders.filterNot { it in priority }
                .filter { api -> api.lang.equals("ar", true) || MOOSAArabicBridge.supports(api) }
                .take(1)
            (priority + extra).distinctBy { it.name.lowercase() }.take(4)
        } else allProviders

        MOOSAPlaybackDiagnostics.start(
            request,
            providers
        )

        if (providers.isEmpty()) {
            if (
                request.originalLanguage.equals(
                    "ar",
                    ignoreCase = true
                )
            ) {
                MOOSAProgress.failureReport(
                    MOOSAPlaybackDiagnostics.report()
                )
            }
            return@coroutineScope false
        }

        MOOSAProgress.start(providers.size)

        val aliasesJob = async {
            // Skip four TMDB alias calls and season metadata when the
            // Arabic title itself is already available. This costs multiple
            // seconds before even the first Akwam search starts.
            if (
                fastArabic &&
                Regex("""[\u0600-\u06FF]""").containsMatchIn(request.title)
            ) {
                buildList {
                    add(request.title)
                    addAll(request.titleAliases)
                    request.season?.let { season ->
                        add("${request.title} الموسم $season")
                        arabicSeasonWord(season)?.let { ordinal ->
                            add("${request.title} الموسم $ordinal")
                        }
                        add("${request.title} ج$season")
                        add("${request.title} $season")
                    }
                }
            } else {
                (request.titleAliases + playbackAliases(request))
                    .distinctBy(::canonicalTitle)
            }
        }

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        val arabicSubNumber = AtomicInteger(0)
        val englishSubNumber = AtomicInteger(0)
        val nativeSubtitles =
            Collections.synchronizedList(
                mutableListOf<SubtitleFile>()
            )
        val emittedLink = AtomicBoolean(false)
        val completedProviders = AtomicInteger(0)
        val shownLinks = AtomicInteger(0)
        val callbackLock = Any()

        // CloudStream receives verified tracks as soon as each file passes,
        // even while source videos are still resolving. Keep the callback
        // serialized with video callbacks and dedupe parallel source results.
        fun emitVerifiedSubtitle(subtitle: SubtitleFile) {
            val language = when (subtitleLanguage(subtitle.lang, subtitle.langTag)) {
                PreferredSubtitleLanguage.ARABIC -> PreferredSubtitleLanguage.ARABIC
                PreferredSubtitleLanguage.ENGLISH -> PreferredSubtitleLanguage.ENGLISH
                PreferredSubtitleLanguage.OTHER -> return
            }
            // CloudStream groups every track with the exact same lang into
            // ONE selector entry. Keep each verified version distinguishable,
            // with a recognisable Arabic/English prefix for auto-selection.
            synchronized(callbackLock) {
                if (!seenSubtitles.add(subtitle.url)) return
                val number = if (language == PreferredSubtitleLanguage.ARABIC)
                    arabicSubNumber.incrementAndGet()
                else englishSubNumber.incrementAndGet()
                val prefix = if (language == PreferredSubtitleLanguage.ARABIC)
                    "Arabic" else "English"
                val details = subtitle.lang.substringAfter(" • ", "")
                    .split(" • ")
                    .filter { it.isNotBlank() }
                    .mapNotNull { part ->
                        when {
                            part.startsWith("OpenSubtitles Backup") -> "OS Backup"
                            part.startsWith("OpenSubtitles") -> "OpenSubtitles"
                            part.startsWith("SubDL") -> "SubDL"
                            part.startsWith("Wyzie") -> "Wyzie"
                            else -> {
                                val format = subtitleReleaseHint(part)
                                val fps = Regex("""(\d{2}(?:\.\d{1,2})?)fps""")
                                    .find(part)?.value
                                listOf(format.takeIf(String::isNotBlank), fps)
                                    .filterNotNull().joinToString(" ")
                                    .takeIf(String::isNotBlank)
                            }
                        }
                    }.distinct().take(2)
                val label = (listOf("$prefix #$number") + details).joinToString(" • ")
                val track = subtitle.copy(lang = label)
                try {
                    subtitleCallback(track)
                } catch (error: Throwable) {
                    logError(error)
                }
            }
        }

        // A single shared gate caps simultaneous file reads across the
        // primary index, fallback catalogs and native provider subtitles.
        val subtitleProbeGate = kotlinx.coroutines.sync.Semaphore(4)

        // Start validating primary tracks without pausing fallback retrieval.
        // Separate coroutines can emit tracks immediately on success, while a
        // shared semaphore limits storage/network pressure on Android TV.
        val externalSubtitleJob = async {
            timed(20_000L) {
                coroutineScope {
                    val later = fetchExternalSubtitles(request) { first ->
                        launch {
                            filterReadableSubtitles(
                                first, ::emitVerifiedSubtitle, subtitleProbeGate
                            )
                        }
                    }
                    filterReadableSubtitles(
                        later, ::emitVerifiedSubtitle, subtitleProbeGate
                    )
                }
            }
        }

        val aliases = aliasesJob.await()

        // Resolve up to three Arabic sources together; finish as soon as the
        // initial batch has returned so no fourth slow source blocks playback.
        val providerBatchSize = if (fastArabic) 3
            else MOOSASettingsStore.providerConcurrency()

        for (batch in providers.chunked(providerBatchSize)) {
            batch.amap { api ->
                var providerWorked = false
                val candidateLinks =
                    Collections.synchronizedList(mutableListOf<ExtractorLink>())
                val extracted = AtomicInteger(0)
                val invalidUrls = AtomicInteger(0)
                val disabledUrls = AtomicInteger(0)
                val downloadNamesSkipped = AtomicInteger(0)
                val lowQualityUrls = AtomicInteger(0)
                val unknownQualityUrls = AtomicInteger(0)
                val failedProbes = AtomicInteger(0)
                val acceptedLinks = AtomicInteger(0)
                val failedReasons = ConcurrentHashMap<String, AtomicInteger>()
                val emittedAkwamMirrors =
                    ConcurrentHashMap.newKeySet<String>()

                try {
                    MOOSAPlaybackDiagnostics.update(
                        api,
                        if (MOOSAArabicBridge.supports(api)) {
                            "Starting Arabic bridge"
                        } else {
                            "Resolving provider"
                        }
                    )

                    val target =
                        resolveTargetForProvider(
                            request,
                            api,
                            aliases
                        )

                    if (target != null) {
                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "Target found • requesting links"
                        )
                        fun collectLink(link: ExtractorLink) {
                            extracted.incrementAndGet()
                            if (!validStreamingUrl(link.url)) {
                                invalidUrls.incrementAndGet()
                                return
                            }
                            // Source/server display names, never the URL path:
                            // Akwam's valid MP4 can be hosted via /download/.
                            if (MOOSAQualityPolicy.hasForbiddenLabel(link)) {
                                downloadNamesSkipped.incrementAndGet()
                                return
                            }

                            // Reject SD and unknown links BEFORE probing them.
                            // Displaying only HD is not enough: SD should not
                            // consume time, network requests or source slots.
                            val height = MOOSAQualityPolicy.height(link)
                            if (height == null || height <= 0) {
                                unknownQualityUrls.incrementAndGet()
                                return
                            }
                            if (!MOOSAQualityPolicy.acceptsHeight(height)) {
                                lowQualityUrls.incrementAndGet()
                                return
                            }
                            if (link.quality <= 0) link.quality = height

                            // Only eligible playback names and 720p+ streams
                            // reach network probing or the visible source list.
                            MOOSAServerRegistry.record(link)
                            if (MOOSAServerRegistry.isDisabled(link)) {
                                disabledUrls.incrementAndGet()
                                return
                            }
                            candidateLinks += link
                        }

                        timed(linksTimeoutFor(api, request)) {
                            api.loadLinks(
                                target.data,
                                isCasting,
                                { subtitle ->
                                    normalizeNativeSubtitle(subtitle, api.name)
                                        ?.takeIf { it.url.isNotBlank() }
                                        ?.let(nativeSubtitles::add)
                                },
                                { link ->
                                    collectLink(link)
                                }
                            )
                        }

                        if (
                            candidateLinks.isEmpty() &&
                            !isStreamPlayProvider(api) &&
                            !fastArabic
                        ) {
                            val freshTarget =
                                resolveTargetForProvider(
                                    request = request,
                                    api = api,
                                    aliases = aliases,
                                    forceRefresh = true
                                )

                            if (freshTarget != null) {
                                timed(linksTimeoutFor(api, request)) {
                                    api.loadLinks(
                                        freshTarget.data,
                                        isCasting,
                                        { subtitle ->
                                            normalizeNativeSubtitle(subtitle, api.name)
                                                ?.takeIf { it.url.isNotBlank() }
                                                ?.let(nativeSubtitles::add)
                                        },
                                        { link ->
                                            collectLink(link)
                                        }
                                    )
                                }
                            }
                        }

                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "Episode matched: ${extracted.get()} extracted, " +
                                "${candidateLinks.size} candidates, " +
                                "${invalidUrls.get()} invalid, ${disabledUrls.get()} disabled, " +
                                "${downloadNamesSkipped.get()} downloads skipped, " +
                                "${lowQualityUrls.get()} SD skipped, ${unknownQualityUrls.get()} unknown skipped"
                        )

                        val allCandidates = candidateLinks
                            .distinctBy(::linkKey)

                        // Akwam Downet HTTPS still uses an untrusted TLS
                        // certificate on the tested path. If Akwam also
                        // supplied the exact HTTP mirror, don't spend up to
                        // two 6.5-second TLS probes on the known-bad URL.
                        // Keep HTTPS for every other host and for Downet
                        // links with no HTTP alternative.
                        val directHttpMirrors = allCandidates
                            .asSequence()
                            .filter {
                                it.url.startsWith("http://", true) &&
                                    it.url.contains(".downet.net/", true)
                            }
                            .map { it.url }
                            .toSet()

                        val candidates = allCandidates
                            .filterNot { link ->
                                link.url.startsWith("https://", true) &&
                                    link.url.contains(".downet.net/", true) &&
                                    link.url.replaceFirst(
                                        "https://",
                                        "http://",
                                        ignoreCase = true
                                    ) in directHttpMirrors
                            }
                            .sortedWith(
                                compareByDescending<ExtractorLink>(::inferQuality)
                                    // When equal-resolution mirrors exist,
                                    // prefer servers that transferred the
                                    // previous 64 KiB test faster on THIS
                                    // device; never demote 4K below 1080p.
                                    .thenByDescending {
                                        MOOSAServerRegistry.sampledMbps(it)
                                            ?: 0.0
                                    }
                            )

                        for (linkBatch in candidates.chunked(linkProbeBatchSize)) {
                            linkBatch.amap { link ->
                                val key = linkKey(link)
                                if (seenLinks.contains(key)) {
                                    return@amap false
                                }

                                // Do not allow untested Arabic source mirrors:
                                // fallback URLs previously bypassed verification,
                                // then were shown even after the probe failed.
                                val verified =
                                    (
                                        !request.originalLanguage.equals("ar", true) &&
                                            !MOOSASettingsStore.verifyLinks()
                                        ) ||
                                        verifyStreamingLink(link)

                                val mirrorIdentity =
                                    if (api.name.equals("Akwam", true)) {
                                        val host = runCatching {
                                            java.net.URI(link.url).host
                                                ?.lowercase()
                                                .orEmpty()
                                        }.getOrDefault("")
                                        if (
                                            host == "downet.net" ||
                                            host.endsWith(".downet.net")
                                        ) {
                                            "${link.url.substringBefore("://").lowercase()}|$host|${inferQuality(link)}"
                                        } else null
                                    } else null

                                if (
                                    verified &&
                                    seenLinks.add(key) &&
                                    (
                                        mirrorIdentity == null ||
                                            emittedAkwamMirrors.add(mirrorIdentity)
                                        )
                                ) {
                                    providerWorked = true
                                    acceptedLinks.incrementAndGet()
                                    emittedLink.set(true)
                                    shownLinks.incrementAndGet()
                                    MOOSAProgress.linkAvailable()

                                    synchronized(callbackLock) {
                                        callback(link)
                                    }
                                    true
                                } else {
                                    if (!verified) {
                                        failedProbes.incrementAndGet()
                                        val reason = MOOSAServerRegistry
                                            .lastProbeReason(link)
                                            ?: "Unverified link"
                                        failedReasons
                                            .computeIfAbsent(reason) { AtomicInteger(0) }
                                            .incrementAndGet()
                                    }
                                    false
                                }
                            }
                        }
                    } else {
                        val lastStatus = MOOSAPlaybackDiagnostics.status(api)
                        if (
                            lastStatus == null ||
                            lastStatus == "Resolving provider" ||
                            lastStatus == "Starting Arabic bridge"
                        ) {
                            MOOSAPlaybackDiagnostics.update(
                                api,
                                "No matching page or episode"
                            )
                        }
                    }

                    if (providerWorked) {
                        val measured = candidateLinks
                            .mapNotNull(MOOSAServerRegistry::sampledMbps)
                            .maxOrNull()
                        val quality = candidateLinks.maxOfOrNull(::inferQuality) ?: 0
                        val rateInfo = measured?.let { speed ->
                            val label = String.format(
                                java.util.Locale.US, "%.1f", speed
                            )
                            val mayBuffer = (quality >= 2160 && speed < 8.0) ||
                                (quality >= 1080 && quality < 2160 && speed < 2.5) ||
                                (quality in 720..1079 && speed < 1.0)
                            " • CDN sample $label Mbps" +
                                if (mayBuffer) " (possible buffering)" else ""
                        }.orEmpty()
                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "Playback ready • ${acceptedLinks.get()} verified, " +
                                "${failedProbes.get()} probe failures" + rateInfo
                        )

                        providerHealth.merge(api.name, 3) { old, add ->
                            (old + add).coerceAtMost(24)
                        }
                    } else {
                        // Keep the original matching/search diagnostic when
                        // no target was found. Don't overwrite it with "No links".
                        if (target != null) {
                            val reportStatus = when {
                                extracted.get() == 0 ->
                                    "Episode matched • extractor returned 0 links"
                                candidateLinks.isEmpty() ->
                                    "Episode matched • ${extracted.get()} URLs, " +
                                        "${invalidUrls.get()} invalid, " +
                                        "${disabledUrls.get()} disabled, " +
                                        "${downloadNamesSkipped.get()} downloads skipped, " +
                                        "${lowQualityUrls.get()} SD skipped, " +
                                        "${unknownQualityUrls.get()} unknown skipped"
                                else -> {
                                    val reasons = failedReasons.entries
                                        .sortedByDescending { it.value.get() }
                                        .take(2)
                                        .joinToString("; ") {
                                            "${it.key} x${it.value.get()}"
                                        }
                                    "Episode matched • ${candidateLinks.distinctBy(::linkKey).size} candidates; " +
                                        "${failedProbes.get()} failed checks, 0 confirmed" +
                                        if (reasons.isNotBlank()) " • $reasons" else ""
                                }
                            }
                            MOOSAPlaybackDiagnostics.update(api, reportStatus)
                        }

                        providerHealth.merge(api.name, -2) { old, add ->
                            (old + add).coerceAtLeast(-12)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logError(error)

                    MOOSAPlaybackDiagnostics.update(
                        api,
                        "Error • ${error.javaClass.simpleName}"
                    )

                    providerHealth.merge(api.name, -2) { old, add ->
                        (old + add).coerceAtLeast(-12)
                    }
                } finally {
                    MOOSAProgress.providerProgress(
                        completedProviders.incrementAndGet(),
                        providers.size,
                        shownLinks.get()
                    )
                }

                providerWorked
            }

            // Native CloudStream only finishes its loading UI when
            // loadLinks() returns. Stop after one confirmed fast-lane
            // source rather than keeping it searching for dead mirrors.
            if (fastArabic && shownLinks.get() > 0) {
                break
            }
        }

        MOOSAProgress.serversReady(shownLinks.get())
        MOOSAProgress.subtitles()

        // Native subtitles also arrive individually as soon as they verify.
        // Verify fewer files concurrently to reduce I/O during playback.
        val nativeSubtitleJob = async {
            timed(10_000L) {
                filterReadableSubtitles(nativeSubtitles.toList(), ::emitVerifiedSubtitle, subtitleProbeGate)
            }
        }
        if (emittedLink.get()) {
            // After any video link is available, stop waiting for optional
            // subtitle hosts. Verified tracks have already been emitted.
            withTimeoutOrNull(3200L) {
                externalSubtitleJob.await()
                nativeSubtitleJob.await()
            }
            externalSubtitleJob.cancel()
            nativeSubtitleJob.cancel()
        } else {
            externalSubtitleJob.await()
            nativeSubtitleJob.await()
        }

        MOOSAProgress.finish(shownLinks.get())

        if (
            shownLinks.get() == 0 &&
            request.originalLanguage.equals(
                "ar",
                ignoreCase = true
            )
        ) {
            MOOSAProgress.failureReport(
                MOOSAPlaybackDiagnostics.report()
            )
        }

        emittedLink.get()
    }

    private suspend fun tmdbHome(
        path: String,
        page: Int,
        isTv: Boolean,
        language: String = "en-US",
        extra: Map<String, String> = emptyMap()
    ): List<SearchResponse> {
        val params = buildMap {
            put("api_key", tmdbApiKey)
            put("page", page.toString())
            put("language", language)
            put("include_adult", "false")
            putAll(extra)
        }

        val data = timed(3500L) {
            app.get(
                "$tmdbApiUrl$path",
                params = params
            ).parsedSafe<TmdbHomePage>()
        } ?: return emptyList()

        return data.results.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.displayTitle.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = if (isTv) {
                "https://www.themoviedb.org/tv/$id"
            } else {
                "https://www.themoviedb.org/movie/$id"
            }
            val poster = item.posterPath?.let {
                "https://image.tmdb.org/t/p/w500$it"
            }

            if (isTv) {
                newTvSeriesSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.TvSeries,
                    fix = false
                ) {
                    this.id = id
                    this.posterUrl = poster
                    this.year = item.year
                    this.score = item.voteAverage?.let { Score.from10(it) }
                }
            } else {
                newMovieSearchResponse(
                    name = title,
                    url = url,
                    type = TvType.Movie,
                    fix = false
                ) {
                    this.id = id
                    this.posterUrl = poster
                    this.year = item.year
                    this.score = item.voteAverage?.let { Score.from10(it) }
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse = MOOSACatalog.home(this, page, request)

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? = MOOSACatalog.search(this, query, page)
}
