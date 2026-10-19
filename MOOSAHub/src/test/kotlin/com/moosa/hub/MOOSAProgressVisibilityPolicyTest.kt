package com.moosa.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAProgressVisibilityPolicyTest {
    @Test
    fun sameEpisodeRefreshDoesNotResurfaceProgressDuringPlayback() {
        val p = MOOSAProgressVisibilityPolicy()
        val episode = "tt3032476:tv:1:2"
        assertTrue(p.begin(episode))
        p.linkAvailable(episode)
        assertTrue(p.isSuppressed(episode))
        assertFalse("Repeated loadLinks for same episode must not flash over video", p.begin(episode))
        assertFalse("Further retries still must not flash", p.begin(episode))
    }

    @Test
    fun nextEpisodeAndAnotherTitleGetFreshPrePlaybackBar() {
        val p = MOOSAProgressVisibilityPolicy()
        val ep1 = "tt3032476:tv:1:2"
        val ep2 = "tt3032476:tv:1:3"
        p.begin(ep1)
        p.linkAvailable(ep1)
        assertTrue("Next episode should still show initial loading", p.begin(ep2))
        assertFalse(p.isSuppressed(ep2))
        p.linkAvailable(ep2)
        assertFalse(p.begin(ep2))
        assertTrue("Different movie should show initial loading", p.begin("tt16311594:movie"))
    }

    @Test
    fun initialFailureWithoutPlayableLinkStillAllowsRetry() {
        val p = MOOSAProgressVisibilityPolicy()
        assertTrue(p.begin("episode-1"))
        assertTrue(p.begin("episode-1"))
        p.linkAvailable("stale-other-episode")
        assertFalse(p.isSuppressed("episode-1"))
        assertTrue(p.begin("episode-1"))
    }

    @Test
    fun playableLinkFromEarlierOverlappingLookupHidesRefreshForSameEpisode() {
        val p = MOOSAProgressVisibilityPolicy()
        assertTrue(p.begin("episode-1"))
        assertTrue("Refresh starts before original lookup finishes", p.begin("episode-1"))
        p.linkAvailable("episode-1")
        assertFalse("First verified stream hides same-episode follow-up", p.begin("episode-1"))
    }

    @Test
    fun staleLinkCallbackDoesNotSuppressProgressForNewEpisode() {
        val p = MOOSAProgressVisibilityPolicy()
        p.begin("ep1")
        p.begin("ep2")
        p.linkAvailable("ep1")
        assertFalse(p.isSuppressed("ep2"))
        assertTrue(p.begin("ep2"))
    }
}
