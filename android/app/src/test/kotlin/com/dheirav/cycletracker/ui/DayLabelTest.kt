package com.dheirav.cycletracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The name the log form and the "Saved" snackbar give a day.
 *
 * Shared because the snackbar confirms *which* day was written, and that only helps if it names the
 * day the way the form header did a moment earlier. "Saved" alone cannot catch a save to the wrong
 * day after the date arrows were tapped.
 */
class DayLabelTest {

    private val today = LocalDate.parse("2025-03-05")

    @Test
    fun `today and yesterday are named in words`() {
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Yesterday", dayLabel(today.minusDays(1), today))
    }

    @Test
    fun `older days carry the weekday so a slip of one day shows`() {
        Locale.setDefault(Locale.UK)
        assertEquals("Mon 3 Mar", dayLabel(today.minusDays(2), today))
    }
}
