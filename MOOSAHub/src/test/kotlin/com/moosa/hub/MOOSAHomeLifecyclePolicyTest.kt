package com.moosa.hub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAHomeLifecyclePolicyTest {
    @Test
    fun cloudstreamHomeFragmentRequiresObserverRebinding() {
        assertTrue(MOOSAHomeLifecyclePolicy.isHomeFragment(
            "com.lagradost.cloudstream3.ui.home.HomeFragment"
        ))
        assertTrue(MOOSAHomeLifecyclePolicy.isHomeFragment(
            "com.custom.cloudstream.ui.home.HomeFragment"
        ))
    }

    @Test
    fun openingMovieSeriesPlayerAndOtherSourcesDoNotRebindHome() {
        listOf(
            "com.lagradost.cloudstream3.ui.result.ResultFragment",
            "com.lagradost.cloudstream3.ui.player.PlayerFragment",
            "com.lagradost.cloudstream3.ui.home.OtherFragment",
            "com.lagradost.cloudstream3.ui.search.SearchFragment",
            "com.lagradost.cloudstream3.ui.home.HomeViewModel",
            "HomeFragment"
        ).forEach {
            assertFalse("Unexpected home lifecycle match for $it",
                MOOSAHomeLifecyclePolicy.isHomeFragment(it))
        }
    }
}
