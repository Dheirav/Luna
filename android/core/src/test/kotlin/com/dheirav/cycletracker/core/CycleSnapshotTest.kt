package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * The snapshot exists so every surface gets the same answer from the same inputs.
 *
 * It was written after the reminder worker turned out to call the engine without the user's stated
 * cycle and period lengths while Today passed both. The ledger keeps one prediction per day, so the
 * worker's 21:00 row replaced the one Today had shown, and accuracy was then graded against a
 * prediction the user never saw. So the tests here are about **the settings reaching every figure**,
 * because a figure computed without them is the exact divergence this type is meant to make
 * impossible.
 */
class CycleSnapshotTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    /** Five-day periods starting on each date. */
    private fun periods(vararg starts: String): List<LocalDate> =
        starts.flatMap { start -> (0L until 5L).map { date(start).plusDays(it) } }

    // One completed cycle of 30 days, then an open one. Too few to measure, so a stated length wins.
    private val bleeding = periods("2025-01-01", "2025-01-31")
    private val today = date("2025-02-10")

    @Test
    fun `a stated cycle length reaches the state, the window and the basis alike`() {
        val snapshot = CycleSnapshot.build(
            bleedingDays = bleeding,
            assumedDays = emptySet(),
            settings = UserCycleSettings(typicalCycleLength = 33),
            today = today,
        )

        assertEquals(33, snapshot.state.expectedCycleLength)
        assertEquals(33, snapshot.basis.expectedCycleLength)
        assertEquals(LengthSource.USER_STATED, snapshot.basis.source)
        assertEquals(date("2025-01-31").plusDays(33), snapshot.window!!.center)
    }

    @Test
    fun `the state is exactly what the engine gives with the same settings`() {
        val settings = UserCycleSettings(typicalCycleLength = 33, typicalPeriodLength = 4)
        val snapshot = CycleSnapshot.build(bleeding, emptySet(), settings, today)

        val direct = CycleEngine().stateFor(
            today,
            CycleProjector.project(bleeding),
            bleedingDays = bleeding.toSet(),
            userTypicalCycleLength = 33,
            userTypicalPeriodLength = 4,
        )
        assertEquals(direct, snapshot.state)
    }

    /** The phase guide, the mood widget and the doctor summary ask about days other than today. */
    @Test
    fun `other dates are resolved with the same settings`() {
        val withSetting = CycleSnapshot.build(
            bleeding, emptySet(), UserCycleSettings(typicalCycleLength = 40), today,
        )
        val without = CycleSnapshot.build(bleeding, emptySet(), UserCycleSettings(), today)

        // Day 21 of the open cycle: luteal on a 30-day cycle, still follicular on a 40-day one.
        val day21 = date("2025-01-31").plusDays(20)
        assertEquals(Phase.FOLLICULAR, withSetting.stateOn(day21).phase)
        assertEquals(Phase.LUTEAL, without.stateOn(day21).phase)
        assertEquals(40, withSetting.stateOn(day21).expectedCycleLength)
    }

    @Test
    fun `the spread setting widens the window and nothing else`() {
        val balanced = CycleSnapshot.build(bleeding, emptySet(), UserCycleSettings(), today)
        val wide = CycleSnapshot.build(
            bleeding, emptySet(), UserCycleSettings(windowSpread = WindowWidth.WIDE.multiplier), today,
        )

        assert(wide.window!!.spanDays > balanced.window!!.spanDays)
        assertEquals(balanced.state, wide.state)
    }

    @Test
    fun `assumed days are carried through to the projection`() {
        val snapshot = CycleSnapshot.build(
            bleedingDays = bleeding,
            assumedDays = periods("2025-01-01").toSet(),
            settings = UserCycleSettings(),
            today = today,
        )
        assertEquals(Source.ASSUMED, snapshot.projection.periods.first().source)
    }

    /** §6: with nothing logged there is no cycle day, no window and nothing to flag. */
    @Test
    fun `no logs means no data rather than a default`() {
        val snapshot = CycleSnapshot.build(
            emptyList(), emptySet(), UserCycleSettings(typicalCycleLength = 30), today,
        )

        assertFalse(snapshot.state.hasData)
        assertNull(snapshot.window)
        assertEquals(emptyList<HealthFlag>(), snapshot.flags)
    }

    @Test
    fun `bleeding today is read from the logs`() {
        val snapshot = CycleSnapshot.build(bleeding, emptySet(), UserCycleSettings(), date("2025-02-02"))
        assert(snapshot.state.isBleeding)
    }
}
