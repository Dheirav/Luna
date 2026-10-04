package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Lateness counted the way clinicians count it, and the point to see someone taken from published
 * definitions (decided 5 Oct 2026).
 *
 * - Late is days after the **expected start date**: the last period's start plus the usual cycle
 *   length. The engine's `daysLate` (§4) is cycle day minus cycle length, which counts the expected
 *   day itself as a late day, so it reads one higher; it stays for the confidence arithmetic, and
 *   everything shown to a person uses this instead.
 * - The doctor point is the secondary amenorrhea threshold (AAFP 2019, ASRM 2024): three months
 *   without a period after regular cycles, six after irregular ones, irregular meaning observed cycle
 *   lengths spread more than FIGO's widest normal range of nine days.
 */
class LateGuidanceTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    @Test
    fun `late is counted from the expected start date`() {
        // Started 26 Aug, usual 28 days, so expected 23 Sep. On 5 Oct that is 12 days.
        assertEquals(12, LateGuidance.daysPastExpected(date("2026-08-26"), 28, date("2026-10-05")))
    }

    @Test
    fun `on the expected day itself nothing is late`() {
        assertEquals(0, LateGuidance.daysPastExpected(date("2026-08-26"), 28, date("2026-09-23")))
        assertEquals(0, LateGuidance.daysPastExpected(date("2026-08-26"), 28, date("2026-09-10")))
    }

    /** Cycles of the given lengths, back to back, then an open one. */
    private fun projection(vararg lengths: Int): Projection {
        var start = date("2026-01-01")
        val periods = lengths.map { length ->
            Period(start, start.plusDays(4), 5, 5).also { start = start.plusDays(length.toLong()) }
        } + Period(start, start.plusDays(4), 5, 5)
        return CycleProjector.fromPeriods(periods)
    }

    @Test
    fun `regular cycles mean three months`() {
        val point = LateGuidance.doctorPoint(projection(28, 29, 27, 28), date("2026-05-01"))
        assertEquals(date("2026-08-01"), point.date)
        assertFalse(point.irregular)
    }

    @Test
    fun `cycles spread more than nine days mean six months`() {
        val point = LateGuidance.doctorPoint(projection(26, 38, 29, 40), date("2026-05-01"))
        assertEquals(date("2026-11-01"), point.date)
        assertTrue(point.irregular)
    }

    /** Too little observed history to call cycles irregular: the earlier, three-month point applies. */
    @Test
    fun `without enough observed cycles the earlier point applies`() {
        val point = LateGuidance.doctorPoint(projection(45), date("2026-05-01"))
        assertEquals(date("2026-08-01"), point.date)
        assertFalse(point.irregular)
    }

    @Test
    fun `estimated cycles never make a history irregular`() {
        var start = date("2026-01-01")
        val periods = listOf(25, 40, 26).map { length ->
            Period(start, start.plusDays(4), 5, 5, Source.ASSUMED).also { start = start.plusDays(length.toLong()) }
        } + Period(start, start.plusDays(4), 5, 5)
        val point = LateGuidance.doctorPoint(CycleProjector.fromPeriods(periods), date("2026-05-01"))
        assertFalse(point.irregular)
    }
}
