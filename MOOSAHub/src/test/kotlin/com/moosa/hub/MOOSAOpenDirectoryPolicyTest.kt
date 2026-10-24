package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAOpenDirectoryPolicyTest {
    @Test fun exactDirectoryAndEpisodeOnly() {
        val dir = MOOSAOpenDirectoryPolicy.directory("Better Call Saul", 2015, 2)
        assertEquals(
            "https://a.111477.xyz/tvs/Better%20Call%20Saul/Season%2002/", dir
        )
        assertNotNull(dir)
        val one = MOOSAOpenDirectoryPolicy.candidate(
            dir!!, "Better.Call.Saul.S02E03.2160p.mkv",
            "Better.Call.Saul.S02E03.2160p.mkv", 2, 3
        )
        assertNotNull(one)
        assertEquals(2160, one!!.hintedHeight)
        assertTrue(one.streamUrl.startsWith("https://p.111477.xyz/bulk?u="))
        assertNull(MOOSAOpenDirectoryPolicy.candidate(
            dir, "Better.Call.Saul.S02E04.2160p.mkv",
            "Better.Call.Saul.S02E04.2160p.mkv", 2, 3
        ))
        assertNull(MOOSAOpenDirectoryPolicy.candidate(
            dir, "Better.Call.Saul.S03E03.2160p.mkv",
            "Better.Call.Saul.S03E03.2160p.mkv", 2, 3
        ))
    }

    @Test fun rejectsFakeHdAndOffSiteVideo() {
        val dir = MOOSAOpenDirectoryPolicy.directory("Parasite", 2019, null)!!
        assertNotNull(MOOSAOpenDirectoryPolicy.candidate(
            dir, "Parasite.2019.1080p.mkv", "Parasite.2019.1080p.mkv", null, null
        ))
        assertNull(MOOSAOpenDirectoryPolicy.candidate(
            dir, "Parasite.2019.480p.mkv", "Parasite.2019.480p.mkv", null, null
        ))
        assertNull(MOOSAOpenDirectoryPolicy.candidate(
            dir, "Parasite.2019.2160p.AV1.mkv", "Parasite.2019.2160p.AV1.mkv", null, null
        ))
        assertNull(MOOSAOpenDirectoryPolicy.candidate(
            dir, "https://evil.example/movie.mkv",
            "Parasite.2019.2160p.mkv", null, null
        ))
        assertNull(MOOSAOpenDirectoryPolicy.candidate(
            dir, "../something.mkv", "Parasite.2019.2160p.mkv", null, null
        ))
        assertNull(MOOSAOpenDirectoryPolicy.directory("../evil", 2019, null))
    }
}
