package com.moosa.hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Keep CloudStream's home responsive when TMDB is slow:
 *  - fresh responses are served immediately,
 *  - usable stale responses are served immediately and refreshed off the UI path,
 *  - overlapping requests for the same shelf share exactly one fetch.
 *
 * The scope is tied to the in-process plugin singleton, not to an Activity.
 * All network work is time-bounded and the retained payloads are size-bounded.
 */
internal class MOOSAHomePageCache(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val body: String, val createdAt: Long)

    private val pages = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

    private fun startOrJoin(
        key: String,
        fetch: suspend () -> String
    ): Deferred<String?> {
        inFlight[key]?.let { return it }

        val promise = CompletableDeferred<String?>()
        val existing = inFlight.putIfAbsent(key, promise)
        if (existing != null) return existing

        scope.launch {
            try {
                val candidate = withTimeoutOrNull(
                    MOOSAHomeLoadPolicy.HOME_NETWORK_TIMEOUT_MS
                ) {
                    try {
                        fetch()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
                // Failed requests and error JSON must not replace last-good data.
                val valid = candidate != null &&
                    candidate.trimStart().startsWith("{") &&
                    candidate.contains("\"results\"")
                if (valid) {
                    val finishedAt = now()
                    synchronized(pages) {
                        if (pages.size >= 36) {
                            MOOSACacheRetention.makeRoom(
                                pages, 36, finishedAt,
                                MOOSAHomeLoadPolicy.STALE_HOME_CACHE_MS
                            ) { it.createdAt }
                        }
                        pages[key] = Entry(candidate!!, finishedAt)
                    }
                    promise.complete(candidate)
                } else {
                    promise.complete(null)
                }
            } catch (_: CancellationException) {
                promise.complete(null)
            } catch (_: Exception) {
                promise.complete(null)
            } finally {
                inFlight.remove(key, promise)
            }
        }
        return promise
    }

    suspend fun get(key: String, fetch: suspend () -> String): String {
        val existing = pages[key]
        if (existing != null) {
            val age = now() - existing.createdAt
            if (MOOSAHomeLoadPolicy.isFresh(age)) return existing.body
            if (MOOSAHomeLoadPolicy.mayUseStale(age)) {
                // Crucial: do not await a 5-second network timeout when a
                // genuine cached row can be rendered right now.
                startOrJoin(key, fetch)
                return existing.body
            }
        }

        // On a cold start only, the caller needs the network result.
        // Concurrent callers share the same bounded in-flight request.
        val loaded = withTimeoutOrNull(
            MOOSAHomeLoadPolicy.HOME_NETWORK_TIMEOUT_MS + 250L
        ) { startOrJoin(key, fetch).await() }
        if (loaded != null) return loaded

        val fallback = pages[key]
        if (fallback != null &&
            MOOSAHomeLoadPolicy.mayUseStale(now() - fallback.createdAt)
        ) return fallback.body
        if (fallback != null) pages.remove(key, fallback)
        return "{}"
    }
}
