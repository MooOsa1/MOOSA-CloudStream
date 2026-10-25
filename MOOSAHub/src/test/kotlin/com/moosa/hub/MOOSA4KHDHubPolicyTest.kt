package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MOOSA4KHDHubPolicyTest {
    @Test fun exactMovieIdentityRequired() {
        assertTrue(MOOSA4KHDHubPolicy.exactCatalogMatch(
            "Dune: Part Two", 2024, "Dune Part Two", 2024
        ))
        assertFalse(MOOSA4KHDHubPolicy.exactCatalogMatch(
            "Dune: Part Two", 2024, "Dune Part One", 2024
        ))
        assertFalse(MOOSA4KHDHubPolicy.exactCatalogMatch(
            "Dune: Part Two", 2024, "Dune: Part Two", 2021
        ))
        assertFalse(MOOSA4KHDHubPolicy.exactCatalogMatch(
            "Dune: Part Two", null, "Dune: Part Two", 2024
        ))
    }

    @Test fun claimsAreOnlyDiscoveryHints() {
        assertTrue(MOOSA4KHDHubPolicy.isUhdRelease("Movie 2160p HDR10.mkv"))
        assertTrue(MOOSA4KHDHubPolicy.isUhdRelease("Movie 4K UHD BluRay"))
        assertFalse(MOOSA4KHDHubPolicy.isUhdRelease("Movie 1080p BluRay"))
        assertFalse(MOOSA4KHDHubPolicy.isCompatibleRelease("Movie 2160p DV HDR10"))
        assertFalse(MOOSA4KHDHubPolicy.isCompatibleRelease("Movie AV1 1080p"))
        assertTrue(MOOSA4KHDHubPolicy.isCompatibleRelease("Movie 2160p HEVC HDR10"))
        assertEquals(2160, MOOSA4KHDHubPolicy.actualQuality(3840, 1598))
        assertEquals(2160, MOOSA4KHDHubPolicy.actualQuality(3840, 2160))
        assertNull(MOOSA4KHDHubPolicy.actualQuality(1920, 600))
        assertNull(MOOSA4KHDHubPolicy.safeDirectFile("https://localhost/video.mkv"))
        assertNull(MOOSA4KHDHubPolicy.safeDirectFile("http://cdn.example.workers.dev/a"))
        assertNull(MOOSA4KHDHubPolicy.safeDirectFile("https://evil.example/?go=workers.dev"))
        assertEquals(
            "https://cdn.example.workers.dev/my%20movie.mkv",
            MOOSA4KHDHubPolicy.safeDirectFile(
                "https://cdn.example.workers.dev/my movie.mkv"
            )
        )
        assertEquals(
            "https://cdn.example.workers.dev/Movie%20%5BHDR%5D.mkv",
            MOOSA4KHDHubPolicy.safeDirectFile(
                "https://cdn.example.workers.dev/Movie [HDR].mkv"
            )
        )
    }

    @Test fun episodeIdsMustMatchAndSeasonPacksNeverBecomePlayers() {
        assertEquals(2, MOOSA4KHDHubPolicy.seasonNumber("S02"))
        assertEquals(1, MOOSA4KHDHubPolicy.episodeNumber("Episode-01"))
        assertEquals(3, MOOSA4KHDHubPolicy.episodeNumber(
            "Better.Call.Saul.S02E03.2160p.WEB-DL.H265.mkv"
        ))
        assertNull(MOOSA4KHDHubPolicy.seasonNumber("S01-S02"))
        assertNull(MOOSA4KHDHubPolicy.episodeNumber(
            "Episode-02 / Better.Call.Saul.S02E03.2160p.mkv"
        ))
        assertTrue(MOOSA4KHDHubPolicy.isStandaloneEpisodeFile(
            "Better.Call.Saul.S02E03.2160p.WEB-DL.HEVC.mkv"
        ))
        assertFalse(MOOSA4KHDHubPolicy.isStandaloneEpisodeFile(
            "Better.Call.Saul.S02.2160p.HEVC.zip"
        ))
        assertFalse(MOOSA4KHDHubPolicy.isStandaloneEpisodeFile(
            "Better.Call.Saul.S02E03.2160p.DV.H265.mkv"
        ))
        assertFalse(MOOSA4KHDHubPolicy.isStandaloneEpisodeFile(
            "Better.Call.Saul.S02E03.2160p.AV1.mkv"
        ))
    }

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun rot13(value: String): String = value.map { c ->
        when (c) {
            in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 13) % 26).toChar()
            in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 13) % 26).toChar()
            else -> c
        }
    }.joinToString("")

    @Test fun gatewayDecodeDoesNotNeedAdRedirects() {
        val target = "https://hubcloud.ist/drive/abc123"
        val encoded = b64(b64(rot13(b64("{\"o\":\"${b64(target)}\"}"))))
        val gatewayPage = "<script>s('o','$encoded',180*1000);</script>"
        assertEquals(target, MOOSA4KHDHubPolicy.decodeGateway(gatewayPage))
        assertNull(MOOSA4KHDHubPolicy.decodeGateway("<html>No encoded target</html>"))
        assertNull(MOOSA4KHDHubPolicy.safeHubPage("https://localhost/drive/a"))
        assertNull(MOOSA4KHDHubPolicy.safeHostPage("https://gamerxyt.com.evil.test/a"))
    }

    private fun elem(id: ByteArray, data: ByteArray): ByteArray {
        require(data.size <= 126)
        return id + byteArrayOf((0x80 or data.size).toByte()) + data
    }

    private fun ebmlUhdSample(
        width: ByteArray, height: ByteArray, codec: String = "V_MPEGH/ISO/HEVC"
    ): ByteArray {
        val video = elem(byteArrayOf(0xe0.toByte()),
            elem(byteArrayOf(0xb0.toByte()), width) +
                elem(byteArrayOf(0xba.toByte()), height))
        val track = elem(byteArrayOf(0xae.toByte()),
            elem(byteArrayOf(0x83.toByte()), byteArrayOf(1)) +
                elem(byteArrayOf(0x86.toByte()), codec.toByteArray()) + video)
        val tracks = elem(
            byteArrayOf(0x16, 0x54, 0xae.toByte(), 0x6b), track
        )
        val header = elem(
            byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()),
            byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        return header + elem(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), tracks)
    }

    @Test fun matroskaAudioLanguagesAreReadFromRealTracks() {
        fun audioTrack(lang: String): ByteArray = elem(
            byteArrayOf(0xae.toByte()),
            elem(byteArrayOf(0x83.toByte()), byteArrayOf(2)) +
                elem(byteArrayOf(0x22, 0xb5.toByte(), 0x9c.toByte()),
                    lang.toByteArray(Charsets.US_ASCII))
        )
        val header = elem(
            byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()),
            byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val tracks = elem(
            byteArrayOf(0x16, 0x54, 0xae.toByte(), 0x6b),
            audioTrack("kor") + audioTrack("hin")
        )
        val payload = header +
            elem(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), tracks)
        val languages = MOOSA4KHDHubPolicy.audioLanguages(payload)
        assertEquals(setOf("kor", "hin"), languages)
        assertTrue(MOOSAAudioTrackPolicy.accept("ko", languages))
        assertFalse(MOOSAAudioTrackPolicy.accept("ja", languages))
        assertNull(MOOSA4KHDHubPolicy.audioLanguages(payload.copyOfRange(0, 12)))

        val mixedTracks = elem(
            byteArrayOf(0x16, 0x54, 0xae.toByte(), 0x6b),
            audioTrack("hin") + audioTrack("und")
        )
        val mixedPayload = header + elem(
            byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), mixedTracks
        )
        val mixed = MOOSA4KHDHubPolicy.audioLanguages(mixedPayload)
        assertEquals(setOf("hin", "und"), mixed)
        assertTrue(MOOSAAudioTrackPolicy.accept("ko", mixed))
    }

    @Test fun ebmlDimensionsComeFromVideoTrack() {
        val uhd = ebmlUhdSample(
            byteArrayOf(0x0f, 0x00), byteArrayOf(0x08, 0x70)
        )
        assertEquals(3840 to 2160, MOOSA4KHDHubPolicy.videoDimensions(uhd))
        assertEquals("V_MPEGH/ISO/HEVC", MOOSA4KHDHubPolicy.compatibleVideo(uhd)?.codecId)
        val hd = ebmlUhdSample(
            byteArrayOf(0x05, 0x00), byteArrayOf(0x02, 0xd0.toByte())
        )
        assertEquals(1280 to 720, MOOSA4KHDHubPolicy.videoDimensions(hd))
        assertNull(MOOSA4KHDHubPolicy.videoDimensions(
            ebmlUhdSample(byteArrayOf(0x0f, 0x00), byteArrayOf(0x08, 0x70), "V_AV1")
        ))
        assertNull(MOOSA4KHDHubPolicy.videoDimensions(uhd.copyOfRange(0, 10)))
        assertNull(MOOSA4KHDHubPolicy.videoDimensions(ByteArray(100)))
    }
}
