package com.moosa.hub

/**
 * Subtitle indexes sometimes return S05E10 releases for a request for S02E10.
 * A readable SRT is not proof that it belongs to the selected episode.
 *
 * Reject only explicit contradictory identifiers. Unlabelled releases remain
 * eligible because many legitimate subtitles have no S/E tags.
 */
internal object MOOSASubtitleEpisodePolicy {
    private val episodeTags = listOf(
        Regex("""(?i)(?:^|[^a-z0-9])s(?:eason)?[ ._-]*0*(\d{1,3})[ ._-]*e(?:pisode)?[ ._-]*0*(\d{1,4})(?=$|[^0-9])"""),
        Regex("""(?i)(?:^|[^a-z0-9])0*(\d{1,3})x0*(\d{1,4})(?=$|[^0-9])"""),
        Regex("""(?i)(?:^|[^a-z0-9])season[ ._-]*0*(\d{1,3})[ ._-]+episode[ ._-]*0*(\d{1,4})(?=$|[^0-9])""")
    )

    fun accepts(
        wantedSeason: Int?,
        wantedEpisode: Int?,
        declaredSeason: Int? = null,
        declaredEpisode: Int? = null,
        vararg releaseNames: String?
    ): Boolean {
        if (wantedSeason == null || wantedEpisode == null) return true
        if (declaredSeason != null && declaredSeason != wantedSeason) return false
        if (declaredEpisode != null && declaredEpisode != wantedEpisode) return false
        for (name in releaseNames) {
            if (name.isNullOrBlank()) continue
            for (pattern in episodeTags) {
                for (match in pattern.findAll(name)) {
                    val season = match.groupValues[1].toIntOrNull() ?: continue
                    val episode = match.groupValues[2].toIntOrNull() ?: continue
                    if (season != wantedSeason || episode != wantedEpisode) return false
                }
            }
        }
        return true
    }
}
