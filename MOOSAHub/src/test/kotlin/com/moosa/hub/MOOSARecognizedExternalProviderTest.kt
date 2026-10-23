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
    @Test fun knownTorrentCandidatesAreBlockedWithoutBlockingHttpVideo() {
        val policy = MOOSARecognizedExternalProvider
        assertTrue(policy.isTorrentCandidate(
            "Torrentio", "4K", "https://example.org/video.mp4"
        ))
        assertTrue(policy.isTorrentCandidate(
            "CineStream", "P2P", "https://example.org/video.m3u8"
        ))
        assertTrue(policy.isTorrentCandidate(
            "CineStream", "Server", "magnet:?xt=urn:btih:aa"
        ))
        assertTrue(policy.isTorrentCandidate(
            "CineStream", "Server", "https://example.org/file.torrent?x=1"
        ))
        assertFalse(policy.isTorrentCandidate(
            "CineStream", "1080p", "https://media.example.org/film.mp4"
        ))
        assertFalse(policy.isTorrentCandidate(
            "StreamPlay", "CDN 720p", "https://media.example.org/master.m3u8"
        ))
    }

}
