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
    private val reasons = linkedMapOf<String, MutableMap<String, Int>>()
    private val candidates = linkedMapOf<String, Int>()
    private val queued = linkedMapOf<String, Int>()
    private val started = linkedMapOf<String, Int>()
    private val omitted = linkedMapOf<String, Int>()

    @Synchronized
    fun begin(titleText: String) {
        title = titleText.take(85)
        status.clear()
        delivered.clear()
        rejected.clear()
        reasons.clear()
        candidates.clear()
        queued.clear()
        started.clear()
        omitted.clear()
        status["OpenSubtitles"] = "waiting"
        status["SubDL"] = "waiting"
        status["Wyzie"] = "waiting"
    }

    @Synchronized
    fun update(source: String, message: String) {
        status[source] = message.take(180)
    }

    /** Survived index parsing; no subtitle text downloaded yet. */
    @Synchronized
    fun candidates(source: String, count: Int) {
        candidates[source] = (candidates[source] ?: 0) + count
    }

    /** Submitted to the limited verification queue. */
    @Synchronized
    fun queued(source: String, count: Int) {
        queued[source] = (queued[source] ?: 0) + count
    }

    /** Download/validation actually began, rather than merely being queued. */
    @Synchronized
    fun started(source: String) {
        started[source] = (started[source] ?: 0) + 1
    }

    /** Excluded while converting API index entries to usable URLs. */
    @Synchronized
    fun omitted(source: String, reason: String) {
        omitted[source] = (omitted[source] ?: 0) + 1
        reason(source, reason)
    }

    @Synchronized
    fun accepted(source: String) {
        delivered[source] = (delivered[source] ?: 0) + 1
    }

    @Synchronized
    fun rejected(source: String) {
        rejected[source] = (rejected[source] ?: 0) + 1
    }

    @Synchronized
    fun reason(source: String, category: String) {
        val group = reasons.getOrPut(source) { linkedMapOf() }
        group[category] = (group[category] ?: 0) + 1
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
            val parsed = candidates[source] ?: 0
            val waiting = queued[source] ?: 0
            val tried = started[source] ?: 0
            val dropped = omitted[source] ?: 0
            if (source == "Native" && good == 0 && bad == 0 && waiting == 0) continue
            append(source).append(": ")
                .append(status[source] ?: "native player tracks")
                .append("\n  usable index URLs: ").append(parsed)
                .append(" | omitted before validation: ").append(dropped)
                .append("\n  queued: ").append(waiting)
                .append(" | checks started: ").append(tried)
                .append("\n  sent to player: ").append(good)
                .append(" | rejected/unreadable: ").append(bad)
                .append("\n")
            val why = reasons[source].orEmpty().entries
                .sortedByDescending { it.value }.take(3)
                .joinToString(" | ") { "${it.key}: ${it.value}" }
            if (why.isNotBlank()) append("  causes: ").append(why).append("\n")
            append("\n")
        }
        append("If queued exceeds checks started, the player search ended ") 
        append("before those files could be inspected.\n")
        append("These numbers are from the addon, not proof that the video ")
        append("player rendered the captions. CloudStream embedded tracks ")
        append("are controlled by the player. No API keys or links are shown.")
    }
}
