package com.moosa.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSADirectStreamProbeTest {
    @Test fun onlySignedCdnFilesNeedNoRangeStartupProbe() {
        assertTrue(MOOSADirectStreamProbe.requiresStartProbe(
            "https://api.hubcloud-2.workers.dev/path/video.mkv"))
        assertTrue(MOOSADirectStreamProbe.requiresStartProbe(
            "https://files.example.r2.dev/path/video.mkv"))
        assertFalse(MOOSADirectStreamProbe.requiresStartProbe(
            "https://pixeldrain.dev/api/file/t4TCMWHC?download"))
        assertFalse(MOOSADirectStreamProbe.requiresStartProbe(
            "https://fakeworkers.dev/path/video.mkv"))
        assertFalse(MOOSADirectStreamProbe.requiresStartProbe(
            "http://cdn.example.workers.dev/video.mkv"))
    }

    @Test fun tinyRangeSuccessMustNotBypassFailedPlaybackRequest() {
        assertFalse(MOOSADirectStreamProbe.acceptsStartResponse(403, "text/html;charset=UTF-8"))
        assertFalse(MOOSADirectStreamProbe.acceptsStartResponse(200, "text/html"))
        assertFalse(MOOSADirectStreamProbe.acceptsStartResponse(200, "application/json"))
        assertTrue(MOOSADirectStreamProbe.acceptsStartResponse(200, "video/x-matroska"))
        assertTrue(MOOSADirectStreamProbe.acceptsStartResponse(206, "application/octet-stream"))
    }
}
