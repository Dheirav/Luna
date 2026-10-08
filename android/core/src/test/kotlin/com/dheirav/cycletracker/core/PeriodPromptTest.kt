package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Which period question Today asks, and the rule behind the silent case: lateness is only earned by
 * someone who was logging.
 */
class PeriodPromptTest {

    private fun d(iso: String) = LocalDate.parse(iso)
    private fun days(from: String, n: Int) = (0 until n).map { d(from).plusDays(it.toLong()) }.toSet()

    /** A window that has passed, with nothing logged after it opened. */
    private val window = PeriodWindow(d("2026-04-03"), d("2026-04-02"), d("2026-04-04"), 1, WindowBasis.MEASURED, 3)
    private val august = days("2026-03-06", 2)

    @Test
    fun `nothing logged since before the window means silent, not late`() {
        val logged = august + d("2026-04-01")
        val since = PeriodPrompt.silentSince(window, logged, today = d("2026-04-18"))
        assertEquals(d("2026-04-01"), since)
        assertEquals(PeriodPrompt.SILENT, PeriodPrompt.forToday(d("2026-04-18"), august, setOf(d("2026-04-01")), window, since))
    }

    @Test
    fun `one answer on or after the window opened earns the lateness`() {
        val logged = august + d("2026-04-01") + d("2026-04-10")
        assertNull(PeriodPrompt.silentSince(window, logged, today = d("2026-04-18")))
    }

    @Test
    fun `an open window is never silent`() {
        assertNull(PeriodPrompt.silentSince(window, august, today = d("2026-04-03")))
    }

    @Test
    fun `a late cycle that was being logged asks whether it has started`() {
        val no = setOf(d("2026-04-10"))
        assertEquals(PeriodPrompt.STARTED, PeriodPrompt.forToday(d("2026-04-18"), august, no, window, silentSince = null))
    }

    @Test
    fun `a running period asks whether it has stopped`() {
        val bleeding = august + days("2026-04-03", 3)
        assertEquals(PeriodPrompt.STOPPED, PeriodPrompt.forToday(d("2026-04-06"), bleeding, emptySet(), window, null))
    }

    @Test
    fun `a period answered as over is not running`() {
        val bleeding = august + days("2026-04-03", 3)
        val no = setOf(d("2026-04-06"))
        assertEquals(PeriodPrompt.STARTED, PeriodPrompt.forToday(d("2026-04-07"), bleeding, no, window, null))
    }

    @Test
    fun `nothing is asked once today is answered, or before the window`() {
        assertEquals(PeriodPrompt.NONE, PeriodPrompt.forToday(d("2026-04-18"), august, setOf(d("2026-04-18")), window, null))
        assertEquals(PeriodPrompt.NONE, PeriodPrompt.forToday(d("2026-03-21"), august, emptySet(), window, null))
    }

    @Test
    fun `while silent, neither the late nor the absent flag is raised`() {
        val projection = CycleProjector.project(days("2025-11-09", 3) + days("2025-12-07", 3) + days("2026-01-04", 3))
        val today = d("2026-04-18")
        val loud = HealthFlags.evaluate(projection, today, 28)
        assertTrue("absent is raised when the silence is ignored", loud.any { it.kind == HealthFlagKind.PERIOD_ABSENT })
        val quiet = HealthFlags.evaluate(projection, today, 28, silentSince = d("2026-01-06"))
        assertTrue(quiet.none { it.kind == HealthFlagKind.PERIOD_ABSENT || it.kind == HealthFlagKind.PERIOD_LATE })
    }

    @Test
    fun `the summary says the cycle length is unknown while silent`() {
        val projection = CycleProjector.project(august)
        val text = ClinicalSummary.build(projection, d("2026-04-18"), 28) // not silent
        assertTrue(text, text.contains("Cycle day today"))
        val silent = ClinicalSummary.text(ClinicalSummary.document(projection, d("2026-04-18"), 28, silentSince = d("2026-04-01")))
        assertTrue(silent, silent.contains("nothing logged since") && !silent.contains("Cycle day today"))
    }
}
