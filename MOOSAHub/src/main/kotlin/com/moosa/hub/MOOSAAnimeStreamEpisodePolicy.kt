package com.moosa.hub

/**
 * Dedicated anime sources return AnimeLoadResponse with independent Subbed,
 * None and Dubbed episode lists. Their episode numbers are often absolute,
 * while TMDB uses season-local numbering.
 *
 * Only return episode data when the title's season and index are defensible.
 * Prefer original-audio (subbed) over dubbed without rejecting dubbed-only
 * episodes altogether.
 */
internal object MOOSAAnimeStreamEpisodePolicy {
    data class Item(val data: String, val season: Int?, val number: Int?)
    data class Tracks(
        val subbed: List<Item> = emptyList(),
        val unlabelled: List<Item> = emptyList(),
        val dubbed: List<Item> = emptyList()
    )

    fun select(
        season: Int,
        episode: Int,
        absolute: Int?,
        sourceTitle: String,
        pageProvesSeason: Boolean,
        tracks: Tracks
    ): String? {
        val explicitPageSeason = MOOSAAnimePolicy.explicitAnimeSeason(sourceTitle)
        if (explicitPageSeason != null && explicitPageSeason != season) return null

        // Different audio tracks may have different episode coverage. Search
        // them independently in original-audio-first order.
        for (items in listOf(tracks.subbed, tracks.unlabelled, tracks.dubbed)) {
            val valid = items.filter { it.data.isNotBlank() && (it.number ?: 0) > 0 }
            if (valid.isEmpty()) continue
            val exact = valid.firstOrNull {
                it.season == season && it.number == episode
            }
            if (exact != null) return exact.data

            val seasons = valid.mapNotNull { it.season }.distinct()
            val flat = seasons.isEmpty() || seasons == listOf(1)
            if (!flat) continue

            if (season == 1 || pageProvesSeason) {
                val local = valid.firstOrNull {
                    it.number == episode && (it.season == null || it.season == 1)
                }
                if (local != null) return local.data
            }

            // When TMDB calls this S2E1 but the source uses EP 13 in an
            // undivided series, require a complete prefix to verify the index.
            if (absolute != null &&
                MOOSAAnimePolicy.allowsContinuousNumbering(
                    requestedSeason = season,
                    localEpisode = episode,
                    absoluteEpisode = absolute,
                    sourceTitle = sourceTitle,
                    sourceEpisodes = valid.map { MOOSAAnimePolicy.SourceIndex(it.season, it.number) }
                )
            ) {
                val continuous = valid.firstOrNull { it.number == absolute }
                if (continuous != null) return continuous.data
            }
        }
        return null
    }
}
