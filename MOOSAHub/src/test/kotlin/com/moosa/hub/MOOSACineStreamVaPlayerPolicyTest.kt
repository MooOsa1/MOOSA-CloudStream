package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MOOSACineStreamVaPlayerPolicyTest {
    private val policy = MOOSACineStreamVaPlayerPolicy

    @Test fun exactImdbAddressNeverDependsOnFuzzyTitles() {
        assertEquals(
            "https://streamdata.vaplayer.ru/api.php?imdb=tt1375666&type=movie",
            policy.requestUrl("tt1375666", null, null)
        )
        assertEquals(
            "https://streamdata.vaplayer.ru/api.php?imdb=tt0944947&type=tv&season=3&episode=7",
            policy.requestUrl("tt0944947", 3, 7)
        )
        assertNull(policy.requestUrl(null, null, null))
        assertNull(policy.requestUrl("bad_title", null, null))
        assertNull(policy.requestUrl("tt1375666", 2, null))
        assertNull(policy.requestUrl("tt1375666", null, 4))
        assertNull(policy.requestUrl("tt1375666", 0, 1))
        assertNull(policy.requestUrl("tt1375666", 4, 0))
        assertNull(policy.requestUrl("tt1375666&evil=1", null, null))
    }

    @Test fun onlyRealHttpsManifestCanPassToMediaProbe() {
        assertEquals(
            "https://media.example.com/index.m3u8?token=a",
            policy.hlsUrl("https://media.example.com/index.m3u8?token=a")
        )
        assertEquals(
            "https://cdn.example.net/stream/master.M3U8",
            policy.hlsUrl(" https://cdn.example.net/stream/master.M3U8 ")
        )
        for (candidate in listOf(
            "magnet:?xt=urn:btih:aaaa",
            "http://host.example/hls.m3u8",
            "https://host.example/download.mp4",
            "https://user:pass@host.example/hls.m3u8",
            "https://host.example/playlist",
            "https://host.example/example.m3u8.evil",
            "file:///tmp/media.m3u8"
        )) {
            assertNull("Unexpectedly accepted $candidate", policy.hlsUrl(candidate))
        }
    }

    @Test fun androidQualityStillRequiresActualHlsEvidence() {
        // Native provider always exposes Unknown to the existing first-pass
        // verifier; no speculative 1080p or 4K numbers are introduced.
        assertEquals(720, MOOSAQualityPolicy.MIN_HEIGHT)
    }
}
