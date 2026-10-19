package com.moosa.hub

/**
 * Reads resolution claims from provider-supplied labels or filename tokens.
 * This is NOT proof of the actual encoded video dimensions: the existing
 * media validator still decides whether a URL is playable.
 *
 * "HD", "HDR", "HEVC" and generic CDN names do not claim a resolution.
 */
internal object MOOSAResolutionLabels {
    private val numeric = Regex(
        """(?i)(?:^|[\s._/=?&#-])(2160|1440|1080|720|576|540|480|360|240)p?(?=$|[\s._/=?&#-])"""
    )
    private val dimensions = listOf(
        2160 to Regex("""(?i)(?<!\d)(?:3840|4096)\s*[x×]\s*2160(?!\d)"""),
        1440 to Regex("""(?i)(?<!\d)2560\s*[x×]\s*1440(?!\d)"""),
        1080 to Regex("""(?i)(?<!\d)1920\s*[x×]\s*1080(?!\d)"""),
        720 to Regex("""(?i)(?<!\d)1280\s*[x×]\s*720(?!\d)""")
    )
    private val ultraHd = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:4k|uhd|ultra[\s._-]*hd)(?![\p{L}\p{N}])"""
    )

    fun claimedHeight(label: String): Int? {
        numeric.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        dimensions.firstOrNull { (_, matcher) -> matcher.containsMatchIn(label) }
            ?.first?.let { return it }
        return if (ultraHd.containsMatchIn(label)) 2160 else null
    }
}
