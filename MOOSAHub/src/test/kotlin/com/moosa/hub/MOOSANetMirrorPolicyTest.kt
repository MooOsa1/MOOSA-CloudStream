package com.moosa.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class MOOSANetMirrorPolicyTest {
    private val bcs = MOOSAHubProvider.HubPlaybackRequest(
        title = "Better Call Saul", year = 2015, tmdbId = 60059,
        isTv = true, originalLanguage = "en", season = 2, episode = 1
    )

    @Test fun exactIdAndEpisodeOnly() {
        assertEquals(
            "https://net27.cc/api/embed-tmdb/60059?type=tv&se=2&ep=1",
            MOOSANetMirrorPolicy.apiUrl(60059, true, 2, 1)
        )
        assertNull(MOOSANetMirrorPolicy.apiUrl(60059, true, 2, null))
        assertEquals("https://net27.cc/api/embed-tmdb/238",
            MOOSANetMirrorPolicy.apiUrl(238, false, null, null))
        val result = JSONObject("""{
            "ok":true,"tmdbId":60059,"title":"Better Call Saul","type":"tv",
            "year":"2015","currentSeason":2,"currentEpisode":1
        }""")
        assertTrue(MOOSANetMirrorPolicy.validIdentity(result, bcs))
        result.put("currentEpisode", 2)
        assertFalse(MOOSANetMirrorPolicy.validIdentity(result, bcs))
        result.put("currentEpisode", 1).put("title", "Breaking Bad")
        assertFalse(MOOSANetMirrorPolicy.validIdentity(result, bcs))
    }

    @Test fun onlyKnownHdSignedFileHosts() {
        val body = JSONObject("""{"streams":[
            {"resolution":1080,"size":564551191,"url":"https://bcdnxw.hakunaymatata.com/v.mp4?sign=a"},
            {"resolution":720,"size":341723811,"url":"https://bcdnxw.hakunaymatata.com/720.mp4?sign=a"},
            {"resolution":360,"url":"https://bcdnxw.hakunaymatata.com/sd.mp4"},
            {"resolution":1080,"url":"https://hakunaymatata.com.evil.test/forged.mp4"}
        ]}""")
        val results = MOOSANetMirrorPolicy.directCandidates(body)
        assertEquals(listOf(1080, 720), results.map { it.height })
    }

    @Test fun refuseAudioOnlyAndAv1Mp4() {
        fun sample(handler: String, codec: String): ByteArray = ByteArray(100).apply {
            "ftyp".toByteArray().copyInto(this, 4)
            handler.toByteArray().copyInto(this, 40)
            codec.toByteArray().copyInto(this, 65)
        }
        assertTrue(MOOSANetMirrorPolicy.compatibleMp4(sample("vide", "avc1")))
        assertTrue(MOOSANetMirrorPolicy.compatibleMp4(sample("vide", "hvc1")))
        assertFalse(MOOSANetMirrorPolicy.compatibleMp4(sample("soun", "mp4a")))
        assertFalse(MOOSANetMirrorPolicy.compatibleMp4(sample("vide", "av01")))
    }
}
