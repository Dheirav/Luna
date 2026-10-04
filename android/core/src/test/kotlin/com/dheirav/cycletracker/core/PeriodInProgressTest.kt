package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * CYCLE_RULES §5.1 as amended on 5 Oct 2026: a period that may still be going is not read as over.
 *
 * Before the amendment, the menstruation phase lasted exactly as many days as had been logged so far.
 * So with bleeding logged on days 1 and 2, day 3 read "Follicular" until day 3 was logged too, an
 * unanswered day treated as a no, and the "Usual period length" setting could never take effect. Now a
 * period stays open until a non-bleeding day is *answered* after it, and while open its length for
 * phase purposes is at least the expected period length.
 */
class PeriodInProgressTest {

    private fun date(iso: String) = LocalDate.parse(iso)
    private fun days(from: String, count: Int) = (0 until count).map { date(from).plusDays(it.toLong()) }

    private val engine = CycleEngine()

    /** Two earlier observed cycles of five-day periods, then a current period logged for two days. */
    private val history = days("2025-01-01", 5) + days("2025-01-29", 5) + days("2025-02-26", 2)
    private val currentStart = date("2025-02-26")

    private fun stateOn(day: Int, noBleeding: Set<LocalDate> = emptySet(), userPeriod: Int? = null): CycleState =
        engine.stateFor(
            currentStart.plusDays(day - 1L),
            CycleProjector.project(history),
            bleedingDays = history.toSet(),
            userTypicalPeriodLength = userPeriod,
            noBleedingDays = noBleeding,
        )

    @Test
    fun `an unlogged day inside the expected period length is still menstruation`() {
        assertEquals(Phase.MENSTRUATION, stateOn(day = 3).phase)
        assertEquals(Phase.MENSTRUATION, stateOn(day = 5).phase)
    }

    @Test
    fun `past the expected period length the phase moves on`() {
        assertEquals(Phase.FOLLICULAR, stateOn(day = 6).phase)
    }

    /** An answered "No bleeding" is what closes a period: then its logged length is its length. */
    @Test
    fun `a no bleeding answer after the last bleeding day closes the period`() {
        val no = setOf(currentStart.plusDays(2))
        assertEquals(Phase.FOLLICULAR, stateOn(day = 3, noBleeding = no).phase)
    }

    @Test
    fun `a no bleeding answer before the last bleeding day does not close it`() {
        val earlier = setOf(currentStart.minusDays(3))
        assertEquals(Phase.MENSTRUATION, stateOn(day = 3, noBleeding = earlier).phase)
    }

    /** The setting the amendment brings to life: with too few observed periods, the stated length rules. */
    @Test
    fun `a stated period length applies while there are too few observed periods`() {
        val oneEarlier = days("2025-01-29", 5) + days("2025-02-26", 2)
        val state = engine.stateFor(
            currentStart.plusDays(6),
            CycleProjector.project(oneEarlier),
            bleedingDays = oneEarlier.toSet(),
            userTypicalPeriodLength = 7,
        )
        assertEquals("day 7 of a stated 7-day period", Phase.MENSTRUATION, state.phase)
    }

    /** §3.1: the expected length comes from finished, observed periods, not the partial current one. */
    @Test
    fun `the current partial period does not shorten the expected length`() {
        // Observed spans of 5 and 5, plus the open 2. Counting the open one would give a median of 5
        // here by luck, so use spans where it would not: two 6-day periods and an open 2-day one.
        val longer = days("2025-01-01", 6) + days("2025-01-29", 6) + days("2025-02-26", 2)
        val day6 = engine.stateFor(
            currentStart.plusDays(5),
            CycleProjector.project(longer),
            bleedingDays = longer.toSet(),
        )
        assertEquals(Phase.MENSTRUATION, day6.phase)
    }

    /** A past cycle's period ended when the next one began, so its logged span stands as it was. */
    @Test
    fun `a period in a completed cycle is closed by the next period`() {
        val dayFourOfFirst = engine.stateFor(
            date("2025-01-01").plusDays(3),
            CycleProjector.project(history),
            bleedingDays = history.toSet(),
        )
        assertEquals(Phase.MENSTRUATION, dayFourOfFirst.phase)

        val daySixOfFirst = engine.stateFor(
            date("2025-01-01").plusDays(5),
            CycleProjector.project(history),
            bleedingDays = history.toSet(),
        )
        assertEquals(Phase.FOLLICULAR, daySixOfFirst.phase)
    }

    @Test
    fun `estimated periods do not set the expected period length`() {
        val assumedLong = days("2025-01-01", 8) + days("2025-01-29", 8)
        val observedCurrent = days("2025-02-26", 2)
        val all = assumedLong + observedCurrent
        val state = engine.stateFor(
            currentStart.plusDays(5),
            CycleProjector.project(all, assumedDays = assumedLong.toSet()),
            bleedingDays = all.toSet(),
        )
        // Estimated 8-day periods must not stretch this one: the default 5 applies, so day 6 has moved on.
        assertEquals(Phase.FOLLICULAR, state.phase)
    }
}
