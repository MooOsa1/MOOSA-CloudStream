package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MOOSARefineDpadPlannerTest {
    private val rows = listOf(
        listOf(80, 190),                        // Movies / Series
        listOf(100, 300, 500, 700, 900),       // Five tab labels
        listOf(100, 335, 570, 805),            // Option row one
        listOf(100, 335, 570, 805),            // Option row two
        listOf(100, 335, 570, 805),            // Option row three, scrolled
        listOf(130, 815)                       // Reset / Show Results
    )
    private fun move(r: Int, c: Int, key: Int, tab: Int = 1) =
        MOOSARefineDpadPlanner.next(rows, r, c, key, tab)

    @Test
    fun tabsMoveSidewaysWithoutJumpingIntoOptionGrid() {
        assertEquals(1 to 2, move(1, 1, MOOSARefineDpadPlanner.RIGHT))
        assertEquals(1 to 0, move(1, 1, MOOSARefineDpadPlanner.LEFT))
        assertNull(move(1, 4, MOOSARefineDpadPlanner.RIGHT))
        assertNull(move(1, 0, MOOSARefineDpadPlanner.LEFT))
    }

    @Test
    fun fromTabGoDownToOptionAndReturnToTheSelectedTab() {
        assertEquals(2 to 2, move(1, 2, MOOSARefineDpadPlanner.DOWN))
        assertEquals(1 to 2, move(2, 0, MOOSARefineDpadPlanner.UP, tab = 2))
        assertEquals(1 to 4, move(0, 1, MOOSARefineDpadPlanner.DOWN, tab = 4))
    }

    @Test
    fun genresNavigateEveryRowIncludingScrolledOffScreenRows() {
        assertEquals(3 to 2, move(2, 2, MOOSARefineDpadPlanner.DOWN))
        assertEquals(4 to 2, move(3, 2, MOOSARefineDpadPlanner.DOWN))
        assertEquals(3 to 2, move(4, 2, MOOSARefineDpadPlanner.UP))
        assertEquals(4 to 3, move(4, 2, MOOSARefineDpadPlanner.RIGHT))
    }

    @Test
    fun footerIsReachableAndNeverLeaksFocusToMoviesBehindFilter() {
        assertEquals(5 to 0, move(4, 0, MOOSARefineDpadPlanner.DOWN))
        assertEquals(5 to 1, move(4, 3, MOOSARefineDpadPlanner.DOWN))
        assertEquals(4 to 3, move(5, 1, MOOSARefineDpadPlanner.UP))
        assertNull(move(5, 1, MOOSARefineDpadPlanner.DOWN))
        assertNull(move(0, 0, MOOSARefineDpadPlanner.UP))
    }

    @Test
    fun oneRowYearAndRatingTabsStillReachBottomActions() {
        val shortRows = listOf(
            listOf(90, 180), listOf(100, 300, 500, 700, 900),
            listOf(100, 330, 560, 790), listOf(130, 810)
        )
        assertEquals(3 to 1, MOOSARefineDpadPlanner.next(
            shortRows, 2, 3, MOOSARefineDpadPlanner.DOWN, 3
        ))
        assertEquals(2 to 3, MOOSARefineDpadPlanner.next(
            shortRows, 3, 1, MOOSARefineDpadPlanner.UP, 3
        ))
    }
}
