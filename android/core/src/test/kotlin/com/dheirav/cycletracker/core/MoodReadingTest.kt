package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule these tests exist to keep: a phase alone can never produce a face.
 *
 * Everything else here is detail. If a future change lets a cycle day drive the mascot, the app has
 * started telling people how they feel on arithmetic alone, and `a phase alone says nothing` is the
 * test that should stop it.
 */
class MoodReadingTest {

    /**
     * `days` logged days in `phase` at `level`, plus `elsewhereDays` in another phase at `elsewhere`.
     *
     * Both lambda parameters are named. Written with bare `it`, the inner `List(n) { … }` index
     * shadowed the outer `elsewhere?.let { … }`, so every "elsewhere" value was its own list index
     * and the comparison baseline was a ramp averaging 3.5 rather than the level asked for. Two
     * tests failed with the right expectations against a fixture that was quietly lying.
     */
    private fun history(
        phase: Phase,
        symptom: Symptom,
        level: Int,
        days: Int,
        elsewhere: Int?,
        elsewhereDays: Int = SymptomPatterns.MIN_DAYS_ELSEWHERE,
    ): List<PhaseObservation> {
        val other = Phase.entries.first { it != phase }
        val inPhase = List(days) { PhaseObservation(phase, mapOf(symptom to level)) }
        val outside = elsewhere?.let { elsewhereLevel ->
            List(elsewhereDays) { PhaseObservation(other, mapOf(symptom to elsewhereLevel)) }
        } ?: emptyList()
        return inPhase + outside
    }

    // -- the rule ----------------------------------------------------------

    @Test
    fun `a phase alone says nothing`() {
        val reading = MoodReadings.read(
            todaysSymptoms = emptyMap(),
            observations = emptyList(),
            phase = Phase.LUTEAL,
        )

        assertEquals(MoodFace.UNKNOWN, reading.face)
        assertEquals(MoodSource.NOTHING, reading.source)
        assertNull(reading.symptom)
        assertEquals(0, reading.daysObserved)
    }

    /** Logged, but not enough of it. Silence, not a neutral face standing in for one. */
    @Test
    fun `too few logged days stays unknown`() {
        val reading = MoodReadings.read(
            todaysSymptoms = emptyMap(),
            observations = history(
                Phase.LUTEAL, Symptom.LOW_MOOD, level = 4,
                days = SymptomPatterns.MIN_DAYS_IN_PHASE - 1, elsewhere = 0,
            ),
            phase = Phase.LUTEAL,
        )

        assertEquals(MoodFace.UNKNOWN, reading.face)
    }

    /** Energy is logged far more often and is not a mood. It must not drive this face. */
    @Test
    fun `non-mood symptoms are ignored however much is logged`() {
        val reading = MoodReadings.read(
            todaysSymptoms = mapOf(Symptom.ENERGY to 0, Symptom.PAIN to 4),
            observations = history(
                Phase.LUTEAL, Symptom.ENERGY, level = 0, days = 20, elsewhere = 4,
                elsewhereDays = 20,
            ),
            phase = Phase.LUTEAL,
        )

        assertEquals(MoodFace.UNKNOWN, reading.face)
        assertEquals(MoodSource.NOTHING, reading.source)
    }

    // -- today outranks the tendency ---------------------------------------

    /**
     * §5.2's shape: an observation of today beats an average of other days.
     *
     * The tendency here is strongly settled and today is logged overwhelming. Showing SETTLED would
     * be telling someone about themselves over their own head.
     */
    @Test
    fun `what was logged today beats what is usually logged`() {
        val settledHistory = history(
            Phase.LUTEAL, Symptom.ANXIETY, level = 0,
            days = 10, elsewhere = 4, elsewhereDays = 10,
        )

        val reading = MoodReadings.read(
            todaysSymptoms = mapOf(Symptom.ANXIETY to 4),
            observations = settledHistory,
            phase = Phase.LUTEAL,
        )

        assertEquals(MoodFace.HEAVY, reading.face)
        assertEquals(MoodSource.TODAY, reading.source)
        assertEquals(Symptom.ANXIETY, reading.symptom)
        assertEquals(4, reading.level)
    }

