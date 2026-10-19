package com.moosa.hub

/**
 * Progress belongs to the initial loading of one title/episode, not to
 * CloudStream's later source refresh or video buffering callbacks.
 *
 * Once we returned a verified link for a specific episode, refreshing the
 * same episode must not reopen our bar over the playing video.
 * A different episode/movie gets its own initial progress bar.
 */
internal class MOOSAProgressVisibilityPolicy {
    private var key: String? = null
    private var playableLinkDelivered = false

    @Synchronized
    fun begin(requestKey: String): Boolean {
        if (key != requestKey) {
            key = requestKey
            playableLinkDelivered = false
        }
        return !playableLinkDelivered
    }

    @Synchronized
    fun linkAvailable(requestKey: String) {
        if (key == requestKey) playableLinkDelivered = true
    }

    @Synchronized
    fun isSuppressed(requestKey: String): Boolean =
        key == requestKey && playableLinkDelivered
}
