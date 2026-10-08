package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What the Insights charts draw, and when a "typical" figure is allowed to exist. */
class InsightsTest {

    private fun d(iso: String) = LocalDate.parse(iso)
    private fun run(from: String, n: Int) = (0 until n).map { d(from).plusDays(it.toLong()) }

    @Test
    fun `two observed cycles are drawn but earn no typical length`() {
        val p = CycleProjector.project(run("2026-03-02", 4) + run("2026-03-30", 4) + run("2026-04-27", 4))
        val i = Insights.build(p)
        assertEquals(listOf(28, 28), i.cycles.map { it.length })
        assertNull("two cycles are not a typical length", i.typicalCycle)
        assertEquals(1, i.cyclesNeeded)
    }

    @Test
    fun `three observed cycles give a typical length and spread`() {
        val p = CycleProjector.project(run("2026-01-30", 4) + run("2026-02-27", 4) + run("2026-03-28", 4) + run("2026-04-24", 4))
        val i = Insights.build(p)
        assertEquals(listOf(28, 29, 27), i.cycles.map { it.length })
        assertEquals(28, i.typicalCycle)
        assertTrue(i.cycleSpread != null)
        assertEquals(0, i.cyclesNeeded)
    }

    @Test
    fun `estimated cycles are drawn as estimated and never count toward typical`() {
        val assumed = (run("2025-11-30", 4) + run("2025-12-28", 4)).toSet()
        val p = CycleProjector.project(assumed + run("2026-01-25", 4) + run("2026-02-22", 4), assumedDays = assumed)
        val i = Insights.build(p)
        assertEquals(listOf(false, false, true), i.cycles.map { it.observed })
        assertNull(i.typicalCycle)
        assertEquals(2, i.cyclesNeeded)
    }

    @Test
    fun `a period's logged and estimated days are kept apart`() {
        val assumed = run("2026-03-04", 4).toSet()
        val p = CycleProjector.project(listOf(d("2026-03-03")) + assumed, assumedDays = assumed)
        val bar = Insights.build(p, noBleedingDays = setOf(d("2026-03-09"))).periods.single()
        assertEquals(1, bar.loggedDays)
        assertEquals(4, bar.estimatedDays)
    }

    /** Found on the Redmi: two logged days, never answered as over, drawn as a finished 2-day period. */
    @Test
    fun `a period not answered as over has no length yet and is left out`() {
        val p = CycleProjector.project(run("2026-03-02", 5) + run("2026-03-30", 2))
        val open = Insights.build(p)
        assertEquals(listOf(d("2026-03-02")), open.periods.map { it.start })
        assertEquals(d("2026-03-30"), open.unfinishedPeriod)
        assertNull("one finished period is not a typical length", open.typicalPeriod)

        val closed = Insights.build(p, noBleedingDays = setOf(d("2026-04-01")))
        assertEquals(listOf(5, 2), closed.periods.map { it.totalDays })
        assertNull(closed.unfinishedPeriod)
    }

    @Test
    fun `nothing logged draws nothing`() {
        assertTrue(Insights.build(Projection.Empty).isEmpty)
        // One period, still open: nothing to chart yet, but not "nothing logged" either.
        assertFalse(Insights.build(CycleProjector.project(run("2026-03-02", 3))).isEmpty)
    }
}
