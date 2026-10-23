package com.moosa.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSARecognizedExternalProviderTest {
    @Test fun exactRegisteredNamesAreRecognized() {
        assertTrue(MOOSARecognizedExternalProvider.isRecognized("CineStream"))
        assertTrue(MOOSARecognizedExternalProvider.isRecognized(" streamplay "))
        assertTrue(MOOSARecognizedExternalProvider.isRecognized("STREAMPLAY"))
    }

    @Test fun impostorsAndUnrelatedProvidersStayExcluded() {
        assertFalse(MOOSARecognizedExternalProvider.isRecognized("MyCineStream"))
        assertFalse(MOOSARecognizedExternalProvider.isRecognized("StreamPlayAlt"))
        assertFalse(MOOSARecognizedExternalProvider.isRecognized(""))
        assertFalse(MOOSARecognizedExternalProvider.shouldIncludeFromGlobal(
            "RandomProvider", false
        ))
    }

    @Test fun animeOriginalGlobalLookupIsNotReduced() {
        assertTrue(MOOSARecognizedExternalProvider.shouldIncludeFromGlobal(
            "OriginalAnimeAddon", true
        ))
        assertTrue(MOOSARecognizedExternalProvider.shouldIncludeFromGlobal(
            "CineStream", false
        ))
    }
}
