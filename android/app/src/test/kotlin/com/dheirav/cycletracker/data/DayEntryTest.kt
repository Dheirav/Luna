package com.dheirav.cycletracker.data

import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Symptom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Bleeding as three states: not answered, no, and yes.
 *
 * Before this, "no" and "not answered" were the same stored value. A day answered "No bleeding"
 * from the form, with nothing else on it, counted as empty and was deleted on Save, while the
 * snackbar said "Saved". And a day where only a symptom was logged showed as "No bleeding" on Today,
 * a claim the person never made. The council review of 5 Oct raised both, independently, three
 * times.
 */
class DayEntryTest {

    private val day = LocalDate.parse("2025-03-01")

    @Test
    fun `a day answered no bleeding is not empty and is kept`() {
        val no = DayEntry(day).answeringBleeding(false)
        assertFalse(no.isEmpty)
        assertEquals(false, no.bleeding)
    }

    @Test
    fun `a day with nothing answered is empty`() {
        assertTrue(DayEntry(day).isEmpty)
        assertNull(DayEntry(day).bleeding)
    }

    @Test
    fun `a symptom alone leaves bleeding unanswered`() {
        val symptomOnly = DayEntry(day, symptoms = mapOf(Symptom.ENERGY to 2))
        assertFalse(symptomOnly.isEmpty)
        assertNull("a symptom is not an answer about bleeding", symptomOnly.bleeding)
    }

    @Test
    fun `answering yes is bleeding, and clearing the answer drops the flow`() {
        val yes = DayEntry(day).answeringBleeding(true).copy(flow = FlowLevel.HEAVY)
        assertEquals(true, yes.bleeding)

        val cleared = yes.answeringBleeding(null)
        assertNull(cleared.bleeding)
        assertNull(cleared.flow)
        assertTrue(cleared.isEmpty)
    }

    @Test
    fun `answering no drops a flow left from a yes`() {
        val no = DayEntry(day).answeringBleeding(true).copy(flow = FlowLevel.LIGHT).answeringBleeding(false)
        assertEquals(false, no.bleeding)
        assertNull(no.flow)
    }

    /** Rows written before the answered flag existed: bleeding is still a yes, absence is unanswered. */
    @Test
    fun `a bleeding row counts as answered even without the flag`() {
        assertEquals(true, DayEntry(day, isBleeding = true).bleeding)
    }
}
