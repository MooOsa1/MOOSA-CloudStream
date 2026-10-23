package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MOOSANativeSourceDiagnosticsTest {
    @Test fun everyInternalSourceIsTestedAgainstSameRealEpisode() {
        val titles = listOf(
            MOOSACineStreamVaPlayerPolicy.SOURCE_NAME,
            MOOSACineStreamVidrockPolicy.SOURCE_NAME,
            MOOSACineStreamVidzeePolicy.SOURCE_NAME
        )
        titles.forEach {
            assertEquals("Better Call Saul S02E01",
                MOOSANativeSourceDiagnostics.sourceTestLabel(it))
        }
        assertNull(MOOSANativeSourceDiagnostics.sourceTestLabel("Other provider"))
        assertEquals(
            "https://streamdata.vaplayer.ru/api.php?imdb=tt3032476&type=tv&season=2&episode=1",
            MOOSACineStreamVaPlayerPolicy.requestUrl("tt3032476", 2, 1)
        )
        assertEquals(
            "https://vidrock.ru/api/tv/60059_2_1/",
            MOOSACineStreamVidrockPolicy.requestUrl(60059, true, 2, 1)
        )
        assertEquals(
            "https://player.vidzee.wtf/api/server?id=60059&sr=0&ss=2&ep=1",
            MOOSACineStreamVidzeePolicy.requestUrl(60059, true, 2, 1, 0)
        )
    }

    @Test fun eachProviderHasOwnBoundedDiagnosticsState() {
        val a = MOOSACineStreamVaPlayerPolicy.SOURCE_NAME
        val b = MOOSACineStreamVidrockPolicy.SOURCE_NAME
        MOOSANativeSourceDiagnostics.clear(a)
        MOOSANativeSourceDiagnostics.clear(b)
        MOOSANativeSourceDiagnostics.mark(a, "HTTP 403")
        MOOSANativeSourceDiagnostics.mark(b, "No links for S02E01")
        assertEquals("HTTP 403", MOOSANativeSourceDiagnostics.note(a))
        assertEquals("No links for S02E01", MOOSANativeSourceDiagnostics.note(b))
        MOOSANativeSourceDiagnostics.clear(a)
        assertNull(MOOSANativeSourceDiagnostics.note(a))
        assertEquals("No links for S02E01", MOOSANativeSourceDiagnostics.note(b))
        MOOSANativeSourceDiagnostics.mark(a, "x".repeat(400))
        assertEquals(100, MOOSANativeSourceDiagnostics.note(a)?.length)
        MOOSANativeSourceDiagnostics.clear(a)
        MOOSANativeSourceDiagnostics.clear(b)
    }
}
