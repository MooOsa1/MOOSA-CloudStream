package com.moosa.hub

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A catalog collision check for yearless/id-less matching candidates. */
internal object MOOSATmdbCollisionGuard {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
    private data class Cached(val value: Boolean, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val ttlPositive = 30 * 60_000L
    private val ttlNegative = 60_000L

    suspend fun hasSingleExactTitle(
        request: MOOSAHubProvider.HubPlaybackRequest,
        aliases: List<String>
    ): Boolean {
        val id = request.tmdbId ?: return false
        val key = "\${request.isTv}|$id|\${request.title}"
        val now = System.currentTimeMillis()
        cache[key]?.let { cached ->
            if (now - cached.at < if (cached.value) ttlPositive else ttlNegative)
                return cached.value
        }
        val resolved = withTimeoutOrNull(3300L) {
            try {
                val endpoint = if (request.isTv) "tv" else "movie"
                val response = app.get(
                    "$BASE/search/$endpoint",
                    params = mapOf(
                        "api_key" to KEY,
                        "query" to request.title,
                        "language" to "en-US",
                        "include_adult" to "false"
                    )
                )
                val obj = JSONObject(response.text)
                val pages = obj.optInt("total_pages", 1)
                val keys = (listOf(request.title) + aliases).map {
                    MOOSAMatchGuard.titleKey(it, request.isTv)
                }.filter(String::isNotBlank).toSet()
                val matches = linkedSetOf<Int>()
                fun collect(page: JSONObject) {
                    val items = page.optJSONArray("results") ?: return
                    for (i in 0 until items.length()) {
                        val result = items.optJSONObject(i) ?: continue
                        val name = result.optString(if (request.isTv) "name" else "title")
                        val original = result.optString(if (request.isTv) "original_name" else "original_title")
                        if (listOf(name, original).any { title ->
                                MOOSAMatchGuard.titleKey(title, request.isTv) in keys
                            }
                        ) {
                            result.optInt("id").takeIf { it > 0 }?.let(matches::add)
                        }
                    }
                }
                collect(obj)
                if (pages > 1 && matches.size <= 1) {
                    val other = app.get(
                        "$BASE/search/$endpoint",
                        params = mapOf(
                            "api_key" to KEY,
                            "query" to request.title,
                            "language" to "en-US",
                            "page" to "2",
                            "include_adult" to "false"
                        )
                    )
                    collect(JSONObject(other.text))
                }
                matches.size == 1 && matches.single() == id
            } catch (_: Throwable) {
                false
            }
        } ?: false
        cache[key] = Cached(resolved, now)
        return resolved
    }
}
