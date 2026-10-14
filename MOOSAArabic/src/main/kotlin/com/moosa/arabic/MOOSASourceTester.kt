package com.moosa.hub

import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
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
        UNVERIFIED,
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

    private fun testQueries(api: MainAPI): List<String> = when {
        api.lang.equals("ar", ignoreCase = true) -> listOf(
            "بيت الروبي",
            "جعفر العمدة",
            "العتاولة",
            "اللعبة",
            "الحريفة"
        )
        TvType.Movie in api.supportedTypes -> listOf(
            "Inception",
            "Avatar",
            "The Dark Knight",
            "Interstellar"
        )
        TvType.TvSeries in api.supportedTypes -> listOf(
            "Breaking Bad",
            "The Last of Us",
            "Wednesday",
            "Game of Thrones"
        )
        TvType.Anime in api.supportedTypes ||
            TvType.AnimeMovie in api.supportedTypes -> listOf(
            "Naruto",
            "One Piece",
            "Attack on Titan",
            "Demon Slayer"
        )
        else -> listOf("Inception", "Avatar")
    }

    private suspend fun candidateTargets(api: MainAPI): List<SearchResponse> {
        val found = mutableListOf<SearchResponse>()

        for (query in testQueries(api)) {
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
            val ranked = search
                .map { result ->
                    val candidate = normalize(result.name)
                    val score = when {
                        candidate == wanted -> 3
                        candidate.contains(wanted) ||
                            wanted.contains(candidate) -> 2
                        else -> 0
                    }
                    result to score
                }
                .sortedByDescending { it.second }
                .take(2)
                .map { it.first }

            found += ranked
        }

        if (found.isEmpty()) {
            val page = api.mainPage.firstOrNull { it.data.isNotBlank() }
            if (page != null) {
                val homepage = try {
                    api.getMainPage(
                        1,
                        com.lagradost.cloudstream3.MainPageRequest(
                            page.name,
                            page.data,
                            page.horizontalImages
                        )
                    )
                } catch (_: Throwable) {
                    null
                }

                homepage
                    ?.items
                    ?.flatMap { it.list }
                    ?.take(4)
                    ?.let(found::addAll)
            }
        }

        return found
            .distinctBy { it.url }
            .take(8)
    }

    private fun playbackData(
        loaded: Any?
    ): List<String> = when (loaded) {
        is MovieLoadResponse ->
            listOfNotNull(loaded.dataUrl.takeIf { it.isNotBlank() })

        is TvSeriesLoadResponse ->
            loaded.episodes
                .mapNotNull { it.data?.takeIf(String::isNotBlank) }
                .distinct()
                .take(3)

        else -> emptyList()
    }

    private suspend fun siteReachable(api: MainAPI): Boolean {
        val url = api.mainUrl.trim()
        if (!url.startsWith("http")) return false

        return withTimeoutOrNull(5000L) {
            try {
                val response = app.get(
                    url,
                    verify = false
                )
                response.isSuccessful ||
                    response.code in 300..499
            } catch (_: Throwable) {
                false
            }
        } ?: false
    }

    suspend fun test(
        api: MainAPI,
        discoveryDepth: Int = 1,
        onUpdate: ((SourceState) -> Unit)? = null
    ): SourceState {
        val state = states.computeIfAbsent(key(api)) {
            MutableState(api.name, api.mainUrl)
        }

        state.status = Status.TESTING
        state.serverCount = 0
        onUpdate?.invoke(snapshot(api))

        val safeDepth = discoveryDepth.coerceIn(1, 3)
        val timeoutMs = if (safeDepth > 1) 42000L else 24000L

        val finalStatus = withTimeoutOrNull(timeoutMs) {
            val links = Collections.synchronizedList(
                mutableListOf<ExtractorLink>()
            )

            val targets = try {
                candidateTargets(api)
            } catch (_: Throwable) {
                emptyList()
            }

            if (targets.isEmpty()) {
                return@withTimeoutOrNull if (siteReachable(api)) {
                    Status.UNVERIFIED
                } else {
                    Status.OFFLINE
                }
            }

            var anyLoaded = false

            for (target in targets.take(safeDepth + 2)) {
                val loaded = try {
                    api.load(target.url)
                } catch (_: Throwable) {
                    null
                } ?: continue

                anyLoaded = true
                val dataItems = playbackData(loaded)

                for (data in dataItems.take(if (safeDepth > 1) 2 else 1)) {
                    withTimeoutOrNull(10000L) {
                        try {
                            api.loadLinks(
                                data,
                                false,
                                {},
                                { link ->
                                    // Source Tester must meet the SAME HD
                                    // and non-Download policy as MOOSA Hub.
                                    // Previously a 480p English movie could
                                    // mark ArabSeed ONLINE despite Arabic
                                    // films and episodes being unavailable.
                                    if (
                                        (link.url.startsWith("http://", true) ||
                                            link.url.startsWith("https://", true)) &&
                                        MOOSAQualityPolicy.accepts(link)
                                    ) {
                                        MOOSAServerRegistry.record(link)
                                        links += link
                                    }
                                }
                            )
                        } catch (_: Throwable) {
                        }
                    }

                    if (safeDepth == 1 && links.isNotEmpty()) break
                }

                if (safeDepth == 1 && links.isNotEmpty()) break
            }

            val unique = links.distinctBy {
                "${it.url}|${it.referer}|${it.headers.hashCode()}"
            }

            val serverKeys = unique
                .map(MOOSAServerRegistry::keyFor)
                .distinct()

            state.serverCount = serverKeys.size

            if (unique.isEmpty()) {
                return@withTimeoutOrNull when {
                    anyLoaded -> Status.UNVERIFIED
                    siteReachable(api) -> Status.UNVERIFIED
                    else -> Status.OFFLINE
                }
            }

            val verificationCandidates = unique
                .distinctBy(MOOSAServerRegistry::keyFor)
                .take(6)

            var anyWorking = false

            for (batch in verificationCandidates.chunked(6)) {
                val results = coroutineScope {
                    batch.map { link ->
                        async {
                            val result =
                                MOOSAServerRegistry.testLink(link)
                            MOOSAServerRegistry.recordResult(
                                link,
                                result
                            )
                            result
                        }
                    }.awaitAll()
                }

                if (results.any { it }) {
                    anyWorking = true
                    break
                }
            }

            if (anyWorking) {
                Status.ONLINE
            } else {
                Status.UNVERIFIED
            }
        } ?: Status.UNVERIFIED

        state.status = finalStatus
        state.lastTested = System.currentTimeMillis()
        onUpdate?.invoke(snapshot(api))
        return snapshot(api)
    }

    suspend fun testAll(
        discoveryDepth: Int = 1,
        onUpdate: ((MainAPI, SourceState) -> Unit)? = null
    ) = coroutineScope {
        val all = sources()
        val batchSize = MOOSASettingsStore
            .providerConcurrency()
            .coerceIn(4, 10)

        for (batch in all.chunked(batchSize)) {
            batch.map { api ->
                async {
                    test(
                        api = api,
                        discoveryDepth = discoveryDepth
                    ) { state ->
                        onUpdate?.invoke(api, state)
                    }
                }
            }.awaitAll()
        }
    }
}
