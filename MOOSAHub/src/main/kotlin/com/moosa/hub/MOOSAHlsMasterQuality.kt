package com.moosa.hub

/**
 * Inspect the actual HLS master playlist rather than guessing the quality
 * from a provider name such as "HD" or "UHD".
 *
 * Only #EXT-X-STREAM-INF video variants WITH a following media playlist URI
 * count. Ordinary media playlists, audio tracks, comments and the poster
 * dimensions cannot prove 720p/1080p/2160p.
 */
internal object MOOSAHlsMasterQuality {
    private val resolution = Regex(
        """(?i)(?:^|,)\s*RESOLUTION\s*=\s*"?(\d{3,5})[xX](\d{3,5})"?(?:,|$)"""
    )

    fun highestVariantHeight(manifest: String): Int? {
        val lines = manifest.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.firstOrNull()?.trimStart('\uFEFF') != "#EXTM3U") return null
        var result: Int? = null
        for (i in 0 until lines.lastIndex) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) continue
            val attrs = line.substringAfter(':')
            val dimensions = resolution.find(attrs) ?: continue
            val width = dimensions.groupValues[1].toIntOrNull() ?: continue
            val height = dimensions.groupValues[2].toIntOrNull() ?: continue
            if (width !in 320..8192 || height !in 240..4320) continue
            val nextLine = lines[i + 1]
            if (nextLine.startsWith("#")) continue
            if (result == null || height > result) result = height
        }
        return result
    }
}
