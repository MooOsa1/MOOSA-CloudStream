package com.moosa.hub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * A signed CDN can return 206 for a tiny, bounded Range yet reject the
 * unbounded GET that Android's video player sends when opening a stream.
 * The tiny MKV header test alone must never classify such links as playable.
 *
 * Probe the actual initial GET and close it after its first byte; never load
 * the multi-gigabyte body into memory or leave an open download behind.
 */
internal object MOOSADirectStreamProbe {
    fun requiresStartProbe(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host.endsWith(".workers.dev") || host.endsWith(".r2.dev") ||
            host.endsWith(".cloudflarestorage.com")
    }

    fun acceptsStartResponse(status: Int, contentType: String?): Boolean =
        (status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) &&
            !contentType.orEmpty().startsWith("text/html", ignoreCase = true) &&
            !contentType.orEmpty().startsWith("application/json", ignoreCase = true)

    suspend fun acceptsUnrangedGet(
        url: String, referer: String, userAgent: String
    ): Boolean {
        if (!requiresStartProbe(url)) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 4_000
                    readTimeout = 4_000
                    setRequestProperty("User-Agent", userAgent)
                    if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                    // Deliberately omit Range: this must match the player's
                    // first request, not the successful bytes=0-131071 probe.
                }
                try {
                    if (!acceptsStartResponse(connection.responseCode, connection.contentType)) {
                        false
                    } else {
                        connection.inputStream.use { input -> input.read() >= 0 }
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
        }
    }
}