    /**
     * The worst of today's burdens, not their average.
     *
     * Averaging overwhelming anxiety with no irritability lands on "slight", which would show a calm
     * face to someone having a very bad day.
     */
    @Test
    fun `today takes the worst burden rather than averaging them`() {
        val reading = MoodReadings.read(
            todaysSymptoms = mapOf(
                Symptom.IRRITABILITY to 0,
                Symptom.ANXIETY to 4,
                Symptom.LOW_MOOD to 0,
            ),
            observations = emptyList(),
            phase = null,
        )

        assertEquals(MoodFace.HEAVY, reading.face)
        assertEquals(Symptom.ANXIETY, reading.symptom)
    }

    @Test
    fun `today with no phase still reads, because it needs none`() {
        val reading = MoodReadings.read(
            todaysSymptoms = mapOf(Symptom.STRESS to 1),
            observations = emptyList(),
            phase = null,
        )

        assertEquals(MoodFace.SETTLED, reading.face)
        assertEquals(MoodSource.TODAY, reading.source)
    }

    // -- the tendency ------------------------------------------------------

    @Test
    fun `a burden usually logged high in this phase reads heavy, with its days`() {
        val reading = MoodReadings.read(
            todaysSymptoms = emptyMap(),
            observations = history(
                Phase.LUTEAL, Symptom.IRRITABILITY, level = 4,
                days = 8, elsewhere = 0, elsewhereDays = 8,
            ),
            phase = Phase.LUTEAL,
        )

        assertEquals(MoodFace.HEAVY, reading.face)
        assertEquals(MoodSource.TENDENCY, reading.source)
        assertEquals(Symptom.IRRITABILITY, reading.symptom)
        assertEquals(8, reading.daysObserved)
    }

    @Test
    fun `a burden usually logged lower in this phase reads settled`() {
        val reading = MoodReadings.read(
            todaysSymptoms = emptyMap(),
            observations = history(
                Phase.FOLLICULAR, Symptom.LOW_MOOD, level = 0,
                days = 8, elsewhere = 4, elsewhereDays = 8,
            ),
            phase = Phase.FOLLICULAR,
        )

        assertEquals(MoodFace.SETTLED, reading.face)
        assertEquals(MoodSource.TENDENCY, reading.source)
    }

    /**
     * Enough logged, nothing unusual — a finding, and not the same as knowing nothing.
     *
     * No symptom is named, because the finding is that none stood out. Naming one anyway would
     * manufacture the pattern `standsOut` exists to refuse.
     */
    @Test
    fun `enough logged with nothing unusual reads steady and names no symptom`() {
        val reading = MoodReadings.read(
            todaysSymptoms = emptyMap(),
            observations = history(
                Phase.LUTEAL, Symptom.STRESS, level = 2,
                days = 8, elsewhere = 2, elsewhereDays = 8,
            ),
            phase = Phase.LUTEAL,
        )

        assertEquals(MoodFace.STEADY, reading.face)
        assertEquals(MoodSource.TENDENCY, reading.source)
        assertNull("steady must not name a symptom", reading.symptom)
        assertEquals(8, reading.daysObserved)
    }

    /** A tendency is never presented as a reading about today, whatever face it wears. */
    @Test
    fun `a tendency never claims to be todays level`() {
        val reading = MoodReadings.read(
            todaysSymptoms = emptyMap(),
            observations = history(
                Phase.LUTEAL, Symptom.IRRITABILITY, level = 4,
                days = 8, elsewhere = 0, elsewhereDays = 8,
            ),
            phase = Phase.LUTEAL,
        )

        assertNull(reading.level)
    }
}
