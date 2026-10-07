package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A period whose first day was logged and whose remaining days came from backfill.
 *
 * Found by the device review on 8 Oct 2026 (B1): such a period took its source from its start day
 * alone, so it was "observed" as a whole. Four estimated days then reached the doctor summary as a
 * five-day observed period, the period-length median and the prolonged-bleeding flag. Its start is
 * still a genuine observation, which is what cycle length needs; its length is not.
 */
class MixedPeriodTest {

    private fun d(iso: String) = LocalDate.parse(iso)
    private fun run(from: String, days: Int) = (0 until days).map { d(from).plusDays(it.toLong()) }

    /** Three periods: logged start + 4 backfilled days, then two wholly logged 3-day periods. */
    private val mixedStart = d("2026-03-03")
    private val assumed = run("2026-03-04", 4).toSet()
    private val bleeding = listOf(mixedStart) + assumed + run("2026-03-31", 3) + run("2026-04-28", 3)
    private val projection = CycleProjector.project(bleeding, assumedDays = assumed)

    @Test
    fun `the mixed period records which of its days were estimated`() {
        val mixed = projection.periods.first()
        assertEquals(Source.OBSERVED, mixed.source)
        assertEquals(5, mixed.bleedingDayCount)
        assertEquals(4, mixed.estimatedDayCount)
        assertEquals(1, mixed.loggedDayCount)
        assertFalse(mixed.whollyObserved)
        assertTrue(projection.periods.drop(1).all { it.whollyObserved })
    }

    @Test
    fun `its start still counts for cycle length`() {
        // Cycle 1 runs from the mixed period's logged start to the next logged start: both observed.
        assertEquals(Source.OBSERVED, projection.cycles.first().source)
    }

    @Test
    fun `its length is left out of the period-length median`() {
        // With the mixed period counted, the median of 5, 3, 3 is still 3, so check with only one
        // wholly observed period left: that is below the two needed, so the default applies.
        val two = CycleProjector.project(listOf(mixedStart) + assumed + run("2026-03-31", 3), assumedDays = assumed)
        assertEquals(CycleConfig.Default.defaultPeriodLength, CycleStats.expectedPeriodLength(two.periods))
    }

    @Test
    fun `backfilled days cannot make a period prolonged`() {
        val longTail = run("2026-03-04", 9).toSet()
        val p = CycleProjector.project(listOf(mixedStart) + longTail, assumedDays = longTail)
        val flags = HealthFlags.evaluate(p, today = d("2026-03-21"), expectedCycleLength = 28)
        assertTrue(flags.none { it.kind == HealthFlagKind.BLEEDING_PROLONGED })
    }

    @Test
    fun `the summary tags it and says how many days were logged`() {
        val text = ClinicalSummary.build(projection, d("2026-02-09"), 28)
        assertTrue(text, text.contains("5 days, 1 logged, 4 estimated"))
        val rows = ClinicalSummary.document(projection, d("2026-02-09"), 28)
            .sections.single { it.title.startsWith("PERIODS") }.items.filterIsInstance<SummaryItem.Row>()
        assertEquals(listOf(true, false, false), rows.map { ClinicalSummary.ESTIMATED in it.tags })
        assertTrue(text, text.contains("3 days, all bleeding"))
    }
}
