package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The summary's structure, which the preview and the PDF draw from.
 *
 * The plain text was checked byte for byte against the old builder when the summary became
 * structured. These pin what the visual renderings rely on: every section present in order, and the
 * ESTIMATED and IN PROGRESS markers carried as tags on the right items, since a renderer that only
 * draws tags must still never show an estimated period as observed.
 */
class SummaryDocumentTest {

    private fun date(iso: String) = LocalDate.parse(iso)
    private fun period(start: String, source: Source) = Period(date(start), date(start).plusDays(4), 5, 5, source)

    private val doc = ClinicalSummary.document(
        CycleProjector.fromPeriods(
            listOf(period("2024-03-01", Source.ASSUMED), period("2024-03-29", Source.OBSERVED), period("2024-04-26", Source.ASSUMED)),
        ),
        today = date("2024-05-10"),
        expectedCycleLength = 28,
    )

    @Test
    fun `every section is present, in order, even when empty`() {
        assertEquals(
            listOf(
                "OVERVIEW", "CYCLES (most recent last)", "PERIODS (bleeding days per episode)",
                "PAIN DURING PERIODS", "BLEEDING BETWEEN PERIODS", "PATTERNS THE APP FLAGGED", "SYMPTOMS BY PHASE",
            ),
            doc.sections.map { it.title },
        )
        assertTrue(doc.sections.all { it.items.isNotEmpty() })
    }

    @Test
    fun `estimated periods and cycles carry the tag, observed ones do not`() {
        val periods = doc.sections.single { it.title.startsWith("PERIODS") }.items.filterIsInstance<SummaryItem.Row>()
        assertEquals(listOf(true, false, true), periods.map { ClinicalSummary.ESTIMATED in it.tags })

        val cycles = doc.sections.single { it.title.startsWith("CYCLES") }.items.filterIsInstance<SummaryItem.Row>()
        assertEquals("first completed cycle is estimated", true, ClinicalSummary.ESTIMATED in cycles.first().tags)
    }

    @Test
    fun `the cycle in progress is tagged, and estimated when it started from an estimate`() {
        val cycles = doc.sections.single { it.title.startsWith("CYCLES") }.items.filterIsInstance<SummaryItem.Row>()
        assertEquals(listOf(ClinicalSummary.IN_PROGRESS, ClinicalSummary.ESTIMATED), cycles.last().tags)
    }

    @Test
    fun `the most recent period figure is tagged when estimated`() {
        val figure = doc.sections.first().items.filterIsInstance<SummaryItem.Figure>()
            .single { it.label == "Most recent period began" }
        assertEquals(listOf(ClinicalSummary.ESTIMATED), figure.tags)
    }

    @Test
    fun `provenance comes before anything else`() {
        assertTrue(doc.preamble.first().contains("Not a medical record"))
    }
}
