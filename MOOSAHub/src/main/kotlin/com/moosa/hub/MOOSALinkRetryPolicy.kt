package com.moosa.hub

/**
 * A second GET 300 ms later cannot turn an HTML landing page, invalid media
 * signature or permanent HTTP denial into a playable stream.
 *
 * Only transient transport failures earn another attempt, which reduces
 * needless waits when a provider returns several dead or blocked links.
 * A server returning HTTP 429/5xx is retried once, never indefinitely.
 */
internal object MOOSALinkRetryPolicy {
    private val httpCode = Regex("""\bHTTP\s+(\d{3})\b""")

    fun shouldRetry(reason: String): Boolean {
        val value = reason.trim()
        if (value == "Media check timed out") return true

        val status = httpCode.find(value)?.groupValues
            ?.getOrNull(1)?.toIntOrNull()
        if (status != null) {
            return status == 408 || status == 425 || status == 429 ||
                status in 500..599
        }

        if (value.startsWith("Request error:")) {
            val type = value.substringAfter(':').trim()
            return type in setOf(
                "SocketTimeoutException",
                "ConnectTimeoutException",
                "ConnectException",
                "SocketException",
                "EOFException",
                "InterruptedIOException",
                "IOException",
                "TimeoutException"
            )
        }

        // These are conclusive checks: HTML/JSON, empty response, bad MP4,
        // invalid HLS/DASH manifests, incompatible or absent byte ranges.
        return false
    }
}
