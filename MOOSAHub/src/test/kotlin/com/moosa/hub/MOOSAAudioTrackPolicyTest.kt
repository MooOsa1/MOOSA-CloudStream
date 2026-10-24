package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAAudioTrackPolicyTest {
    @Test fun koreanOriginalRejectsHindiOnlyAndPreservesMultiAudio() {
        assertFalse(MOOSAAudioTrackPolicy.accept("ko", setOf("hin")))
        assertTrue(MOOSAAudioTrackPolicy.accept("ko", setOf("kor", "hin")))
        assertTrue(MOOSAAudioTrackPolicy.isMultiAudio(setOf("ko", "hi")))
        assertFalse(MOOSAAudioTrackPolicy.accept("ko", null))
    }

    @Test fun knownDubMismatchRejectsEnglishFilmToo() {
        assertFalse(MOOSAAudioTrackPolicy.accept("en", setOf("hin")))
        assertTrue(MOOSAAudioTrackPolicy.accept("en", setOf("eng", "hin")))
        assertTrue(MOOSAAudioTrackPolicy.accept("en", null)) // legacy unknown English mux
        assertEquals("kor", MOOSAAudioTrackPolicy.normalize("ko-KR"))
        assertEquals("jpn", MOOSAAudioTrackPolicy.normalize("ja"))
        assertEquals("audio eng/hin", MOOSAAudioTrackPolicy.display(setOf("hi", "en")))
    }
}
