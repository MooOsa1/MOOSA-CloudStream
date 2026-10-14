package com.moosa.arabic

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal object MOOSAArabicBridge {
    private enum class Kind {
        AKWAM,
        TOP_CINEMA,
        FASEL_HD,
        WECIMA,
        CIMA_CLUB,
        ARAB_SEED
    }

    fun supports(api: MainAPI): Boolean =
        kindOf(api) != null

    private fun kindOf(api: MainAPI): Kind? {
        val label =
            "${api.name} ${api.mainUrl} ${api.javaClass.name}"
                .lowercase()

        return when {
            "akwam" in label ||
                "اكوام" in label -> Kind.AKWAM

            "topcinema" in label ||
                "top cinema" in label -> Kind.TOP_CINEMA

            "fasel" in label ||
                "فاصل" in label -> Kind.FASEL_HD

            "wecima" in label ||
                "we cima" in label -> Kind.WECIMA

            "cimaclub" in label ||
                "cima club" in label -> Kind.CIMA_CLUB

            "arabseed" in label ||
                "arab seed" in label ||
                "عرب سيد" in label -> Kind.ARAB_SEED

            else -> null
        }
    }

    private fun normalize(value: String): String =
        buildString {
            value.lowercase().forEach { ch ->
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
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun cleanIdentity(value: String): String =
        normalize(value)
            .replace(Regex("""(?:الموسم|موسم|season|الجزء|جزء|part|ج|s)\s*(?:\d+|الاولى?|الثانيه?|الثالثه?|الرابعه?|الخامسه?|السادسه?|السابعه?|الثامنه?|التاسعه?|العاشره?)"""), " ")
            .replace(Regex("""(?:الحلقه|حلقه)\s*\d+(?:\s+(?:الاولى|الاول|الثانيه|الثاني|الثالثه|الثالث|الرابعه|الرابع|الخامسه|الخامس))?"""), " ")
            .replace(
                Regex("""(^|\s)(?:فيلم|مسلسل|مسلسلات|مشاهده|تحميل|مترجم|مدبلج|كامل|اونلاين|اون لاين)(?=\s|$)"""),
                " "
            )
            .replace(
                Regex("""\b(?:movie|series|show|watch|download|subbed|dubbed|complete|online)\b"""),
                " "
            )
            .replace(
                Regex("""\b(?:2160p|1440p|1080p|720p|480p|4k|uhd|fhd|hdr|hevc|x264|x265|web ?dl|webrip|bluray)\b"""),
                " "
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun words(value: String): Set<String> =
        cleanIdentity(value)
            .split(" ")
            .filter {
                it.length >= 2 &&
                    !it.matches(Regex("""\d+"""))
            }
            .toSet()

    private fun scoreTitle(
        candidate: String,
        aliases: List<String>
    ): Int {
        val c = cleanIdentity(candidate)
        if (c.isBlank()) return 0

        var best = 0

        aliases.forEach { alias ->
            val a = cleanIdentity(alias)
            if (a.isBlank()) return@forEach

            fun identityNumbers(value: String): Set<String> =
                Regex("""\b\d+\b""")
                    .findAll(value)
                    .map { it.value }
                    .filterNot {
                        val number = it.toIntOrNull()
                        number != null && number in 1900..2099
                    }
                    .toSet()

            if (identityNumbers(c) != identityNumbers(a)) {
                return@forEach
            }

            if (c == a) {
                best = maxOf(best, 100)
                return@forEach
            }

            val cw = words(c)
            val aw = words(a)
            if (cw.isEmpty() || aw.isEmpty()) {
                return@forEach
            }

            val common = cw.intersect(aw).size
            val minSize = minOf(cw.size, aw.size)
            val maxSize = maxOf(cw.size, aw.size)

            val hasSeasonMarker = Regex(
                """(?:^|\s)(?:الموسم|موسم|season|الجزء|جزء|part|ج|s)\s*(?:\d+|الاولى?|الثانيه?|الثالثه?|الرابعه?|الخامسه?|السادسه?|السابعه?|الثامنه?|التاسعه?|العاشره?)""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(normalize(candidate))

            if (
                common == minSize &&
                (maxSize == minSize || common >= 2 && maxSize <= minSize + 1 || hasSeasonMarker)
            ) {
                best = maxOf(best, 92)
            } else {
                val ratio =
                    common.toDouble() /
                        maxSize.coerceAtLeast(1)

                best =
                    maxOf(
                        best,
                        (ratio * 80.0).toInt()
                    )
            }
        }

        return best
    }

    private fun searchTimeout(kind: Kind): Long =
        when (kind) {
            Kind.FASEL_HD -> 18_000L
            Kind.AKWAM -> 18_000L
            Kind.TOP_CINEMA -> 12_000L
            Kind.WECIMA -> 12_000L
            Kind.CIMA_CLUB -> 12_000L
            Kind.ARAB_SEED -> 25_000L
        }

    private fun loadTimeout(kind: Kind): Long =
        when (kind) {
            Kind.FASEL_HD -> 45_000L
            Kind.AKWAM -> 40_000L
            Kind.TOP_CINEMA -> 40_000L
            Kind.WECIMA -> 35_000L
            Kind.CIMA_CLUB -> 35_000L
            Kind.ARAB_SEED -> 50_000L
        }

    private fun queryList(
        request: MOOSAArabicProvider.HubPlaybackRequest,
        aliases: List<String>
    ): List<String> {
        val rawTitles =
            (listOf(request.title) + aliases)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy(::normalize)

        val arabicTitles =
            rawTitles.filter {
                Regex("""[\u0600-\u06FF]""")
                    .containsMatchIn(it)
            }

        val plainTitles =
            rawTitles.filterNot {
                Regex(
                    """(?:الموسم|موسم|season)\s*\d+""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(normalize(it))
            }

        val base =
            (arabicTitles + plainTitles)
                .distinctBy(::normalize)
                .take(8)

        val season = request.season
        if (!request.isTv || season == null) {
            val yearVariants =
                if (request.year == null) {
                    emptyList()
                } else {
                    base.map { "$it ${request.year}" }
                }

            return (base + yearVariants)
                .distinctBy(::normalize)
                .take(12)
        }

        val seasonVariants =
            base.flatMap { title ->
                listOf(
                    "$title $season",
                    "$title الموسم $season"
                )
            }

        // Search the real show name first, then requested season variants.
        // A long alias list previously crowded out the precise season query.
        return (base.take(2) + seasonVariants.take(4) + base.drop(2))
            .distinctBy(::normalize)
            .take(8)
    }

    private fun explicitSeason(value: String): Int? {
        val normalized = normalize(value)
        val token = Regex(
            """(?:^|\s)(?:الموسم|موسم|الجزء|جزء|ج|season|part)\s*(\d{1,2}|الاول|الاولى|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر)(?=\s|$)""",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.groupValues?.getOrNull(1) ?: return null

        return token.toIntOrNull() ?: mapOf(
            "الاول" to 1, "الاولى" to 1,
            "الثاني" to 2, "الثالث" to 3,
            "الرابع" to 4, "الخامس" to 5,
            "السادس" to 6, "السابع" to 7,
            "الثامن" to 8, "التاسع" to 9,
            "العاشر" to 10
        )[token]
    }

    private fun episodeData(
        loaded: TvSeriesLoadResponse,
        request: MOOSAArabicProvider.HubPlaybackRequest,
        candidateTitle: String,
        aliases: List<String>
    ): String? {
        val season = request.season ?: return null
        val episode = request.episode ?: return null

        val exact =
            loaded.episodes.firstOrNull {
                it.season == season &&
                    it.episode == episode
            }

        if (!exact?.data.isNullOrBlank()) {
            return exact?.data
        }

        val knownSeasons =
            loaded.episodes
                .mapNotNull { it.season }
                .filter { it > 0 }
                .distinct()

        // A season-specific result can expose one season of episodes
        // indexed as season 1. Never remap explicit seasons 2, 3, 4, etc.
        if (knownSeasons.isNotEmpty() && knownSeasons != listOf(1)) {
            return null
        }

        val seasonIsProven =
            season == 1 ||
                (
                    explicitSeason(candidateTitle) == season &&
                        scoreTitle(candidateTitle, aliases) >= 88
                )

        if (!seasonIsProven) return null

        return loaded.episodes
            .firstOrNull {
                it.episode == episode &&
                    (it.season == null || it.season == 1)
            }
            ?.data
            ?.takeIf { it.isNotBlank() }
    }

    private fun movieMatches(
        loaded: MovieLoadResponse,
        request: MOOSAArabicProvider.HubPlaybackRequest,
        aliases: List<String>
    ): Boolean {
        if (
            scoreTitle(
                loaded.name,
                aliases
            ) < 88
        ) {
            return false
        }

        val candidateYear = loaded.year
        val wantedYear = request.year

        return candidateYear == null ||
            wantedYear == null ||
            abs(candidateYear - wantedYear) <= 1
    }

    suspend fun resolve(
        api: MainAPI,
        request: MOOSAArabicProvider.HubPlaybackRequest,
        aliases: List<String>
    ): MOOSAArabicProvider.ProviderTarget? {
        val kind = kindOf(api) ?: return null
        val queries = queryList(request, aliases).take(6)
        val seenUrls = linkedSetOf<String>()

        MOOSAPlaybackDiagnostics.update(
            api,
            "Arabic bridge • searching ${queries.size} queries"
        )

        for (query in queries) {
            val results =
                withTimeoutOrNull(
                    searchTimeout(kind)
                ) {
                    api.search(query, 1)
                        ?.items
                        .orEmpty()
                }.orEmpty()

            MOOSAPlaybackDiagnostics.update(
                api,
                "Search \"$query\" • ${results.size} results"
            )

            val candidates =
                results
                    .filter {
                        seenUrls.add(it.url) &&
                            (
                                !request.isTv ||
                                    explicitSeason(it.name) == null ||
                                    explicitSeason(it.name) == request.season
                            )
                    }
                    .map {
                        it to scoreTitle(
                            it.name,
                            aliases
                        )
                    }
                    .filter {
                        (_, score) -> score >= 88
                    }
                    .sortedByDescending {
                        (_, score) -> score
                    }
                    .take(4)

            for ((candidate, _) in candidates) {
                MOOSAPlaybackDiagnostics.update(
                    api,
                    "Matched \"${candidate.name}\" • loading page"
                )

                val loaded =
                    withTimeoutOrNull(
                        loadTimeout(kind)
                    ) {
                        api.load(candidate.url)
                    }

                if (loaded == null) {
                    MOOSAPlaybackDiagnostics.update(
                        api,
                        "Page load timed out • ${candidate.name}"
                    )
                    continue
                }

                if (request.isTv) {
                    val series =
                        loaded as? TvSeriesLoadResponse

                    if (series == null) {
                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "Matched page loaded as movie, not series"
                        )
                        continue
                    }

                    if (
                        (
                            explicitSeason(series.name) != null &&
                                explicitSeason(series.name) != request.season
                        ) ||
                        scoreTitle(
                            series.name,
                            aliases
                        ) < 88
                    ) {
                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "Loaded wrong title • ${series.name}"
                        )
                        continue
                    }

                    // A season can have a different release year from the
                    // series premiere. Episode season/number are validated below.
                    val data =
                        episodeData(
                            loaded = series,
                            request = request,
                            candidateTitle =
                                candidate.name,
                            aliases = aliases
                        )

                    if (data.isNullOrBlank()) {
                        MOOSAPlaybackDiagnostics.update(
                            api,
                            "Series found • S${request.season}E${request.episode} missing"
                        )
                        continue
                    }

                    MOOSAPlaybackDiagnostics.update(
                        api,
                        "Episode found • extracting links"
                    )

                    return MOOSAArabicProvider.ProviderTarget(
                        providerName = api.name,
                        mainUrl = api.mainUrl,
                        data = data
                    )
                }

                val movie =
                    loaded as? MovieLoadResponse

                if (movie == null) {
                    MOOSAPlaybackDiagnostics.update(
                        api,
                        "Matched page loaded as series, not movie"
                    )
                    continue
                }

                if (
                    !movieMatches(
                        movie,
                        request,
                        aliases
                    )
                ) {
                    MOOSAPlaybackDiagnostics.update(
                        api,
                        "Loaded wrong movie • ${movie.name}"
                    )
                    continue
                }

                val data =
                    movie.dataUrl
                        ?.takeIf {
                            it.isNotBlank()
                        }

                if (data.isNullOrBlank()) {
                    MOOSAPlaybackDiagnostics.update(
                        api,
                        "Movie matched • no playback data"
                    )
                    continue
                }

                MOOSAPlaybackDiagnostics.update(
                    api,
                    "Movie matched • extracting links"
                )

                return MOOSAArabicProvider.ProviderTarget(
                    providerName = api.name,
                    mainUrl = api.mainUrl,
                    data = data
                )
            }
        }

        MOOSAPlaybackDiagnostics.update(
            api,
            "No usable match after all queries"
        )

        return null
    }

}
