package com.moosa.hub

/**
 * Anime-specific safety check for third-party meta-providers that aggregate
 * multiple extractors inside one CloudStream plugin.
 *
 * Upstream CineStream/CSX code (ProviderRegistry + CineStreamExtractors)
 * includes extractors that select the FIRST title search result, or accept
 * any substring/first result when the requested title is not found. Its
 * load() page can still have the right IMDb/TMDB metadata, because that
 * metadata belongs to CineStream's catalog, not the chosen video source.
 *
 * Filtering CineStream's entire plugin would destroy independent working
 * links. Quarantine only extractor identities with directly observed unsafe
 * title selection, only for anime, and leave all non-anime sources alone.
 *
 * This check is defensive, not proof that other sources' video content is
 * correct. It has no web I/O and runs before link probing/callback.
 */
internal object MOOSAAnimeExtractorSafety {
    // Anchored to extractor label, never scan titles or host/URL substrings.
    // Confirmed from SaurabhKaperwan/CSX CineStreamExtractors.kt:
    // Castle: substring match, then first search result fallback.
    // AniKoto/AnimeDao/AniDB/AniZone/AniNeko: first title search hit.
    // GramCinema: broken inverted file-name matching in Bollywood extractor.
    private val unsafeCineStreamExtractors = listOf(
        "castle",
        "anikoto",
        "animedao",
        "anidb",
        "anizone",
        "anineko",
        "gramcinema"
    )

    private fun isCineStream(providerName: String, providerClass: String): Boolean =
        providerName.contains("cinestream", ignoreCase = true) ||
            providerClass.contains("cinestream", ignoreCase = true) ||
            providerClass.startsWith("com.megix.Cine", ignoreCase = true)

    private fun hasUnsafeExtractorLabel(value: String): String? {
        val label = value.trim().lowercase(java.util.Locale.ROOT)
        if (label.isBlank()) return null
        return unsafeCineStreamExtractors.firstOrNull { engine ->
            label == engine ||
                label.startsWith("$engine ") ||
                label.startsWith("$engine[") ||
                label.startsWith("$engine -") ||
                label.startsWith("$engine(")
        }
    }

    /**
     * @return original unsafe extractor identity; null if not shown unsafe.
     * Does not globally disable CineStream or independent AniKoto extensions.
     */
    fun unsafeEngine(
        isAnime: Boolean,
        providerName: String,
        providerClass: String,
        linkSource: String,
        linkName: String
    ): String? {
        if (!isAnime || !isCineStream(providerName, providerClass)) return null
        return hasUnsafeExtractorLabel(linkSource)
            ?: hasUnsafeExtractorLabel(linkName)
    }
}
