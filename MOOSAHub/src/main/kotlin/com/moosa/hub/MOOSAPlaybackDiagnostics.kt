package com.moosa.hub

import com.lagradost.cloudstream3.MainAPI

internal object MOOSAPlaybackDiagnostics {
    private val lock = Any()
    private val states =
        linkedMapOf<String, Pair<String, String>>()

    @Volatile
    private var header: String = "Playback diagnostics"

    private fun key(api: MainAPI): String =
        "${api.name}|${api.mainUrl}|${api.javaClass.name}"

    fun start(
        request: MOOSAHubProvider.HubPlaybackRequest,
        providers: List<MainAPI>
    ) {
        synchronized(lock) {
            states.clear()

            header = buildString {
                append(request.title)

                if (
                    request.season != null &&
                    request.episode != null
                ) {
                    append(
                        " • S${request.season}E${request.episode}"
                    )
                }

                append(
                    " • ${providers.size} providers"
                )
            }

            providers.forEach { api ->
                states[key(api)] =
                    api.name to "Queued"
            }
        }
    }

    fun update(
        api: MainAPI,
        status: String
    ) {
        synchronized(lock) {
            states[key(api)] =
                api.name to status
        }
    }

    fun report(): String =
        synchronized(lock) {
            buildString {
                append(header)

                if (states.isEmpty()) {
                    append("\n\nNo providers were loaded.")
                    return@buildString
                }

                append("\n\n")

                states.values
                    .forEachIndexed {
                        index,
                        (name, status) ->

                        if (index > 0) {
                            append("\n")
                        }

                        append(name)
                        append(": ")
                        append(status)
                    }
            }
        }
}
