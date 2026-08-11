package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests are mostly about refusing to speak.
 *
 * The failure mode this guards against is the one every cycle app has: inventing a personal
 * pattern from three data points and presenting it as insight. Silence is the correct output far
 * more often than a summary is.
 */
class SymptomPatternsTest {

    private fun days(phase: Phase, count: Int, vararg values: Pair<Symptom, Int>) =
        List(count) { PhaseObservation(phase, values.toMap()) }

    @Test
    fun `says nothing until there are enough days in the phase`() {
        val four = days(Phase.LUTEAL, 4, Symptom.ENERGY to 1)

        assertTrue(SymptomPatterns.summarise(four, Phase.LUTEAL).isEmpty())
        assertEquals(1, SymptomPatterns.summarise(four + days(Phase.LUTEAL, 1, Symptom.ENERGY to 1), Phase.LUTEAL).size)
    }

    /** Rule 2 — a day where the symptom was not logged is not a zero, and averaging it as one
     *  would drag every mean toward the floor. */
    @Test
    fun `days without the symptom logged are excluded, not counted as zero`() {
        val logged = days(Phase.LUTEAL, 5, Symptom.PAIN to 4)
        val blank = List(20) { PhaseObservation(Phase.LUTEAL, emptyMap()) }

        val summary = SymptomPatterns.summarise(logged + blank, Phase.LUTEAL).single()

        assertEquals(5, summary.daysObserved)
        assertEquals(4.0, summary.phaseMean, 1e-9)
    }

    @Test
    fun `no comparison is drawn without enough days outside the phase`() {
        val observations = days(Phase.LUTEAL, 6, Symptom.ENERGY to 1) +
            days(Phase.FOLLICULAR, 2, Symptom.ENERGY to 4)

        val summary = SymptomPatterns.summarise(observations, Phase.LUTEAL).single()

        assertNull(summary.elsewhereMean)
        assertFalse(summary.standsOut)
        assertNull(summary.worseHere)
    }

    /** A gap inside the noise of an ordinary week must not be reported as a pattern. */
    @Test
    fun `a small difference does not stand out`() {
        val observations = days(Phase.LUTEAL, 6, Symptom.ENERGY to 2) +
            days(Phase.FOLLICULAR, 6, Symptom.ENERGY to 2) +
            days(Phase.OVULATION, 1, Symptom.ENERGY to 3)

        val summary = SymptomPatterns.summarise(observations, Phase.LUTEAL).single()

        assertFalse(summary.standsOut)
    }

    @Test
    fun `a clear difference stands out`() {
        val observations = days(Phase.LUTEAL, 6, Symptom.ENERGY to 1) +
            days(Phase.FOLLICULAR, 6, Symptom.ENERGY to 4)

        val summary = SymptomPatterns.summarise(observations, Phase.LUTEAL).single()

        assertTrue(summary.standsOut)
        assertEquals(1.0, summary.phaseMean, 1e-9)
        assertEquals(4.0, summary.elsewhereMean!!, 1e-9)
    }

    // -- scale direction ---------------------------------------------------

    /** Energy runs low-to-good, so *less* of it here is worse. */
    @Test
    fun `lower energy in the phase counts as worse`() {
        val observations = days(Phase.LUTEAL, 6, Symptom.ENERGY to 1) +
            days(Phase.FOLLICULAR, 6, Symptom.ENERGY to 4)

        assertEquals(true, SymptomPatterns.summarise(observations, Phase.LUTEAL).single().worseHere)
    }

    /** Pain runs the other way — more of it is worse. Without the direction flag the app would
     *  report severe pain as an improvement. */
    @Test
    fun `higher pain in the phase counts as worse`() {
        val observations = days(Phase.MENSTRUATION, 6, Symptom.PAIN to 4) +
            days(Phase.FOLLICULAR, 6, Symptom.PAIN to 0)

        val summary = SymptomPatterns.summarise(observations, Phase.MENSTRUATION).single()

        assertEquals(true, summary.worseHere)
    }

    @Test
    fun `better in this phase is reported as better`() {
        val observations = days(Phase.FOLLICULAR, 6, Symptom.SLEEP to 4) +
            days(Phase.LUTEAL, 6, Symptom.SLEEP to 1)

        assertEquals(false, SymptomPatterns.summarise(observations, Phase.FOLLICULAR).single().worseHere)
    }

    // -- presentation ------------------------------------------------------

    @Test
    fun `the mean is labelled with the symptom's own anchor word`() {
        val observations = days(Phase.LUTEAL, 6, Symptom.ENERGY to 1)

        assertEquals("Low", SymptomPatterns.summarise(observations, Phase.LUTEAL).single().label())
    }

    @Test
    fun `the most distinctive symptom is listed first`() {
        val observations = days(
            Phase.LUTEAL, 6,
            Symptom.ENERGY to 2, Symptom.PAIN to 4,
        ) + days(
            Phase.FOLLICULAR, 6,
            Symptom.ENERGY to 3, Symptom.PAIN to 0,
        )

        val summaries = SymptomPatterns.summarise(observations, Phase.LUTEAL)

        assertEquals(Symptom.PAIN, summaries.first().symptom)
    }

    /** Days the engine could not place in a phase are excluded from both sides of the comparison
     *  rather than silently grouped as "elsewhere". */
    @Test
    fun `unplaced days are ignored entirely`() {
        val observations = days(Phase.LUTEAL, 6, Symptom.ENERGY to 1) +
            List(9) { PhaseObservation(null, mapOf(Symptom.ENERGY to 4)) }

        val summary = SymptomPatterns.summarise(observations, Phase.LUTEAL).single()

        assertNull("unplaced days must not become a comparison group", summary.elsewhereMean)
    }

    @Test
    fun `an empty history says nothing rather than failing`() {
        assertTrue(SymptomPatterns.summarise(emptyList(), Phase.LUTEAL).isEmpty())
    }
}
