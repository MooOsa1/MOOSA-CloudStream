package com.moosa.hub

import java.util.Locale

/**
 * Audio verification must use actual container track metadata, not filenames,
 * provider country, the TMDB poster or a generic "Multi" badge.
 *
 * "unknown" deliberately has its own state: no verified language != English.
 */
internal object MOOSAAudioTrackPolicy {
    private val names = mapOf(
        "en" to "eng", "ko" to "kor", "hi" to "hin",
        "ja" to "jpn", "zh" to "zho", "ar" to "ara",
        "fr" to "fra", "de" to "deu", "es" to "spa",
        "it" to "ita", "pt" to "por", "ru" to "rus",
        "ta" to "tam", "te" to "tel", "ml" to "mal",
        "bn" to "ben", "tr" to "tur", "th" to "tha",
        "vi" to "vie", "id" to "ind", "fa" to "fas",
        "ur" to "urd", "pl" to "pol", "nl" to "nld",
        "sv" to "swe", "da" to "dan"
    )

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val key = raw.trim().lowercase(Locale.ROOT).substringBefore('-')
        return names[key] ?: key.takeIf { it in names.values }
    }

    /** null languages = no reliable metadata, empty = confirmed no labelled audio. */
    fun accept(original: String?, languages: Set<String>?): Boolean {
        val wanted = normalize(original) ?: return languages == null ||
            languages.any { normalize(it) != null }
        if (languages == null) {
            // Keep existing English playback when an MP4 has a tail moov or
            // unlabelled mux. Do not expose a Korean/Japanese/etc. movie as
            // dubbed-only without proof of its native-language audio.
            return wanted == "eng"
        }
        return languages.any { normalize(it) == wanted }
    }

    fun display(languages: Set<String>?): String = when {
        languages == null -> "audio unknown"
        languages.isEmpty() -> "no audio track"
        else -> "audio " + languages.mapNotNull(::normalize)
            .distinct().sorted().joinToString("/")
    }

    fun isMultiAudio(languages: Set<String>?): Boolean =
        languages?.mapNotNull(::normalize)?.distinct()?.size?.let { it > 1 } == true
}
