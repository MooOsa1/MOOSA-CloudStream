package com.moosa.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSASubtitleEpisodePolicyTest {
    @Test fun acceptsOnlyRequestedBetterCallSaulRelease() {
        assertTrue(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "Better.Call.Saul.S02E10.1080p.BluRay.x265-RARBG"))
        assertTrue(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "Better.Call.Saul.2x10.WEBRip"))
        assertTrue(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "Better Call Saul Season 2 Episode 10"))
        assertFalse(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "Better.Call.Saul.S05E10.WEBRip.x264-ION10"))
        assertFalse(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "Better.Call.Saul.S02E09.720p"))
    }

    @Test fun explicitStructuredFieldsCannotBeIgnored() {
        assertFalse(MOOSASubtitleEpisodePolicy.accepts(2, 10, 5, null,
            "Better.Call.Saul.S02E10"))
        assertFalse(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, 9,
            "Better.Call.Saul.S02E10"))
        assertTrue(MOOSASubtitleEpisodePolicy.accepts(2, 10, 2, 10,
            "Better.Call.Saul.S02E10"))
    }

    @Test fun unlabelledTracksRemainAvailableButConflictingTracksNeverDo() {
        assertTrue(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "Better Call Saul English WEB-DL"))
        assertTrue(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "S02E10", "Better.Call.Saul.1080p"))
        assertFalse(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "S02E10", "Better.Call.Saul.S05E10.WEBRip"))
        assertTrue(MOOSASubtitleEpisodePolicy.accepts(null, null, null, null,
            "Better.Call.Saul.S05E10")) // movies are unaffected
    }

    @Test fun avoidsReadingNumbersInsideOtherTitles() {
        assertTrue(MOOSASubtitleEpisodePolicy.accepts(2, 10, null, null,
            "CinemaS02E10Special"))
    }
}
