package com.dheirav.cycletracker.ui

import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.reminder.answerConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The friction fixes from the council review (F3, F4, F5).
 *
 * Catching up: missed days used to be a loop of open, step back, save, land on Today, repeat, with
 * nothing saying days had been missed. The rule for when Today mentions it is the part worth pinning:
 * only consecutive days just before today, only since the first log, and never for a single day,
 * which the evening reminder already covers.
 */
class CatchUpTest {

    private val today = LocalDate.parse("2026-10-07")
    private fun d(iso: String) = LocalDate.parse(iso)

    @Test
    fun `the unlogged days just before today are found, oldest first`() {
        val logged = setOf(d("2026-10-01"), d("2026-10-03"))
        assertEquals(
            listOf(d("2026-10-04"), d("2026-10-05"), d("2026-10-06")),
            unloggedRecentDays(logged, today),
        )
    }

    @Test
    fun `a single missed day is left to the reminder`() {
        assertTrue(unloggedRecentDays(setOf(d("2026-10-05")), today).isEmpty())
    }

    @Test
    fun `logging yesterday means nothing to catch up`() {
        assertTrue(unloggedRecentDays(setOf(d("2026-10-01"), d("2026-10-06")), today).isEmpty())
    }

    @Test
    fun `it looks back a week at most`() {
        val found = unloggedRecentDays(setOf(d("2026-08-01")), today)
        assertEquals(7, found.size)
        assertEquals(d("2026-09-30"), found.first())
    }

    @Test
    fun `nothing is asked before the first log`() {
        assertTrue(unloggedRecentDays(emptySet(), today).isEmpty())
        assertTrue("days before the first log are not missed days", unloggedRecentDays(setOf(d("2026-10-05")), today).isEmpty())
    }

    // -- F3: the notification answer's confirmation --------------------------------

    @Test
    fun `the confirmation names the answer and the day`() {
        Locale.setDefault(Locale.UK)
        assertEquals("Logged: no bleeding, Sun 4 Oct", answerConfirmation(bleeding = false, date = d("2026-10-04")))
        assertEquals("Logged: bleeding, Mon 5 Oct", answerConfirmation(bleeding = true, date = d("2026-10-05")))
    }

    // -- F5: one name for the bleeding phase ----------------------------------------

    @Test
    fun `the bleeding phase is called Period everywhere`() {
        assertEquals("Period", phaseName(Phase.MENSTRUATION))
        assertEquals("Luteal", phaseName(Phase.LUTEAL))
    }

    /** Found on the phone: five weeks unlogged read "7 days not logged". The line counts the whole run. */
    @Test
    fun `a run longer than a week is not undercounted`() {
        // 27 Aug to 6 Oct: 41 days, said as a number rather than "over a week" (device review p3).
        assertEquals("41 days not logged", catchUpLine(setOf(d("2026-08-26")), today))
        assertEquals("3 days not logged", catchUpLine(setOf(d("2026-10-01"), d("2026-10-03")), today))
        assertEquals(null, catchUpLine(setOf(d("2026-10-06")), today))
    }
}
