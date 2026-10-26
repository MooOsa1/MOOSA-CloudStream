package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAAnonymousHttpPolicyTest {
    @Test fun exactImdbEpisodeOnly() {
        assertEquals(
            "https://doom-addon.zxflix.com/stream/series/tt3032476:2:10.json",
            MOOSAAnonymousHttpPolicy.streamApi(
                "https://doom-addon.zxflix.com", "tt3032476", 2, 10, true)
        )
        assertEquals(
            "https://plaviohd.vercel.app/stream/movie/tt15398776.json",
            MOOSAAnonymousHttpPolicy.streamApi(
                "https://plaviohd.vercel.app", "tt15398776", null, null, false)
        )
        assertNull(MOOSAAnonymousHttpPolicy.streamApi(
            "https://plaviohd.vercel.app", "tt3032476", 2, null, true))
        assertNull(MOOSAAnonymousHttpPolicy.streamApi(
            "https://plaviohd.vercel.app", "not-imdb", null, null, false))
    }

    @Test fun disallowsPrivateUrlsAndP2P() {
        assertNull(MOOSAAnonymousHttpPolicy.safeMediaUrl("http://cdn.example.com/video.mkv"))
        assertNull(MOOSAAnonymousHttpPolicy.safeMediaUrl("https://127.0.0.1/secret"))
        assertNull(MOOSAAnonymousHttpPolicy.safeMediaUrl("https://192.168.1.5/movie.mkv"))
        assertNull(MOOSAAnonymousHttpPolicy.safeMediaUrl("https://server.internal/movie.mkv"))
        assertNull(MOOSAAnonymousHttpPolicy.safeMediaUrl("https://user:pass@cdn.example.com/file"))
        assertNull(MOOSAAnonymousHttpPolicy.safeMediaUrl("magnet:?xt=urn:btih:abc"))
        assertNull(MOOSAAnonymousHttpPolicy.safeMediaUrl("https://cdn.example.com/movie.torrent"))
        assertTrue(MOOSAAnonymousHttpPolicy.acceptedCandidate(
            "Movie 2160p", "Fast HTTP", null, null))
        assertFalse(MOOSAAnonymousHttpPolicy.acceptedCandidate(
            "Movie 2160p", "Torrentio", null, null))
        assertFalse(MOOSAAnonymousHttpPolicy.acceptedCandidate(
            "Movie 4K", "Direct", "abcdef123", null))
        assertFalse(MOOSAAnonymousHttpPolicy.acceptedCandidate(
            "Movie 4K", "Direct", null, 0))
        assertEquals("https://cdn.example.com/movie.mkv",
            MOOSAAnonymousHttpPolicy.safeMediaUrl("https://cdn.example.com/movie.mkv"))
    }

    @Test fun qualityRequiresMediaMetadataNotLabels() {
        assertNull(MOOSAAnonymousHttpPolicy.hlsHeight("#EXTM3U\n#EXTINF:4,\nvideo.ts"))
        assertEquals(2160, MOOSAAnonymousHttpPolicy.hlsHeight(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160\n" +
            "video.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080\nhd.m3u8"))
        assertEquals(2160, MOOSAAnonymousHttpPolicy.videoHeight(3840, 1600))
        assertEquals(1080, MOOSAAnonymousHttpPolicy.videoHeight(1920, 800))
        assertNull(MOOSAAnonymousHttpPolicy.videoHeight(640, 360))
        assertEquals("Pixeldrain", MOOSAAnonymousHttpPolicy.sourceName("Pixeldrain 2160p UHD"))
    }
}
