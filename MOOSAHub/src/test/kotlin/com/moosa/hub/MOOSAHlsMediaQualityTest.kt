package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAHlsMediaQualityTest {
    private fun fromHex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun tsWithSps(hexSps: String): ByteArray {
        // Synthetic MPEG-TS packets containing an H264 Annex-B SPS; all
        // content is test-generated, not third-party media.
        val sample = ByteArray(188 * 3) { 0xff.toByte() }
        for (i in 0..2) {
            val at = 188 * i
            sample[at] = 0x47
            sample[at + 1] = 0x01
            sample[at + 2] = 0x00
            sample[at + 3] = 0x10
        }
        val prefix = byteArrayOf(0, 0, 0, 1)
        val data = prefix + fromHex(hexSps)
        data.copyInto(sample, destinationOffset = 4)
        return sample
    }

    @Test fun exactSpsResolutionFromRealH264Profiles() {
        // Real FFmpeg/libx264 generated SPS: 1280x720 and 640x480.
        val hd = tsWithSps("6742c01fda014016ec044000000300400000030083c60ca8")
        val sd = tsWithSps("6742c016da0280f6c044000003000400000300083c58ba80")
        assertEquals(720, MOOSAHlsMediaQuality.h264HeightFromTs(hd))
        assertEquals(480, MOOSAHlsMediaQuality.h264HeightFromTs(sd))
        assertTrue(MOOSAQualityPolicy.acceptsHeight(
            MOOSAHlsMediaQuality.h264HeightFromTs(hd)
        ))
        assertTrue(!MOOSAQualityPolicy.acceptsHeight(
            MOOSAHlsMediaQuality.h264HeightFromTs(sd)
        ))
    }

    @Test fun rejectInvalidTransportStreamsRatherThanInventHd() {
        val blank = ByteArray(188 * 3)
        assertNull(MOOSAHlsMediaQuality.h264HeightFromTs(blank))
        val notTransportStream = ByteArray(188 * 3) { 0x47 }
        assertNull(MOOSAHlsMediaQuality.h264HeightFromTs(notTransportStream))
        val malformed = tsWithSps("6742c01fda014016ec044000000300400000030083c60ca8")
        // Destroy the SPS marker so no 'HD' can be guessed from packet size.
        malformed[4] = 0x11
        assertNull(MOOSAHlsMediaQuality.h264HeightFromTs(malformed))
    }

    @Test fun onlyClearHttpsMediaPlaylistsAreEligibleForOneSegmentProbe() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" +
            "#EXTINF:6.0,\nsegment01.ts?token=abc\n#EXTINF:6.0,\nsegment02.ts\n"
        assertEquals(
            "https://cdn.example/season2/segment01.ts?token=abc",
            MOOSAHlsMediaQuality.firstTsSegmentUrl(
                "https://cdn.example/season2/index.m3u8", media
            )
        )
        assertNull(MOOSAHlsMediaQuality.firstTsSegmentUrl(
            "https://cdn.example/index.m3u8",
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n" + media
        ))
        assertNull(MOOSAHlsMediaQuality.firstTsSegmentUrl(
            "https://cdn.example/index.m3u8",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720\n720p/index.m3u8"
        ))
        assertNull(MOOSAHlsMediaQuality.firstTsSegmentUrl(
            "https://cdn.example/index.m3u8",
            "#EXTM3U\n#EXTINF:4.0,\nhttp://cdn.example/video.ts"
        ))
        assertNull(MOOSAHlsMediaQuality.firstTsSegmentUrl(
            "https://cdn.example/index.m3u8",
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nsegment.m4s"
        ))
    }
}
