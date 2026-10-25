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

    /**
     * Strict only when the file explicitly proves it has the WRONG audio.
     * A missing moov, missing language code, or an `und` track does NOT
     * establish a dub mismatch. v144 rejected ALL unknown Korean/Japanese
     * tracks; it hid previously playable sources without audio evidence.
     */
    fun accept(original: String?, languages: Set<String>?): Boolean {
        if (languages == null) return true // unknown != wrong language
        if (languages.isEmpty()) return false // parser proved no audio tracks
        val wanted = normalize(original) ?: return true
        val known = languages.mapNotNull(::normalize).toSet()
        if (wanted in known) return true
        // An unlabeled track may carry the requested original soundtrack.
        // Explicit, fully labeled Hindi-only for Korean remains rejected.
        if (languages.any { normalize(it) == null }) return true
        return false
    }

    fun unverified(original: String?, languages: Set<String>?): Boolean {
        if (languages == null || languages.isEmpty()) return languages == null
        val wanted = normalize(original) ?: return true
        val known = languages.mapNotNull(::normalize).toSet()
        return wanted !in known && languages.any { normalize(it) == null }
    }

    fun display(languages: Set<String>?): String = when {
        languages == null -> "audio unknown"
        languages.isEmpty() -> "no audio track"
        else -> {
            val labeled = languages.mapNotNull(::normalize).distinct().sorted()
            val labels = labeled + if (languages.any { normalize(it) == null })
                listOf("unknown") else emptyList()
            "audio " + labels.joinToString("/")
        }
    }

    fun isMultiAudio(languages: Set<String>?): Boolean =
        languages?.mapNotNull(::normalize)?.distinct()?.size?.let { it > 1 } == true
}
