package com.moosa.hub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.AnimeSearchResponse
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
import java.util.concurrent.atomic.AtomicLong
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
        val titleAliases: List<String> = emptyList(),
        val isAnime: Boolean = false,
        val absoluteEpisode: Int? = null
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
        // Official SubDL responses may provide "lang" without "language".
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("release_name") val releaseName: String? = null,
        @JsonProperty("fps") val fps: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("unpack_files") val unpackFiles: List<SubDlFile> = emptyList()
    )

    private data class SubDlFile(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("release_name") val releaseName: String? = null,
        @JsonProperty("name") val name: String? = null,
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

    private data class TmdbGenre(
        @JsonProperty("id") val id: Int? = null
    )

    private data class TmdbDetail(
        @JsonProperty("genres") val genres: List<TmdbGenre> = emptyList(),
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
    // CloudStream guesses subtitle MIME from the URL suffix, not Content-Type.
    // Remember the actual validated text format so extensionless SubDL VTT
    // links are not handed to ExoPlayer as SRT (which renders no captions).
    private val verifiedSubtitleFormats = ConcurrentHashMap<String, String>()
    // One SubDL limit response must not trigger another 10 download attempts.
    private val subDlDownloadBlockedUntilMs = AtomicLong(0L)
    @Volatile private var subDlDownloadLimitReason = ""

    private val cacheLifetimeMs = 20 * 60 * 1000L
    private val subtitleCacheLifetimeMs = 15 * 60 * 1000L
    private val maxTargetCacheEntries = 512
    private val maxSubtitleCacheEntries = 96
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
        is AnimeSearchResponse -> result.year
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
        }.firstOrNull() ?: MOOSAAnimePolicy.explicitAnimeSeason(value)
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
        // Fuzzy ranking is allowed *after* an exact official-title gate.
        // A near-name is not proof of identity (Dune vs Dune Part Two,
        // Scary Movie vs Scary Movie 2, Naruto vs Naruto Shippuden).
        val strict = MOOSAMatchGuard.titleAndClaimCompatible(
            MOOSAMatchGuard.Claim(
                title = aliases.firstOrNull().orEmpty(),
                year = year, season = season
            ),
            MOOSAMatchGuard.Evidence(
                title = result.name, year = resultYear(result)
            ),
            aliases
        )
        if (!strict) return 0
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

    private fun identityClaim(request: HubPlaybackRequest) =
        MOOSAMatchGuard.Claim(
            title = request.title,
            year = request.year,
            imdbId = request.imdbId,
            tmdbId = request.tmdbId,
            season = request.season,
            episode = request.episode,
            isAnime = request.isAnime
        )

    private fun loadedEvidence(
        result: LoadResponse,
        searchYear: Int? = null
    ): MOOSAMatchGuard.Evidence =
        MOOSAMatchGuard.Evidence(
            title = result.name,
            year = result.year,
            searchYear = searchYear,
            imdbId = result.getImdbId(),
            tmdbId = result.getTMDbId()?.toIntOrNull(),
            additionalNames = if (result is AnimeLoadResponse) {
                listOfNotNull(result.engName, result.japName) +
                    result.synonyms.orEmpty()
            } else emptyList()
        )

    private fun loadedIdentityMatches(
        result: LoadResponse,
        request: HubPlaybackRequest,
        aliases: List<String>,
        searchYear: Int? = null
    ): Boolean =
        MOOSAMatchGuard.verifiedIdentity(
            identityClaim(request), loadedEvidence(result, searchYear), aliases
        )

    /** The only yearless recovery path: same exact official title in
     * search AND loaded page, plus TMDB proves no other same-name work.
     * Conflicting IDs, years and episode numbers still reject before I/O.
     */
    private suspend fun verifiedSearchLoadedIdentity(
        loaded: LoadResponse,
        search: SearchResponse,
        request: HubPlaybackRequest,
        aliases: List<String>
    ): Boolean {
        val evidence = loadedEvidence(loaded, resultYear(search))
            .copy(searchTitle = search.name)
        val claim = identityClaim(request)
        if (MOOSAMatchGuard.verifiedIdentity(claim, evidence, aliases)) return true
        if (!MOOSAMatchGuard.verifiedIdentity(
                claim, evidence, aliases, allowCorroboratedTitleOnly = true
            )
        ) return false
        return MOOSATmdbCollisionGuard.hasSingleExactTitle(request, aliases)
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
            (if (request.isAnime) listOf("ja-JP", "en-US", "ar-SA")
             else listOf("ar-SA", "ar", "en-US")).map { language ->
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
            // Anime sites frequently index each cour/season under its own
            // English/Romaji title, e.g. "2nd Season", not TMDB S2E1.
            if (request.isAnime && season > 1) {
                preferredBase.take(3).forEach { title ->
                    addAll(MOOSAAnimePolicy.seasonQueries(title, season))
                }
                addAll(seasonNames)
            }
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
            // Do not remove anime "Season 2" / "2nd Season" by the same
            // canonical key as the base show: sources search them separately.
            .distinctBy { normalizeArabicOrthography(normalizeTitle(it)) }
            .take(if (request.isAnime) 28 else 24)
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
            if (request.isAnime && wantSeries && season != null && season > 1) {
                // Try sequel-season names first rather than spending the whole
                // provider timeout on the base season's generic page.
                val seasonSpecific = cleanAliases.filter { title ->
                    MOOSAAnimePolicy.explicitAnimeSeason(title) == season
                }
                (seasonSpecific + cleanAliases.filterNot { it in seasonSpecific })
                    .distinct()
            } else if (
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
        var firstCandidateQuery: Int? = null

        for ((index, query) in queryVariants.withIndex()) {
            // First searchable title is NOT evidence the matching episode
            // exists. Check two additional query variants for other pages.
            if (firstCandidateQuery?.let { index > it + 2 } == true) break
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
                        animeGenerationMatches(request, item.name, resultYear(item)) &&
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
                    if (firstCandidateQuery == null) firstCandidateQuery = index
                    break
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

    private fun animeGenerationMatches(
        request: HubPlaybackRequest,
        title: String,
        year: Int?
    ): Boolean =
        !request.isAnime ||
            MOOSAAnimePolicy.sameFirstSeasonGeneration(
                request.year,
                year ?: MOOSAAnimePolicy.yearInTitle(title),
                request.season
            )

    private fun nativeAnimeEpisodeData(
        series: AnimeLoadResponse,
        request: HubPlaybackRequest,
        searchTitle: String,
        aliases: List<String>
    ): String? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null
        if (!animeGenerationMatches(request, series.name, series.year) ||
            !animeGenerationMatches(request, searchTitle, null)
        ) return null

        val matchingSeasonPage = listOf(series.name, searchTitle).any { title ->
            MOOSAAnimePolicy.explicitAnimeSeason(title) == season &&
                seasonSpecificTitleMatch(title, aliases, season)
        }
        fun entries(status: DubStatus): List<MOOSAAnimeStreamEpisodePolicy.Item> =
            series.episodes[status].orEmpty().map { item ->
                val number = item.episode?.takeIf { it > 0 }
                    ?: Regex("""(?i)\b(?:episode|ep)\s*[:#-]?\s*(\d{1,4})\b""")
                        .find(item.name.orEmpty())
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                val srcSeason = item.season?.takeIf { it > 0 }
                    ?: MOOSAAnimePolicy.explicitAnimeSeason(item.name.orEmpty())
                MOOSAAnimeStreamEpisodePolicy.Item(
                    data = item.data, season = srcSeason, number = number
                )
            }
        return MOOSAAnimeStreamEpisodePolicy.select(
            season = season,
            episode = episode,
            absolute = request.absoluteEpisode,
            sourceTitle = series.name,
            pageProvesSeason = matchingSeasonPage,
            tracks = MOOSAAnimeStreamEpisodePolicy.Tracks(
                subbed = entries(DubStatus.Subbed),
                unlabelled = entries(DubStatus.None),
                dubbed = entries(DubStatus.Dubbed)
            )
        )
    }

    private fun animeSeriesHasEpisode(
        series: TvSeriesLoadResponse,
        request: HubPlaybackRequest,
        searchTitle: String,
        aliases: List<String>
    ): Boolean {
        val season = request.season ?: return false
        val episode = request.episode ?: return false
        if (!animeGenerationMatches(request, series.name, series.year) ||
            !animeGenerationMatches(request, searchTitle, null)
        ) return false
        fun number(item: Episode): Int? =
            item.episode?.takeIf { it > 0 }
                ?: Regex("""(?i)\b(?:episode|ep)\s*[:#-]?\s*(\d{1,4})\b""")
                    .find(item.name.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        fun sourceSeason(item: Episode): Int? =
            item.season?.takeIf { it > 0 }
                ?: MOOSAAnimePolicy.explicitAnimeSeason(item.name.orEmpty())

        val explicitTitleSeasons = listOf(series.name, searchTitle)
            .mapNotNull(MOOSAAnimePolicy::explicitAnimeSeason)
        if (explicitTitleSeasons.any { it != season }) return false

        if (series.episodes.any {
                sourceSeason(it) == season && number(it) == episode
            }) return true

        val availableSeasons = series.episodes.mapNotNull(::sourceSeason).distinct()
        val validSingleSeason =
            availableSeasons.isEmpty() || availableSeasons == listOf(1)
        val pageProvesSeason =
            explicitTitleSeasons.contains(season) &&
                listOf(series.name, searchTitle).any {
                    seasonSpecificTitleMatch(it, aliases, season)
                }
        if (validSingleSeason && (season == 1 || pageProvesSeason) &&
            series.episodes.any { number(it) == episode }
        ) return true

        return MOOSAAnimePolicy.allowsContinuousNumbering(
            requestedSeason = season,
            localEpisode = episode,
            absoluteEpisode = request.absoluteEpisode,
            sourceTitle = series.name,
            sourceEpisodes = series.episodes.map { item ->
                MOOSAAnimePolicy.SourceIndex(sourceSeason(item), number(item))
            }
        )
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
                response is TvSeriesLoadResponse ||
                    (request.isAnime && response is AnimeLoadResponse)
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
                correctType(direct) &&
                animeGenerationMatches(request, direct.name, direct.year) &&
                loadedIdentityMatches(direct, request, aliases) &&
                (!request.isAnime || when (direct) {
                    is AnimeLoadResponse ->
                        nativeAnimeEpisodeData(direct, request, direct.name, aliases) != null
                    is TvSeriesLoadResponse ->
                        animeSeriesHasEpisode(direct, request, direct.name, aliases)
                    else -> true
                })
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
                    ) &&
                    animeGenerationMatches(request, direct.name, direct.year) &&
                    (!request.isAnime || when (direct) {
                        is AnimeLoadResponse ->
                            nativeAnimeEpisodeData(direct, request, direct.name, aliases) != null
                        is TvSeriesLoadResponse ->
                            animeSeriesHasEpisode(direct, request, direct.name, aliases)
                        else -> true
                    })
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
                    arabSeedEpisodeConfirmed(api, it, request, wantSeries) &&
                    animeGenerationMatches(request, it.name, resultYear(it))
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
            if (!animeGenerationMatches(request, loaded.name, loaded.year)) continue

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
            // A matching anime title is not proof that the requested
            // episode is on this provider page. Try the NEXT search result
            // before caching an unusable base-series target.
            if (request.isAnime) {
                if (loaded is TvSeriesLoadResponse &&
                    !animeSeriesHasEpisode(loaded, request, match.name, aliases)
                ) continue
                if (loaded is AnimeLoadResponse &&
                    nativeAnimeEpisodeData(loaded, request, match.name, aliases) == null
                ) continue
            }

            if (
                verifiedSearchLoadedIdentity(
                    loaded, match, request, aliases
                )
            ) {
                return MatchedLoad(loaded, match.name)
            }
        }

        if (candidates.isNotEmpty()) {
            MOOSAPlaybackDiagnostics.update(
                api,
                "Strict identity rejected • missing/mismatched year, ID, title or episode"
            )
        }

        return null
    }

    private fun isBundledArabicProvider(api: MainAPI): Boolean =
        api.javaClass.name.startsWith("com.moosa.hub.bundled.")

    // Native CineStream-origin direct source. Unlike a registered external
    // CineStream plugin, this ships and resolves streams inside MOOSA Hub.
    // It is not exposed as an extra home-page extension.
    private val native4KHDHub = MOOSA4KHDHubProvider()
    private val nativeVixSrc = MOOSAVixSrcProvider()
    private val nativeNetMirror = MOOSANetMirrorProvider()
    private val nativeOpenDirectory = MOOSAOpenDirectoryProvider()
    private val nativeVaPlayer = MOOSACineStreamVaPlayerProvider()
    private val nativeVidrock = MOOSACineStreamVidrockProvider()
    private val nativeVidzee = MOOSACineStreamVidzeeProvider()

    private fun playbackProviders(
        request: HubPlaybackRequest
    ): List<MainAPI> {
        val disabled =
            MOOSASettingsStore.disabledSources()

        fun hasOwnLoadLinks(api: MainAPI): Boolean =
            try {
                // Anime extensions may inherit loadLinks from a shared site
                // base class rather than declare it on the concrete provider.
                // Kotlin declaredMethods misses those valid providers.
                api.javaClass.methods.any { method ->
                    method.name == "loadLinks" &&
                        method.declaringClass != MainAPI::class.java
                }
            } catch (_: Throwable) {
                false
            }

        // Plugins can register in CloudStream's global provider registry
        // without being present in its currently mapped UI provider list.
        // Consider both for anime; never load other plugins recursively.
        // CineStream / StreamPlay can be registered globally even if the
        // CloudStream UI-mapped list is stale after plugin hot-loading.
        // Pull ONLY these recognized extras for movies/series. The anime
        // registry retains its full existing behavior.
        // This is registration interoperability, NOT a bundled provider.
        val registry = (
            listOf(native4KHDHub, nativeVixSrc, nativeNetMirror, nativeOpenDirectory, nativeVaPlayer, nativeVidrock, nativeVidzee) + apis.toList() +
            allProviders.toList().filter { api ->
                MOOSARecognizedExternalProvider.shouldIncludeFromGlobal(
                    api.name, request.isAnime
                )
            }
        ).distinctBy { api ->
            "${api.name}|${api.sourcePlugin.orEmpty()}|${api.mainUrl}"
        }
        val available = registry
            .filter { api ->
                // Arabic sites belong to the independent native provider.
                (!isArabicProvider(api) || (request.isAnime &&
                    (TvType.Anime in api.supportedTypes ||
                        TvType.AnimeMovie in api.supportedTypes ||
                        TvType.OVA in api.supportedTypes))) &&
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
                        !request.isAnime && request.isTv && it is MOOSAVixSrcProvider -> 10
                        !request.isAnime && !request.isTv && it is MOOSA4KHDHubProvider -> 10
                        !request.isAnime && request.isTv && it is MOOSA4KHDHubProvider -> 7
                        !request.isAnime && it is MOOSAVixSrcProvider -> 9
                        !request.isAnime && it is MOOSANetMirrorProvider -> 8
                        !request.isAnime && it is MOOSAOpenDirectoryProvider -> 6
                        arabicRequest && isAkwamProvider(it) -> 6
                        arabicRequest && it.name.equals("ShahidWBas", true) -> 5
                        arabicRequest && isArabSeedProvider(it) -> 4
                        isBundledArabicProvider(it) -> 3
                        request.isAnime && (TvType.Anime in it.supportedTypes ||
                            TvType.AnimeMovie in it.supportedTypes ||
                            TvType.OVA in it.supportedTypes) -> 2
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

    /** Same live source eligibility used by the player, not just tester rows. */
    internal fun animeSourceCount(): Int =
        playbackProviders(
            HubPlaybackRequest(
                title = "Anime",
                isTv = true,
                originalLanguage = "ja",
                isAnime = true
            )
        ).size

    private fun providerCacheKey(
        request: HubPlaybackRequest,
        api: MainAPI
    ): String = listOf(
        "strict-v20-collision-aware",
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

    private fun cacheTarget(key: String, target: ProviderTarget) {
        val now = System.currentTimeMillis()
        MOOSACacheRetention.makeRoom(
            targetCache, maxTargetCacheEntries, now,
            cacheLifetimeMs, CachedTarget::createdAt
        )
        targetCache[key] = CachedTarget(now, target)
    }

    private suspend fun resolveTargetForProvider(
        request: HubPlaybackRequest,
        api: MainAPI,
        aliases: List<String>,
        forceRefresh: Boolean = false
    ): ProviderTarget? {
        // VixSrc resolves TMDB ID + exact S/E directly, not fuzzy titles.
        if (api is MOOSAVixSrcProvider) {
            if (request.isAnime || request.tmdbId == null ||
                (request.isTv && (request.season == null || request.episode == null))
            ) return null
            return ProviderTarget(api.name, api.mainUrl, request.toJson())
        }
        if (api is MOOSAOpenDirectoryProvider) {
            if (request.isAnime || MOOSAOpenDirectoryPolicy.directory(
                    request.title, request.year,
                    if (request.isTv) request.season else null
                ) == null || (request.isTv && request.episode == null)
            ) return null
            return ProviderTarget(api.name, api.mainUrl, request.toJson())
        }
        if (api is MOOSANetMirrorProvider) {
            if (request.isAnime || MOOSANetMirrorPolicy.apiUrl(
                    request.tmdbId, request.isTv, request.season, request.episode
                ) == null
            ) return null
            return ProviderTarget(api.name, api.mainUrl, request.toJson())
        }
        // The local UHD adapter performs its own exact title/year matching
        // on Play. No fuzzy search or external repository is required.
        if (api is MOOSA4KHDHubProvider) {
            if (request.isAnime || request.year == null ||
                (request.isTv && (request.season == null || request.episode == null))
            ) return null
            return ProviderTarget(api.name, api.mainUrl, request.toJson())
        }
        // The native VaPlayer adapter addresses IMDb IDs directly and needs
        // NO fuzzy title search, Google translation, TMDB-page scraping or
        // separately installed CineStream. Reject incomplete series episodes.
        if (api is MOOSACineStreamVaPlayerProvider) {
            MOOSACineStreamVaPlayerPolicy.requestUrl(
                request.imdbId, request.season, request.episode
            ) ?: return null
            return ProviderTarget(api.name, api.mainUrl, request.toJson())
        }
        if (api is MOOSACineStreamVidrockProvider) {
            // Exact TMDB show ID plus season/episode, never fuzzy matching or
            // falling back to another season when the requested one is absent.
            MOOSACineStreamVidrockPolicy.requestUrl(
                request.tmdbId, request.isTv, request.season, request.episode
            ) ?: return null
            return ProviderTarget(api.name, api.mainUrl, request.toJson())
        }
        if (api is MOOSACineStreamVidzeeProvider) {
            MOOSACineStreamVidzeePolicy.requestUrl(
                request.tmdbId, request.isTv, request.season, request.episode, 0
            ) ?: return null
            return ProviderTarget(api.name, api.mainUrl, request.toJson())
        }
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
                cacheTarget(cacheKey, bridged)

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

        val dataCandidate = if (!isSeries || loaded is MovieLoadResponse) {
            (loaded as? MovieLoadResponse)?.dataUrl
        } else if (request.isAnime && loaded is AnimeLoadResponse) {
            nativeAnimeEpisodeData(loaded, request, matched.searchTitle, aliases)
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

            // If TMDB S2E3 is absolute episode 15, an anime source
            // sometimes publishes only Season 1 episodes 1..N. Use E15
            // solely when the entire continuous prefix is verified.
            val continuous = if (
                request.isAnime &&
                exact == null && seasonless == null &&
                firstSeasonFallback == null &&
                MOOSAAnimePolicy.allowsContinuousNumbering(
                    requestedSeason = season,
                    localEpisode = episode,
                    absoluteEpisode = request.absoluteEpisode,
                    sourceTitle = series.name,
                    sourceEpisodes = series.episodes.map { item ->
                        MOOSAAnimePolicy.SourceIndex(
                            season = providerSeason(item),
                            episode = episodeNumber(item)
                        )
                    }
                )
            ) {
                series.episodes.firstOrNull { item ->
                    episodeNumber(item) == request.absoluteEpisode &&
                        (providerSeason(item) == null || providerSeason(item) == 1)
                }
            } else null

            val episodeData = (
                exact
                    ?: seasonless
                    ?: firstSeasonFallback
                    ?: continuous
                )?.data

            if (episodeData.isNullOrBlank()) {
                MOOSAPlaybackDiagnostics.update(
                    api,
                    "Series matched, but S${season}E${episode} not confirmed"
                )
            }
            episodeData
        }
        val data = dataCandidate?.takeIf { it.isNotBlank() } ?: return null
        // Verify explicit SxxExx / season+episode hints inside upstream
        // episode data before caching a provider target. This applies to
        // every movie/series/anime provider, not only reported bad servers.
        if (MOOSAMediaIdentityPolicy.targetContradicts(identityClaim(request), data)) {
            MOOSAPlaybackDiagnostics.update(
                api, "Provider episode payload contradicts requested season/episode"
            )
            return null
        }

        val target = ProviderTarget(
            providerName = api.name,
            mainUrl = api.mainUrl,
            data = data
        )

        cacheTarget(cacheKey, target)

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

        val anime = MOOSAAnimePolicy.isAnime(
            detectedOriginalLanguage, detail?.genres?.mapNotNull { it.id }.orEmpty()
        )

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
                if (anime) base.type = TvType.AnimeMovie
                base.dataUrl = HubPlaybackRequest(
                    title = base.name,
                    year = base.year,
                    imdbId = effectiveImdbId,
                    tmdbId = tmdbId,
                    isTv = false,
                    originalLanguage = detectedOriginalLanguage,
                    titleAliases = sourceTitleAliases,
                    isAnime = anime
                ).toJson()
                base
            }

            is TvSeriesLoadResponse -> {
                if (anime) base.type = TvType.Anime
                val absolutes = if (anime) {
                    MOOSAAnimePolicy.absoluteEpisodes(
                        base.episodes.mapNotNull { entry ->
                            val season = entry.season ?: return@mapNotNull null
                            val number = entry.episode ?: return@mapNotNull null
                            MOOSAAnimePolicy.Index(season, number)
                        }
                    )
                } else emptyMap()
                base.episodes.forEach { episode ->
                    val absolute = if (anime && episode.season != null &&
                        episode.episode != null
                    ) {
                        absolutes[MOOSAAnimePolicy.Index(
                            episode.season!!, episode.episode!!
                        )]
                    } else null
                    if (anime) {
                        episode.name = MOOSAAnimePolicy.displayEpisodeName(
                            episode.name, episode.season, episode.episode, absolute
                        )
                    }
                    episode.data = HubPlaybackRequest(
                        title = base.name,
                        year = base.year,
                        imdbId = effectiveImdbId,
                        tmdbId = tmdbId,
                        isTv = isTv,
                        originalLanguage = detectedOriginalLanguage,
                        titleAliases = sourceTitleAliases,
                        season = episode.season,
                        episode = episode.episode,
                        isAnime = anime,
                        absoluteEpisode = absolute
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
        request.episode?.toString().orEmpty(),
        (MOOSASettingsStore.subdlKey()?.hashCode() ?: 0).toString(),
        (MOOSASettingsStore.wyzieKey()?.hashCode() ?: 0).toString()
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
        } ?: run {
            MOOSASubtitleHealth.update("OpenSubtitles", "Index timeout or invalid response")
            return emptyList()
        }

        MOOSASubtitleHealth.update(
            "OpenSubtitles",
            "Index: ${response.subtitles.count { it.lang == "ara" }} Arabic, " +
                "${response.subtitles.count { it.lang == "eng" }} English"
        )
        val parsed = response.subtitles
            .mapNotNull { normalizeExternalSubtitle(it, "OpenSubtitles") }
            .distinctBy { it.url }
        MOOSASubtitleHealth.candidates("OpenSubtitles", parsed.size)
        return parsed
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
            """{"id":1,"jsonrpc":"2.0","method":"subtitles.find","params":[null,{"query":{"itemHash":"$query"},"supportsZip":false}]}""".toByteArray()
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
        val key = MOOSASettingsStore.wyzieKey() ?: run {
            MOOSASubtitleHealth.update("Wyzie", "No API key configured")
            return emptyList()
        }
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
        } ?: run {
            MOOSASubtitleHealth.update("Wyzie", "Index timeout or invalid response")
            return emptyList()
        }

        MOOSASubtitleHealth.update("Wyzie", "Index: ${response.size} entries")
        val parsed = response.mapNotNull { item ->
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
        MOOSASubtitleHealth.candidates("Wyzie", parsed.size)
        return parsed
    }

    private suspend fun fetchSubDlSubtitles(
        request: HubPlaybackRequest,
        imdb: String
    ): List<SubtitleFile> {
        val key = MOOSASettingsStore.subdlKey() ?: run {
            MOOSASubtitleHealth.update("SubDL", "No API key configured")
            return emptyList()
        }
        if (System.currentTimeMillis() < subDlDownloadBlockedUntilMs.get()) {
            MOOSASubtitleHealth.update(
                "SubDL", "Download temporarily paused: " + subDlDownloadLimitReason
            )
            return emptyList()
        }
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
        } ?: run {
            MOOSASubtitleHealth.update("SubDL", "Index timeout or invalid response")
            return emptyList()
        }

        if (!response.status) {
            MOOSASubtitleHealth.update("SubDL", "API status=false (key/quota/server)")
            return emptyList()
        }

        val individualFiles = response.subtitles.sumOf { it.unpackFiles.size }
        MOOSASubtitleHealth.update(
            "SubDL", "Index: ${response.subtitles.size} entries, " +
                "$individualFiles individual files; ZIP archives are skipped"
        )
        val parsed = response.subtitles.flatMap { subtitle ->
            val parentLang = subtitle.language?.takeIf { it.isNotBlank() }
                ?: subtitle.lang.orEmpty()

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

                    val raw = file.url?.takeIf { it.isNotBlank() } ?: run {
                        MOOSASubtitleHealth.omitted("SubDL", "file URL missing")
                        return@mapNotNull null
                    }
                    // Only SRT and VTT are supported through CloudStream's
                    // URL-subtitle callback. No ASS/SSA/SUB disguised as SRT.
                    // SubDL's "format" label is not always canonical.
                    // Explicit ASS/SSA/IDX cannot be passed to CloudStream's
                    // SRT/VTT-only URL callback; unknown format labels must
                    // instead be checked from the actual timed text body.
                    val format = file.format?.trim()?.trimStart('.')?.lowercase()
                    val suffix = file.name?.substringAfterLast('.', "")?.lowercase()
                    val unsupported = setOf("ass", "ssa", "idx", "sub", "sup", "ttml")
                    if (format in unsupported || suffix in unsupported) {
                        MOOSASubtitleHealth.omitted("SubDL", "unsupported format")
                        return@mapNotNull null
                    }
                    val url = if (raw.startsWith("http")) raw
                    else "https://dl.subdl.com$raw"

                    val language = file.language?.takeIf { it.isNotBlank() } ?: parentLang
                    val label = when (subtitleLanguage(language, language)) {
                        PreferredSubtitleLanguage.ARABIC -> "Arabic • SubDL"
                        PreferredSubtitleLanguage.ENGLISH -> "English • SubDL"
                        PreferredSubtitleLanguage.OTHER -> {
                            MOOSASubtitleHealth.omitted("SubDL", "unknown file language")
                            return@mapNotNull null
                        }
                    }

                    val detail = file.releaseName
                        ?: subtitle.releaseName
                        ?: file.name
                        ?: "subtitle"
                    val fps = subtitle.fps?.toDoubleOrNull()
                        ?.takeIf { it in 23.0..60.0 }
                        ?.let { String.format(java.util.Locale.US, "%.2ffps", it) }
                    SubtitleFile(
                        lang = listOfNotNull(label, detail, fps)
                            .joinToString(" • "),
                        url = url
                    )
                }
            } else {
                val raw = subtitle.url?.takeIf { it.isNotBlank() } ?: run {
                    MOOSASubtitleHealth.omitted("SubDL", "parent URL missing")
                    return@flatMap emptyList()
                }
                if (
                    raw.endsWith(".zip", ignoreCase = true) ||
                    raw.endsWith(".rar", ignoreCase = true)
                ) {
                    MOOSASubtitleHealth.omitted("SubDL", "ZIP needs on-demand extraction")
                    return@flatMap emptyList()
                }

                val url = if (raw.startsWith("http")) raw
                else "https://dl.subdl.com$raw"

                val label = when (subtitleLanguage(parentLang, parentLang)) {
                    PreferredSubtitleLanguage.ARABIC -> "Arabic • SubDL"
                    PreferredSubtitleLanguage.ENGLISH -> "English • SubDL"
                    PreferredSubtitleLanguage.OTHER -> {
                        MOOSASubtitleHealth.omitted("SubDL", "unknown parent language")
                        return@flatMap emptyList()
                    }
                }

                listOf(
                    SubtitleFile(
                        lang = "${label} • ${subtitle.releaseName ?: "subtitle"}",
                        url = url
                    )
                )
            }
        }.distinctBy { it.url }
        MOOSASubtitleHealth.candidates("SubDL", parsed.size)
        return parsed
    }

    private suspend fun fetchExternalSubtitles(
        request: HubPlaybackRequest,
        onEarlyCandidates: suspend (List<SubtitleFile>) -> Unit
    ): Unit = coroutineScope {
        val imdb = resolveSubtitleImdb(request) ?: return@coroutineScope
        val cacheKey = subtitleCacheKey(request, imdb)

        subtitleCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.createdAt < subtitleCacheLifetimeMs) {
                MOOSASubtitleHealth.update("OpenSubtitles", "Reusing cached index")
                MOOSASubtitleHealth.update("SubDL", "Reusing cached index")
                MOOSASubtitleHealth.update("Wyzie", "Reusing cached index")
                cached.items.groupBy { MOOSASubtitleHealth.sourceOf(it.lang) }
                    .forEach { (source, items) ->
                        MOOSASubtitleHealth.candidates(source, items.size)
                    }
                cached.items.groupBy { MOOSASubtitleHealth.sourceOf(it.lang) }
                    .values.forEach { onEarlyCandidates(it) }
                return@coroutineScope
            }
            subtitleCache.remove(cacheKey)
        }

        // Run all three independent indexes concurrently. Previously an
        // OpenSubtitles timeout delayed SubDL/Wyzie delivery, and awaiting a
        // slow Wyzie response also hid a ready SubDL result (or vice versa).
        // Each source publishes its own candidates immediately; validation
        // remains separately bounded by the single shared playback semaphore.
        val v3 = async {
            fetchOpenSubtitlesV3(request, imdb)
                .filter { validStreamingUrl(it.url) }
                .distinctBy { it.url }
                .also { if (it.isNotEmpty()) onEarlyCandidates(it) }
        }
        val wyzie = async {
            fetchWyzieSubtitles(request, imdb)
                .also { if (it.isNotEmpty()) onEarlyCandidates(it) }
        }
        val subDl = async {
            fetchSubDlSubtitles(request, imdb)
                .also { if (it.isNotEmpty()) onEarlyCandidates(it) }
        }

        // Don't mistake English-only indexes for adequate Arabic coverage.
        // OpenSubtitles legacy is an independent, documented subtitle index.
        // Only consult it if v3 has fewer than two Arabic candidates.
        // Begin the fallback as soon as v3 completes instead of waiting for
        // slower SubDL/Wyzie indexes; still use the existing playback window.
        val backupJob = async {
            val primaryIndex = v3.await()
            if (primaryIndex.count {
                    subtitleLanguage(it.lang, it.langTag) ==
                        PreferredSubtitleLanguage.ARABIC
                } < 2
            ) {
                // Legacy and v3 can return the same subtitle URL. Remove
                // duplicates BEFORE validation, not only at cache merge time.
                val primaryUrls = primaryIndex.map { it.url }.toSet()
                fetchOpenSubtitlesLegacy(request, imdb)
                    .filterNot { it.url in primaryUrls }
                    .also { if (it.isNotEmpty()) onEarlyCandidates(it) }
            } else emptyList()
        }
        val primary = v3.await()
        val supplemental = wyzie.await() + subDl.await()
        val backup = backupJob.await()
        val all = (primary + supplemental + backup)
            .filter { validStreamingUrl(it.url) }
            .filterNot {
                val url = it.url.substringBefore("?").lowercase()
                url.endsWith(".zip") || url.endsWith(".rar") ||
                    url.endsWith(".7z") || url.endsWith(".gz")
            }
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)

        if (all.isNotEmpty()) {
            MOOSACacheRetention.makeRoom(
                subtitleCache, maxSubtitleCacheEntries,
                System.currentTimeMillis(), subtitleCacheLifetimeMs,
                CachedSubtitleList::createdAt
            )
            subtitleCache[cacheKey] = CachedSubtitleList(
                createdAt = System.currentTimeMillis(), items = all
            )
        }
    }

    private val subtitleTiming = Regex(
        """(?m)(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3}"""
    )
    // An arrow somewhere in the file is not enough: Media3 cannot display
    // malformed clock formats or WEBVTT if told the MIME is SubRip.
    private val supportedSrtTiming = Regex(
        """(?m)^\s*(?:\d{2}:)?\d{2}:\d{2},\d{3}\s*-->\s*(?:\d{2}:)?\d{2}:\d{2},\d{3}"""
    )
    private val supportedVttTiming = Regex(
        """(?m)^\s*(?:\d{2}:)?\d{2}:\d{2}\.\d{3}\s*-->\s*(?:\d{2}:)?\d{2}:\d{2}\.\d{3}"""
    )

    private fun subtitleCheckKey(sub: SubtitleFile): String =
        sub.url + "|" + (sub.headers?.hashCode() ?: 0) +
            "|" + subtitleLanguage(sub.lang, sub.langTag)

    private suspend fun isReadableSubtitle(sub: SubtitleFile): Boolean {
        // A cache hit avoids a network request, text decoding and an I/O burst.
        // Different Referer/headers and different claimed languages are NOT
        // interchangeable. Expired/failed checks are retried quickly.
        val cacheKey = subtitleCheckKey(sub)
        val now = System.currentTimeMillis()
        subtitleCheckCache[cacheKey]?.let { entry ->
            val lifetime = if (entry.readable) {
                successfulSubtitleCheckLifetimeMs
            } else failedSubtitleCheckLifetimeMs
            if (now - entry.checkedAt in 0 until lifetime) return entry.readable
            subtitleCheckCache.remove(cacheKey, entry)
        }
        val result = inspectSubtitleContent(sub)
        if (subtitleCheckCache.size >= 256) {
            // Clearing the ENTIRE cache at 256 caused an avoidable storm of
            // subtitle re-downloads on later episodes, plus erased known
            // VTT/SRT formats. Evict only expired / oldest checks.
            val nowMs = System.currentTimeMillis()
            subtitleCheckCache.entries.forEach { entry ->
                val ttl = if (entry.value.readable)
                    successfulSubtitleCheckLifetimeMs
                else failedSubtitleCheckLifetimeMs
                if (nowMs - entry.value.checkedAt !in 0 until ttl) {
                    if (subtitleCheckCache.remove(entry.key, entry.value)) {
                        verifiedSubtitleFormats.remove(entry.key)
                    }
                }
            }
            if (subtitleCheckCache.size >= 256) {
                subtitleCheckCache.entries.minByOrNull { it.value.checkedAt }
                    ?.let { entry ->
                        if (subtitleCheckCache.remove(entry.key, entry.value)) {
                            verifiedSubtitleFormats.remove(entry.key)
                        }
                    }
            }
        }
        subtitleCheckCache[cacheKey] = CachedSubtitleCheck(
            System.currentTimeMillis(), result
        )
        return result
    }

    // Classify failures by content shape, without recording/downloaded text,
    // URLs, headers or credentials in device diagnostics.
    private fun subtitleNonCueCategory(body: String): String {
        val head = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .take(512).lowercase(java.util.Locale.ROOT)
        if (head.startsWith("{") || head.startsWith("[{")) {
            // Only report predefined error categories. Never log the raw JSON:
            // SubDL errors can contain URLs, request data or API credentials.
            val json = runCatching { org.json.JSONObject(body) }.getOrNull()
            val value = listOfNotNull(
                json?.opt("error")?.toString(),
                json?.opt("code")?.toString(),
                json?.opt("message")?.toString(),
                json?.opt("detail")?.toString()
            ).joinToString(" ").lowercase(java.util.Locale.ROOT)
                .ifBlank { head }
            return when {
                listOf("daily_limit", "download_limit", "quota", "limit exceeded",
                    "limit reached", "daily download", "exceeded daily").any { it in value } ->
                    "SubDL JSON: daily download quota"
                listOf("rate_limit", "too many requests", "slow down",
                    "rate exceeded", "throttled").any { it in value } ->
                    "SubDL JSON: temporary rate limit"
                listOf("not found", "file_missing", "does not exist",
                    "invalid file", "resource_not_found").any { it in value } ->
                    "SubDL JSON: file not found"
                listOf("key", "unauthoriz", "forbidden", "authentication",
                    "access denied", "permission", "paid").any { it in value } ->
                    "SubDL JSON: authentication/permission required"
                listOf("service_busy", "temporarily unavailable",
                    "internal server", "server error").any { it in value } ->
                    "SubDL JSON: server unavailable"
                json?.has("url") == true || json?.has("download_url") == true ->
                    "SubDL JSON: download link envelope"
                else -> "SubDL JSON: unexpected download payload"
            }
        }
        return when {
            head.startsWith("<") ->
                "markup response, not SRT/VTT"
            head.contains("too many requests") || head.contains("rate limit") ||
                head.contains("quota") || head.contains("limit exceeded") ->
                "rate/quota response text"
            head.contains("access denied") || head.contains("forbidden") ||
                head.contains("unauthorized") || head.contains("blocked") ->
                "access denied response text"
            head.contains("not found") || head.contains("file missing") ->
                "missing file response text"
            head.startsWith("[script info]") || head.contains("dialogue:") ->
                "ASS/SSA subtitle, not URL-compatible"
            body.contains("-->") ->
                "unrecognized timestamp syntax"
            body.length < 256 ->
                "short response without subtitle timestamps"
            else -> "text without timestamps in first 32KiB"
        }
    }

    private suspend fun inspectSubtitleContent(sub: SubtitleFile): Boolean {
        val url = sub.url
        val source = MOOSASubtitleHealth.sourceOf(sub.lang)
        if (!validStreamingUrl(url)) {
            MOOSASubtitleHealth.reason(source, "invalid URL")
            return false
        }
        if (Regex("""(?i)\.(?:zip|rar|7z|gz)(?:[?#]|$)""").containsMatchIn(url)) {
            MOOSASubtitleHealth.reason(source, "compressed/unsupported")
            return false
        }
        if (source == "SubDL" &&
            System.currentTimeMillis() < subDlDownloadBlockedUntilMs.get()
        ) {
            MOOSASubtitleHealth.reason(source, "download skipped: limit cooldown")
            return false
        }
        // SubDL's documented unpacked-file endpoint returns a whole SRT/VTT.
        // Bazarr also performs a regular GET; the old Range request could
        // produce JSON error payloads rather than actual subtitle bytes.
        // Keep the small ranged probe for other providers to avoid extra I/O.
        val probeHeaders = if (source == "SubDL") {
            (sub.headers ?: emptyMap()) +
                mapOf("Accept" to "text/plain,text/vtt,*/*")
        } else {
            (sub.headers ?: emptyMap()) + mapOf(
                "Accept" to "text/plain,text/vtt,*/*",
                "Range" to "bytes=0-32767"
            )
        }
        val body = timed(4200L) {
            app.get(url, headers = probeHeaders).text.take(32_768)
        } ?: run {
            MOOSASubtitleHealth.reason(source, "download/timeout (possibly 403)")
            return false
        }

        // One broken glyph should not disqualify an otherwise readable file;
        // reject heavily damaged encodings, HTML error pages and binary data.
        if (body.isBlank() || body.count { it == '\uFFFD' } > 4 ||
            body.contains("\u0000") ||
            body.contains("<html", ignoreCase = true) ||
            body.contains("<!DOCTYPE", ignoreCase = true)
        ) {
            MOOSASubtitleHealth.reason(source, "wrong encoding/HTML/binary")
            return false
        }

        if (!subtitleTiming.containsMatchIn(body)) {
            val cause = subtitleNonCueCategory(body)
            if (source == "SubDL" &&
                (cause == "SubDL JSON: daily download quota" ||
                    cause == "SubDL JSON: temporary rate limit")
            ) {
                // A rate throttle may clear quickly. Do not hammer daily
                // download quota errors for the next hour.
                val pauseMs = if (cause == "SubDL JSON: daily download quota")
                    60 * 60 * 1000L else 2 * 60 * 1000L
                val until = System.currentTimeMillis() + pauseMs
                subDlDownloadBlockedUntilMs.updateAndGet { current ->
                    maxOf(current, until)
                }
                subDlDownloadLimitReason = if (pauseMs > 120_000L)
                    "daily download quota" else "temporary rate limit"
            }
            MOOSASubtitleHealth.reason(source, cause)
            return false
        }
        val hasVttHeader = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .startsWith("WEBVTT", ignoreCase = true)
        val fileFormat = if (hasVttHeader) "vtt" else "srt"
        val playableTiming = if (hasVttHeader) supportedVttTiming
            else supportedSrtTiming
        if (!playableTiming.containsMatchIn(body)) {
            MOOSASubtitleHealth.reason(source, "unsupported cue timing")
            return false
        }
        val content = body.substringAfter("-->", body)
        val arabicCount = content.count { it in '\u0600'..'\u06FF' }
        val latinCount = content.count { it in 'A'..'Z' || it in 'a'..'z' }
        val readable = when (subtitleLanguage(sub.lang, sub.langTag)) {
            PreferredSubtitleLanguage.ARABIC -> arabicCount >= 8
            PreferredSubtitleLanguage.ENGLISH -> latinCount >= 12
            PreferredSubtitleLanguage.OTHER -> false
        }
        if (readable) {
            verifiedSubtitleFormats[subtitleCheckKey(sub)] = fileFormat
        } else {
            MOOSASubtitleHealth.reason(source, "empty/mismatched language")
        }
        return readable
    }

    private suspend fun filterReadableSubtitles(
        subtitles: List<SubtitleFile>,
        onVerified: (SubtitleFile) -> Unit,
        gate: kotlinx.coroutines.sync.Semaphore,
        sourceGates: ConcurrentHashMap<String, kotlinx.coroutines.sync.Semaphore>,
        externalSlots: kotlinx.coroutines.sync.Semaphore? = null
    ): List<SubtitleFile> = coroutineScope {
        // A short first pass delivers good tracks faster and with fewer
        // requests. Only if it underfills a language do we inspect further
        // variants from the same index. This prevents the first few broken
        // links from hiding a working Arabic release later in the results.
        // The shared four-request semaphore still covers ALL providers.
        val byLanguage = subtitles
            .distinctBy { it.url }
            .sortedByDescending(::subtitleScore)
            .groupBy { subtitleLanguage(it.lang, it.langTag) }
        val arabic = byLanguage[PreferredSubtitleLanguage.ARABIC].orEmpty().take(14)
        val english = byLanguage[PreferredSubtitleLanguage.ENGLISH].orEmpty().take(7)

        suspend fun checkBatch(candidates: List<SubtitleFile>): List<SubtitleFile> {
            candidates.groupBy { MOOSASubtitleHealth.sourceOf(it.lang) }
                .forEach { (source, entries) ->
                    MOOSASubtitleHealth.queued(source, entries.size)
                }
            return candidates.map { subtitle ->
                async {
                    val source = MOOSASubtitleHealth.sourceOf(subtitle.lang)
                    // Previously OpenSubtitles could occupy all four global
                    // slots, leaving SubDL's 37 indexed files with ZERO
                    // attempts before the player search deadline.
                    // Reserve fairness without increasing total concurrency.
                    val sourceGate = sourceGates.computeIfAbsent(source) {
                        // Avoid parallel download bursts on SubDL's quota.
                        kotlinx.coroutines.sync.Semaphore(if (source == "SubDL") 1 else 2)
                    }
                    sourceGate.withPermit {
                        suspend fun inspectOne(): SubtitleFile? = gate.withPermit {
                            MOOSASubtitleHealth.started(source)
                            if (isReadableSubtitle(subtitle)) {
                                onVerified(subtitle)
                                subtitle
                            } else {
                                MOOSASubtitleHealth.rejected(source)
                                null
                            }
                        }
                        // Three external slots + one reserved native slot;
                        // still four max I/O probes, no added video delay.
                        if (externalSlots != null) externalSlots.withPermit {
                            inspectOne()
                        } else inspectOne()
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val isSubDlOnly = subtitles.isNotEmpty() &&
            subtitles.all { MOOSASubtitleHealth.sourceOf(it.lang) == "SubDL" }
        // On a free plan, probing 10-20 files per title rapidly burns
        // the daily download quota. Start with at most three SubDL files.
        val initial = if (isSubDlOnly)
            checkBatch(arabic.take(2) + english.take(1))
        else checkBatch(arabic.take(5) + english.take(3))
        val arabicCount = initial.count {
            subtitleLanguage(it.lang, it.langTag) == PreferredSubtitleLanguage.ARABIC
        }
        val englishCount = initial.count {
            subtitleLanguage(it.lang, it.langTag) == PreferredSubtitleLanguage.ENGLISH
        }
        // Do not probe additional files when each language is already
        // represented. A sparse or broken first pass earns a limited retry.
        val retry = if (isSubDlOnly) {
            if (System.currentTimeMillis() < subDlDownloadBlockedUntilMs.get())
                emptyList()
            else (if (arabicCount < 1) arabic.drop(2).take(1) else emptyList()) +
                (if (englishCount < 1) english.drop(1).take(1) else emptyList())
        } else {
            (if (arabicCount < 2) arabic.drop(5).take(9) else emptyList()) +
                (if (englishCount < 1) english.drop(3).take(4) else emptyList())
        }
        val additional = if (retry.isNotEmpty()) checkBatch(retry) else emptyList()
        (initial + additional).sortedByDescending(::subtitleScore)
    }

    private fun normalizeNativeSubtitle(
        subtitle: SubtitleFile,
        source: String
    ): SubtitleFile? {
        val language = when (subtitleLanguage(subtitle.lang, subtitle.langTag)) {
            PreferredSubtitleLanguage.ARABIC -> "Arabic"
            PreferredSubtitleLanguage.ENGLISH -> "English"
            PreferredSubtitleLanguage.OTHER -> return null
        }
        // Preserve which installed repository provided the track, without
        // leaking free-form filenames that can confuse player language matching.
        val safeSource = source.replace(" • ", " ")
            .replace(Regex("""[\r\n\t]"""), " ").trim().take(28)
        val release = subtitleReleaseHint(subtitle.lang)
        val fps = Regex("""(\d{2}(?:\.\d{1,2})?)fps""", RegexOption.IGNORE_CASE)
            .find(subtitle.lang)?.value
        val label = (listOf(language, "Repo: $safeSource") +
            listOfNotNull(release.takeIf(String::isNotBlank), fps))
            .joinToString(" • ")
        return subtitle.copy(lang = label)
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
            api is MOOSAVixSrcProvider -> 17_000L
            api is MOOSANetMirrorProvider -> 15_000L
            api is MOOSA4KHDHubProvider -> 26_000L
            api is MOOSACineStreamVidzeeProvider -> 16500L

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
        MOOSASubtitleHealth.begin(request.title)
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
            if (request.isAnime) {
                MOOSAProgress.failureReport(
                    MOOSAPlaybackDiagnostics.report() +
                        "\n\nThere are no eligible installed playback sources. " +
                        "Anime extensions may be installed but not registered " +
                        "with CloudStream's active provider API. The search uses " +
                        "registered providers only. Check the actual loaded names " +
                        "in Source Tester and disabled sources in MOOSA settings.",
                    "MOOSA Anime • No sources"
                )
            } else if (request.originalLanguage.equals("ar", ignoreCase = true)) {
                MOOSAProgress.failureReport(MOOSAPlaybackDiagnostics.report())
            }
            return@coroutineScope false
        }

        val progressKey = buildString {
            append(request.imdbId ?: request.tmdbId?.toString() ?: request.title)
            append(if (request.isTv) ":series" else ":movie")
            if (request.isTv) {
                append(":")
                append(request.season ?: -1)
                append(":")
                append(request.episode ?: -1)
            }
        }
        val progressToken = MOOSAProgress.start(providers.size, progressKey)

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
                    .distinctBy { title ->
                        if (request.isAnime) normalizeTitle(title)
                        else canonicalTitle(title)
                    }
            }
        }

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubtitles = ConcurrentHashMap.newKeySet<String>()
        // Installed-repository subtitles were previously collected here and
        // not inspected until all video providers finished. On a fast player
        // search they could be cancelled without a single validation attempt.
        val nativeProbeJobs = Collections.synchronizedList(
            mutableListOf<kotlinx.coroutines.Job>()
        )
        val nativeSeenCandidates = ConcurrentHashMap.newKeySet<String>()
        val nativeQueued = AtomicInteger(0)
        val nativePerSource = ConcurrentHashMap<String, AtomicInteger>()
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
            // CloudStream's source/subtitle dialog groups by the exact
            // SubtitleData.originalName, which comes directly from this lang.
            // Share one label per language + origin so each source gets
            // ONE row. Each distinct validated URL remains an independently
            // selectable item in that row, even without A/B/C numbering.
            synchronized(callbackLock) {
                if (!seenSubtitles.add(subtitle.url)) return
                val prefix = if (language == PreferredSubtitleLanguage.ARABIC)
                    "Arabic" else "English"
                val sourceDetails = subtitle.lang.substringAfter(" • ", "")
                val origin = when {
                    sourceDetails.startsWith("OpenSubtitles", ignoreCase = true) ->
                        "OpenSubtitles"
                    sourceDetails.startsWith("SubDL", ignoreCase = true) ->
                        "SubDL"
                    sourceDetails.startsWith("Wyzie", ignoreCase = true) ->
                        "Wyzie"
                    sourceDetails.startsWith("Repo: ", ignoreCase = true) ->
                        sourceDetails.substringAfter("Repo: ")
                            .substringBefore(" • ").trim().take(28)
                            .ifBlank { "Other Repositories" }
                    else -> "Other Repositories"
                }
                val label = "$prefix • $origin"
                val format = verifiedSubtitleFormats[subtitleCheckKey(subtitle)]
                // The official CloudStream player infers MIME exclusively
                // using subtitleFile.url.endsWith("vtt"). HTTP URL fragments
                // are not transmitted to the server, so this safely advertises
                // validated extensionless VTT without another network call.
                val playbackUrl = if (
                    format == "vtt" &&
                    !subtitle.url.substringBefore('#').endsWith(".vtt", true)
                ) subtitle.url.substringBefore('#') + "#moosa.vtt"
                else subtitle.url
                val track = subtitle.copy(lang = label, url = playbackUrl)
                try {
                    subtitleCallback(track)
                    MOOSASubtitleHealth.accepted(
                        MOOSASubtitleHealth.sourceOf(subtitle.lang)
                    )
                } catch (error: Throwable) {
                    logError(error)
                }
            }
        }

        // A single shared gate caps simultaneous file reads across the
        // primary index, fallback catalogs and native provider subtitles.
        val subtitleProbeGate = kotlinx.coroutines.sync.Semaphore(4)
        val subtitleExternalSlots = kotlinx.coroutines.sync.Semaphore(3)
        val subtitleSourceGates =
            ConcurrentHashMap<String, kotlinx.coroutines.sync.Semaphore>()

        fun collectInstalledRepositorySubtitle(
            track: SubtitleFile,
            sourceName: String
        ) {
            val candidate = normalizeNativeSubtitle(track, sourceName) ?: return
            if (!validStreamingUrl(candidate.url)) return
            // Do not blindly request subtitles from every installed provider.
            // These callbacks belong to already-matched video targets, so
            // there is no additional provider search or title mismatch.
            val unique = candidate.url + "|" +
                (candidate.headers?.hashCode() ?: 0) + "|" +
                subtitleLanguage(candidate.lang, candidate.langTag)
            if (!nativeSeenCandidates.add(unique)) return
            val language = subtitleLanguage(candidate.lang, candidate.langTag)
            val group = nativePerSource.computeIfAbsent("$sourceName|$language") {
                AtomicInteger(0)
            }
            val limit = if (language == PreferredSubtitleLanguage.ARABIC) 3 else 2
            if (group.incrementAndGet() > limit) {
                group.decrementAndGet()
                return
            }
            if (nativeQueued.incrementAndGet() > 12) {
                nativeQueued.decrementAndGet()
                group.decrementAndGet()
                return
            }
            MOOSASubtitleHealth.update("Native", "Matched installed repositories")
            MOOSASubtitleHealth.candidates("Native", 1)
            // Start checking each available installed-provider subtitle while
            // link discovery continues. Reuse the existing four-I/O gate.
            val job = launch {
                filterReadableSubtitles(
                    listOf(candidate), ::emitVerifiedSubtitle,
                    subtitleProbeGate, subtitleSourceGates
                )
            }
            nativeProbeJobs.add(job)
        }

        // Start validating primary tracks without pausing fallback retrieval.
        // Separate coroutines can emit tracks immediately on success, while a
        // shared semaphore limits storage/network pressure on Android TV.
        val externalSubtitleJob = async {
            timed(20_000L) {
                coroutineScope {
                    fetchExternalSubtitles(request) { candidates ->
                        launch {
                            filterReadableSubtitles(
                                candidates, ::emitVerifiedSubtitle,
                                subtitleProbeGate, subtitleSourceGates,
                                subtitleExternalSlots
                            )
                        }
                    }
                }
            }
        }

        val aliases = aliasesJob.await()

        // A fixed chunk queue made source 11 wait for the *slowest* source
        // in the first ten, even after other slots had already finished.
        // Use a fair, bounded semaphore: each completed provider immediately
        // releases a slot for the next provider. Arabic fast mode retains its
        // original three-provider first-batch behaviour.
        val providerBatchSize = if (fastArabic) 3
            else MOOSASettingsStore.providerConcurrency()
        val providerGate = kotlinx.coroutines.sync.Semaphore(providerBatchSize)
        // A single global budget prevents 10 concurrent providers from
        // opening 10 probes each (100 network connections on Fire TV).
        val linkProbeGate = kotlinx.coroutines.sync.Semaphore(linkProbeBatchSize)
        val scheduledKeys = ConcurrentHashMap.newKeySet<String>()
        val providerGroups = if (fastArabic) providers.chunked(providerBatchSize)
            else listOf(providers)

        for (batch in providerGroups) {
            batch.amap { api ->
                providerGate.withPermit {
                val providerWorked = AtomicBoolean(false)
                val candidateLinks =
                    Collections.synchronizedList(mutableListOf<ExtractorLink>())
                val extracted = AtomicInteger(0)
                val invalidUrls = AtomicInteger(0)
                val disabledUrls = AtomicInteger(0)
                val downloadNamesSkipped = AtomicInteger(0)
                val lowQualityUrls = AtomicInteger(0)
                val unsafeAnimeSourcesSkipped = AtomicInteger(0)
                val unknownQualityUrls = AtomicInteger(0)
                val unknownHlsCandidates = AtomicInteger(0)
                val failedProbes = AtomicInteger(0)
                val acceptedLinks = AtomicInteger(0)
                val failedReasons = ConcurrentHashMap<String, AtomicInteger>()
                val emittedAkwamMirrors =
                    ConcurrentHashMap.newKeySet<String>()
                val acceptingCandidates = AtomicBoolean(true)
                val candidateProbeJobs = Collections.synchronizedList(
                    mutableListOf<kotlinx.coroutines.Job>()
                )

                // A provider may emit its first media URL immediately, then
                // spend tens of seconds resolving its additional mirrors.
                // Validate every callback as it arrives, instead of holding
                // *all* candidates until api.loadLinks has returned.
                fun scheduleProbe(link: ExtractorLink) {
                    val key = linkKey(link)
                    if (seenLinks.contains(key) || !scheduledKeys.add(key)) return
                    val job = launch {
                        linkProbeGate.withPermit {
                            try {
                                // Unknown-quality HLS master playlists are
                                // admissible ONLY after reading their real
                                // variant heights; never bypass this check
                                // even if the user disabled routine probing.
                                val mustInspectHlsQuality =
                                    link.isM3u8 &&
                                        !MOOSAQualityPolicy.acceptsHeight(
                                            MOOSAQualityPolicy.height(link)
                                        )
                                val verified =
                                    (!mustInspectHlsQuality &&
                                        !MOOSASettingsStore.verifyLinks()) ||
                                        verifyStreamingLink(link)
                                val meetsHdFloor =
                                    MOOSAQualityPolicy.accepts(link)
                                val mirrorIdentity =
                                    if (api.name.equals("Akwam", true)) {
                                        val host = runCatching {
                                            java.net.URI(link.url).host
                                                ?.lowercase().orEmpty()
                                        }.getOrDefault("")
                                        if (host == "downet.net" ||
                                            host.endsWith(".downet.net")
                                        ) {
                                            "${link.url.substringBefore("://").lowercase()}|$host|${inferQuality(link)}"
                                        } else null
                                    } else null

                                val candidateCanEmit = verified && meetsHdFloor &&
                                    seenLinks.add(key) &&
                                    (mirrorIdentity == null ||
                                        emittedAkwamMirrors.add(mirrorIdentity))
                                val uniqueEpisodeStream = !candidateCanEmit ||
                                    MOOSAEpisodeLinkReuseGuard.accepts(
                                        api.name, identityClaim(request), key
                                    )
                                if (candidateCanEmit && uniqueEpisodeStream) {
                                    providerWorked.set(true)
                                    acceptedLinks.incrementAndGet()
                                    emittedLink.set(true)
                                    shownLinks.incrementAndGet()
                                    // The player can show this server without
                                    // waiting for the other provider callbacks.
                                    synchronized(callbackLock) {
                                        MOOSAProgress.linkAvailable(progressToken, progressKey)
                                        callback(link)
                                    }
                                } else if (candidateCanEmit && !uniqueEpisodeStream) {
                                    // A provider served the exact same URL,
                                    // referer and headers to a different
                                    // movie/episode during this app session.
                                    // Do not suppress its other unique servers.
                                    MOOSAPlaybackDiagnostics.update(
                                        api, "Duplicate media across different titles/episodes • skipped"
                                    )
                                } else if (verified && !meetsHdFloor) {
                                    // HLS media playlists with no verified
                                    // resolution stay excluded rather than
                                    // being misleadingly labeled HD or 4K.
                                    unknownQualityUrls.incrementAndGet()
                                } else if (!verified) {
                                    failedProbes.incrementAndGet()
                                    val reason =
                                        MOOSAServerRegistry.lastProbeReason(link)
                                            ?: "Unverified link"
                                    failedReasons
                                        .computeIfAbsent(reason) { AtomicInteger(0) }
                                        .incrementAndGet()
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                logError(error)
                            }
                        }
                    }
                    candidateProbeJobs.add(job)
                }

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
                            // An anime meta-provider can correctly identify
                            // the series in its catalog but then resolve the
                            // FIRST unrelated search result in an embedded
                            // extractor. Reject only proven-unsafe extractor
                            // families within CineStream. Other anime
                            // providers/servers are still independently tried.
                            val unsafeAnimeEngine = MOOSAAnimeExtractorSafety.unsafeEngine(
                                request.isAnime, api.name, api.javaClass.name,
                                link.source, link.name
                            )
                            if (unsafeAnimeEngine != null) {
                                unsafeAnimeSourcesSkipped.incrementAndGet()
                                MOOSAPlaybackDiagnostics.update(
                                    api, "Unsafe anime title lookup ($unsafeAnimeEngine) • skipped"
                                )
                                return
                            }
                            if (MOOSARecognizedExternalProvider.isTorrentCandidate(
                                    link.source, link.name, link.url
                                )
                            ) {
                                invalidUrls.incrementAndGet()
                                MOOSAPlaybackDiagnostics.update(
                                    api, "Torrent/P2P server excluded"
                                )
                                return
                            }
                            if (!validStreamingUrl(link.url)) {
                                invalidUrls.incrementAndGet()
                                return
                            }
                            // An extractor may return a playable but DIFFERENT
                            // episode. Only block explicit conflicting S/E or
                            // year in a readable direct video filename; ignore
                            // opaque CDNs/HLS links instead of guessing.
                            if (MOOSAMediaIdentityPolicy.linkContradicts(
                                    identityClaim(request), link.url,
                                    link.source, link.name
                                )
                            ) {
                                invalidUrls.incrementAndGet()
                                MOOSAPlaybackDiagnostics.update(
                                    api, "Contradictory video/episode identity • skipped"
                                )
                                return
                            }
                            // Source/server display names, never the URL path:
                            // Akwam's valid MP4 can be hosted via /download/.
                            if (MOOSAQualityPolicy.hasForbiddenLabel(link)) {
                                downloadNamesSkipped.incrementAndGet()
                                return
                            }

                            // Never invent HD for unknown MP4 links.
                            // HLS is special: a master playlist can state
                            // genuine 720p/1080p/2160p renditions even if the
                            // addon says "Unknown". Inspect at most THREE
                            // such HLS links per source to avoid slow scans.
                            val height = MOOSAQualityPolicy.height(link)
                            val inspectUnknownHls =
                                link.isM3u8 && (height == null || height <= 0)
                            if (height == null || height <= 0) {
                                if (!inspectUnknownHls ||
                                    unknownHlsCandidates.incrementAndGet() > 3
                                ) {
                                    unknownQualityUrls.incrementAndGet()
                                    return
                                }
                            } else if (!MOOSAQualityPolicy.acceptsHeight(height)) {
                                lowQualityUrls.incrementAndGet()
                                return
                            }
                            if (link.quality <= 0 && height != null && height > 0) {
                                link.quality = height
                            }

                            // Only eligible playback names and 720p+ streams
                            // reach network probing or the visible source list.
                            MOOSAServerRegistry.record(link)
                            if (MOOSAServerRegistry.isDisabled(link)) {
                                disabledUrls.incrementAndGet()
                                return
                            }
                            if (!acceptingCandidates.get()) return
                            candidateLinks += link
                            scheduleProbe(link)
                        }

                        timed(linksTimeoutFor(api, request)) {
                            api.loadLinks(
                                target.data,
                                isCasting,
                                { subtitle ->
                                    collectInstalledRepositorySubtitle(
                                        subtitle, api.name
                                    )
                                },
                                { link ->
                                    collectLink(link)
                                }
                            )
                        }

                        if (
                            candidateLinks.isEmpty() &&
                            !isStreamPlayProvider(api) &&
                            api !is MOOSAVixSrcProvider &&
                            api !is MOOSANetMirrorProvider &&
                            api !is MOOSAOpenDirectoryProvider &&
                            api !is MOOSA4KHDHubProvider &&
                            api !is MOOSACineStreamVaPlayerProvider &&
                            api !is MOOSACineStreamVidrockProvider &&
                            api !is MOOSACineStreamVidzeeProvider &&
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
                                            collectInstalledRepositorySubtitle(
                                                subtitle, api.name
                                            )
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
                                "${lowQualityUrls.get()} SD skipped, ${unknownQualityUrls.get()} unknown skipped, " +
                                "${unsafeAnimeSourcesSkipped.get()} unsafe anime extractors skipped"
                        )

                        // api.loadLinks has now completed or timed out.
                        // Keep all already-scheduled verification coroutines
                        // alive until they report their results. Each was
                        // emitted directly to CloudStream on completion.
                        acceptingCandidates.set(false)
                        candidateProbeJobs.toList().forEach { it.join() }
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

                    if (providerWorked.get()) {
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
                        shownLinks.get(),
                        progressToken
                    )
                }

                providerWorked.get()
                } // providerGate: released immediately for each finished provider
            }

            // Native CloudStream only finishes its loading UI when
            // loadLinks() returns. Stop after one confirmed fast-lane
            // source rather than keeping it searching for dead mirrors.
            if (fastArabic && shownLinks.get() > 0) {
                break
            }
        }

        MOOSAProgress.serversReady(shownLinks.get(), progressToken)
        MOOSAProgress.subtitles(progressToken)

        if (emittedLink.get()) {
            // Native verification already started during provider loadLinks.
            // Let native checks finish first within the SAME existing 3.2s
            // grace period; don't increase server-search or player latency.
            withTimeoutOrNull(3200L) {
                nativeProbeJobs.toList().forEach { it.join() }
                externalSubtitleJob.await()
            }
            externalSubtitleJob.cancel()
            nativeProbeJobs.toList().forEach { it.cancel() }
        } else {
            externalSubtitleJob.await()
            nativeProbeJobs.toList().forEach { it.join() }
        }

        MOOSAProgress.finish(shownLinks.get(), progressToken)

        if (shownLinks.get() == 0) {
            if (request.isAnime) {
                // Explain the 0/N denominator with actual provider names and
                // separate missing episode matches from rejected/unknown-quality
                // links. Never silently accept unknown resolution below 720p.
                MOOSAProgress.failureReport(
                    MOOSAPlaybackDiagnostics.report() +
                        "\n\nThis counter counts installed playback providers, " +
                        "not repositories or individual server URLs. " +
                        "MOOSA now reads native Subbed/Dubbed anime episode lists " +
                        "from loaded anime extensions; only providers actually " +
                        "registered in CloudStream can be queried. " +
                        "Unknown-quality or below-720p links stay excluded. " +
                        "Check Source Tester for loaded names and disabled sources.",
                    "MOOSA Anime • No verified servers"
                )
            } else if (request.originalLanguage.equals("ar", ignoreCase = true)) {
                MOOSAProgress.failureReport(MOOSAPlaybackDiagnostics.report())
            }
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
