package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAVixSrcPolicyTest {
    @Test fun requiresTmdbIdAndExactEpisode() {
        assertEquals(
            "https://vixsrc.to/api/tv/60059/2/1",
            MOOSAVixSrcPolicy.apiUrl(60059, true, 2, 1)
        )
        assertEquals(
            "https://vixsrc.to/api/movie/238",
            MOOSAVixSrcPolicy.apiUrl(238, false, null, null)
        )
        assertNull(MOOSAVixSrcPolicy.apiUrl(60059, true, 2, null))
        assertNull(MOOSAVixSrcPolicy.apiUrl(null, false, null, null))
        assertNull(MOOSAVixSrcPolicy.apiUrl(0, false, null, null))
    }

    @Test fun onlyVixSrcHttpsAndValidToken() {
        assertEquals(
            "https://vixsrc.to/embed/190508?token=abc",
            MOOSAVixSrcPolicy.safeEmbed("/embed/190508?token=abc")
        )
        assertNull(MOOSAVixSrcPolicy.safeEmbed("//evil.com/embed/190508"))
        assertNull(MOOSAVixSrcPolicy.safeEmbed("/embed/../evil"))
        assertNull(MOOSAVixSrcPolicy.safePlaylist("https://vixsrc.to.evil.test/playlist/1"))
        assertNull(MOOSAVixSrcPolicy.safePlaylist("http://vixsrc.to/playlist/1"))
        assertEquals(
            "https://vixsrc.to/playlist/190508",
            MOOSAVixSrcPolicy.safePlaylist("\\/\\/vixsrc.to\\/playlist\\/190508")
        )
        assertEquals(
            "https://vixsrc.to/playlist/190508",
            MOOSAVixSrcPolicy.safePlaylist("""\/\/vixsrc.to\/playlist\/190508""")
        )

        val expires = System.currentTimeMillis() / 1000 + 1800
        val embed = """var source = {"token":"abcdef0123456789","expires":"$expires","url":"\\/\\/vixsrc.to\\/playlist\\/190508"};"""
        val data = MOOSAVixSrcPolicy.tokenFromHtml(embed)
        assertNotNull(data)
        assertTrue(MOOSAVixSrcPolicy.masterUrl(data!!).contains("expires=$expires&h=1"))
        assertTrue(MOOSAVixSrcPolicy.masterUrl(data).endsWith("&lang=en"))
        assertTrue(MOOSAVixSrcPolicy.masterUrl(data, "hi").endsWith("&lang=hi"))
        assertNull(MOOSAVixSrcPolicy.tokenFromHtml(embed.replace(expires.toString(), "10")))
    }

    @Test fun prefersOriginalAudioInsteadOfItalianDefault() {
        val manifest = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Italian",DEFAULT=YES,LANGUAGE="ita",URI="ita.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=NO,LANGUAGE="eng",URI="eng.m3u8"
"""
        assertEquals("en", MOOSAVixSrcPolicy.preferredAudioLanguage(null))
        assertTrue(MOOSAVixSrcPolicy.hasOriginalAudio(manifest, "en"))
        assertTrue(MOOSAVixSrcPolicy.hasOriginalAudio(manifest, "it"))
        assertTrue(!MOOSAVixSrcPolicy.hasOriginalAudio(manifest, "hi"))
    }

    @Test fun hlsQualityIsFromRealRenditionList() {
        val url = "https://vixsrc.to/playlist/190508?token=abcdef0123456789&expires=123456"
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=854x480
480/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
720/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
1080/index.m3u8
"""
        val best = MOOSAVixSrcPolicy.highestVariant(url, master)
        assertNotNull(best)
        assertEquals(1080, best!!.height)
        assertEquals("https://vixsrc.to/playlist/1080/index.m3u8", best.url)
        assertNull(MOOSAVixSrcPolicy.highestVariant(url, "<html>Not a playlist</html>"))
        assertNull(MOOSAVixSrcPolicy.highestVariant(url, "#EXTM3U\n#EXTINF:1\n360.ts"))
    }

    @Test fun fallsBackTo720When1080IsAudioOnlyOrAv1() {
        val masterUrl = "https://vixsrc.to/playlist/episode?token=abc"
        val playlist = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480,CODECS="avc1.42e01e,mp4a.40.2"
sd/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,CODECS="mp4a.40.2"
audio-only/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="av01.0.08M.08,mp4a.40.2"
av1/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
hd/index.m3u8
"""
        val renditions = MOOSAVixSrcPolicy.hdVariants(masterUrl, playlist)
        assertEquals(1, renditions.size)
        assertEquals(720, renditions.first().height)
        assertTrue(renditions.first().declaredVideoCodec)
        assertTrue(renditions.first().url.endsWith("/playlist/hd/index.m3u8"))
    }

    @Test fun reports1080And720CandidatesWithoutDowngradingToSd() {
        val url = "https://vixsrc.to/playlist/episode?token=abc"
        val playlist = """#EXTM3U
#EXT-X-STREAM-INF:RESOLUTION=1280x720,CODECS="avc1.4d401f"
720/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=1920x1080,CODECS="avc1.640028"
1080/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=854x480,CODECS="avc1.42e01e"
480/index.m3u8
"""
        assertEquals(listOf(1080, 720), MOOSAVixSrcPolicy.hdVariants(url, playlist)
            .map { it.height })
    }

    @Test fun checksMediaPlaylistBeforeEmitting() {
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n" +
            "#EXTINF:6,\nsegments/00001.ts\n#EXTINF:6,\nsegments/00002.ts"
        assertEquals(
            "https://vixsrc.to/playlist/190508/segments/00001.ts",
            MOOSAVixSrcPolicy.firstSegment(
                "https://vixsrc.to/playlist/190508/index.m3u8", playlist
            )
        )
        assertNull(MOOSAVixSrcPolicy.firstSegment(
            "https://vixsrc.to/playlist/190508/index.m3u8", "#EXTM3U\n#EXT-X-ENDLIST"
        ))
    }
}
