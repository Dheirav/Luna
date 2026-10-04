package com.dheirav.cycletracker.ui

import com.dheirav.cycletracker.core.DayTag
import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.data.DaySummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Today says once today has been logged.
 *
 * The button used to read "Log today" whatever had happened, so the main screen never answered "have I
 * done it?" and logging produced nothing visible. The summary has to be short enough for one button
 * and faithful to what was stored: anchor words, never numbers, and no bleeding claim the user did not
 * make.
 */
class LoggedSummaryTest {

    private fun day(
        bleeding: Boolean = false,
        flow: FlowLevel? = null,
        symptoms: Map<Symptom, Int> = emptyMap(),
        tags: Set<DayTag> = emptySet(),
        notes: String = "",
        // Most cases here are an answered day; the unanswered case is tested on its own.
        answered: Boolean = true,
    ) = DaySummary(
        isBleeding = bleeding,
        isAssumed = false,
        flow = flow,
        hasNotes = notes.isNotBlank(),
        notes = notes,
        symptoms = symptoms,
        tags = tags,
        bleedingAnswered = answered || bleeding,
    )

    @Test
    fun `a day logged as no bleeding says so, because that is an observation`() {
        assertEquals("No bleeding", loggedSummary(day()))
    }

    @Test
    fun `bleeding names the flow when one was given`() {
        assertEquals("Bleeding, light", loggedSummary(day(bleeding = true, flow = FlowLevel.LIGHT)))
        assertEquals("Bleeding", loggedSummary(day(bleeding = true)))
    }

    @Test
    fun `symptoms use their anchor words in form order`() {
        val summary = day(symptoms = mapOf(Symptom.PAIN to 1, Symptom.ENERGY to 2))
        assertEquals("No bleeding · Energy OK · Pain mild", loggedSummary(summary))
    }

    @Test
    fun `a long day is cut to three items and says how many more`() {
        val summary = day(
            symptoms = mapOf(Symptom.ENERGY to 2, Symptom.PAIN to 1, Symptom.SLEEP to 3),
            tags = setOf(DayTag.entries.first()),
            notes = "x",
        )
        assertEquals("No bleeding · Energy OK · Pain mild · +3 more", loggedSummary(summary))
    }

    /** A symptom logged on its own says nothing about bleeding, and the line must not pretend it does. */
    @Test
    fun `an unanswered bleeding question is said to be unrecorded, not no`() {
        val summary = day(symptoms = mapOf(Symptom.ENERGY to 2), answered = false)
        assertEquals("Bleeding not recorded · Energy OK", loggedSummary(summary))
    }
}
