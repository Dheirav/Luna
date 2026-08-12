package com.dheirav.cycletracker.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The summary is read by someone with no context, little time, and the authority to act on it.
 *
 * The failure that matters is not a typo — it is a clinician reading "12 cycles, median 28 days"
 * and taking it for twelve measurements when two were remembered and ten were extrapolated by
 * this app. Most of these tests exist to keep that impossible.
 */
class ClinicalSummaryTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    private fun periods(vararg spec: Pair<String, Source>) =
        spec.map { (start, source) ->
            Period(date(start), date(start).plusDays(4), 5, 5, source)
        }

    private fun summary(
        projection: Projection,
        today: String = "2024-06-01",
        expected: Int = 28,
        flags: List<HealthFlag> = emptyList(),
        symptoms: List<PhaseSymptomSummary> = emptyList(),
    ) = ClinicalSummary.build(projection, date(today), expected, flags, symptoms)

    // -- the distinction that matters --------------------------------------

    @Test
    fun `observed and estimated cycles are counted separately`() {
        val projection = CycleProjector.fromPeriods(
            periods(
                "2024-01-01" to Source.ASSUMED,
                "2024-01-29" to Source.ASSUMED,
                "2024-02-26" to Source.OBSERVED,
                "2024-03-25" to Source.OBSERVED,
            ),
        )

        val text = summary(projection)

        assertTrue(text, text.contains("of which observed"))
        assertTrue(text, text.contains("of which estimated"))
    }

    @Test
    fun `estimated cycles are marked on every row that lists one`() {
        val projection = CycleProjector.fromPeriods(
            periods("2024-01-01" to Source.ASSUMED, "2024-01-29" to Source.ASSUMED),
        )

        val text = summary(projection)

        assertTrue("an extrapolated cycle must say so", text.contains("ESTIMATED"))
    }

    @Test
    fun `a summary always states that it is self-reported and not a medical record`() {
        val text = summary(CycleProjector.fromPeriods(periods("2024-01-01" to Source.OBSERVED)))

        assertTrue(text, text.contains("Not a medical record"))
        assertTrue(text, text.contains("Self-reported"))
    }

    /** §3.2 again: a median over extrapolated cycles is the app quoting its own assumption. */
    @Test
    fun `no median is reported without three observed cycles`() {
        val projection = CycleProjector.fromPeriods(
            periods(
                "2024-01-01" to Source.ASSUMED,
                "2024-01-29" to Source.ASSUMED,
                "2024-02-26" to Source.ASSUMED,
                "2024-03-25" to Source.ASSUMED,
            ),
        )

        val text = summary(projection)

        assertTrue(text, text.contains("Median observed length ..... not enough observed cycles"))
    }

    @Test
    fun `a median is reported once three cycles have been observed`() {
        val projection = CycleProjector.fromPeriods(
            periods(
                "2024-01-01" to Source.OBSERVED,
                "2024-01-29" to Source.OBSERVED,
                "2024-02-26" to Source.OBSERVED,
                "2024-03-25" to Source.OBSERVED,
            ),
        )

        val text = summary(projection)

        assertTrue(text, text.contains("Median observed length ..... 28 days"))
    }

    // -- content -----------------------------------------------------------

    @Test
    fun `the open cycle is marked in progress rather than given a length`() {
        val projection = CycleProjector.fromPeriods(
            periods("2024-04-01" to Source.OBSERVED, "2024-04-29" to Source.OBSERVED),
        )

        val text = summary(projection, today = "2024-05-10")

        assertTrue(text, text.contains("IN PROGRESS"))
    }

    @Test
    fun `bleeding between periods gets its own section`() {
        val bleeding = listOf(
            date("2024-03-01"), date("2024-03-02"), date("2024-03-03"),
            date("2024-03-10"),
            date("2024-03-29"), date("2024-03-30"),
        )
        val projection = CycleProjector.project(bleeding)

        val text = summary(projection, today = "2024-04-01")

        assertTrue(text, text.contains("BLEEDING BETWEEN PERIODS"))
        assertTrue(text, text.contains("10 Mar 2024"))
    }

    @Test
    fun `flags are carried across with their evidence`() {
        val flag = HealthFlag(
            kind = HealthFlagKind.CYCLES_LONG,
            headline = "2 of your last 4 cycles ran long",
            detail = "They were 44, 47 days.",
            on = date("2024-05-01"),
        )

        val text = summary(
            CycleProjector.fromPeriods(periods("2024-01-01" to Source.OBSERVED)),
            flags = listOf(flag),
        )

        assertTrue(text, text.contains("PATTERNS THE APP FLAGGED"))
        assertTrue("the numbers must travel with the claim", text.contains("They were 44, 47 days."))
    }

    /**
     * Every section appears, and says so when it is empty.
     *
     * This test used to assert the opposite, with the reasoning that "a clinician reading an empty
     * heading has to work out whether it means none or not recorded". That problem is real and the
     * remedy was wrong: an empty heading is ambiguous, but a *missing* heading is invisible, and a
     * reader cannot interrogate something they never saw. So neither — the heading stays and states
     * its own emptiness, which is what the app already does everywhere else it says "blank means
     * unknown, not zero".
     */
    @Test
    fun `an empty section says so instead of disappearing`() {
        val text = summary(CycleProjector.fromPeriods(periods("2024-01-01" to Source.OBSERVED)))

        listOf(
            "CYCLES",
            "PERIODS",
            "BLEEDING BETWEEN PERIODS",
            "PATTERNS THE APP FLAGGED",
            "SYMPTOMS BY PHASE",
        ).forEach { heading ->
            assertTrue("$heading is missing entirely\n$text", text.contains(heading))
        }

        assertTrue(text, text.contains("None recorded."))
        assertTrue(text, text.contains("Nothing flagged."))
        assertTrue(text, text.contains("No symptoms logged."))
    }

    /**
     * The two reasons the symptoms section can be empty are different facts, and read differently.
     *
     * Empty summaries with symptoms on record means the phase could not be worked out — not that
     * nothing was logged. Collapsing both into one blank is the ambiguity this section exists to
     * avoid, and only the caller can tell them apart, hence the flag.
     */
    @Test
    fun `logged-but-unplaceable symptoms are distinguished from none at all`() {
        val projection = CycleProjector.fromPeriods(periods("2024-01-01" to Source.OBSERVED))

        val logged = ClinicalSummary.build(
            projection = projection,
            today = LocalDate.parse("2024-01-20"),
            expectedCycleLength = 28,
            anySymptomsLogged = true,
        )
        assertTrue(logged, logged.contains("not enough cycle history to place them in a phase"))
        assertFalse(logged, logged.contains("No symptoms logged."))

        val notLogged = summary(projection)
        assertTrue(notLogged, notLogged.contains("No symptoms logged."))
    }

    /**
     * A display limit that is not announced reads as "this is everything".
     *
     * Sixteen periods 28 days apart, against a list capped at twelve — so the note must appear, and
     * must name both numbers. A clinician counting twelve cycles in a summary built from fifteen is
     * counting the wrong number, and nothing on the page would have told them.
     */
    @Test
    fun `a truncated list says how much it is showing`() {
        var start = date("2024-01-01")
        val sixteen = (1..16).map {
            Period(start, start.plusDays(4), 5, 5, Source.OBSERVED).also { _ -> start = start.plusDays(28) }
        }
        val text = summary(CycleProjector.fromPeriods(sixteen), today = "2025-06-01")

        assertTrue("no truncation note\n$text", text.contains("most recent 12 of 16 shown"))
        assertTrue(
            "cycles list was truncated without saying so\n$text",
            Regex("most recent 12 of 1[0-9] shown").containsMatchIn(text),
        )
    }

    /** A list that fits says nothing — the note is information, not decoration. */
    @Test
    fun `an untruncated list carries no note`() {
        val text = summary(
            CycleProjector.fromPeriods(
                periods(
                    "2024-01-01" to Source.OBSERVED,
                    "2024-01-29" to Source.OBSERVED,
                ),
            ),
        )

        assertFalse(text, text.contains("shown)"))
    }

    @Test
    fun `an empty history still produces a valid summary rather than failing`() {
        val text = summary(Projection.Empty)

        assertTrue(text, text.contains("CYCLE SUMMARY"))
        assertTrue(text, text.contains("END OF SUMMARY"))
        assertTrue(text, text.contains("Completed cycles recorded ... 0"))
    }

    @Test
    fun `symptom averages state how many days they rest on`() {
        val s = PhaseSymptomSummary(Symptom.PAIN, phaseMean = 3.2, elsewhereMean = 0.8, daysObserved = 9)

        val text = summary(
            CycleProjector.fromPeriods(periods("2024-01-01" to Source.OBSERVED)),
            symptoms = listOf(s),
        )

        assertTrue(text, text.contains("across 9 days"))
        assertTrue(text, text.contains("vs 0.8 in other phases"))
    }
}
