package com.moosa.hub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MOOSAReyaPolicyTest {
    private val masterUrl = "https://noisy-king-5904.streamed-7.workers.dev/master.m3u8"

    @Test fun apiRequiresExactTmdbAndTvEpisode() {
        assertEquals(
            "https://vidnst.reyamika.eu.cc/api/stream?tmdbId=60059&type=tv&season=2&episode=3",
            MOOSAReyaPolicy.apiUrl(60059, true, 2, 3)
        )
        assertNull(MOOSAReyaPolicy.apiUrl(null, true, 2, 3))
        assertNull(MOOSAReyaPolicy.apiUrl(60059, true, 2, null))
        val response = JSONObject(
            """{"tmdbId":60059,"mediaType":"tv","season":2,"episode":3,"streams":[]}"""
        )
        assertTrue(MOOSAReyaPolicy.identity(response, 60059, true, 2, 3))
        assertFalse(MOOSAReyaPolicy.identity(response, 60059, true, 2, 2))
        assertFalse(MOOSAReyaPolicy.identity(response, 60059, false, null, null))
        assertFalse(MOOSAReyaPolicy.identity(response, 60058, true, 2, 3))
    }

    @Test fun manifestHasRealHdVariantsAndRejectsAudioOnly() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=10000000,RESOLUTION=3840x2160
2160p/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
1080p/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=3840x2160,CODECS="mp4a.40.2"
audio-only/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=3840x2160,CODECS="av01.0.08M.08,mp4a.40.2"
av1/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=854x480
sd/index.m3u8
"""
        val choices = MOOSAReyaPolicy.variants(masterUrl, master)
        assertEquals(listOf(2160, 1080), choices.map { it.height })
        assertTrue(choices[0].url.endsWith("/2160p/index.m3u8"))
        assertNull(MOOSAReyaPolicy.safeMedia("http://127.0.0.1/video.m3u8"))
        assertNull(MOOSAReyaPolicy.safeMedia("https://example.com/file.m3u8"))
        assertNull(MOOSAReyaPolicy.safeMedia("//evil.com/a", masterUrl))
    }

    @Test fun mediaPlaylistMustHaveMapAndRealSegments() {
        val playlist = """#EXTM3U
#EXT-X-VERSION:7
#EXT-X-MAP:URI="init.mp4"
#EXTINF:5.000,
seg0001.m4s
"""
        val url = masterUrl.replace("master", "1080/index")
        val result = MOOSAReyaPolicy.media(url, playlist)
        assertNotNull(result)
        assertTrue(result!!.init.endsWith("/1080/init.mp4"))
        assertTrue(result.segment.endsWith("/1080/seg0001.m4s"))
        assertNull(MOOSAReyaPolicy.media(url, playlist.replace(
            "#EXT-X-VERSION:7", "#EXT-X-KEY:METHOD=AES-128"
        )))
        assertNull(MOOSAReyaPolicy.media(url, playlist.replace(
            "#EXT-X-MAP:URI=\"init.mp4\"", ""
        )))
    }

    private fun box(name: String, body: ByteArray): ByteArray {
        val size = body.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte()
        ) + name.toByteArray(Charsets.US_ASCII) + body
    }
    private fun mediaTrack(kind: String, width: Int = 0, height: Int = 0): ByteArray {
        val tkhd = ByteArray(84)
        val w = width shl 16
        val h = height shl 16
        for (i in 0..3) {
            tkhd[tkhd.size - 8 + i] = (w ushr (24 - 8 * i)).toByte()
            tkhd[tkhd.size - 4 + i] = (h ushr (24 - 8 * i)).toByte()
        }
        val hdlr = ByteArray(12)
        kind.toByteArray(Charsets.US_ASCII).copyInto(hdlr, 8)
        val mdhd = ByteArray(24)
        val lang = "eng".fold(0) { x, c -> (x shl 5) or (c.code - 96) }
        mdhd[20] = (lang ushr 8).toByte()
        mdhd[21] = lang.toByte()
        val codec = if (kind == "vide")
            box("stsd", "avc1".toByteArray()) else ByteArray(0)
        return box("trak", box("tkhd", tkhd) +
            box("mdia", box("mdhd", mdhd) + box("hdlr", hdlr) + codec))
    }

    @Test fun inspectRealTracksRejectsAudioOnlyAndBadDimensions() {
        val good = box("ftyp", "isom".toByteArray()) +
            box("moov",mediaTrack("vide",3840,2160) + mediaTrack("soun"))
        val info = MOOSAReyaPolicy.inspectInit(good)
        assertNotNull(info)
        assertEquals(3840, info!!.width)
        assertEquals(2160, info.height)
        assertEquals("AVC", info.codec)
        assertTrue(info.audioPresent)
        assertEquals(setOf("eng"), info.audioLanguages)
        assertNull(MOOSAReyaPolicy.inspectInit(box("ftyp", "isom".toByteArray()) +
            box("moov", mediaTrack("soun"))))
        val moof = box("moof", ByteArray(6000)) + box("mdat",ByteArray(32))
        assertTrue(MOOSAReyaPolicy.isFmp4Fragment(moof))
        assertFalse(MOOSAReyaPolicy.isFmp4Fragment(good))
    }
}
