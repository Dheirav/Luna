package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Health flags are the one place this app comes close to saying something about someone's body,
 * so most of these tests are about **not** firing: not on invented data, not on thin data, and
 * not on an ordinary cycle that ran a few days over.
 *
 * A false alarm here is not a cosmetic bug. It is the app frightening someone about a pattern it
 * made up during backfill.
 */
class HealthFlagsTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    /** Cycles of the given lengths, back to back, all periods five days long. */
    private fun projection(
        vararg lengths: Int,
        source: Source = Source.OBSERVED,
        from: String = "2026-01-05",
    ): Projection {
        var start = date(from)
        val periods = lengths.map { length ->
            Period(start, start.plusDays(4), 5, 5, source).also { start = start.plusDays(length.toLong()) }
        } + Period(start, start.plusDays(4), 5, 5, source)
        return CycleProjector.fromPeriods(periods)
    }

    private fun kinds(flags: List<HealthFlag>) = flags.map { it.kind }.toSet()

    // -- refusals ----------------------------------------------------------

    @Test
    fun `an ordinary history raises nothing`() {
        val flags = HealthFlags.evaluate(
            projection(28, 29, 27, 28),
            today = date("2026-05-01"),
            expectedCycleLength = 28,
        )

        assertTrue(flags.map { it.kind }.toString(), flags.none { it.kind == HealthFlagKind.CYCLES_LONG })
        assertTrue(flags.none { it.kind == HealthFlagKind.CYCLES_SHORT })
    }

    /**
     * The one that matters most. Backfill invented eleven cycles; if extrapolation could raise a
     * health flag, the app would be alarming the user about its own arithmetic (§3.2).
     */
    @Test
    fun `assumed cycles never raise a length flag`() {
        val assumed = projection(44, 47, 45, 46, source = Source.ASSUMED)

        val flags = HealthFlags.evaluate(assumed, date("2026-06-01"), 28)

        assertFalse(HealthFlagKind.CYCLES_LONG in kinds(flags))
    }

    @Test
    fun `a backfilled long period does not raise a prolonged bleeding flag`() {
        val periods = listOf(Period(date("2026-03-01"), date("2026-03-14"), 14, 14, Source.ASSUMED))

        val flags = HealthFlags.evaluate(
            CycleProjector.fromPeriods(periods), date("2026-03-20"), 28,
        )

        assertFalse(HealthFlagKind.BLEEDING_PROLONGED in kinds(flags))
    }

    @Test
    fun `too few observed cycles raise no length flag`() {
        val flags = HealthFlags.evaluate(projection(44, 47), date("2026-04-01"), 28)

        assertFalse(HealthFlagKind.CYCLES_LONG in kinds(flags))
    }

    /** One long cycle is ordinary. Two or more is a pattern worth mentioning. */
    @Test
    fun `a single long cycle is not flagged`() {
        val flags = HealthFlags.evaluate(projection(28, 44, 27, 29), date("2026-06-01"), 28)

        assertFalse(HealthFlagKind.CYCLES_LONG in kinds(flags))
    }

    @Test
    fun `a cycle running a few days over is not called late`() {
        // Day 32 of an expected 28 — four days over, under the seven-day threshold.
        val flags = HealthFlags.evaluate(
            CycleProjector.fromPeriods(
                listOf(Period(date("2026-06-01"), date("2026-06-05"), 5, 5)),
            ),
            today = date("2026-07-02"),
            expectedCycleLength = 28,
        )

        assertFalse(HealthFlagKind.PERIOD_LATE in kinds(flags))
    }

    // -- what does fire ----------------------------------------------------

    @Test
    fun `repeated long cycles are flagged with their lengths`() {
        val flags = HealthFlags.evaluate(projection(44, 47, 28, 41), date("2024-03-15"), 28)

        val flag = flags.single { it.kind == HealthFlagKind.CYCLES_LONG }
        assertTrue(flag.detail, flag.detail.contains("41"))
        assertTrue(flag.detail, flag.detail.contains("44"))
        assertTrue(flag.detail, flag.detail.contains("47"))
    }

    @Test
    fun `repeated short cycles are flagged`() {
        val flags = HealthFlags.evaluate(projection(20, 19, 28, 22), date("2026-05-01"), 24)

        assertTrue(HealthFlagKind.CYCLES_SHORT in kinds(flags))
    }

    @Test
    fun `a period well past its expected date is flagged`() {
        val flags = HealthFlags.evaluate(
            CycleProjector.fromPeriods(
                listOf(Period(date("2026-06-01"), date("2026-06-05"), 5, 5)),
            ),
            today = date("2026-07-08"),
            expectedCycleLength = 28,
        )

        val flag = flags.single { it.kind == HealthFlagKind.PERIOD_LATE }
        assertTrue(flag.headline, flag.headline.contains("10 days"))
    }

    @Test
    fun `three months with no period is flagged regardless of lateness`() {
        val flags = HealthFlags.evaluate(
            CycleProjector.fromPeriods(
                listOf(Period(date("2026-01-01"), date("2026-01-05"), 5, 5)),
            ),
            today = date("2026-05-01"),
            expectedCycleLength = 28,
        )

        assertTrue(HealthFlagKind.PERIOD_ABSENT in kinds(flags))
        // Absence supersedes lateness — reporting both would say the same thing twice.
        assertFalse(HealthFlagKind.PERIOD_LATE in kinds(flags))
    }

    @Test
    fun `prolonged bleeding is flagged with its length`() {
        val periods = listOf(Period(date("2026-04-01"), date("2026-04-11"), 11, 11))

        val flags = HealthFlags.evaluate(
            CycleProjector.fromPeriods(periods), date("2026-04-14"), 28,
        )

        assertTrue(flags.single { it.kind == HealthFlagKind.BLEEDING_PROLONGED }.headline.contains("11"))
    }

    /**
     * Surfaces data the app has always computed and never shown: `CycleProjector` produces
     * SpottingEvents, CYCLE_RULES §2.3 calls them a health-flag input, and nothing read them.
     */
    @Test
    fun `spotting between periods is surfaced`() {
        // A one-day bleed nine days after a period start — too soon to be a new cycle, so §2.2
        // classifies it as spotting rather than a period.
        val bleeding = listOf(
            date("2026-06-01"), date("2024-01-15"), date("2026-06-03"),
            date("2026-06-10"),
            date("2026-06-29"), date("2024-02-12"),
        )
        val projection = CycleProjector.project(bleeding)

        assertEquals("expected the middle bleed to be spotting", 1, projection.spotting.size)

        val flags = HealthFlags.evaluate(projection, date("2024-02-13"), 28)

        assertTrue(HealthFlagKind.SPOTTING_BETWEEN_PERIODS in kinds(flags))
    }

    @Test
    fun `an empty history raises nothing at all`() {
        assertTrue(HealthFlags.evaluate(Projection.Empty, date("2026-06-01"), 28).isEmpty())
    }

    @Test
    fun `flags come back most recent first`() {
        val flags = HealthFlags.evaluate(projection(44, 47, 41, 45), date("2026-09-01"), 28)

        val dates = flags.mapNotNull { it.on }
        assertEquals(dates.sortedDescending(), dates)
    }

    // -- late and absent need an observed anchor ---------------------------

    /** The council review found these two skipped the observed-only rule every other flag keeps. */
    @Test
    fun `an estimated last period raises neither late nor absent`() {
        val estimated = CycleProjector.fromPeriods(
            listOf(Period(date("2026-01-01"), date("2026-01-05"), 5, 5, Source.ASSUMED)),
        )

        val longAfter = HealthFlags.evaluate(estimated, date("2026-05-01"), 28)
        val slightlyAfter = HealthFlags.evaluate(estimated, date("2026-02-08"), 28)

        assertFalse(HealthFlagKind.PERIOD_ABSENT in kinds(longAfter))
        assertFalse(HealthFlagKind.PERIOD_LATE in kinds(slightlyAfter))
    }

    @Test
    fun `the late flag says when the expected length is assumed rather than measured`() {
        val oneObserved = CycleProjector.fromPeriods(
            listOf(Period(date("2026-06-01"), date("2026-06-05"), 5, 5)),
        )

        val assumed = HealthFlags.evaluate(
            oneObserved, date("2026-07-08"), 28, lengthSource = LengthSource.APP_DEFAULT,
        ).single { it.kind == HealthFlagKind.PERIOD_LATE }
        val stated = HealthFlags.evaluate(
            oneObserved, date("2026-07-08"), 31, lengthSource = LengthSource.USER_STATED,
        )

        assertTrue(assumed.detail, assumed.detail.contains("the app assumes is 28 days"))
        assertFalse(assumed.detail, assumed.detail.contains("usually"))
        // 31 days stated, so day 38 is exactly 7 days over: flagged at the default threshold.
        val statedLate = stated.single { it.kind == HealthFlagKind.PERIOD_LATE }
        assertTrue(statedLate.detail, statedLate.detail.contains("you set at 31 days"))
    }

    @Test
    fun `flag dates are written for a person, not in ISO form`() {
        val flags = HealthFlags.evaluate(
            CycleProjector.fromPeriods(listOf(Period(date("2026-01-01"), date("2026-01-05"), 5, 5))),
            today = date("2026-05-01"),
            expectedCycleLength = 28,
        )

        val absent = flags.single { it.kind == HealthFlagKind.PERIOD_ABSENT }
        // Month names follow the machine's locale, so only the shape is asserted.
        assertTrue(absent.detail, Regex("""started on 1 \S+ 2026""").containsMatchIn(absent.detail))
        assertFalse(absent.detail, absent.detail.contains("2026-01-01"))
    }

    // -- severe period pain (decided 5 Oct 2026) ----------------------------------

    /** Five observed five-day periods, 28 days apart, from 2026-01-05. */
    private val fivePeriods = projection(28, 28, 28, 28)

    private fun periodDay(index: Int, day: Int) = date("2026-01-05").plusDays(index * 28L + day)

    @Test
    fun `severe pain in two of the last three periods is flagged, with the counts`() {
        val pain = mapOf(periodDay(3, 0) to 3, periodDay(4, 1) to 4)

        val flag = HealthFlags.evaluate(fivePeriods, date("2026-05-20"), 28, painByDate = pain)
            .single { it.kind == HealthFlagKind.PAIN_SEVERE_DURING_PERIODS }

        assertTrue(flag.headline, flag.headline.contains("2 of your last 3 periods"))
        assertFalse("reported, never diagnosed", flag.detail.contains("endometriosis", ignoreCase = true))
    }

    @Test
    fun `severe pain in one period is not a pattern`() {
        val pain = mapOf(periodDay(4, 0) to 4, periodDay(3, 0) to 2)
        val flags = HealthFlags.evaluate(fivePeriods, date("2026-05-20"), 28, painByDate = pain)
        assertFalse(HealthFlagKind.PAIN_SEVERE_DURING_PERIODS in kinds(flags))
    }

    @Test
    fun `pain on estimated periods never raises the flag`() {
        val assumed = projection(28, 28, 28, 28, source = Source.ASSUMED)
        val pain = mapOf(periodDay(3, 0) to 4, periodDay(4, 0) to 4)
        val flags = HealthFlags.evaluate(assumed, date("2026-05-20"), 28, painByDate = pain)
        assertFalse(HealthFlagKind.PAIN_SEVERE_DURING_PERIODS in kinds(flags))
    }
}
