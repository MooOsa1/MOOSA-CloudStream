package com.moosa.hub

/**
 * TMDB splits some long-running anime into seasons, but many anime sources
 * publish a single continuously numbered list. Keep BOTH identities.
 * Never infer continuous numbering when TMDB has missing episode metadata.
 */
internal object MOOSAAnimePolicy {
    data class Index(val season: Int, val episode: Int)
    data class SourceIndex(val season: Int?, val episode: Int?)

    fun isAnime(originalLanguage: String?, genreIds: List<Int>): Boolean =
        originalLanguage.equals("ja", ignoreCase = true) && 16 in genreIds

    fun absoluteEpisodes(episodes: Collection<Index>): Map<Index, Int> {
        val bySeason = episodes
            .filter { it.season > 0 && it.episode > 0 }
            .groupBy { it.season }
            .mapValues { (_, list) -> list.map { it.episode }.distinct().sorted() }
        val result = mutableMapOf<Index, Int>()
        var previousTotal = 0
        var season = 1
        while (true) {
            val numbers = bySeason[season] ?: break
            // Skipped/unreleased episodes make all later absolute positions
            // ambiguous. Do not manufacture a potentially wrong server key.
            if (numbers.isEmpty() || numbers.first() != 1 ||
                numbers.last() != numbers.size
            ) break
            numbers.forEach { episode ->
                result[Index(season, episode)] = previousTotal + episode
            }
            previousTotal += numbers.size
            season++
        }
        return result
    }

    fun displayEpisodeName(
        current: String?,
        season: Int?,
        episode: Int?,
        absolute: Int?
    ): String? {
        if (season == null || episode == null || absolute == null ||
            absolute == episode
        ) return current
        val title = current?.trim().orEmpty()
        return "EP $absolute" + if (title.isBlank() ||
            title.equals("Episode $episode", true) ||
            title.equals("Episode $absolute", true)
        ) "" else " • $title"
    }

    fun seasonQueries(title: String, season: Int): List<String> {
        if (season <= 1 || title.isBlank()) return emptyList()
        val ordinal = when (season % 100) {
            11, 12, 13 -> "th"
            else -> when (season % 10) {
                1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
            }
        }
        return listOf(
            "$title ${season}$ordinal Season",
            "$title Season $season",
            "$title S$season"
        )
    }

    fun explicitAnimeSeason(title: String): Int? {
        val patterns = listOf(
            Regex("""\b(\d{1,2})(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE),
            Regex("""\bseason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bs(\d{1,2})\b""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { p ->
            p.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    fun allowsContinuousNumbering(
        requestedSeason: Int,
        localEpisode: Int,
        absoluteEpisode: Int?,
        sourceTitle: String,
        sourceEpisodes: Collection<SourceIndex>
    ): Boolean {
        val absolute = absoluteEpisode ?: return false
        if (requestedSeason <= 1 || absolute <= localEpisode ||
            absolute > 2000
        ) return false
        // A page advertising "2nd Season" is NOT a continuous S1 index.
        // Such a page must be resolved by local episode number instead.
        if (explicitAnimeSeason(sourceTitle) != null) return false
        if (sourceEpisodes.any { (season, _) -> season != null && season != 1 }) {
            return false
        }
        val numbers = sourceEpisodes.mapNotNull { it.episode }.toSet()
        // Require the entire continuous prefix: a single hit at episode 27
        // can otherwise be a completely unrelated anime/arc index.
        return (1..absolute).all { it in numbers }
    }
}
