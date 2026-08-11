package com.dheirav.cycletracker.core

/**
 * CYCLE_RULES.md §5 — phase boundaries anchored backward from the next expected period.
 *
 * This replaces DEFECT 3. The old model scaled all four phases proportionally to cycle length,
 * which adapts in the wrong direction: the luteal phase is the conserved one at roughly 14 days,
 * and cycle-length variation lives almost entirely in the follicular phase.
 *
 * For a 45-day cycle, proportional scaling puts ovulation around day 21–25; anchoring from the
 * next period puts it at 29–32. Eight days out, and the error grows with cycle length — so the
 * old model was least accurate for exactly the irregular cycles that most need a tracker.
 */
object PhaseAnchor {

    fun boundaries(
        expectedCycleLength: Int,
        periodLength: Int,
        lutealLength: Int = CycleConfig.Default.defaultLutealLength,
    ): PhaseBoundaries {
        // The +4 floor guarantees at least one follicular day on very short cycles.
        val ovulationDay = maxOf(periodLength + 4, expectedCycleLength - lutealLength)

        return PhaseBoundaries(
            ovulationDay = ovulationDay,
            ranges = linkedMapOf(
                Phase.MENSTRUATION to PhaseRange(1, periodLength),
                Phase.FOLLICULAR to PhaseRange(periodLength + 1, ovulationDay - 3),
                Phase.OVULATION to PhaseRange(ovulationDay - 2, ovulationDay + 1),
                // Open-ended, so a late cycle stays luteal instead of falling off the end.
                Phase.LUTEAL to PhaseRange(ovulationDay + 2, null),
            ),
        )
    }

    /**
     * §5.3 — confidence in the phase assignment.
     *
     * Three independent discounts: distance from the phase edge, how regular the cycles are,
     * and how late the current cycle is running. Note this scores the *phase*, not the symptom
     * predictions built on top of it — those get their own measured accuracy in Phase 3.
     */
    fun confidence(
        range: PhaseRange,
        cycleDay: Int,
        cycleLengthVariability: Double?,
        daysLate: Int = 0,
        config: CycleConfig = CycleConfig.Default,
    ): Double {
        val distance = if (range.endInclusive == null) {
            cycleDay - range.start
        } else {
            minOf(cycleDay - range.start, range.endInclusive - cycleDay)
        }

        // The +1 matters: without it the first and last day of every phase would score zero,
        // which is far too harsh for a day genuinely inside the phase, merely next to an edge.
        val positional = ((distance + 1) / config.boundarySoftnessDays).coerceIn(0.0, 1.0)
        val regularity = CycleStats.regularity(cycleLengthVariability, config)
        val lateness = if (daysLate > 0) {
            maxOf(config.latenessFloor, 1.0 - daysLate / config.latenessScaleDays)
        } else {
            1.0
        }

        return positional * regularity * lateness
    }
}
