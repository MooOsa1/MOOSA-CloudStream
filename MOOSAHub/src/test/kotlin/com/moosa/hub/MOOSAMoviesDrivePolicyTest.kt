package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MOOSAMoviesDrivePolicyTest {
    @Test fun requiresExactImdbIdentity() {
        assertEquals("tt1375666", MOOSAMoviesDrivePolicy.imdbId("tt1375666"))
        assertNull(MOOSAMoviesDrivePolicy.imdbId("1375666"))
        assertNull(MOOSAMoviesDrivePolicy.imdbId("tt12?q=other"))
        assertEquals(
            "https://new4.moviesdrive.beer/inception-2010/",
            MOOSAMoviesDrivePolicy.moviePermalink("/inception-2010/")
        )
        assertNull(MOOSAMoviesDrivePolicy.moviePermalink("//evil.test/"))
        assertNull(MOOSAMoviesDrivePolicy.moviePermalink("/../another-movie/"))
    }

    @Test fun movieAndHostRoutesCannotEscapeToAds() {
        assertEquals("https://mdrive.lol/archive/21128/",
            MOOSAMoviesDrivePolicy.archive("https://mdrive.lol/archive/21128/"))
        assertNull(MOOSAMoviesDrivePolicy.archive("https://mdrive.lol.evil.test/archive/21128/"))
        assertNull(MOOSAMoviesDrivePolicy.archive("https://mdrive.lol/tag/movie/"))
        assertEquals("https://hubcloud.ist/drive/eq0eg0eqo00ggxq",
            MOOSAMoviesDrivePolicy.hub("https://hubcloud.ist/drive/eq0eg0eqo00ggxq"))
        assertNull(MOOSAMoviesDrivePolicy.hub("https://hubcloud.ist/admin"))
        assertNull(MOOSAMoviesDrivePolicy.terminal("https://gamerxyt.com.evil.test/hubcloud.php"))
    }

    @Test fun pixelLandingIsConvertedIntoSeekableApiAndNoFake4k() {
        assertEquals("https://pixeldrain.dev/api/file/9XEEpY9K",
            MOOSAMoviesDrivePolicy.pixeldrainApi("https://pixeldrain.dev/u/9XEEpY9K"))
        assertEquals("https://pixeldrain.dev/api/file/9XEEpY9K",
            MOOSAMoviesDrivePolicy.pixeldrainApi("https://pixeldrain.dev/api/file/9XEEpY9K"))
        assertNull(MOOSAMoviesDrivePolicy.pixeldrainApi("http://pixeldrain.dev/u/9XEEpY9K"))
        assertNull(MOOSAMoviesDrivePolicy.pixeldrainApi("https://pixeldrain.dev/u/9XEEpY9K?download=1"))
        assertNull(MOOSAMoviesDrivePolicy.pixeldrainApi("https://pixeldrain.dev.evil.test/u/9XEEpY9K"))
        assertEquals(2160, MOOSAMoviesDrivePolicy.releaseQuality("4K [2160p SDR HEVC – 7GB]"))
        assertEquals(1080, MOOSAMoviesDrivePolicy.releaseQuality("1080p 10bit HEVC [2.3GB]"))
        assertNull(MOOSAMoviesDrivePolicy.releaseQuality("unknown quality"))
    }
}
