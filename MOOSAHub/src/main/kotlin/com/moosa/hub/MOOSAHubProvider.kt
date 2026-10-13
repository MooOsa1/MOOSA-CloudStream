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
        val episode: Int? = null
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
        val lang: String? = null
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
            get() = title ?: name ?: originalTitle ?: originalName ?: ""

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

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val subtitleCacheLifetimeMs = 15 * 60 * 1000L
    private val providerResolveTimeoutMs = 30000L
    private val providerLinksTimeoutMs = 22000L
    private val streamPlayLinksTimeoutMs = 60000L
    private val subtitleTimeoutMs = 2200L
    private val fastSubtitleTimeoutMs = 1350L
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
        val markers = Regex("""(?:^|\\s)(?:الموسم|موسم|season|الجزء|جزء|part|ج|s)\\s*([\\p{L}\\d]+)""", RegexOption.IGNORE_CASE)
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

        val generated = buildList {
            preferredBase
                .take(8)
                .forEach { title ->
                    add("$title $season")

                    if (
                        Regex("""[\u0600-\u06FF]""")
                            .containsMatchIn(title)
                    ) {
                        add("$title الموسم $season")

                        arabicSeasonWord(season)
                            ?.let { word ->
                                add("$title الموسم $word")
                            }
                    }

                    add("$title Season $season")
                    add("$title - $season")
                }

            addAll(seasonNames)
        }

        return (
            generated +
                preferredBase
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(::canonicalTitle)
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
            isArabicProvider(api) &&
                request.isTv -> 100000L

            isArabicProvider(api) -> 38000L

            isStreamPlayProvider(api) -> 35000L

            else -> providerResolveTimeoutMs
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
            .distinctBy(::canonicalTitle)
            .take(16)

        val queryVariants = buildList {
            addAll(cleanAliases)

            if (year != null) {
                cleanAliases
                    .take(4)
                    .forEach { alias ->
                        add("$alias $year")
                    }
            }
        }.distinct().take(18)

        val results =
            mutableListOf<SearchResponse>()

        for ((index, query) in queryVariants.withIndex()) {
            val first = timed(
                searchTimeoutFor(
                    api,
                    request
                )
            ) {
                api.search(query, 1)
                    ?.items
                    .orEmpty()
            }.orEmpty()

            results += first

            val hasStrict = first.any {
                strictSearchScore(
                    it,
                    aliases,
                    year,
                    wantSeries,
                    originalLanguage,
                    season
                ) >= 90
            }

            if (hasStrict) break

            if (index < 2) {
                val second = timed(
                    searchTimeoutFor(
                        api,
                        request
                    )
                ) {
                    api.search(query, 2)
                        ?.items
                        .orEmpty()
                }.orEmpty()

                results += second

                if (
                    second.any {
                        strictSearchScore(
                            it,
                            aliases,
                            year,
                            wantSeries,
                            originalLanguage,
                            season
                        ) >= 90
                    }
                ) {
                    break
                }
            }
        }

        return results
            .distinctBy { it.url }
    }

    private suspend fun loadBestMatch(
        api: MainAPI,
        request: HubPlaybackRequest,
        aliases: List<String>
    ): LoadResponse? {
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
                return direct
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
                    return direct
                }
            }
        }

        val candidates = searchProvider(
            api = api,
            request = request,
            aliases = aliases,
            year = request.year,
            wantSeries = wantSeries,
            originalLanguage =
                request.originalLanguage,
            season = request.season
        )
            .asSequence()
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

        for ((match, _) in candidates) {
            val loaded = timed(
                providerLoadTimeoutFor(
                    api,
                    request
                )
            ) {
                api.load(match.url)
            } ?: continue

            if (!correctType(loaded)) continue

            if (
                loadedIdentityMatches(
                    loaded,
                    request,
                    aliases
                )
            ) {
                return loaded
            }
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

        return available
            .filter { api ->
                isBundledArabicProvider(api) ||
                    api.name.lowercase() !in bundledNames
            }
            .sortedWith(
                compareByDescending<MainAPI> {
                    if (isBundledArabicProvider(it)) {
                        3
                    } else if (
                        request.originalLanguage.equals(
                            "ar",
                            ignoreCase = true
                        ) &&
                        MOOSAArabicBridge.supports(it)
                    ) {
                        2
                    } else if (
                        request.originalLanguage.equals(
                            "ar",
                            ignoreCase = true
                        ) &&
                        it.lang.equals(
                            "ar",
                            ignoreCase = true
                        )
                    ) {
                        1
                    } else {
                        0
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
        "strict-v11",
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
                    cacheLifetimeMs
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
            MOOSAArabicBridge.supports(api)
        ) {
            val bridged =
                MOOSAArabicBridge.resolve(
                    api = api,
                    request = request,
                    aliases = aliases
                )

            if (bridged != null) {
                targetCache[cacheKey] =
                    CachedTarget(
                        createdAt =
                            System.currentTimeMillis(),
                        target = bridged
                    )

                return bridged
            }
        }

        val isSeries =
            request.season != null &&
                request.episode != null
        val loaded = timed(
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

        val data = if (!isSeries) {
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

            val pageProvesSeason =
                seasonSpecificTitleMatch(
                    series.name,
                    aliases,
                    season
                )

            val seasonless =
                if (
                    exact == null &&
                    explicitSeasons.isEmpty() &&
                    (
                        season == 1 ||
                            pageProvesSeason
                        )
                ) {
                    series.episodes.firstOrNull {
                        episodeNumber(it) == episode
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

            (
                exact
                    ?: seasonless
                    ?: firstSeasonFallback
                )?.data
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

    private suspend fun translateSynopsisToArabic(
        text: String
    ): String? {
        val source = cleanSynopsis(text) ?: return null

        val response = timed(3000L) {
            app.get(
                "https://translate.googleapis.com/translate_a/single",
                params = mapOf(
                    "client" to "gtx",
                    "sl" to "auto",
                    "tl" to "ar",
                    "dt" to "t",
                    "q" to source
                )
            ).text
        } ?: return null

        val translated = try {
            val root = JSONArray(response)
            val segments = root.optJSONArray(0)
                ?: return null

            buildString {
                for (index in 0 until segments.length()) {
                    val segment =
                        segments.optJSONArray(index)
                            ?: continue
                    val value =
                        segment.optString(0)
                            .trim()

                    if (value.isNotBlank()) {
                        if (isNotEmpty()) append(' ')
                        append(value)
                    }
                }
            }
        } catch (_: Throwable) {
            return null
        }

        return cleanSynopsis(translated)
            ?.takeIf { clean ->
                clean.any {
                    ch -> ch in '\u0600'..'\u06FF'
                }
            }
    }

    private suspend fun resolveArabicSynopsis(
        tmdbId: Int?,
        isTv: Boolean,
        englishFallback: String?
    ): String? {
        val id = tmdbId ?: return null
        val type = if (isTv) "tv" else "movie"
        val cacheKey = "$type:$id"

        synopsisCache[cacheKey]?.let {
            cleanSynopsis(it)?.let { clean ->
                return clean
            }
        }

        for (
            language in
            listOf("ar-SA", "ar-AE", "ar-EG", "ar")
        ) {
            val overview = timed(1800L) {
                app.get(
                    "$tmdbApiUrl/$type/$id",
                    params = mapOf(
                        "api_key" to tmdbApiKey,
                        "language" to language
                    )
                ).parsedSafe<TmdbOverview>()
                    ?.overview
            }

            val clean = cleanSynopsis(overview)
            if (
                !clean.isNullOrBlank() &&
                clean.any {
                    it in '\u0600'..'\u06FF'
                }
            ) {
                synopsisCache[cacheKey] = clean
                return clean
            }
        }

        val fallback =
            cleanSynopsis(englishFallback)

        val translated =
            fallback?.let {
                translateSynopsisToArabic(it)
            }

        val result =
            cleanSynopsis(translated)
                ?: fallback

        if (!result.isNullOrBlank()) {
            synopsisCache[cacheKey] = result
        }

        return result
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
        val effectiveImdbId = imdbJob.await()
        val arabicOverview = synopsisJob.await()

        detail?.let { meta ->
            val originalName =
                meta.originalTitle
                    ?: meta.originalName

            val localizedName =
                meta.title
                    ?: meta.name

            val preferredName =
                when (
                    meta.originalLanguage
                        ?.lowercase()
                ) {
                    "ar",
                    "en" ->
                        originalName
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: localizedName

                    else ->
                        localizedName
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: originalName
                }

            if (!preferredName.isNullOrBlank()) {
                base.name = preferredName
            }
        }

        if (!arabicOverview.isNullOrBlank()) {
            base.plot = arabicOverview
        } else {
            base.plot = cleanSynopsis(base.plot)
        }

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

        when (base) {
            is MovieLoadResponse -> {
                base.dataUrl = HubPlaybackRequest(
                    title = base.name,
                    year = base.year,
                    imdbId = effectiveImdbId,
                    tmdbId = tmdbId,
                    isTv = false,
                    originalLanguage =
                        detail?.originalLanguage
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
                        originalLanguage =
                            detail?.originalLanguage,
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

        return score
    }

    private fun normalizeExternalSubtitle(
        sub: StremioSubtitle,
        source: String
    ): SubtitleFile? {
        val url = sub.url?.takeIf { it.startsWith("http") } ?: return null

        return when (subtitleLanguage(sub.lang)) {
            PreferredSubtitleLanguage.ARABIC ->
                SubtitleFile("Arabic • $source", url)
            PreferredSubtitleLanguage.ENGLISH ->
                SubtitleFile("English • $source", url)
            PreferredSubtitleLanguage.OTHER -> null
        }
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

        val timeout = if (MOOSASettingsStore.arabicFastMode()) 900L else 1800L

        return timed(timeout) {
            app.get(
                "$tmdbApiUrl/$type/$tmdbId/external_ids",
                params = mapOf("api_key" to tmdbApiKey)
            ).parsedSafe<TmdbExternalIds>()
                ?.imdbId
                ?.takeIf { it.startsWith("tt") }
        }
    }

    private fun currentSubtitleTimeoutMs(): Long =
        if (MOOSASettingsStore.arabicFastMode()) {
            fastSubtitleTimeoutMs
        } else {
            subtitleTimeoutMs
        }

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
                "https://sub.wyzie.ru/search",
                params = params,
                headers = mapOf("Accept" to "application/json")
            ).parsedSafe<Array<WyzieSubtitle>>()
        } ?: return emptyList()

        return response.mapNotNull { item ->
            val url = item.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val lang = item.language.orEmpty()
            when (subtitleLanguage(lang, lang)) {
                PreferredSubtitleLanguage.ARABIC ->
                    SubtitleFile(item.display ?: "Arabic • Wyzie", url)
                PreferredSubtitleLanguage.ENGLISH ->
                    SubtitleFile(item.display ?: "English • Wyzie", url)
                PreferredSubtitleLanguage.OTHER -> null
            }
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

        val response = timed(2200L) {
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
        onArabic: (SubtitleFile) -> Unit
    ): List<SubtitleFile> = coroutineScope {
        val imdb = resolveSubtitleImdb(request) ?: return@coroutineScope emptyList()
        val cacheKey = subtitleCacheKey(request, imdb)

        subtitleCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < subtitleCacheLifetimeMs) {
                cached.items
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
                return@coroutineScope cached.items
            }
            subtitleCache.remove(cacheKey)
        }

        val v3 = async {
            fetchOpenSubtitlesV3(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }

        val legacy = async {
            fetchOpenSubtitlesLegacy(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }

        val wyzie = async {
            fetchWyzieSubtitles(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }

        val subDl = async {
            fetchSubDlSubtitles(request, imdb).also { subtitles ->
                subtitles
                    .filter {
                        subtitleLanguage(it.lang, it.langTag) ==
                            PreferredSubtitleLanguage.ARABIC
                    }
                    .sortedByDescending(::subtitleScore)
                    .forEach(onArabic)
            }
        }

        val all = (
            v3.await() +
                legacy.await() +
                wyzie.await() +
                subDl.await()
            )
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)

        if (all.isNotEmpty()) {
            subtitleCache[cacheKey] = CachedSubtitleList(
                createdAt = System.currentTimeMillis(),
                items = all
            )
        }

        all
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

    private fun inferQuality(link: ExtractorLink): Int {
        if (link.quality >= 720) return link.quality

        val text = "${link.name} ${link.source} ${link.url}".lowercase()
        return when {
            "2160" in text || "4k" in text || "uhd" in text -> 2160
            "1440" in text || "qhd" in text || "2k" in text -> 1440
            "1080" in text || "fhd" in text || "fullhd" in text || "full hd" in text -> 1080
            "720" in text || Regex("""\bhd\b""").containsMatchIn(text) -> 720
            else -> link.quality
        }
    }

    private fun isDownloadLabeled(link: ExtractorLink): Boolean {
        val label = "${link.name} ${link.source}".lowercase()
        return "download" in label || "تحميل" in label
    }

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
        val providers = playbackProviders(request)

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
            playbackAliases(request)
        }

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        val nativeSubtitles =
            Collections.synchronizedList(
                mutableListOf<SubtitleFile>()
            )
        val fallbackLinks =
            Collections.synchronizedList(
                mutableListOf<ExtractorLink>()
            )
        val emittedLink = AtomicBoolean(false)
        val completedProviders = AtomicInteger(0)
        val shownLinks = AtomicInteger(0)
        val callbackLock = Any()

        val externalSubtitleJob = async {
            timed(6500L) {
                fetchExternalSubtitles(request) { subtitle ->
                    if (
                        seenSubtitles.add(
                            subtitle.url
                        )
                    ) {
                        subtitleCallback(
                            subtitle
                        )
                    }
                }
            } ?: emptyList()
        }

        val aliases = aliasesJob.await()

        val providerBatchSize = MOOSASettingsStore.providerConcurrency()

        for (batch in providers.chunked(providerBatchSize)) {
            batch.amap { api ->
                var providerWorked = false

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
                        val candidateLinks =
                            Collections.synchronizedList(mutableListOf<ExtractorLink>())

                        timed(linksTimeoutFor(api, request)) {
                            api.loadLinks(
                                target.data,
                                isCasting,
                                { subtitle ->
                                    normalizeNativeSubtitle(subtitle, api.name)?.let { normalized ->
                                        if (normalized.url.isBlank()) return@let

                                        when (
                                            subtitleLanguage(
                                                normalized.lang,
                                                normalized.langTag
                                            )
                                        ) {
                                            PreferredSubtitleLanguage.ARABIC -> {
                                                if (seenSubtitles.add(normalized.url)) {
                                                    subtitleCallback(normalized)
                                                }
                                            }

                                            PreferredSubtitleLanguage.ENGLISH -> {
                                                nativeSubtitles += normalized
                                            }

                                            PreferredSubtitleLanguage.OTHER -> Unit
                                        }
                                    }
                                },
                                { link ->
                                    if (
                                        validStreamingUrl(link.url) &&
                                        !isDownloadLabeled(link)
                                    ) {
                                        MOOSAServerRegistry.record(link)

                                        if (!MOOSAServerRegistry.isDisabled(link)) {
                                            candidateLinks += link
                                        }
                                    }
                                }
                            )
                        }

                        if (
                            candidateLinks.isEmpty() &&
                            !isStreamPlayProvider(api)
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
                                            normalizeNativeSubtitle(
                                                subtitle,
                                                api.name
                                            )?.let { normalized ->
                                                if (
                                                    normalized.url.isBlank()
                                                ) {
                                                    return@let
                                                }

                                                when (
                                                    subtitleLanguage(
                                                        normalized.lang,
                                                        normalized.langTag
                                                    )
                                                ) {
                                                    PreferredSubtitleLanguage.ARABIC -> {
                                                        if (
                                                            seenSubtitles.add(
                                                                normalized.url
                                                            )
                                                        ) {
                                                            subtitleCallback(
                                                                normalized
                                                            )
                                                        }
                                                    }

                                                    PreferredSubtitleLanguage.ENGLISH -> {
                                                        nativeSubtitles +=
                                                            normalized
                                                    }

                                                    PreferredSubtitleLanguage.OTHER ->
                                                        Unit
                                                }
                                            }
                                        },
                                        { link ->
                                            if (
                                                validStreamingUrl(
                                                    link.url
                                                ) &&
                                                !isDownloadLabeled(link)
                                            ) {
                                                MOOSAServerRegistry
                                                    .record(link)

                                                if (
                                                    !MOOSAServerRegistry
                                                        .isDisabled(link)
                                                ) {
                                                    candidateLinks += link
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "Provider returned ${candidateLinks.size} link(s)"
                        )

                        val candidates = candidateLinks
                            .distinctBy(::linkKey)
                            .sortedByDescending(::inferQuality)

                        for (linkBatch in candidates.chunked(linkProbeBatchSize)) {
                            linkBatch.amap { link ->
                                val key = linkKey(link)
                                if (seenLinks.contains(key)) {
                                    return@amap false
                                }

                                val verified =
                                    !MOOSASettingsStore.verifyLinks() ||
                                        verifyStreamingLink(link)

                                if (
                                    verified &&
                                    seenLinks.add(key)
                                ) {
                                    providerWorked = true
                                    emittedLink.set(true)
                                    shownLinks.incrementAndGet()
                                    MOOSAProgress.linkAvailable()

                                    synchronized(callbackLock) {
                                        callback(link)
                                    }
                                    true
                                } else {
                                    if (
                                        !verified &&
                                        !seenLinks.contains(key) &&
                                        (
                                            !request.originalLanguage.equals(
                                                "ar",
                                                ignoreCase = true
                                            ) ||
                                                isArabicProvider(api) ||
                                                MOOSAArabicBridge.supports(api) ||
                                                isStreamPlayProvider(api)
                                            )
                                    ) {
                                        fallbackLinks += link
                                    }
                                    false
                                }
                            }
                        }
                    } else {
                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "No matching page or episode"
                        )
                    }

                    if (providerWorked) {
                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "Playback ready"
                        )

                        providerHealth.merge(api.name, 3) { old, add ->
                            (old + add).coerceAtMost(24)
                        }
                    } else {
                        if (
                            !MOOSAPlaybackDiagnostics
                                .report()
                                .contains(
                                    "${api.name}: No matching page or episode"
                                )
                        ) {
                            MOOSAPlaybackDiagnostics.update(
                                api,
                                "No usable playback links"
                            )
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
        }

        if (
            shownLinks.get() == 0 &&
            fallbackLinks.isNotEmpty()
        ) {
            fallbackLinks
                .distinctBy(::linkKey)
                .sortedByDescending(::inferQuality)
                .take(10)
                .forEach { link ->
                    val key = linkKey(link)
                    if (seenLinks.add(key)) {
                        emittedLink.set(true)
                        shownLinks.incrementAndGet()
                        MOOSAProgress.linkAvailable()

                        callback(link)
                    }
                }
        }

        MOOSAProgress.serversReady(shownLinks.get())
        MOOSAProgress.subtitles()

        val externalSubtitles = externalSubtitleJob.await()

        val remainingSubtitles = (
            nativeSubtitles +
                externalSubtitles.filter {
                    subtitleLanguage(it.lang, it.langTag) == PreferredSubtitleLanguage.ENGLISH
                }
            )
            .filter {
                subtitleLanguage(it.lang, it.langTag) != PreferredSubtitleLanguage.OTHER
            }
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)

        remainingSubtitles.forEach { subtitle ->
            if (seenSubtitles.add(subtitle.url)) {
                try {
                    subtitleCallback(subtitle)
                } catch (error: Throwable) {
                    logError(error)
                }
            }
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
