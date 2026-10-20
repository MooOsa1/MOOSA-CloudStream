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
        // Name of the search result which led to this LOADED provider page.
        val searchTitle: String? = null,
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
                .replace(Regex("""\s+(?:episode|ep|الحلقه|حلقة|حلقه)\s*\d{1,4}$"""), "")
                .replace(Regex("""\s+(?:season|الموسم)\s*\d{1,2}$"""), "")
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
            year in 1900..2100 && year != requested
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

    /** Pure catalog decision. No fuzzy aliases or vote-based title estimates. */
    fun uniquelyIdentifiedInCatalog(requestedId: Int, candidates: Collection<Int>): Boolean =
        candidates.toSet() == setOf(requestedId)

    /**
     * Only reject explicit, readable media-file contradictions. Generic
     * CDN hashes/master.m3u8 paths have no identity evidence and pass.
     * A network probe proves link viability, NOT video identity.
     */
    fun mediaFilenameContradicts(claim: Claim, url: String): Boolean {
        val basename = try {
            val path = java.net.URI(url).rawPath.orEmpty()
            java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        } catch (_: Throwable) { return false }
        if (!Regex("""(?i)\.(?:mp4|mkv|avi|webm)$""").containsMatchIn(basename))
            return false
        val raw = basename.replace(Regex("""(?i)\.(?:mp4|mkv|avi|webm)$"""), "")
        if (raw.length < 7) return false
        val statedSeason = explicitSeason(raw)
        val statedEpisode = explicitEpisode(raw)
        if (claim.season != null && statedSeason != null &&
            statedSeason != claim.season) return true
        if (claim.episode != null && statedEpisode != null &&
            statedEpisode != claim.episode) return true

        // Only a release suffix on a readable file may contradict the year:
        // 1917.2019.mkv is the title "1917" released in 2019, not 1917.
        val words = normalized(raw)
        val years = Regex("""\b(?:19|20)\d{2}\b""")
            .findAll(words).mapNotNull { it.value.toIntOrNull() }.toList()
        if (claim.year != null && years.isNotEmpty() &&
            words.any(Char::isLetter) && words.split(' ').size >= 3
        ) {
            val lastYear = years.last()
            if (lastYear != claim.year) return true
        }
        return false
    }

    /** Call only after loading the actual provider page, never for search alone. */
    fun verifiedIdentity(
        claim: Claim,
        item: Evidence,
        officialAliases: List<String>,
        allowCorroboratedTitleOnly: Boolean = false
    ): Boolean {
        if (!titleAndClaimCompatible(claim, item, officialAliases)) return false
        if (matchingId(claim, item)) return true

        val year = claim.year
        val knownYears = listOfNotNull(
            item.year, item.searchYear, statedReleaseYear(item.title)
        ).filter { it in 1900..2100 }
        if (year != null && knownYears.any { it == year } &&
            !(claim.isAnime && (claim.season ?: 0) > 1 &&
                item.year == null && item.searchYear == null)
        ) return true

        // Controlled recovery for otherwise-valid yearless sources:
        // exact official name on *both* search and loaded page AND
        // a separate TMDB lookup confirming there is no same-name remake.
        // Never grant this to direct load() (which has no search page).
        if (!allowCorroboratedTitleOnly || knownYears.isNotEmpty()) return false
        val searched = item.searchTitle ?: return false
        if (searched.isBlank()) return false
        val keys = (listOf(claim.title) + officialAliases)
            .map { titleKey(it, claim.season != null) }
            .filter { it.isNotBlank() }.toSet()
        val searchKey = titleKey(searched, claim.season != null)
        val loadedKey = titleKey(item.title, claim.season != null)
        return searchKey.isNotBlank() &&
            searchKey in keys &&
            loadedKey in keys &&
            searchKey == loadedKey
    }
}
