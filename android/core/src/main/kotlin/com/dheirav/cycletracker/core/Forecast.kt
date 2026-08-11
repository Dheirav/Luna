package com.dheirav.cycletracker.core

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Turns a single predicted date into an honest window, and explains where it came from.
 *
 * The problem this fixes: the Today screen states "next period expected 25 Aug" — one exact day —
 * on the same screen as "cycle variability: not enough observed cycles". Those two claims cannot
 * both be true. A single date is a statement of certainty the data does not support, and it is
 * wrong on all but one day in any case.
 *
 * A window is honest at every stage of data collection. With little history it is wide; as real
 * cycles accumulate it narrows on its own, without anyone deciding when the app has "enough" data.
 */

/** Whether a window was measured from the user's cycles or assumed pending data. */
enum class WindowBasis {
    /** Half-width computed from this user's observed cycle lengths. */
    MEASURED,

    /**
     * No measured variability yet, so a stated default spread was used. The UI **must** say so —
     * presenting an assumed window as a measured one is the failure this whole file exists to
     * prevent.
     */
    ASSUMED,
}

/**
 * A range the next period is likely to fall in.
 *
 * Roughly a 68% window under a normal approximation, not a guarantee. Cycle lengths are not truly
 * normal — they are right-skewed, since illness and stress delay a period far more often than
 * anything brings one early — so real coverage runs slightly below the nominal figure. That is
 * acceptable for a "most likely" band and is why the UI should never phrase this as a promise.
 */
data class PeriodWindow(
    val center: LocalDate,
    val earliest: LocalDate,
    val latest: LocalDate,
    val halfWidthDays: Int,
    val basis: WindowBasis,
    /** Observed cycles behind the estimate. Zero when [basis] is [WindowBasis.ASSUMED]. */
    val observedCycles: Int,
) {
    /** Total days spanned, inclusive. A five-day window is `earliest..latest` = 5. */
    val spanDays: Int get() = daysBetween(earliest, latest) + 1

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(earliest) && !date.isAfter(latest)
}

/**
 * Where [CycleStats.expectedCycleLength] got its number — the receipt for the prediction.
 *
 * Listed most to least authoritative, and the order is the point: these are four genuinely
 * different claims, and collapsing them into "cycle length: 28" is how an app ends up citing its
 * own backfill as evidence.
 */
enum class LengthSource {
    /** Median of at least three **observed** cycles. The only one that measures this body. */
    MEDIAN_OF_OBSERVED,

    /** Too few observed cycles, so the user's own stated figure was used. */
    USER_STATED,

    /** Neither available; the median includes extrapolated backfill. Treat as a placeholder — it
     *  is largely the app quoting an assumption it made earlier. */
    MEDIAN_WITH_ESTIMATES,

    /** Nothing at all. [CycleConfig.defaultCycleLength] — a population figure describing nobody. */
    APP_DEFAULT,
}

/**
 * Everything needed to answer "why does it say that?".
 *
 * Exists because the counts are the part that matters and the part nothing currently shows. The
 * seeded database has twelve completed cycles of which **one** is observed; a screen saying
 * "28 days, from 12 cycles" would be true and deeply misleading. The split is the honest unit.
 */
data class PredictionBasis(
    val expectedCycleLength: Int,
    val source: LengthSource,
    /** Cycles inside [CycleConfig.plausibleCycleRange] that fed the estimate. */
    val cyclesUsed: Int,
    val observedCycles: Int,
    val assumedCycles: Int,
    /** Unrounded median, for display. Null when too few cycles to take one. */
    val medianCycleLength: Double?,
    /** Null below three observed cycles — honest ignorance, not zero. */
    val variability: Double?,
) {
    /** True when the estimate rests mostly on extrapolated backfill rather than observation. */
    val mostlyAssumed: Boolean get() = assumedCycles > observedCycles
}

object Forecast {

    /**
     * Widening for estimating the spread from a small sample.
     *
     * The textbook prediction-interval correction for a new draw when the mean is estimated from
     * `n` points: `sqrt(1 + 1/n)`. It matters most exactly where this app starts — at three
     * observed cycles it widens the window by 15%, and by twelve it has faded to 4%.
     *
     * A Student-t factor on top would be more correct still, and would widen small samples
     * considerably more. It is left out deliberately: at n=3 a 90% t-interval spans about three
     * weeks, which is arithmetically defensible and useless to act on. The band is documented as
     * "most likely", not as a coverage guarantee, and that is the honest trade.
     */
    private fun smallSampleFactor(n: Int): Double = sqrt(1.0 + 1.0 / n)

