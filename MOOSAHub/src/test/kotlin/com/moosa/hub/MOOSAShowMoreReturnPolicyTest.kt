package com.moosa.hub

import org.junit.Assert.*
import org.junit.Test

class MOOSAShowMoreReturnPolicyTest {
    @Test fun neverRestoreToAnotherCategoryAtSavedPosition() {
        assertTrue(MOOSAShowMoreReturnPolicy.sameCategory("Top Indian Movies", "Top Indian Movies"))
        assertFalse(MOOSAShowMoreReturnPolicy.sameCategory("Top Indian Movies", "Trending Indian Movies"))
        assertFalse(MOOSAShowMoreReturnPolicy.sameCategory("Top Indian Movies", "English Movies"))
        assertFalse(MOOSAShowMoreReturnPolicy.sameCategory("Top Indian Movies", null))
        assertFalse(MOOSAShowMoreReturnPolicy.sameCategory("", "Top Indian Movies"))
    }

    @Test fun revisitOriginalRowThenScanNeighborsOnReorder() {
        assertEquals(listOf(4, 3, 5, 2, 6, 1, 7, 0),
            MOOSAShowMoreReturnPolicy.candidatePositions(4, 8))
        assertEquals(listOf(0, 1, 2, 3),
            MOOSAShowMoreReturnPolicy.candidatePositions(-1, 4))
        assertEquals(listOf(0, 1, 2),
            MOOSAShowMoreReturnPolicy.candidatePositions(50, 3))
    }

    @Test fun noCategoryWhenRowsAreNotLoaded() {
        assertTrue(MOOSAShowMoreReturnPolicy.candidatePositions(2, 0).isEmpty())
        assertTrue(MOOSAShowMoreReturnPolicy.candidatePositions(2, -1).isEmpty())
    }

    @Test fun categoryIdentityDoesNotChangeAfterOpeningSeveralMovies() {
        val origin = "Top Indian Movies"
        val reboundRows = listOf("Trending Indian Movies", "Arabic Movies", origin)
        assertEquals(2, reboundRows.indexOfFirst {
            MOOSAShowMoreReturnPolicy.sameCategory(origin, it)
        })
    }
}
