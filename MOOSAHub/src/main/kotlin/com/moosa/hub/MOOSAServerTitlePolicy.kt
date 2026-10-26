package com.moosa.hub

import java.util.Locale

/** Short, factual player labels. CloudStream adds the quality badge itself. */
internal object MOOSAServerTitlePolicy {
    private val languageNames = mapOf(
        "eng" to "English", "hin" to "Hindi", "ara" to "Arabic",
        "kor" to "Korean", "jpn" to "Japanese", "zho" to "Chinese",
        "ita" to "Italian", "fra" to "French", "spa" to "Spanish",
        "deu" to "German", "por" to "Portuguese", "rus" to "Russian",
        "tam" to "Tamil", "tel" to "Telugu", "mal" to "Malayalam"
    )

    fun audio(languages: Set<String>?): String {
        if (languages == null) return "Audio ?"
        if (languages.isEmpty()) return "No audio"
        val names = languages.mapNotNull(MOOSAAudioTrackPolicy::normalize)
            .distinct().sorted().map { languageNames[it] ?: it.uppercase(Locale.ROOT) }
        val unknown = languages.any { MOOSAAudioTrackPolicy.normalize(it) == null }
        return "Audio " + (names + if (unknown) listOf("?") else emptyList())
            .ifEmpty { listOf("?") }.joinToString(" / ")
    }

    fun reyaMirror(source: String): String {
        if (source.contains("vidnest", true)) return "VidNest"
        return source.replace(Regex("""[^\p{L}\p{N} ._-]"""), " ")
            .trim().take(25).ifBlank { "Stream" }
    }
}
