package com.dheirav.cycletracker.ui

import com.dheirav.cycletracker.core.LengthSource
import com.dheirav.cycletracker.core.MoodFace
import com.dheirav.cycletracker.ui.theme.MascotMood
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two things on Today's hero that used to claim more than the data had earned.
 *
 * "of a 28-day cycle" read the same whether 28 was the median of cycles the user logged or a
 * population default describing nobody. And the mascot's face followed the phase, so it smiled on
 * day 41 of a 28-day cycle: a mood inferred from a calendar, which the project rules forbid.
 */
class TodayWordingTest {

    @Test
    fun `a measured length is stated plainly`() {
        assertEquals("of a 28-day cycle", cycleLengthPhrase(28, LengthSource.MEDIAN_OF_OBSERVED))
    }

    @Test
    fun `a length the user stated says it came from them`() {
        assertEquals("of a 31-day cycle, from your setting", cycleLengthPhrase(31, LengthSource.USER_STATED))
    }

    @Test
    fun `a length the app assumed says it was assumed`() {
        assertEquals("of a cycle assumed to be 28 days", cycleLengthPhrase(28, LengthSource.APP_DEFAULT))
        assertEquals(
            "of a cycle assumed to be 29 days",
            cycleLengthPhrase(29, LengthSource.MEDIAN_WITH_ESTIMATES),
        )
    }

    @Test
    fun `before the basis has loaded the length is stated plainly`() {
        assertEquals("of a 28-day cycle", cycleLengthPhrase(28, null))
    }

    // -- mascot ---------------------------------------------------------------

    @Test
    fun `with nothing logged today the mascot rests and does not smile`() {
        assertEquals(MascotMood.RESTING, mascotMoodFor(null))
        assertEquals(MascotMood.RESTING, mascotMoodFor(MoodFace.UNKNOWN))
    }

    @Test
    fun `the face follows what was logged today`() {
        assertEquals(MascotMood.CALM, mascotMoodFor(MoodFace.SETTLED))
        assertEquals(MascotMood.RESTING, mascotMoodFor(MoodFace.STEADY))
        assertEquals(MascotMood.TENDER, mascotMoodFor(MoodFace.HEAVY))
    }

    // -- the gap question ------------------------------------------------------

    private fun d(iso: String) = java.time.LocalDate.parse(iso)

    @Test
    fun `the gap question names each day, with the month once`() {
        java.util.Locale.setDefault(java.util.Locale.UK)
        assertEquals("Were you bleeding on Sat 4 and Sun 5 Oct?", gapQuestion(listOf(d("2025-10-04"), d("2025-10-05"))))
        assertEquals(
            "Were you bleeding on Sat 4, Sun 5 and Mon 6 Oct?",
            gapQuestion(listOf(d("2025-10-04"), d("2025-10-05"), d("2025-10-06"))),
        )
    }

    @Test
    fun `a gap across a month end names both months`() {
        java.util.Locale.setDefault(java.util.Locale.UK)
        assertEquals(
            "Were you bleeding on Fri 31 Jan and Sat 1 Feb?",
            gapQuestion(listOf(d("2025-01-31"), d("2025-02-01"))),
        )
    }
}
