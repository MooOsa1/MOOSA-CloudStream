package com.moosa.hub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Collections

/** User-initiated connection. External CloudStream extensions run only on the device. */
object MOOSACloudStreamBridge {
    private const val BASE = "https://moosa-web.onrender.com"
    private const val STORE = "MOOSAHub"
    private const val KEY = "moosa_web_bridge_token"
    private const val ENABLED = "moosa_web_bridge_enabled"
    @Volatile private var running = false
    @Volatile private var enabled = false
    @Volatile var status = "Not connected"
        private set

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun toast(ctx: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    fun start(ctx: Context) {
        if (running || !prefs(ctx).getBoolean(ENABLED, false)) return
        val token = prefs(ctx).getString(KEY, null).orEmpty()
        if (token.isNotBlank()) {
            enabled = true
            poll(ctx.applicationContext, token)
        }
    }

    fun connect(ctx: Context, codeInput: String) {
        val code = codeInput.trim().uppercase()
        if (!code.matches(Regex("^[A-F0-9]{14}$"))) {
            toast(ctx, "Enter the 14-character code from MOOSA Web")
            return
        }
        Thread({
            try {
                val response = http("POST", "/api/bridge/claim", null,
                    JSONObject().put("code", code))
                val token = response.optString("agentToken")
                if (token.isBlank()) error("Pair code expired")
                prefs(ctx).edit().putString(KEY, token).putBoolean(ENABLED, true).apply()
                enabled = true
                status = "Paired"
                toast(ctx, "MOOSA Web Bridge connected")
                poll(ctx.applicationContext, token)
            } catch (_: Throwable) {
                status = "Pairing failed"
                toast(ctx, "Pair code failed. Generate a new code on the website.")
            }
        }, "MOOSA-Web-Claim").start()
    }

    fun disconnect(ctx: Context) {
        enabled = false
        prefs(ctx).edit().remove(KEY).putBoolean(ENABLED, false).apply()
        status = "Disconnected"
    }

    private fun http(method: String, path: String, token: String?, data: JSONObject? = null): JSONObject {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.connectTimeout = 7_000
        conn.readTimeout = if (path == "/api/bridge/next") 28_000 else 10_000
        conn.setRequestProperty("Accept", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token)
        if (data != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            if (data != null) conn.outputStream.use { out ->
                out.write(data.toString().toByteArray(Charsets.UTF_8))
            }
            val statusCode = conn.responseCode
            val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) error("HTTP " + statusCode)
            return JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun providers(): List<MainAPI> =
        apis.filter { api ->
            api.name != "MOOSA Hub" &&
                MOOSASourceTester.isEnabled(api) &&
                (api.providerType == ProviderType.DirectProvider ||
                    runCatching { api.javaClass.declaredMethods.any { it.name == "loadLinks" } }
                        .getOrDefault(false))
        }
            .distinctBy { (it.name + "|" + it.mainUrl).lowercase() }
            .sortedBy { it.name.lowercase() }
            .take(80)

    private fun poll(ctx: Context, token: String) {
        synchronized(this) {
            if (running) return
            running = true
        }
        Thread({
            try {
                var lastList = 0L
                var failures = 0
                while (enabled && prefs(ctx).getString(KEY, null) == token) {
                    try {
                        val t = System.currentTimeMillis()
                        if (t - lastList > 90_000) {
                            val list = JSONArray()
                            providers().forEach { list.put(it.name) }
                            http("POST", "/api/bridge/providers", token,
                                JSONObject().put("providers", list))
                            lastList = t
                        }
                        status = "Online: " + providers().size + " providers"
                        val task = http("GET", "/api/bridge/next", token).optJSONObject("task")
                        if (task != null) resolve(task, token)
                        failures = 0
                    } catch (_: Throwable) {
                        status = "Reconnecting"
                        failures = (failures + 1).coerceAtMost(4)
                        Thread.sleep((1000L shl failures).coerceAtMost(12_000L))
                    }
                }
            } finally {
                synchronized(this) { running = false }
                if (!enabled) status = "Disconnected"
                else start(ctx) // Reconnect after re-pairing with a new token.
            }
        }, "MOOSA-Web-Bridge").start()
    }

    private fun normalized(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun titleMatches(actual: String, wanted: List<String>, year: Int?): Boolean {
        val normalizedActual = normalized(actual)
        return wanted.any { name ->
            val w = normalized(name)
            w.length >= 3 && (
                normalizedActual == w ||
                (year != null && normalizedActual == w + " " + year) ||
                normalizedActual.startsWith(w + " ") &&
                    normalizedActual.removePrefix(w + " ").matches(Regex("\\d{4}"))
            )
        }
    }

    private suspend fun searchOne(api: MainAPI, task: JSONObject): JSONArray {
        val tv = task.optString("media") == "tv"
        val year = task.optInt("year", 0).takeIf { it > 0 }
        val season = task.optInt("season", -1)
        val episode = task.optInt("episode", -1)
        val imdb = task.optString("imdbId")
        val tmdb = task.optInt("id").toString()
        val titles = listOf(task.optString("title"), task.optString("originalTitle"))
            .filter { it.length >= 2 }.distinct()
        val results = JSONArray()
        val seen = mutableSetOf<String>()
        val subtitles = Collections.synchronizedList(mutableListOf<JSONObject>())

        for (query in titles.take(2)) {
            val matches = withTimeoutOrNull(4200L) {
                val paged = runCatching { api.search(query, 1)?.items.orEmpty() }
                    .getOrDefault(emptyList())
                if (paged.isNotEmpty()) paged
                else runCatching { api.search(query).orEmpty() }.getOrDefault(emptyList())
            }.orEmpty().filter { titleMatches(it.name, titles, year) }.take(3)
            for (hit in matches) {
                val loaded = withTimeoutOrNull(6000L) { api.load(hit.url) } ?: continue
                val matchTmdb = loaded.getTMDbId()?.toIntOrNull()
                val matchImdb = loaded.getImdbId()
                if (matchTmdb != null && matchTmdb.toString() != tmdb) continue
                if (imdb.startsWith("tt") && !matchImdb.isNullOrBlank() &&
                    !matchImdb.equals(imdb, ignoreCase = true)) continue
                if (!titleMatches(loaded.name, titles, year)) continue
                val payload = if (tv) {
                    val show = loaded as? TvSeriesLoadResponse ?: continue
                    show.episodes.firstOrNull {
                        it.season == season && it.episode == episode
                    }?.data
                } else (loaded as? MovieLoadResponse)?.dataUrl
                if (payload.isNullOrBlank()) continue

                withTimeoutOrNull(7500L) {
                    api.loadLinks(payload, false,
                        { subtitle ->
                            val language = when {
                                subtitle.lang.startsWith("ar", true) ||
                                subtitle.lang.contains("Arabic", true) ||
                                subtitle.lang.contains("عربي") -> "ar"
                                subtitle.lang.startsWith("en", true) ||
                                subtitle.lang.contains("English", true) -> "en"
                                else -> ""
                            }
                            if (language.isNotBlank() && subtitle.url.startsWith("https://")) {
                                subtitles.add(JSONObject().put("language", language)
                                    .put("url", subtitle.url)
                                    .put("label", subtitle.lang.take(90))
                                    .put("source", api.name))
                            }
                        },
                        { link: ExtractorLink ->
                            val height = MOOSAQualityPolicy.height(link)
                            if (height != null && height >= 720 &&
                                link.url.startsWith("https://", true) &&
                                !MOOSAQualityPolicy.hasForbiddenLabel(link) &&
                                seen.add(link.url)) {
                                results.put(JSONObject()
                                    .put("url", link.url)
                                    .put("quality", height)
                                    .put("name", (api.name + " • " + link.name).take(95)))
                            }
                        }
                    )
                }
                if (results.length() > 0) {
                    val subs = JSONArray(subtitles)
                    for (i in 0 until results.length()) results.getJSONObject(i)
                        .put("subtitles", subs)
                    return results
                }
            }
        }
        return results
    }

    private fun resolve(task: JSONObject, token: String) {
        val id = task.optString("requestId")
        if (id.isBlank()) return
        try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(42_000L) {
                    coroutineScope {
                        val slots = Semaphore(4)
                        providers().forEach { api ->
                            launch {
                                slots.withPermit {
                                    val chunk = try {
                                        searchOne(api, task)
                                    } catch (_: Throwable) {
                                        JSONArray()
                                    }
                                    // Send immediately when this provider completes, not
                                    // after every slower provider in the batch.
                                    if (chunk.length() > 0) {
                                        try {
                                            http("POST", "/api/bridge/report", token,
                                                JSONObject().put("requestId", id)
                                                    .put("sources", chunk)
                                                    .put("final", false))
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                http("POST", "/api/bridge/report", token,
                    JSONObject().put("requestId", id)
                        .put("sources", JSONArray()).put("final", true))
            } catch (_: Throwable) {}
        }
    }
}
