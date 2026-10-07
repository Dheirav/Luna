package com.dheirav.cycletracker.widget

import com.dheirav.cycletracker.core.MoodFace
import com.dheirav.cycletracker.core.MoodReading
import com.dheirav.cycletracker.core.MoodSource
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.Symptom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mood widget's sentence must match the direction of the data (council review H6): a symptom can
 * stand out because it is logged less in this phase, and the widget used to say "you often log" it.
 */
class MoodWordingTest {

    private fun tendency(face: MoodFace) =
        MoodReading(face, Symptom.LOW_MOOD, MoodSource.TENDENCY, daysObserved = 8)

    @Test
    fun `logged more than usual reads as often`() {
        assertEquals("You often log low mood around now", wording(tendency(MoodFace.HEAVY), Phase.LUTEAL).first)
    }

    @Test
    fun `logged less than usual reads as less, not often`() {
        val headline = wording(tendency(MoodFace.SETTLED), Phase.LUTEAL).first
        assertEquals("Less low mood than usual around now", headline)
        assertTrue(!headline.contains("often"))
    }
}
