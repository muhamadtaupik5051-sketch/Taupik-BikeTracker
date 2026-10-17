package com.biketracker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedRoutesTest {
    @Test
    fun thinKeepsEndpointsAndLimit() {
        val src = (0 until 1000).toList()
        val out = SavedRoutes.thin(src, 80)
        assertEquals(80, out.size)
        assertEquals(0, out.first())
        assertEquals(999, out.last())
    }

    @Test
    fun thinLeavesShortListsAlone() {
        val src = listOf(1, 2, 3)
        assertEquals(src, SavedRoutes.thin(src, 80))
    }

    @Test
    fun parsePtsSkipsBadPoints() {
        val p = SavedRoutes.parsePts("1.5,2.5;x,y;3,4")
        assertEquals(2, p.size)
        assertEquals(3.0, p[1][0], 0.0001)
    }
}
