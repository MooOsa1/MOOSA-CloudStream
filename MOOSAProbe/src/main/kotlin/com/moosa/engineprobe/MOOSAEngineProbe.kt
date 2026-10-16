package com.moosa.engineprobe

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only extension. Big Buck Bunny is a CC-BY licensed Blender open film.
 * Only reports stage/count/status. Never logs URLs, headers or credentials.
 * No video download or playback.
 */
@CloudstreamPlugin
class MOOSAEngineProbe : Plugin() {
    private val tag = "MOOSA_ENGINE_PROBE"
    private val title = "Big Buck Bunny"

    override fun load(context: Context) {
        Thread({
            runCatching { runProbe() }.onFailure {
                Log.e(tag, "stage=init outcome=error class=" + it.javaClass.simpleName)
            }
        }, "MOOSA-Engine-Probe").start()
    }

    private fun normalized(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
            .replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun runProbe() = runBlocking {
        val wanted = listOf("CineStream", "StreamPlay")
        val deadline = System.currentTimeMillis() + 60000L
        var available: Map<String, MainAPI> = emptyMap()
        while (System.currentTimeMillis() < deadline) {
            available = apis.filter { it.name in wanted }.associateBy { it.name }
            if (available.keys.containsAll(wanted)) break
            Thread.sleep(1000)
        }
        Log.i(tag, "stage=discovery registered=" + available.keys.sorted().joinToString(","))
        for (name in wanted) {
            val api = available[name]
            if (api == null) {
                Log.w(tag, "provider=" + name + " stage=discovery outcome=missing")
                continue
            }
            try {
                runSingle(api, name)
            } catch (err: Throwable) {
                Log.w(tag, "provider=" + name + " stage=unexpected outcome=error class=" + err.javaClass.simpleName)
            }
        }
        Log.i(tag, "stage=complete result=finished")
    }

    private suspend fun runSingle(api: MainAPI, provider: String) {
        Log.i(tag, "provider=" + provider + " stage=search status=start")
        val results = withTimeoutOrNull(15000L) {
            try {
                api.search(title, 1)?.items.orEmpty().ifEmpty { api.search(title).orEmpty() }
            } catch (err: Throwable) {
                Log.w(tag, "provider=" + provider + " stage=search outcome=error class=" + err.javaClass.simpleName)
                emptyList()
            }
        }
        if (results == null) {
            Log.i(tag, "provider=" + provider + " stage=search outcome=timeout")
            return
        }
        val matching = results.filter { normalized(it.name) == normalized(title) }
        Log.i(tag, "provider=" + provider + " stage=search outcome=returned count=" + results.size + " exact=" + matching.size)
        val first = matching.firstOrNull()
        if (first == null) {
            Log.i(tag, "provider=" + provider + " stage=load outcome=skipped reason=no_exact_licensed_title")
            return
        }
        val loaded = withTimeoutOrNull(15000L) {
            try { api.load(first.url) }
            catch (err: Throwable) {
                Log.w(tag, "provider=" + provider + " stage=load outcome=error class=" + err.javaClass.simpleName)
                null
            }
        }
        if (loaded == null) {
            Log.i(tag, "provider=" + provider + " stage=load outcome=null_or_timeout")
            return
        }
        if (normalized(loaded.name) != normalized(title)) {
            Log.w(tag, "provider=" + provider + " stage=load outcome=rejected reason=title_mismatch")
            return
        }
        val payload = (loaded as? MovieLoadResponse)?.dataUrl
        Log.i(tag, "provider=" + provider + " stage=load outcome=returned kind=" + loaded.javaClass.simpleName + " moviePayload=" + !payload.isNullOrBlank())
        if (payload.isNullOrBlank()) {
            Log.i(tag, "provider=" + provider + " stage=loadLinks outcome=skipped reason=no_movie_payload")
            return
        }
        val streams = AtomicInteger(0)
        val subtitles = AtomicInteger(0)
        val https = AtomicInteger(0)
        val hd = AtomicInteger(0)
        val hls = AtomicInteger(0)
        val mp4 = AtomicInteger(0)
        val withReferer = AtomicInteger(0)
        val withHeaders = AtomicInteger(0)
        val arabicSubtitles = AtomicInteger(0)
        val englishSubtitles = AtomicInteger(0)
        val extracted = withTimeoutOrNull(18000L) {
            try {
                api.loadLinks(payload, false,
                    { subtitle ->
                        subtitles.incrementAndGet()
                        if (subtitle.lang.startsWith("ar", ignoreCase = true) ||
                            subtitle.lang.contains("arabic", ignoreCase = true))
                            arabicSubtitles.incrementAndGet()
                        if (subtitle.lang.startsWith("en", ignoreCase = true) ||
                            subtitle.lang.contains("english", ignoreCase = true))
                            englishSubtitles.incrementAndGet()
                    },
                    { link ->
                        streams.incrementAndGet()
                        if (link.url.startsWith("https://", ignoreCase = true)) https.incrementAndGet()
                        if (link.quality >= 720) hd.incrementAndGet()
                        val bare = link.url.substringBefore('?').substringBefore('#').lowercase()
                        if (bare.endsWith(".m3u8")) hls.incrementAndGet()
                        if (bare.endsWith(".mp4")) mp4.incrementAndGet()
                        if (link.referer.isNotBlank()) withReferer.incrementAndGet()
                        if (link.headers.isNotEmpty()) withHeaders.incrementAndGet()
                    }
                )
            } catch (err: Throwable) {
                Log.w(tag, "provider=" + provider + " stage=loadLinks outcome=error class=" + err.javaClass.simpleName)
                false
            }
        }
        Log.i(tag, "provider=" + provider + " stage=loadLinks outcome=" +
            (if (extracted == null) "timeout" else "returned") + " success=" +
            (extracted == true) + " links=" + streams.get() + " subtitles=" + subtitles.get() +
            " https=" + https.get() + " hd720plus=" + hd.get() +
            " hls=" + hls.get() + " mp4=" + mp4.get() +
            " referer_required=" + withReferer.get() + " custom_headers=" + withHeaders.get() +
            " ar_subtitles=" + arabicSubtitles.get() + " en_subtitles=" + englishSubtitles.get())
    }
}
