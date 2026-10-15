package com.moosa.hub

/**
 * Last real CloudStream subtitle search. Recording counters here must never
 * create extra API requests or store API keys, subtitle text or download URLs.
 */
internal object MOOSASubtitleHealth {
    private var title = "Not searched yet"
    private val status = linkedMapOf<String, String>()
    private val delivered = linkedMapOf<String, Int>()
    private val rejected = linkedMapOf<String, Int>()

    @Synchronized
    fun begin(titleText: String) {
        title = titleText.take(85)
        status.clear()
        delivered.clear()
        rejected.clear()
        status["OpenSubtitles"] = "waiting"
        status["SubDL"] = "waiting"
        status["Wyzie"] = "waiting"
    }

    @Synchronized
    fun update(source: String, message: String) {
        status[source] = message.take(180)
    }

    @Synchronized
    fun accepted(source: String) {
        delivered[source] = (delivered[source] ?: 0) + 1
    }

    @Synchronized
    fun rejected(source: String) {
        rejected[source] = (rejected[source] ?: 0) + 1
    }

    fun sourceOf(label: String): String = when {
        label.contains("SubDL", true) -> "SubDL"
        label.contains("Wyzie", true) -> "Wyzie"
        label.contains("OpenSubtitles", true) -> "OpenSubtitles"
        else -> "Native"
    }

    @Synchronized
    fun report(): String = buildString {
        append("Last playback: ").append(title).append("\n\n")
        for (source in listOf("OpenSubtitles", "SubDL", "Wyzie", "Native")) {
            if (source != "Native" && source !in status) continue
            val good = delivered[source] ?: 0
            val bad = rejected[source] ?: 0
            if (source == "Native" && good == 0 && bad == 0) continue
            append(source).append(": ")
                .append(status[source] ?: "native player tracks")
                .append("\n  verified/displayed by extension: ")
                .append(good)
                .append(" | rejected/unreadable: ")
                .append(bad)
                .append("\n\n")
        }
        append("These numbers are from the addon, not proof that the video ")
        append("player rendered the captions. CloudStream embedded tracks ")
        append("are controlled by the player. No API keys or links are shown.")
    }
}
