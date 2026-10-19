package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MOOSAResolutionLabelsTest {
    @Test
    fun recognizesUnambiguous4kAndUltraHdSourceLabels() {
        for (label in listOf(
            "Source 4K HEVC", "UHD BluRay", "Ultra HD", "Ultra-HD HDR",
            "2160p WEB-DL", "3840x2160", "4096×2160",
            "https://example.org/media?quality=4K"
        )) {
            assertEquals("Expected claimed 4K for $label", 2160, MOOSAResolutionLabels.claimedHeight(label))
        }
    }

    @Test
    fun neverPromotesUnknownHdOrLowQualitySourceInto4k() {
        for (label in listOf(
            "HD", "HDR HEVC", "Full HD", "Stream-4kids",
            "4kingdom", "Movie part 4", "1280x2160",
            "480p", "720p", "1080p"
        )) {
            val result = MOOSAResolutionLabels.claimedHeight(label)
            if (label in listOf("480p","720p","1080p")) {
                assertEquals(label.filter(Char::isDigit).toInt(), result)
            } else assertNull("Unexpected height from $label", result)
        }
        assertEquals(480, MOOSAResolutionLabels.claimedHeight("480p - 4K remaster trailer"))
    }

    @Test
    fun recognizesOtherRealResolutionLabels() {
        assertEquals(720, MOOSAResolutionLabels.claimedHeight("1280x720"))
        assertEquals(1080, MOOSAResolutionLabels.claimedHeight("1920x1080"))
        assertEquals(1440, MOOSAResolutionLabels.claimedHeight("2560×1440"))
    }
}
