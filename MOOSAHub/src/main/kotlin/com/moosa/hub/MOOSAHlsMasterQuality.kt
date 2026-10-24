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

    /** Some servers return an audio playlist while advertising a video quality. */
    fun explicitlyAudioOnly(manifest: String): Boolean {
        val lines = manifest.lines().map(String::trim)
        if (lines.firstOrNull() != "#EXTM3U") return false
        val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF:", true) }
        if (variants.isNotEmpty()) {
            val codecs = Regex("""(?i)CODECS="([^"]+)"""")
            return variants.all { line ->
                val declared = codecs.find(line)?.groupValues?.get(1)?.lowercase()
                    ?: return@all false
                declared.split(',').all { codec ->
                    val value = codec.trim()
                    value.startsWith("mp4a") || value.startsWith("ac-3") ||
                        value.startsWith("ec-3") || value.startsWith("opus")
                }
            }
        }
        return lines.any { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO", true) } &&
            lines.none { it.startsWith("#EXTINF:", true) }
    }

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
