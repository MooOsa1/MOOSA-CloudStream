package com.moosa.engineprobe

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger

/**
 * CI-only, loopback-only Android test adapter for original CloudStream .cs3 providers.
 * Do not expose the test port to the network or package this into release plugins.
 *
 * GET /v1/health -> confirms HTTP transport works
 * POST /v1/probe -> on-demand real search/load/loadLinks for one freely licensed
 * test title. Only returns count/status metadata, never URLs/cookies/headers.
 */
object MOOSAProbeHttp {
    private const val PORT = 18878
    private const val TITLE = "Big Buck Bunny"
    private const val TOKEN = "moosa-ci-local-test-token-not-for-production"
    private const val TAG = "MOOSA_HTTP_PROBE"
    @Volatile private var started = false

    @Synchronized fun start() {
        if (started) return
        started = true
        Thread({
            try {
                ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                    Log.i(TAG, "local_http=ready port=$PORT")
                    while (!Thread.currentThread().isInterrupted) {
                        val client = server.accept()
                        Thread({ handle(client) }, "MOOSA-Probe-Request").start()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "local_http=failed type=" + t.javaClass.simpleName)
            } finally {
                started = false
            }
        }, "MOOSA-Probe-Local-HTTP").start()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8))

    private fun reply(socket: Socket, status: Int, payload: JSONObject) {
        val content = payload.toString().toByteArray(StandardCharsets.UTF_8)
        val msg = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 $status $msg\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Length: " + content.size + "\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().write(header.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().write(content)
        socket.getOutputStream().flush()
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 110000
                if (!socket.inetAddress.isLoopbackAddress) {
                    reply(socket, 404, JSONObject().put("error", "not_found"))
                    return
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val request = reader.readLine()?.split(' ') ?: emptyList()
                if (request.size != 3) {
                    reply(socket, 400, JSONObject().put("error", "bad_request")); return
                }
                var authorization = ""
                var length = 0
                var ended = false
                for (i in 0 until 48) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) { ended = true; break }
                    if (line.length > 4096) {
                        reply(socket, 400, JSONObject().put("error", "header_too_large")); return
                    }
                    val split = line.indexOf(':')
                    if (split < 0) continue
                    val name = line.substring(0, split).trim().lowercase()
                    val value = line.substring(split + 1).trim()
                    if (name == "authorization") authorization = value
                    if (name == "content-length") length = value.toIntOrNull() ?: -1
                }
                if (!ended || length !in 0..1024) {
                    reply(socket, 400, JSONObject().put("error", "invalid_body_length")); return
                }
                if (request[0] == "GET" && request[1] == "/v1/health") {
                    reply(socket, 200, JSONObject()
                        .put("ready", true).put("runtime", "original-cloudstream-android-test")
                        .put("production", false)); return
                }
                if (request[0] != "POST" || request[1] != "/v1/probe") {
                    reply(socket, 404, JSONObject().put("error", "unknown_route")); return
                }
                if (!constantTimeEquals(authorization, "Bearer $TOKEN")) {
                    reply(socket, 401, JSONObject().put("error", "unauthorized")); return
                }
                val chars = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(chars, read, length - read)
                    if (n < 0) break
                    read += n
                }
                if (read != length) {
                    reply(socket, 400, JSONObject().put("error", "incomplete_body")); return
                }
                val body = try { JSONObject(String(chars)) } catch (_: Throwable) { null }
                if (body?.optString("media") != "movie" ||
                    body.optString("title") != TITLE ||
                    body.optString("purpose") != "licensed-emulator-test") {
                    reply(socket, 400, JSONObject().put("error", "only_licensed_test_title")); return
                }
                Log.i(TAG, "request=accepted test=licensed_movie")
                val report = runLicensedProbe()
                reply(socket, 200, report)
            } catch (t: Throwable) {
                Log.e(TAG, "local_http=request_failed type=" + t.javaClass.simpleName)
                runCatching { reply(socket, 500, JSONObject().put("error", "probe_failed")) }
            }
        }
    }

    private fun normal(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun runLicensedProbe(): JSONObject = runBlocking {
        val requested = listOf("CineStream", "StreamPlay")
        val deadline = System.currentTimeMillis() + 40000L
        var available: Map<String, MainAPI> = emptyMap()
        while (System.currentTimeMillis() < deadline) {
            available = apis.filter { it.name in requested }.associateBy { it.name }
            if (available.keys.containsAll(requested)) break
            Thread.sleep(700)
        }
        val data = JSONArray()
        for (name in requested) {
            val api = available[name]
            if (api == null) {
                data.put(JSONObject().put("provider", name).put("search", "plugin_not_registered"))
                continue
            }
            data.put(probeOne(api, name))
        }
        Log.i(TAG, "request=finished providers=" + data.length())
        JSONObject().put("sample", TITLE)
            .put("rights", "CC-BY (Blender Foundation)")
            .put("production", false)
            .put("sources", JSONArray()) // Never return stream URLs in this prototype
            .put("providers", data)
    }

    private suspend fun probeOne(api: MainAPI, name: String): JSONObject {
        val summary = JSONObject().put("provider", name)
        val candidates = withTimeoutOrNull(13000L) {
            try {
                api.search(TITLE, 1)?.items.orEmpty().ifEmpty { api.search(TITLE).orEmpty() }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                summary.put("search", "error")
                emptyList()
            }
        }
        if (candidates == null) {
            summary.put("search", "timeout")
            return summary
        }
        val exact = candidates.filter { normal(it.name) == normal(TITLE) }
        summary.put("search", "returned")
            .put("searchResults", candidates.size).put("exactMatches", exact.size)
        val match = exact.firstOrNull() ?: return summary.put("load", "skipped_no_exact_match")
        val loaded = withTimeoutOrNull(13000L) {
            try { api.load(match.url) } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
        }
        if (loaded == null) return summary.put("load", "null_or_timeout")
        if (normal(loaded.name) != normal(TITLE)) return summary.put("load", "rejected_mismatch")
        val movieData = (loaded as? MovieLoadResponse)?.dataUrl
        if (movieData.isNullOrBlank()) return summary.put("load", "no_movie_payload")
        summary.put("load", "movie_payload")

        val total = AtomicInteger(0)
        val https = AtomicInteger(0)
        val hd = AtomicInteger(0)
        val hls = AtomicInteger(0)
        val referer = AtomicInteger(0)
        val headers = AtomicInteger(0)
        val subs = AtomicInteger(0)
        val completed = withTimeoutOrNull(18000L) {
            try {
                api.loadLinks(movieData, false,
                    { subs.incrementAndGet() },
                    { link ->
                        total.incrementAndGet()
                        if (link.url.startsWith("https://", true)) https.incrementAndGet()
                        if (link.quality >= 720) hd.incrementAndGet()
                        if (link.url.substringBefore('?').endsWith(".m3u8", true)) hls.incrementAndGet()
                        if (link.referer.isNotBlank()) referer.incrementAndGet()
                        if (link.headers.isNotEmpty()) headers.incrementAndGet()
                    }
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                false
            }
        }
        summary.put("loadLinks", if (completed == null) "timeout" else "returned")
            .put("success", completed == true)
            .put("links", total.get()).put("https", https.get())
            .put("hd720Plus", hd.get()).put("hls", hls.get())
            .put("refererCount", referer.get()).put("headersCount", headers.get())
            .put("subtitles", subs.get())
        return summary
    }
}
