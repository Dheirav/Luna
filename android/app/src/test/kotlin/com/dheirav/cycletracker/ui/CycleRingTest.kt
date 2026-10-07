package com.dheirav.cycletracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring's one rule that is easy to get wrong: a late cycle must not wrap round and put today
 * where an early day would be.
 */
class CycleRingTest {

    @Test
    fun `day 14 of 28 is halfway round the first lap`() {
        val g = ringGeometry(cycleDay = 14, expectedLength = 28, periodLength = 5, ovulationDay = 14, windowStartDay = 27, windowEndDay = 31)
        assertEquals(180f, g.progressSweep, 0.01f)
        assertFalse(g.onSecondLap)
        assertEquals(180f, g.todayAngle, 0.01f)
    }

    /** Found on the Redmi: day 40 of a 28-day cycle. Wrapping would have drawn it at day 12. */
    @Test
    fun `a late cycle completes the lap and goes onto a second one`() {
        val g = ringGeometry(cycleDay = 40, expectedLength = 28, periodLength = 5, ovulationDay = 14, windowStartDay = null, windowEndDay = null)
        assertEquals(360f, g.progressSweep, 0.01f)
        assertTrue(g.onSecondLap)
        assertEquals(12 * 360f / 28, g.overflowSweep, 0.01f)
        assertNull(g.window)
    }

    @Test
    fun `the second lap never closes into what looks like a new cycle`() {
        val g = ringGeometry(cycleDay = 200, expectedLength = 28, periodLength = 5, ovulationDay = 14, windowStartDay = null, windowEndDay = null)
        assertEquals(330f, g.overflowSweep, 0.01f)
    }

    @Test
    fun `the window is a band across the expected date, not a point`() {
        // A window of 27 to 31 on a 28-day cycle straddles the top of the ring.
        val (start, sweep) = ringGeometry(20, 28, 5, 14, windowStartDay = 27, windowEndDay = 31).window!!
        assertEquals(26 * 360f / 28, start, 0.01f)
        assertEquals(5 * 360f / 28, sweep, 0.01f)
    }
}
