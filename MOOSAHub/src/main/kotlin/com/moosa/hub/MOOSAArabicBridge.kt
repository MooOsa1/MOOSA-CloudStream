package com.moosa.hub

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
        CIMA_CLUB
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
            .replace(
                Regex(
                    """(^|\s)(?:فيلم|مسلسل|مسلسلات|مشاهده|تحميل|مترجم|مدبلج|كامل|اونلاين|اون لاين)(?=\s|$)"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\b(?:movie|series|show|watch|download|subbed|dubbed|complete|online)\b"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\b(?:2160p|1440p|1080p|720p|480p|4k|uhd|fhd|hdr|hevc|x264|x265|web ?dl|webrip|bluray)\b"""
                ),
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

            if (common == minSize) {
                val numericA =
                    Regex("""\b\d+\b""")
                        .findAll(a)
                        .map { it.value }
                        .toSet()

                val numericC =
                    Regex("""\b\d+\b""")
                        .findAll(c)
                        .map { it.value }
                        .toSet()

                val numberPenalty =
                    if (
                        numericA.isNotEmpty() &&
                        numericC.isNotEmpty() &&
                        numericA.intersect(numericC).isEmpty()
                    ) {
                        25
                    } else {
                        0
                    }

                best =
                    maxOf(
                        best,
                        92 - numberPenalty
                    )
            } else {
                val ratio =
                    common.toDouble() /
                        minSize.coerceAtLeast(1)

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
            Kind.AKWAM -> 10_000L
            Kind.TOP_CINEMA -> 12_000L
            Kind.WECIMA -> 12_000L
            Kind.CIMA_CLUB -> 12_000L
        }

    private fun loadTimeout(kind: Kind): Long =
        when (kind) {
            Kind.FASEL_HD -> 45_000L
            Kind.AKWAM -> 40_000L
            Kind.TOP_CINEMA -> 40_000L
            Kind.WECIMA -> 35_000L
            Kind.CIMA_CLUB -> 35_000L
        }

    private fun queryList(
        request: MOOSAHubProvider.HubPlaybackRequest,
        aliases: List<String>
    ): List<String> {
        val season = request.season

        val arabic =
            aliases.filter {
                Regex("""[\u0600-\u06FF]""")
                    .containsMatchIn(it)
            }

        val base =
            (arabic + aliases + request.title)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy(::normalize)

        if (!request.isTv || season == null) {
            return base.take(8)
        }

        val preferred = mutableListOf<String>()

        base.take(8).forEach { title ->
            preferred += "$title $season"
            preferred += "$title الموسم $season"
            preferred += title
        }

        return preferred
            .distinctBy(::normalize)
            .take(14)
    }

    private fun episodeData(
        loaded: TvSeriesLoadResponse,
        request: MOOSAHubProvider.HubPlaybackRequest,
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

        if (knownSeasons.isNotEmpty()) {
            return null
        }

        val seasonIsProven =
            season == 1 ||
                Regex(
                    """(?:الموسم|season)\s*$season\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(
                    normalize(candidateTitle)
                ) ||
                aliases.any {
                    val n = normalize(it)
                    n.contains(
                        Regex("""\b$season\b""")
                    ) &&
                        scoreTitle(
                            candidateTitle,
                            listOf(it)
                        ) >= 90
                }

        if (!seasonIsProven) {
            return null
        }

        return loaded.episodes
            .firstOrNull {
                it.episode == episode
            }
            ?.data
            ?.takeIf { it.isNotBlank() }
    }

    private fun movieMatches(
        loaded: MovieLoadResponse,
        request: MOOSAHubProvider.HubPlaybackRequest,
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
        request: MOOSAHubProvider.HubPlaybackRequest,
        aliases: List<String>
    ): MOOSAHubProvider.ProviderTarget? {
        val kind = kindOf(api) ?: return null
        val queries = queryList(request, aliases)
        val seenUrls = linkedSetOf<String>()

        for (query in queries) {
            val results =
                withTimeoutOrNull(
                    searchTimeout(kind)
                ) {
                    api.search(query, 1)
                        ?.items
                        .orEmpty()
                }.orEmpty()

            val candidates =
                results
                    .filter {
                        seenUrls.add(it.url)
                    }
                    .map {
                        it to scoreTitle(
                            it.name,
                            aliases + query
                        )
                    }
                    .filter {
                        (_, score) -> score >= 72
                    }
                    .sortedByDescending {
                        (_, score) -> score
                    }
                    .take(4)

            for ((candidate, _) in candidates) {
                val loaded: LoadResponse =
                    withTimeoutOrNull(
                        loadTimeout(kind)
                    ) {
                        api.load(candidate.url)
                    } ?: continue

                if (request.isTv) {
                    val series =
                        loaded as? TvSeriesLoadResponse
                            ?: continue

                    if (
                        scoreTitle(
                            series.name,
                            aliases + query
                        ) < 78
                    ) {
                        continue
                    }

                    val data =
                        episodeData(
                            loaded = series,
                            request = request,
                            candidateTitle =
                                candidate.name,
                            aliases = aliases + query
                        ) ?: continue

                    return MOOSAHubProvider.ProviderTarget(
                        providerName = api.name,
                        mainUrl = api.mainUrl,
                        data = data
                    )
                } else {
                    val movie =
                        loaded as? MovieLoadResponse
                            ?: continue

                    if (
                        !movieMatches(
                            movie,
                            request,
                            aliases + query
                        )
                    ) {
                        continue
                    }

                    val data =
                        movie.dataUrl
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: continue

                    return MOOSAHubProvider.ProviderTarget(
                        providerName = api.name,
                        mainUrl = api.mainUrl,
                        data = data
                    )
                }
            }
        }

        return null
    }
}
