package com.moosa.hub

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Playback policy: only show streams with a known resolution >= 720p.
 *
 * Never "upgrade" an explicitly low-quality link based on a title, CDN ID,
 * show name, or a generic HD label. Unknown quality is not silently treated
 * as HD, because it may contain 360p/480p.
 *
 * A provider must supply an actual resolution (from its quality tab or HLS
 * rendition) or a resolution label with an unambiguous 720p+ token.
 */
object MOOSAQualityPolicy {
    const val MIN_HEIGHT = 720

    private val explicitResolution = Regex(
        """(?i)(?:^|[\s._/=?&#-])(2160|1440|1080|720|576|540|480|360|240)p?(?=$|[\s._/=?&#-])"""
    )

    fun labeledHeight(value: String): Int? =
        explicitResolution.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun height(link: ExtractorLink): Int? {
        // A positive numeric quality from a provider always wins.
        // If it says 480, never re-label it 1080 based on an URL.
        val declared = link.quality
        if (declared != Qualities.Unknown.value && declared > 0) {
            return declared
        }

        // Explicit CDN labels or filenames are secondary evidence. A bare
        // "HD" or a four-digit episode number is not a resolution.
        return labeledHeight(link.name)
            ?: labeledHeight(link.source)
            ?: labeledHeight(link.url)
    }

    fun accepts(link: ExtractorLink): Boolean =
        (height(link) ?: 0) >= MIN_HEIGHT

    fun acceptsHeight(height: Int?): Boolean =
        height != null && height >= MIN_HEIGHT
}
