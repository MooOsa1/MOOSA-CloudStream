package com.moosa.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSALinkRetryPolicyTest {
    @Test
    fun rejectsPermanentFailuresWithoutWastingAnotherProbe() {
        val permanent = listOf(
            "Invalid URL",
            "No valid HLS playlist",
            "Invalid DASH / HTTP 200",
            "Non-video Content-Type: text/html",
            "Non-video Content-Type: application/json",
            "Response has no recognized video signature",
            "Empty media response",
            "HLS HTTP 403",
            "Media HTTP 404",
            "Media HTTP 410",
            "Downet ignored first byte-range request",
            "Downet seek failed HTTP 200; range=",
            "Request error: SSLHandshakeException",
            "Request error: UnknownHostException",
            "Request error: CertificateException"
        )
        for (reason in permanent) {
            assertFalse("Unexpected retry for $reason",
                MOOSALinkRetryPolicy.shouldRetry(reason))
        }
    }

    @Test
    fun transientNetworkOrUpstreamFailuresGetOneSecondChance() {
        val transient = listOf(
            "Media check timed out",
            "HLS HTTP 408",
            "HLS HTTP 429",
            "Media HTTP 500",
            "Media HTTP 502",
            "Invalid DASH / HTTP 503",
            "Downet seek failed HTTP 503; range=",
            "Request error: SocketTimeoutException",
            "Request error: ConnectException",
            "Request error: SocketException",
            "Request error: EOFException"
        )
        for (reason in transient) {
            assertTrue("Expected one retry for $reason",
                MOOSALinkRetryPolicy.shouldRetry(reason))
        }
    }
}
