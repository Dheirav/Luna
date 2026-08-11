package com.dheirav.cycletracker.core

import java.time.LocalDate

/**
 * Ties CYCLE_RULES.md §3–§6 together: given a projection and a date, produce the [CycleState]
 * the UI renders.
 *
 * The engine holds no state of its own. Everything derives from the projection, which itself
 * derives from the daily logs.
 */
class CycleEngine(
    private val config: CycleConfig = CycleConfig.Default,
) {

    fun stateFor(
        date: LocalDate,
        projection: Projection,
        bleedingDays: Set<LocalDate> = emptySet(),
        userTypicalCycleLength: Int? = null,
        userTypicalPeriodLength: Int? = null,
        lutealLength: Int = config.defaultLutealLength,
    ): CycleState {
        val expectedCycleLength = CycleStats.expectedCycleLength(
            projection.cycles, userTypicalCycleLength, config,
        )
        val variability = CycleStats.cycleLengthVariability(projection.cycles, config)
        val isBleeding = date in bleedingDays

        // §6 — no periods means no cycle day. Inventing day 14 here is fabricating data.
        val cycle = cycleContaining(date, projection)
            ?: return CycleState(
                date = date,
                cycleDay = null,
                cycleStart = null,
                expectedCycleLength = expectedCycleLength,
                daysLate = 0,
                phase = null,
                phaseConfidence = 0.0,
                ovulationDay = null,
                nextPeriodExpected = null,
                cycleLengthVariability = variability,
                isBleeding = isBleeding,
            )

        // §4 — 1-indexed and unbounded. It does not wrap; being late is information to surface.
        val cycleDay = daysBetween(cycle.start, date) + 1
        val daysLate = maxOf(0, cycleDay - expectedCycleLength)

        val periodLength = projection.periods
            .firstOrNull { it.start == cycle.start }
            ?.spanDays
            ?: CycleStats.expectedPeriodLength(projection.periods, userTypicalPeriodLength, config)

        val boundaries = PhaseAnchor.boundaries(expectedCycleLength, periodLength, lutealLength)
        val computedPhase = boundaries.phaseFor(cycleDay)

        // §5.2 — menstruation is observed, not inferred. A logged bleed beats the computed range.
        val phase = if (isBleeding) Phase.MENSTRUATION else computedPhase
        val confidence = when {
            isBleeding -> 1.0
            phase == null -> 0.0
            else -> PhaseAnchor.confidence(
                boundaries.ranges.getValue(phase), cycleDay, variability, daysLate, config,
            )
        }

        return CycleState(
            date = date,
            cycleDay = cycleDay,
            cycleStart = cycle.start,
            expectedCycleLength = expectedCycleLength,
            daysLate = daysLate,
            phase = phase,
            phaseConfidence = confidence,
            ovulationDay = boundaries.ovulationDay,
            nextPeriodExpected = cycle.start.plusDays(expectedCycleLength.toLong()),
            cycleLengthVariability = variability,
            isBleeding = isBleeding,
        )
    }

    /** The cycle whose span covers [date]. Handles retro-viewing an earlier cycle, not only the current one. */
    private fun cycleContaining(date: LocalDate, projection: Projection): Cycle? =
        projection.cycles.lastOrNull { cycle ->
            !date.isBefore(cycle.start) && (cycle.end == null || !date.isAfter(cycle.end))
        }
}
