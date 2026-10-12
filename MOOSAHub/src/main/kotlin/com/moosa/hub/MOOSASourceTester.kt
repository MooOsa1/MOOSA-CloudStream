package com.moosa.hub

import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object MOOSASourceTester {
    enum class Status {
        UNKNOWN,
        TESTING,
        ONLINE,
        OFFLINE
    }

    data class SourceState(
        val name: String,
        val mainUrl: String,
        val status: Status,
        val serverCount: Int,
        val lastTested: Long?
    )

    private data class MutableState(
        val name: String,
        val mainUrl: String,
        var status: Status = Status.UNKNOWN,
        var serverCount: Int = 0,
        var lastTested: Long? = null
    )

    private val states = ConcurrentHashMap<String, MutableState>()

    private fun key(api: MainAPI): String =
        "${api.name}|${api.mainUrl}".lowercase()

    fun sources(): List<MainAPI> =
        apis
            .filter {
                it.name != "MOOSA Hub" &&
                    it.providerType == ProviderType.DirectProvider
            }
            .distinctBy { key(it) }
            .sortedBy { it.name.lowercase() }

    fun snapshot(api: MainAPI): SourceState {
        val state = states.computeIfAbsent(key(api)) {
            MutableState(api.name, api.mainUrl)
        }
        return SourceState(
            name = state.name,
            mainUrl = state.mainUrl,
            status = state.status,
            serverCount = state.serverCount,
            lastTested = state.lastTested
        )
    }

    fun isEnabled(api: MainAPI): Boolean =
        MOOSASettingsStore.disabledSources()
            .none { it.equals(api.name, ignoreCase = true) }

    fun setEnabled(api: MainAPI, enabled: Boolean) {
        val disabled = MOOSASettingsStore.disabledSources().toMutableSet()
        if (enabled) {
            disabled.removeAll { it.equals(api.name, ignoreCase = true) }
        } else {
            disabled += api.name
        }
        MOOSASettingsStore.setDisabledSources(disabled)
    }

    fun setAll(enabled: Boolean) {
        MOOSASettingsStore.setDisabledSources(
            if (enabled) emptySet()
            else sources().map { it.name }.toSet()
        )
    }

    fun disableOffline() {
        val disabled = MOOSASettingsStore.disabledSources().toMutableSet()
        sources().forEach { api ->
            if (snapshot(api).status == Status.OFFLINE) {
                disabled += api.name
            }
        }
        MOOSASettingsStore.setDisabledSources(disabled)
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun testQuery(api: MainAPI): String = when {
        TvType.Movie in api.supportedTypes -> "Inception"
        TvType.TvSeries in api.supportedTypes -> "Breaking Bad"
        TvType.Anime in api.supportedTypes ||
            TvType.AnimeMovie in api.supportedTypes -> "Naruto"
        else -> "Inception"
    }

    suspend fun test(
        api: MainAPI,
        onUpdate: ((SourceState) -> Unit)? = null
    ): SourceState {
        val state = states.computeIfAbsent(key(api)) {
            MutableState(api.name, api.mainUrl)
        }
        state.status = Status.TESTING
        state.serverCount = 0
        onUpdate?.invoke(snapshot(api))

        val result = withTimeoutOrNull(22000L) {
            try {
                val query = testQuery(api)
                val search = try {
                    api.quickSearch(query)
                } catch (_: Throwable) {
                    null
                }.takeUnless { it.isNullOrEmpty() }
                    ?: try {
                        api.search(query)
                    } catch (_: Throwable) {
                        null
                    }
                    ?: emptyList()

                val wanted = normalize(query)
                val match = search
                    .sortedByDescending {
                        val candidate = normalize(it.name)
                        when {
                            candidate == wanted -> 3
                            candidate.contains(wanted) || wanted.contains(candidate) -> 2
                            else -> 0
                        }
                    }
                    .firstOrNull()
                    ?: return@withTimeoutOrNull false

                val loaded = try {
                    api.load(match.url)
                } catch (_: Throwable) {
                    null
                } ?: return@withTimeoutOrNull false

                val data = when (loaded) {
                    is MovieLoadResponse -> loaded.dataUrl
                    is TvSeriesLoadResponse -> loaded.episodes
                        .firstOrNull { !it.data.isNullOrBlank() }
                        ?.data
                    else -> null
                }?.takeIf { it.isNotBlank() }
                    ?: return@withTimeoutOrNull false

                val links = Collections.synchronizedList(
                    mutableListOf<ExtractorLink>()
                )

                withTimeoutOrNull(12000L) {
                    try {
                        api.loadLinks(
                            data,
                            false,
                            {},
                            { link ->
                                val label = "${link.name} ${link.source}".lowercase()
                                if (
                                    (
                                        link.url.startsWith("http://", true) ||
                                            link.url.startsWith("https://", true)
                                        ) &&
                                    "download" !in label &&
                                    "تحميل" !in label
                                ) {
                                    MOOSAServerRegistry.record(link)
                                    links += link
                                }
                            }
                        )
                    } catch (_: Throwable) {
                    }
                }

                val unique = links.distinctBy {
                    "${it.url}|${it.referer}|${it.headers.hashCode()}"
                }
                state.serverCount = unique.size

                if (unique.isEmpty()) {
                    return@withTimeoutOrNull false
                }

                coroutineScope {
                    unique
                        .take(8)
                        .map { link ->
                            async {
                                val working = MOOSAServerRegistry.testLink(link)
                                MOOSAServerRegistry.recordResult(link, working)
                                working
                            }
                        }
                        .awaitAll()
                        .any { it }
                }
            } catch (_: Throwable) {
                false
            }
        } ?: false

        state.status = if (result) Status.ONLINE else Status.OFFLINE
        state.lastTested = System.currentTimeMillis()
        onUpdate?.invoke(snapshot(api))
        return snapshot(api)
    }

    suspend fun testAll(
        onUpdate: ((MainAPI, SourceState) -> Unit)? = null
    ) = coroutineScope {
        val all = sources()

        for (batch in all.chunked(5)) {
            batch.map { api ->
                async {
                    test(api) { state ->
                        onUpdate?.invoke(api, state)
                    }
                }
            }.awaitAll()
        }
    }
}
