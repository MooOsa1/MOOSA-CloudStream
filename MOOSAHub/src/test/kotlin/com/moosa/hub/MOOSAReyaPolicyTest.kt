package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAReyaPolicyTest {
    private fun box(name: String, payload: ByteArray): ByteArray {
        val n = payload.size + 8
        return byteArrayOf(
            (n ushr 24).toByte(), (n ushr 16).toByte(),
            (n ushr 8).toByte(), n.toByte()
        ) + name.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun track(
        video: Boolean, width: Int = 3840, height: Int = 2160,
        codec: String = "hvc1", language: String = "und"
    ): ByteArray {
        val tkhd = ByteArray(80)
        fun fixed(pos: Int, value: Int) {
            val n = value shl 16
            for (i in 0..3) tkhd[pos + i] = (n ushr (24 - 8 * i)).toByte()
        }
        fixed(72, width)
        fixed(76, height)
        val hdlr = ByteArray(12)
        (if (video) "vide" else "soun")
            .toByteArray().copyInto(hdlr, 8)
        val mdhd = ByteArray(24)
        val n = language.fold(0) { acc, c -> (acc shl 5) or (c.code - 96) }
        mdhd[20] = (n ushr 8).toByte()
        mdhd[21] = n.toByte()
        val sample = box(if (video) codec else "mp4a", ByteArray(8))
        val stsd = box("stsd", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + sample)
        val media = box("mdia",
            box("mdhd", mdhd) + box("hdlr", hdlr) +
                box("minf", box("stbl", stsd))
        )
        return box("trak", box("tkhd", tkhd) + media)
    }

    private fun init(width: Int, height: Int, codec: String, audio: String = "eng"): ByteArray =
        box("ftyp", byteArrayOf(0, 0, 0, 0)) +
            box("moov", track(true, width, height, codec) +
                track(false, codec = "mp4a", language = audio))

    @Test fun confirmsReal4KHEVCAndMuxedOriginalAudio() {
        val tracks = MOOSAReyaPolicy.trackInfo(init(3840, 2160, "hvc1", "eng"))
        assertNotNull(tracks)
        assertEquals(3840, tracks!!.videoWidth)
        assertEquals(2160, tracks.videoHeight)
        assertEquals("hvc1", tracks.videoCodec)
        assertTrue(tracks.audioPresent)
        assertEquals(setOf("eng"), tracks.languages)
        assertTrue(MOOSAAudioTrackPolicy.accept("en", tracks.languages))
        assertFalse(MOOSAAudioTrackPolicy.accept("ko", tracks.languages))
    }

    @Test fun excludesUnsupported4KCodecAndMislabelled1080() {
        assertNull(MOOSAReyaPolicy.trackInfo(init(3840, 2160, "av01")))
        assertNull(MOOSAReyaPolicy.trackInfo(init(3840, 2160, "avc1")))
        assertNotNull(MOOSAReyaPolicy.trackInfo(init(1920, 1080, "avc1")))
    }

    @Test fun originalKoreanRequiredNotHindiOnly() {
        val hindi = MOOSAReyaPolicy.trackInfo(init(3840, 2160, "hvc1", "hin"))
        assertNotNull(hindi)
        assertFalse(MOOSAAudioTrackPolicy.accept("ko", hindi!!.languages))
        assertTrue(MOOSAAudioTrackPolicy.accept("hi", hindi.languages))
    }

    @Test fun exactEpisodeMasterAndChildUrl() {
        val master = "https://noisy-king-5904.streamed-7.workers.dev/a/master.m3u8"
        val playlist = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160,CODECS="hvc1.1.6.L123,mp4a.40.2"
2160/playlist.m3u8
#EXT-X-STREAM-INF:RESOLUTION=1920x1080,BANDWIDTH=2000000,CODECS="avc1.640028,mp4a.40.2"
1080/playlist.m3u8
#EXT-X-STREAM-INF:RESOLUTION=1280x720,BANDWIDTH=1000000,CODECS="mp4a.40.2"
audioonly.m3u8
"""
        val list = MOOSAReyaPolicy.variants(master, playlist)
        assertEquals(listOf(2160, 1080), list.map { it.height })
        assertTrue(list[0].url.contains("/2160/playlist.m3u8"))
        assertNull(MOOSAReyaPolicy.childUrl(master, "https://127.0.0.1/secret"))
        assertNull(MOOSAReyaPolicy.childUrl(master, "http://bad.test/video"))
        assertNull(MOOSAReyaPolicy.apiUrl(null, true, 2, 3))
        assertNull(MOOSAReyaPolicy.apiUrl(60059, true, null, 3))
        assertTrue(MOOSAReyaPolicy.apiUrl(60059, true, 2, 3)!!
            .contains("type=tv&season=2&episode=3"))
    }

    @Test fun fragmentedMp4RequiresMappedInitAndValidFragment() {
        val playlist = """#EXTM3U
#EXT-X-VERSION:7
#EXT-X-MAP:URI="init.mp4"
#EXTINF:4.0,
seg001.m4s
"""
        val u = "https://noisy-king-5904.streamed-7.workers.dev/2160/playlist.m3u8"
        val p = MOOSAReyaPolicy.mediaParts(u, playlist)
        assertNotNull(p)
        assertTrue(p!!.init.endsWith("/2160/init.mp4"))
        assertTrue(p.segment.endsWith("/2160/seg001.m4s"))
        assertNull(MOOSAReyaPolicy.mediaParts(u, playlist.replace(
            "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\""
        )))
        assertTrue(MOOSAReyaPolicy.fragmentLooksPlayable(
            box("moof", ByteArray(50)) + box("mdat", ByteArray(160))
        ))
        assertFalse(MOOSAReyaPolicy.fragmentLooksPlayable(
            "<html>Error</html>".toByteArray()
        ))
    }
}
