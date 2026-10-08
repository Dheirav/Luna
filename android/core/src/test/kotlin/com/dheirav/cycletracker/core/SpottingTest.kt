package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Spotting as its own answer (§2.5): recorded, never a period day, and it ends a period. */
class SpottingTest {

    private fun d(iso: String) = LocalDate.parse(iso)
    private fun run(from: String, n: Int) = (0 until n).map { d(from).plusDays(it.toLong()) }

    @Test
    fun `spotting days never start or extend a period`() {
        val p = CycleProjector.project(run("2026-03-01", 4), spottingDays = setOf(d("2026-03-05"), d("2026-03-15")))
        assertEquals(1, p.periods.size)
        assertEquals(d("2026-03-04"), p.periods.single().end)
    }

    @Test
    fun `a run of spotting days becomes one spotting event`() {
        val p = CycleProjector.project(run("2026-03-01", 4), spottingDays = run("2026-03-14", 2).toSet() + d("2026-03-20"))
        assertEquals(listOf(d("2026-03-14") to 2, d("2026-03-20") to 1), p.spotting.map { it.start to it.spanDays })
    }

    @Test
    fun `spotting between periods raises the between-periods flag`() {
        val p = CycleProjector.project(run("2026-03-01", 4) + run("2026-03-29", 4), spottingDays = setOf(d("2026-03-14")))
        val flags = HealthFlags.evaluate(p, today = d("2026-04-05"), expectedCycleLength = 28)
        assertTrue(flags.any { it.kind == HealthFlagKind.SPOTTING_BETWEEN_PERIODS })
    }

    @Test
    fun `spotting ends a running period, as a no would`() {
        val bleeding = run("2026-03-01", 2)
        val open = CycleSnapshot.build(bleeding, emptySet(), UserCycleSettings(), today = d("2026-03-04"))
        val closed = CycleSnapshot.build(
            bleeding, emptySet(), UserCycleSettings(), today = d("2026-03-04"),
            noBleedingDays = setOf(d("2026-03-03")), spottingDays = setOf(d("2026-03-03")),
        )
        // Open, the period counts at least the expected length; ended by spotting, it is two days.
        assertTrue(open.state.periodLength!! > 2)
        assertEquals(2, closed.state.periodLength)
    }
}
