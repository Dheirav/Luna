package com.dheirav.cycletracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The spreadsheet export: blank stays blank, words not codes, and text that would break a CSV. */
class CsvExportTest {

    private fun d(iso: String) = LocalDate.parse(iso)

    private val logs = listOf(
        DailyLogEntity(date = d("2026-03-02"), isBleeding = true, bleedingAnswered = true, flow = "HEAVY", notes = "cramps, then \"better\""),
        DailyLogEntity(date = d("2026-03-01"), isBleeding = false, bleedingAnswered = true, flow = SPOTTING_FLOW),
        DailyLogEntity(date = d("2026-03-03"), isBleeding = false, bleedingAnswered = false, notes = "line one\nline two"),
        DailyLogEntity(date = d("2026-03-04"), isBleeding = true, bleedingAnswered = true, source = "ASSUMED"),
    )
    private val csv = CsvExport.build(
        logs,
        listOf(SymptomValueEntity(d("2026-03-02"), "energy", 1)),
        listOf(DayTagEntity(d("2026-03-02"), "travel")),
    )
    private val lines = csv.split("\r\n")

    @Test
    fun `one row per day, oldest first, after a header`() {
        assertTrue(lines[0].startsWith("Date,Bleeding,Flow,Recorded by,Energy"))
        assertTrue(lines[1].startsWith("2026-03-01,Spotting,"))
        assertTrue(lines[2].startsWith("2026-03-02,Yes,Heavy,You,Low,"))
    }

    @Test
    fun `an unanswered question is a blank cell, never no`() {
        assertTrue(lines[3].startsWith("2026-03-03,,,You,"))
    }

    @Test
    fun `estimated days say so`() {
        assertTrue(csv.contains("2026-03-04,Yes,,Estimated by Luna,"))
    }

    @Test
    fun `commas, quotes and line breaks in notes survive`() {
        assertTrue(csv.contains("\"cramps, then \"\"better\"\"\""))
        assertTrue(csv.contains("\"line one\nline two\""))
        assertTrue(csv.contains(",Travel,"))
    }

    @Test
    fun `every row has as many columns as the header`() {
        // Parse properly rather than split on commas, since quoted cells may hold commas.
        fun count(row: String): Int {
            var n = 1; var quoted = false
            row.forEach { c -> if (c == '"') quoted = !quoted else if (c == ',' && !quoted) n++ }
            return n
        }
        val rows = Regex("(?:[^\"\\r\\n]|\"(?:[^\"]|\"\")*\")+").findAll(csv.trimEnd()).map { it.value }.filter { it.isNotBlank() }.toList()
        assertEquals(5, rows.size)
        rows.forEach { assertEquals(it, count(rows[0]), count(it)) }
    }
}
