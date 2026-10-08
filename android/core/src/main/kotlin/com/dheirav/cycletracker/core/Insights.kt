package com.dheirav.cycletracker.core

import java.time.LocalDate

/** One completed cycle, for the cycle-length chart. */
data class CycleBar(val start: LocalDate, val length: Int, val observed: Boolean)

/** One finished period, split into the days the person logged and the days that were estimated. */
data class PeriodBar(val start: LocalDate, val loggedDays: Int, val estimatedDays: Int) {
    val totalDays: Int get() = loggedDays + estimatedDays
}

/**
 * What the Insights screen draws, worked out here so the screen only draws it.
 *
 * Every figure follows the rules the rest of the app does: estimated cycles and days are carried
 * separately so the chart can draw them dashed, and a "typical" figure exists only when the data has
 * earned it (three observed cycles for a cycle length, two wholly observed periods for a period
 * length, the same thresholds as §3 and §3.1). Below that it is null, and the screen says how many
 * more are needed rather than drawing a line through too little.
 */
data class Insights(
    /** Completed cycles, oldest first, at most [MAX_BARS]. */
    val cycles: List<CycleBar>,
    /** Finished periods, oldest first, at most [MAX_BARS]. A period still running is left out. */
    val periods: List<PeriodBar>,
    /** Median of observed cycles, or null below three. */
    val typicalCycle: Int?,
    /** Standard deviation of observed cycles in days, or null below three. */
    val cycleSpread: Double?,
    /** Median of wholly observed periods, or null below two. */
    val typicalPeriod: Int?,
    val observedCycles: Int,
    /**
     * The latest period, when it has not been answered as over: its length is not known yet, so it
     * is left out of the chart and the typical figure, and the screen says so (§5.1).
     */
    val unfinishedPeriod: LocalDate? = null,
) {
    /** Observed cycles still needed before a typical length means anything. */
    val cyclesNeeded: Int get() = (MIN_OBSERVED_CYCLES - observedCycles).coerceAtLeast(0)

    /** Nothing at all to show, not even a period waiting to be answered as over. */
    val isEmpty: Boolean get() = cycles.isEmpty() && periods.isEmpty() && unfinishedPeriod == null

    companion object {
        const val MAX_BARS = 12
        const val MIN_OBSERVED_CYCLES = 3

        /**
         * The usual range for cycle length in adults, FIGO 2018 (Munro et al., "The two FIGO systems
         * for normal and abnormal uterine bleeding symptoms"): 24 to 38 days. Drawn as a labelled
         * band behind the bars, as a reference and never as a judgement on any one cycle.
         */
        val USUAL_CYCLE_RANGE = 24..38

        /** FIGO 2018 again: a period of up to 8 days is within the usual range. */
        const val USUAL_PERIOD_MAX = 8

        fun build(
            projection: Projection,
            /** Days answered "no bleeding", which are what close a period (§5.1 as amended). */
            noBleedingDays: Set<LocalDate> = emptySet(),
            config: CycleConfig = CycleConfig.Default,
        ): Insights {
            // The same rule the engine uses for phases: a period is over once the next one has begun
            // or a "no bleeding" answer follows it. Until then its span so far is not its length.
            // Drawn as finished, the latest period on the Redmi read "2 days, all logged" and fed
            // the typical period, while the ring beside it rightly treated it as still open.
            val latest = projection.periods.lastOrNull()
            val runningPeriodStart = latest?.takeIf { p ->
                projection.periods.size == projection.cycles.size &&
                    projection.cycles.lastOrNull()?.end == null &&
                    noBleedingDays.none { it.isAfter(p.end) }
            }?.start
            val completed = projection.cycles.mapNotNull { c ->
                c.length?.takeIf { it in config.plausibleCycleRange }?.let { CycleBar(c.start, it, c.source == Source.OBSERVED) }
            }
            val periods = projection.periods
                .filter { it.start != runningPeriodStart }
                .map { PeriodBar(it.start, it.loggedDayCount, it.estimatedDayCount) }

            val observedLengths = completed.filter { it.observed }.map { it.length }.takeLast(config.cycleLengthSampleSize)
            val whollyObserved = projection.periods
                .filter { it.whollyObserved && it.start != runningPeriodStart }
                .map { it.spanDays }
                .takeLast(config.cycleLengthSampleSize)

            return Insights(
                cycles = completed.takeLast(MAX_BARS),
                periods = periods.takeLast(MAX_BARS),
                typicalCycle = observedLengths.takeIf { it.size >= MIN_OBSERVED_CYCLES }?.let { CycleStats.roundHalfUp(CycleStats.median(it)) },
                cycleSpread = CycleStats.cycleLengthVariability(projection.cycles, config),
                typicalPeriod = whollyObserved.takeIf { it.size >= 2 }?.let { CycleStats.roundHalfUp(CycleStats.median(it)) },
                observedCycles = completed.count { it.observed },
                unfinishedPeriod = runningPeriodStart,
            )
        }
    }
}
