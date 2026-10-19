package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MOOSAHlsMasterQualityTest {
    @Test
    fun discovers2160pFromActualHlsMasterEvenIfSourceNameIsUnknown() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-STREAM-INF:BANDWIDTH=1700000,RESOLUTION=1280x720,CODECS="avc1"
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4300000,RESOLUTION=1920x1080,CODECS="avc1"
            1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=17000000,RESOLUTION=3840x2160,CODECS="hvc1"
            4k/index.m3u8
        """.trimIndent()
        assertEquals(2160, MOOSAHlsMasterQuality.highestVariantHeight(master))
    }

    @Test
    fun findsHighestValidMasterRenditionWithMixedCaseAttributes() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:RESOLUTION=2560x1440,BANDWIDTH=7000000
            /1440.m3u8
            #EXT-X-STREAM-INF:resolution=4096x2160,bandwidth=15000000
            https://cdn.example.test/2160.m3u8
        """.trimIndent()
        assertEquals(2160, MOOSAHlsMasterQuality.highestVariantHeight(master))
    }

    @Test
    fun doesNotClaimQualityFromMediaSegmentsOrUnpairedStreamInf() {
        assertNull(MOOSAHlsMasterQuality.highestVariantHeight("""
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            segment_4k_2160.ts
        """.trimIndent()))
        assertNull(MOOSAHlsMasterQuality.highestVariantHeight("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=16000000,RESOLUTION=3840x2160
            #EXT-X-ENDLIST
        """.trimIndent()))
        assertNull(MOOSAHlsMasterQuality.highestVariantHeight("""
            #EXTM3U
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=16000000,RESOLUTION=3840x2160,URI="iframe.m3u8"
        """.trimIndent()))
        assertNull(MOOSAHlsMasterQuality.highestVariantHeight("#EXT-X-STREAM-INF:RESOLUTION=3840x2160"))
    }

    @Test
    fun lowerResolutionMasterIsNotMislabeled4k() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            hd.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
            fullhd.m3u8
        """.trimIndent()
        assertEquals(1080, MOOSAHlsMasterQuality.highestVariantHeight(master))
    }
}
