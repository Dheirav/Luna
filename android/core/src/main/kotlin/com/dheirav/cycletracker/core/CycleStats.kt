package com.dheirav.cycletracker.core

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The expected cycle length together with where it came from.
 *
 * One return value rather than two calls, because the number and its provenance are one decision.
 * Anything that describes the estimate to a user — the basis panel, the clinical summary — must be
 * describing the same branch that produced it, and the only way to guarantee that is to hand both
 * out at once.
 *
 * The two samples come along for the same reason: the counts a user reads ("3 cycles observed, 11
 * estimated") are what justify the branch, so they have to be counted over the samples the branch
 * actually consulted rather than over a third sample assembled elsewhere.
 */
data class ExpectedLength(
    val days: Int,
    val source: LengthSource,
    /** Plausible **observed** cycles in the sample window. Its size is what the ≥3 test measures. */
    val observedSample: List<Cycle>,
    /** Plausible cycles of any source in the sample window. */
    val allSample: List<Cycle>,
)

/** CYCLE_RULES.md §3 — length and variability statistics over completed cycles. */
object CycleStats {

    /**
     * §3 — median of the last [CycleConfig.cycleLengthSampleSize] plausible cycles, rounded half up.
     *
     * Median rather than mean: one anomalous cycle (illness, a missed period, an error that
     * survived the plausibility filter) moves a mean badly and a median barely at all.
     *
     * Assumed cycles still seed the estimate — that is the point of backfill — but they now rank
     * **below** anything the user states about their own body. The old order asked only whether
     * three cycles existed, which meant a backfill of eleven synthetic 28-day cycles silently
     * outvoted a user who said "mine are 31". The app was inventing cycles, then citing them back
     * as though it had measured something, and overruling the one person who actually knew.
     *
     * Precedence, most to least authoritative:
     *  1. the median of **observed** cycles — a measurement of this body
     *  2. what the user says is typical — their own knowledge, imperfect but real
     *  3. the median including **assumed** cycles — the app's own extrapolation
     *  4. [CycleConfig.defaultCycleLength] — a population figure that describes nobody
     *
     * A user who states nothing sees exactly the previous behaviour, so no existing fixture moves.
     *
     * **The branch is decided here and nowhere else.** [Forecast.basis] used to re-derive it in order
     * to label the number, with its own copy of the sample selection — and the two copies applied the
     * source filter and the last-N window in the opposite order, so `observed >= 3` there and
     * `observed.size >= 3` here were not the same predicate. They agreed only because assumed cycles
     * happen to be older than observed ones, which is true of today's data and written down nowhere.
     * A number labelled with a basis it did not come from is the one failure this app exists to
     * prevent, so the label now comes from the same call as the number.
     */
    fun expectedLength(
        cycles: List<Cycle>,
        userTypicalCycleLength: Int? = null,
        config: CycleConfig = CycleConfig.Default,
    ): ExpectedLength {
        val observed = sample(cycles, config, observedOnly = true)
        val all = sample(cycles, config)
        val observedLengths = observed.mapNotNull { it.length }
        val allLengths = all.mapNotNull { it.length }
        return when {
            observedLengths.size >= 3 -> ExpectedLength(
                roundHalfUp(median(observedLengths)),
                LengthSource.MEDIAN_OF_OBSERVED,
                observed,
                all,
            )
            userTypicalCycleLength != null ->
                ExpectedLength(userTypicalCycleLength, LengthSource.USER_STATED, observed, all)
            allLengths.size >= 3 -> ExpectedLength(
                roundHalfUp(median(allLengths)),
                LengthSource.MEDIAN_WITH_ESTIMATES,
                observed,
                all,
            )
            else -> ExpectedLength(config.defaultCycleLength, LengthSource.APP_DEFAULT, observed, all)
        }
    }

    /** The number alone, for the callers that do not need to explain it. */
    fun expectedCycleLength(
        cycles: List<Cycle>,
        userTypicalCycleLength: Int? = null,
        config: CycleConfig = CycleConfig.Default,
    ): Int = expectedLength(cycles, userTypicalCycleLength, config).days

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

    /**
     * The cycles a statistic may draw on: plausible length, most recent N, optionally observed only.
     *
     * Returns the cycles rather than their lengths so a caller can also report *what kind* they were.
     * The order of the three steps is load-bearing — filtering by source before taking the last N
     * gives the last N observed cycles, while taking the last N first gives however many of the most
     * recent cycles happen to be observed. Those differ, and having both spellings in the codebase is
     * what let the basis label drift from the number it labelled.
     */
    private fun sample(
        cycles: List<Cycle>,
        config: CycleConfig,
        observedOnly: Boolean = false,
    ): List<Cycle> =
        cycles.filter { !observedOnly || it.source == Source.OBSERVED }
            .filter { it.length != null && it.length in config.plausibleCycleRange }
            .takeLast(config.cycleLengthSampleSize)

    private fun lengthSample(
        cycles: List<Cycle>,
        config: CycleConfig,
        observedOnly: Boolean = false,
    ): List<Int> = sample(cycles, config, observedOnly).mapNotNull { it.length }

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