    /**
     * The window around the next expected period, or null when there is nothing to predict from.
     *
     * Null rather than a guess: §6 forbids inventing a cycle for a user with no periods logged,
     * and that applies to the window as much as to the cycle day.
     */
    fun periodWindow(
        cycleStart: LocalDate?,
        expectedCycleLength: Int,
        cycles: List<Cycle>,
        config: CycleConfig = CycleConfig.Default,
        forecastConfig: ForecastConfig = ForecastConfig.Default,
    ): PeriodWindow? {
        if (cycleStart == null) return null

        val center = cycleStart.plusDays(expectedCycleLength.toLong())
        val variability = CycleStats.cycleLengthVariability(cycles, config)
        val observed = cycles.count { it.source == Source.OBSERVED && it.length != null }

        val halfWidth = if (variability != null && observed > 0) {
            ceil(variability * forecastConfig.spreadMultiplier * smallSampleFactor(observed)).toInt()
        } else {
            ceil(forecastConfig.assumedVariabilityDays * forecastConfig.spreadMultiplier).toInt()
        }.coerceAtLeast(forecastConfig.minHalfWidthDays)

        return PeriodWindow(
            center = center,
            earliest = center.minusDays(halfWidth.toLong()),
            latest = center.plusDays(halfWidth.toLong()),
            halfWidthDays = halfWidth,
            basis = if (variability != null) WindowBasis.MEASURED else WindowBasis.ASSUMED,
            observedCycles = if (variability != null) observed else 0,
        )
    }

    /**
     * The receipt: what the expected length was built from.
     *
     * Mirrors the branch structure of [CycleStats.expectedCycleLength] rather than re-deriving it,
     * so the explanation cannot drift away from the number it claims to explain. If that function
     * gains a branch, this must gain one too — [FixtureTest] will not catch it.
     */
    fun basis(
        cycles: List<Cycle>,
        userTypicalCycleLength: Int? = null,
        config: CycleConfig = CycleConfig.Default,
    ): PredictionBasis {
        val plausible = cycles
            .filter { it.length != null && it.length in config.plausibleCycleRange }
            .takeLast(config.cycleLengthSampleSize)
        val observed = plausible.count { it.source == Source.OBSERVED }

        val source = when {
            observed >= 3 -> LengthSource.MEDIAN_OF_OBSERVED
            userTypicalCycleLength != null -> LengthSource.USER_STATED
            plausible.size >= 3 -> LengthSource.MEDIAN_WITH_ESTIMATES
            else -> LengthSource.APP_DEFAULT
        }

        return PredictionBasis(
            expectedCycleLength = CycleStats.expectedCycleLength(cycles, userTypicalCycleLength, config),
            source = source,
            cyclesUsed = plausible.size,
            observedCycles = observed,
            assumedCycles = plausible.count { it.source == Source.ASSUMED },
            medianCycleLength = CycleStats.medianCycleLength(cycles, config),
            variability = CycleStats.cycleLengthVariability(cycles, config),
        )
    }
}

/**
 * How wide a window the user wants.
 *
 * A **coverage preference, not a data override.** The width still derives from measured
 * variability; this only chooses how many standard deviations to span. That distinction is what
 * keeps the setting honest — a user cannot dial the window down to a single day and make the app
 * assert a certainty it does not have, because [ForecastConfig.minHalfWidthDays] still floors it
 * and an erratic history still produces a wide band whatever is chosen here.
 *
 * The trade is genuine in both directions and the UI should say so plainly: a narrow window is
 * wrong more often, a wide one is right more often and tells you less.
 */
enum class WindowWidth(val multiplier: Double) {
    /** About half the time, if cycles are normal-ish. For someone who would rather have a tight
     *  guess and check back than a range too broad to plan around. */
    NARROW(0.7),

    /** Roughly two thirds of the time. The default. */
    BALANCED(1.0),

    /** Around nine times in ten, at the cost of a noticeably broader range. */
    WIDE(1.5),
}

/** Window constants. Separate from [CycleConfig], which is drift-tested against the spec fixture. */
data class ForecastConfig(
    /**
     * Standard deviations either side of centre. 1.0 gives roughly a 68% band under a normal
     * approximation — a "most likely" window rather than a near-certain one. Raising it produces
     * a window that is more often right and less often useful.
     */
    val spreadMultiplier: Double = 1.0,

    /**
     * Spread assumed before this user's own variability can be measured.
     *
     * **A stated assumption, not a measurement**, which is why any window built from it is tagged
     * [WindowBasis.ASSUMED]. Set deliberately wider than a typical adult cycle's standard
     * deviation: when the app is ignorant it should overstate its uncertainty, never understate it.
     */
    val assumedVariabilityDays: Double = 4.0,

    /** No window is ever narrower than this. Predicting a period to the exact day is not a claim
     *  this app can support, however regular the history looks. */
    val minHalfWidthDays: Int = 1,
) {
    companion object {
        val Default = ForecastConfig()
    }
}
