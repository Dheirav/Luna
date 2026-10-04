package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Finding the unlogged days that split a period.
 *
 * CYCLE_RULES §2.1 bridges one non-bleeding day inside a period, so logging days 1 and 4 of a period
 * and nothing between splits it: a one-day period, then "spotting". The person was most likely too
 * unwell to log, which is the moment logging is hardest. Rather than change the rule silently, the app
 * asks (decided 5 Oct). These tests pin down when it asks: only across days that were never logged at
 * all, since an answered "no" is an answer, and only across gaps the spec does not already bridge.
 */
class UnloggedGapsTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    @Test
    fun `two unlogged days between bleeding days are a gap to ask about`() {
        val bleeding = listOf(date("2025-03-01"), date("2025-03-04"))

        val gaps = UnloggedGaps.find(bleeding, loggedDays = bleeding.toSet())

        assertEquals(1, gaps.size)
        assertEquals(listOf(date("2025-03-02"), date("2025-03-03")), gaps.single().days)
        assertEquals(date("2025-03-04"), gaps.single().resumesOn)
    }

    @Test
    fun `one unlogged day is already bridged by the spec, so nothing is asked`() {
        val bleeding = listOf(date("2025-03-01"), date("2025-03-03"))
        assertTrue(UnloggedGaps.find(bleeding, bleeding.toSet()).isEmpty())
    }

    @Test
    fun `an answered day in the gap means it was not forgotten`() {
        val bleeding = listOf(date("2025-03-01"), date("2025-03-04"))
        val logged = bleeding.toSet() + date("2025-03-02")

        assertTrue(UnloggedGaps.find(bleeding, logged).isEmpty())
    }

    @Test
    fun `a gap longer than three days is a real gap, not a forgotten stretch`() {
        val bleeding = listOf(date("2025-03-01"), date("2025-03-06"))
        assertTrue(UnloggedGaps.find(bleeding, bleeding.toSet()).isEmpty())
    }

    @Test
    fun `several gaps are found, oldest first`() {
        val bleeding = listOf(
            date("2025-01-01"), date("2025-01-04"),
            date("2025-03-01"), date("2025-03-05"),
        )
        val gaps = UnloggedGaps.find(bleeding, bleeding.toSet())

        assertEquals(2, gaps.size)
        assertTrue(gaps[0].resumesOn.isBefore(gaps[1].resumesOn))
        assertEquals(3, gaps[1].days.size)
    }

    /** The spotting flag waits for the answer instead of calling day 4 of a period "spotting". */
    @Test
    fun `spotting just after an unanswered gap does not raise the flag`() {
        val bleeding = listOf(date("2025-03-01"), date("2025-03-04"))
        val projection = CycleProjector.project(bleeding)
        assertTrue("precondition: the split produces a spotting event", projection.spotting.isNotEmpty())

        val held = HealthFlags.evaluate(
            projection, date("2025-03-10"), 28,
            heldSpotting = UnloggedGaps.find(bleeding, bleeding.toSet()).map { it.resumesOn }.toSet(),
        )
        val raised = HealthFlags.evaluate(projection, date("2025-03-10"), 28)

        assertFalse(HealthFlagKind.SPOTTING_BETWEEN_PERIODS in held.map { it.kind })
        assertTrue(HealthFlagKind.SPOTTING_BETWEEN_PERIODS in raised.map { it.kind })
    }
}
