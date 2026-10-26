package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class MOOSAServerTitlePolicyTest {
    @Test fun namesShowTrueTrackLanguagesWithoutInventingEnglish() {
        assertEquals("Audio ?", MOOSAServerTitlePolicy.audio(null))
        assertEquals("Audio English / Hindi", MOOSAServerTitlePolicy.audio(
            setOf("hin", "en")))
        assertEquals("Audio Hindi", MOOSAServerTitlePolicy.audio(setOf("hi")))
        assertEquals("Audio Korean / ?", MOOSAServerTitlePolicy.audio(
            setOf("kor", "und")))
    }

    @Test fun mirrorNamesAreReadableWithoutUntrustedMarkup() {
        assertEquals("VidNest", MOOSAServerTitlePolicy.reyaMirror("vidnest"))
        assertEquals("VidNest", MOOSAServerTitlePolicy.reyaMirror("vidnest 4K"))
        assertEquals("Stream", MOOSAServerTitlePolicy.reyaMirror("<>"))
    }
}
