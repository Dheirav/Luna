package com.dheirav.cycletracker.ui

import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.data.DayEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Whether the log form holds something that leaving would throw away.
 *
 * Back used to return to the previous screen unconditionally, so a filled-in day vanished on a stray
 * gesture, and the date arrows did the same by loading the next day over it. The guard that now asks
 * first is only as good as this flag: a false positive nags on every exit, which teaches people to tap
 * through it, and a false negative is the original bug.
 */
class LogUiStateTest {

    private val day = LocalDate.parse("2025-03-01")
    private val saved = DayEntry(day, isBleeding = true, flow = FlowLevel.LIGHT, exists = true)

    private fun opened(entry: DayEntry) = LogUiState(entry = entry, original = entry, loading = false)

    @Test
    fun `a day just opened has nothing to lose`() {
        assertFalse(opened(saved).dirty)
    }

    @Test
    fun `an edit is something to lose`() {
        val state = opened(saved)
        assertTrue(state.copy(entry = state.entry.copy(flow = FlowLevel.HEAVY)).dirty)
    }

    /** Tapping a level and tapping it again is how the form unsets a value; it should not nag. */
    @Test
    fun `an edit undone by hand is nothing to lose`() {
        val state = opened(saved)
        val touched = state.copy(entry = state.entry.copy(symptoms = mapOf(Symptom.core.first() to 1)))
        val untouched = touched.copy(entry = touched.entry.copy(symptoms = emptyMap()))
        assertFalse(untouched.dirty)
    }

    @Test
    fun `typing a note on an empty day is something to lose`() {
        val state = opened(DayEntry(day))
        assertTrue(state.copy(entry = state.entry.copy(notes = "cramps after lunch")).dirty)
    }

    /** Before the day has loaded there is no original to compare with, and nothing typed yet. */
    @Test
    fun `nothing is lost while the day is still loading`() {
        assertFalse(LogUiState(entry = DayEntry(day, notes = "x"), loading = true).dirty)
    }
}
