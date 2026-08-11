package com.dheirav.cycletracker.core

import kotlin.math.floor
import kotlin.math.sqrt

/** CYCLE_RULES.md §3 — length and variability statistics over completed cycles. */
object CycleStats {

    /**
     * §3 — median of the last [CycleConfig.cycleLengthSampleSize] plausible cycles, rounded half up.
     *
     * Median rather than mean: one anomalous cycle (illness, a missed period, an error that
     * survived the plausibility filter) moves a mean badly and a median barely at all.
     *
     * Assumed cycles are included here. Seeding the length estimate is the entire point of backfill.
     */
    fun expectedCycleLength(
        cycles: List<Cycle>,
        userTypicalCycleLength: Int? = null,
        config: CycleConfig = CycleConfig.Default,
    ): Int {
        val sample = lengthSample(cycles, config)
        return when {
            sample.size >= 3 -> roundHalfUp(median(sample))
            userTypicalCycleLength != null -> userTypicalCycleLength
            else -> config.defaultCycleLength
        }
    }

    /** Unrounded median, for display ("your cycles average 28.5 days"). Null when the sample is too small. */
    fun medianCycleLength(cycles: List<Cycle>, config: CycleConfig = CycleConfig.Default): Double? =
        lengthSample(cycles, config).takeIf { it.size >= 3 }?.let { median(it) }

    /**
     * §3.2 — population standard deviation over **observed** cycles only.
     *
     * Assumed cycles are excluded deliberately. A uniform extrapolated backfill has a standard
     * deviation of exactly zero, which would drive regularity to 1.0 and make the app report
     * maximum confidence from data nobody ever observed.
     *
     * Returns null below three observed cycles — honest ignorance, not a fabricated number.
     */
    fun cycleLengthVariability(
        cycles: List<Cycle>,
        config: CycleConfig = CycleConfig.Default,
    ): Double? {
        val observed = cycles
            .filter { it.source == Source.OBSERVED }
            .mapNotNull { it.length }
            .filter { it in config.plausibleCycleRange }
            .takeLast(config.cycleLengthSampleSize)
        return if (observed.size >= 3) populationStdDev(observed) else null
    }

    /** §5.3 — how much the length history should be trusted. Floors so chaotic cycles still count for something. */
    fun regularity(variability: Double?, config: CycleConfig = CycleConfig.Default): Double =
        if (variability == null) {
            0.5
        } else {
            1.0 - (variability / config.variabilityScaleDays)
                .coerceIn(0.0, 1.0 - config.confidenceFloorRegularity)
        }

    /** §3.1 — median span of recently completed periods. Feeds ovulationDay via the periodLength+4 floor. */
    fun expectedPeriodLength(
        periods: List<Period>,
        userTypicalPeriodLength: Int? = null,
        config: CycleConfig = CycleConfig.Default,
    ): Int {
        val spans = periods.takeLast(config.cycleLengthSampleSize).map { it.spanDays }
        return when {
            spans.size >= 2 -> roundHalfUp(median(spans))
            userTypicalPeriodLength != null -> userTypicalPeriodLength
            else -> config.defaultPeriodLength
        }
    }

    // -- internals --------------------------------------------------------

    private fun lengthSample(cycles: List<Cycle>, config: CycleConfig): List<Int> =
        cycles.mapNotNull { it.length }
            .filter { it in config.plausibleCycleRange }
            .takeLast(config.cycleLengthSampleSize)

    /** The sample size is even, so a full sample's median lands on a half. §3 pins round-half-up. */
    internal fun roundHalfUp(value: Double): Int = floor(value + 0.5).toInt()

    internal fun median(values: List<Int>): Double {
        require(values.isNotEmpty()) { "median of empty list" }
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toDouble()
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    internal fun populationStdDev(values: List<Int>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
