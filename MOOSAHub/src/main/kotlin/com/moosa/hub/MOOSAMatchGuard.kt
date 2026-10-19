package com.moosa.hub

/**
 * Fail-closed identity policy shared by movie, TV and anime lookups.
 *
 * A fuzzy name is useful for ranking SEARCH results, never for proving that
 * a provider's playable payload belongs to the selected TMDB item.
 *
 * An exact title/official alias AND credible release-year evidence is required
 * when the provider supplies no matching IMDb/TMDB identifier. If a provider
 * cannot establish the identity, MOOSA must return no server rather than
 * risk playing an unrelated title.
 */
internal object MOOSAMatchGuard {
    data class Claim(
        val title: String,
        val year: Int? = null,
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val isAnime: Boolean = false
    )
    data class Evidence(
        val title: String,
        val year: Int? = null,
        val searchYear: Int? = null,
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val additionalNames: List<String> = emptyList()
    )

    private fun normalizedDigits(text: String) = buildString {
        for (ch in text) append(when (ch) {
            '٠', '۰' -> '0'; '١', '۱' -> '1'; '٢', '۲' -> '2'
            '٣', '۳' -> '3'; '٤', '۴' -> '4'; '٥', '۵' -> '5'
            '٦', '۶' -> '6'; '٧', '۷' -> '7'; '٨', '۸' -> '8'
            '٩', '۹' -> '9'; else -> ch
        })
    }

    private fun normalized(text: String): String =
        normalizedDigits(text.lowercase().replace("&", " and "))
            .replace(Regex("[أإآٱ]"), "ا")
            .replace("ى", "ي").replace("ة", "ه")
            .replace(Regex("[\\u064B-\\u065F\\u0670ـ]"), "")
            .replace(Regex("[’'‘]"), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    /** Metadata decorations only. Do NOT erase Movie, Part, II or other title words. */
    fun titleKey(text: String, isSeries: Boolean): String {
        var value = normalized(text)
            .replace(Regex("""^(?:watch|download|stream|مشاهده|تحميل)\s+"""), "")
            .replace(Regex("""\s+(?:1080p|720p|2160p|4k|webrip|bluray|webdl)$"""), "")
            .replace(Regex("""\s+(?:19|20)\d{2}$"""), "")
            .trim()
        if (isSeries) {
            value = value
                .replace(Regex("""\s+s\d{1,2}\s*e\d{1,4}$"""), "")
                .replace(Regex("""\s+(?:season|الموسم)\s*\d{1,2}$"""), "")
                .replace(Regex("""\s+(?:episode|ep|الحلقه|حلقة|حلقه)\s*\d{1,4}$"""), "")
                .trim()
        }
        return value
    }

    private fun explicitEpisode(title: String): Int? {
        val s = normalized(title)
        return listOf(
            Regex("""\bs\d{1,2}\s*e(\d{1,4})\b"""),
            Regex("""\b(?:episode|ep|الحلقه|حلقه)\s*(\d{1,4})\b""")
        ).firstNotNullOfOrNull { it.find(s)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    private fun explicitSeason(title: String): Int? {
        val s = normalized(title)
        return listOf(
            Regex("""\bs(\d{1,2})\s*e\d{1,4}\b"""),
            Regex("""\b(?:season|الموسم)\s*(\d{1,2})\b"""),
            Regex("""\b(\d{1,2})(?:st|nd|rd|th)\s*season\b""")
        ).firstNotNullOfOrNull { it.find(s)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    private fun statedReleaseYear(name: String): Int? {
        // A suffix (1979) is release metadata; '2001: A Space Odyssey'
        // contains a number in the actual title and is NOT a year hint.
        return Regex("""(?:\s+|\()((?:19|20)\d{2})\)?\s*$""")
            .find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun idConflict(claim: Claim, item: Evidence): Boolean =
        (claim.imdbId != null && item.imdbId != null &&
            !claim.imdbId.equals(item.imdbId, ignoreCase = true)) ||
        (claim.tmdbId != null && item.tmdbId != null &&
            claim.tmdbId != item.tmdbId)

    private fun matchingId(claim: Claim, item: Evidence): Boolean =
        (claim.imdbId != null && item.imdbId != null &&
            claim.imdbId.equals(item.imdbId, ignoreCase = true)) ||
        (claim.tmdbId != null && item.tmdbId != null &&
            claim.tmdbId == item.tmdbId)

    private fun yearConflicts(claim: Claim, item: Evidence): Boolean {
        val requested = claim.year ?: return false
        // The explicit first year of a distinct anime sequel often refers
        // to a different TV cour. Don't treat it as the original series
        // premiere when the requested season is > 1.
        val sequel = claim.isAnime && (claim.season ?: 0) > 1
        if (sequel) return false
        return listOfNotNull(item.year, item.searchYear,
            statedReleaseYear(item.title)).any { year ->
            year in 1900..2100 && kotlin.math.abs(year - requested) > 1
        }
    }

    private fun episodeConflicts(claim: Claim, item: Evidence): Boolean {
        if (claim.season == null) return false
        return (listOf(item.title) + item.additionalNames).any { title ->
            val s = explicitSeason(title)
            val e = explicitEpisode(title)
            (s != null && s != claim.season) ||
                (e != null && claim.episode != null && e != claim.episode)
        }
    }

    fun titleAndClaimCompatible(
        claim: Claim,
        item: Evidence,
        officialAliases: List<String>
    ): Boolean {
        if (idConflict(claim, item) || yearConflicts(claim, item) ||
            episodeConflicts(claim, item)) return false
        if (matchingId(claim, item)) return true
        val expected = (listOf(claim.title) + officialAliases)
            .map { titleKey(it, claim.season != null) }
            .filter { it.isNotBlank() }.toSet()
        return (listOf(item.title) + item.additionalNames).any { name ->
            val key = titleKey(name, claim.season != null)
            key.isNotBlank() && key in expected
        }
    }

    /** Call only after loading the actual provider page, never for search alone. */
    fun verifiedIdentity(
        claim: Claim,
        item: Evidence,
        officialAliases: List<String>
    ): Boolean {
        if (!titleAndClaimCompatible(claim, item, officialAliases)) return false
        if (matchingId(claim, item)) return true

        val year = claim.year ?: return false
        // A year that neither the result page nor the search result publishes
        // is not evidence. Exact names alone confuse remakes/reboots.
        if (claim.isAnime && (claim.season ?: 0) > 1 &&
            item.year == null && item.searchYear == null) return false
        return listOfNotNull(item.year, item.searchYear,
            statedReleaseYear(item.title)).any {
            it in 1900..2100 && kotlin.math.abs(it - year) <= 1
        }
    }
}
