package com.dheirav.cycletracker.core

import java.time.LocalDate
import kotlin.math.abs

/**
 * CYCLE_RULES.md rule 3 — "confidence is earned". This is the earning.
 *
 * Everything the app currently calls confidence is a *proxy*: `PhaseAnchor.confidence` combines
 * how far a day sits from a phase boundary with how variable the cycle lengths have been. Neither
 * term has ever been checked against what actually happened. A user with wildly mistimed
 * predictions and consistent cycle lengths would be shown high confidence, forever.
 *
 * The fix is to write predictions down when they are made and grade them when the period arrives.
 * That is only possible going forward — a prediction cannot be reconstructed after the fact,
 * because the inputs it was made from are gone the moment the logs change. Hence recording lands
 * before any of the UI that consumes it.
 *
 * Nothing here is a forecast. Scoring is arithmetic over recorded pairs, and it reports nothing
 * at all until there is enough of a track record to be worth reporting.
 */

/** Prediction constants. Deliberately not part of [CycleConfig], which is mirrored in
 *  `spec/cycle_fixtures.json` and drift-tested — these have no counterpart in the spec fixture. */
data class PredictionConfig(
    /**
     * Scored cycles required before any accuracy figure is published.
     *
     * Three, matching the floor `CycleStats.cycleLengthVariability` already uses. Below it the
     * mean absolute error of one or two cycles is noise wearing a number's clothes.
     */
    val minScoredCycles: Int = 3,
    /** Most recent scored cycles to aggregate over. Old accuracy is not current accuracy. */
    val sampleSize: Int = 12,
    /** "Close enough" for the hit-rate figure. Two days is the resolution a user acts on. */
    val hitToleranceDays: Int = 2,
) {
    companion object {
        val Default = PredictionConfig()
    }
}

/**
 * A prediction as it was made, frozen.
 *
 * [cycleStart] identifies which cycle the prediction was about, and is what scoring matches on.
 * Storing it rather than inferring later is the point: if the user retro-logs a bleed and the
 * projection shifts, this record still says what was predicted, when, and from what.
 */
data class PredictionRecord(
    /** The day the prediction was made. One record per day is the intended cadence. */
    val madeOn: LocalDate,
    /** Start of the cycle that was open when this was made. */
    val cycleStart: LocalDate,
    val predictedNextPeriod: LocalDate,
    val expectedCycleLength: Int,
    /** Null when there were fewer than three observed cycles to compute it from. */
    val variability: Double?,
) {
    /** How far ahead the prediction reached. Negative once the predicted date has passed. */
    val leadTimeDays: Int get() = daysBetween(madeOn, predictedNextPeriod)
}

/** A record graded against what happened. */
data class ScoredPrediction(
    val record: PredictionRecord,
    val actualNextPeriod: LocalDate,
) {
    /** Signed, in days. Positive means the period arrived **later** than predicted. */
    val errorDays: Int get() = daysBetween(record.predictedNextPeriod, actualNextPeriod)

    val absoluteErrorDays: Int get() = abs(errorDays)
}

/**
 * Measured accuracy. Every field is an observation, not an estimate.
 *
 * [bias] is separate from [meanAbsoluteError] because the two failure modes need different fixes:
 * a large absolute error with near-zero bias means the cycles are genuinely irregular, while a
 * large *biased* error means the expected-length estimate is systematically off and could be
 * corrected. Averaging them together would hide that distinction.
 */
data class PredictionAccuracy(
    val sampleSize: Int,
    val meanAbsoluteError: Double,
    val bias: Double,
    /** Fraction landing within [PredictionConfig.hitToleranceDays]. */
    val hitRate: Double,
    val worstError: Int,
)

object PredictionScorer {

    /**
     * Grades every record whose cycle has since ended.
     *
     * A record is scorable when a *later* period start exists — that period is what the prediction
     * was reaching for. Two exclusions matter:
     *
     *  - The actual period must be [Source.OBSERVED]. Grading a prediction against a backfilled
     *    guess measures agreement between two estimates, which is not accuracy (§3.2).
     *  - The open cycle is never scored. Its period has not happened yet, and treating "hasn't
     *    arrived" as "arrived late" would drag error toward zero as a cycle progresses.
     */
    fun score(
        records: List<PredictionRecord>,
        periods: List<Period>,
    ): List<ScoredPrediction> {
        val sorted = periods.sortedBy { it.start }
        return records.mapNotNull { record ->
            val actual = sorted.firstOrNull { it.start > record.cycleStart } ?: return@mapNotNull null
            if (actual.source != Source.OBSERVED) return@mapNotNull null
            ScoredPrediction(record, actual.start)
        }.sortedBy { it.record.madeOn }
    }

    /**
     * One prediction per cycle — the last one made before the period arrived.
     *
     * Recording daily means a single cycle contributes ~28 records, and averaging over all of them
     * would weight long cycles more heavily purely for having more days in them. Taking the last
     * also answers the question a user actually asks: how good was the app's final call?
     *
     * Records made *after* the actual start are dropped. A "prediction" issued once bleeding has
     * begun is a reading of the present, and counting it would flatter the numbers.
     */
    fun latestPerCycle(scored: List<ScoredPrediction>): List<ScoredPrediction> =
        scored.filter { it.record.madeOn < it.actualNextPeriod }
            .groupBy { it.record.cycleStart }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.record.madeOn } }
            .sortedBy { it.record.cycleStart }

    /** Predictions made at least [days] ahead — the honest way to compare like with like, since a
     *  call made three days out is a different problem from one made three weeks out. */
    fun atLeadTimeAtLeast(scored: List<ScoredPrediction>, days: Int): List<ScoredPrediction> =
        scored.filter { it.record.leadTimeDays >= days }

    /**
     * Aggregates, or **null** when there is not enough to report.
     *
     * Null rather than zero, and null rather than a figure with a caveat attached. Rule 3 does not
     * say to display a low confidence when the track record is thin; it says not to display one.
     */
    fun accuracy(
        scored: List<ScoredPrediction>,
        config: PredictionConfig = PredictionConfig.Default,
    ): PredictionAccuracy? {
        val sample = scored.takeLast(config.sampleSize)
        if (sample.size < config.minScoredCycles) return null

        val errors = sample.map { it.errorDays }
        return PredictionAccuracy(
            sampleSize = sample.size,
            meanAbsoluteError = errors.map { abs(it) }.average(),
            bias = errors.average(),
            hitRate = errors.count { abs(it) <= config.hitToleranceDays } / sample.size.toDouble(),
            worstError = errors.maxByOrNull { abs(it) } ?: 0,
        )
    }
}
