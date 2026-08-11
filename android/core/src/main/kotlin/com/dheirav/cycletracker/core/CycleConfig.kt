package com.dheirav.cycletracker.core

/**
 * Constants from CYCLE_RULES.md §0. Mirrored in spec/cycle_fixtures.json `config` —
 * FixtureTest asserts the two agree, so drift between spec and code fails the build.
 */
data class CycleConfig(
    /** Non-bleeding days tolerated inside one period. */
    val maxIntraPeriodGapDays: Int = 1,
    /** Bleeding sooner than this after a cycle start is spotting, not a new cycle. */
    val minDaysBetweenCycleStarts: Int = 10,
    /** Days from ovulation to the next period. The conserved phase. */
    val defaultLutealLength: Int = 14,
    val defaultCycleLength: Int = 28,
    val defaultPeriodLength: Int = 5,
    /** Cycles outside this are logging errors, not cycles, and are excluded from statistics. */
    val plausibleCycleRange: IntRange = 15..60,
    val cycleLengthSampleSize: Int = 6,
    /** Minimum regularity multiplier — even chaotic cycles carry some signal. */
    val confidenceFloorRegularity: Double = 0.4,
    val variabilityScaleDays: Double = 7.0,
    /** Days from a phase edge at which positional confidence reaches 1.0. */
    val boundarySoftnessDays: Double = 2.0,
    /** Days of lateness at which the late-cycle discount bottoms out. */
    val latenessScaleDays: Double = 14.0,
    val latenessFloor: Double = 0.3,
) {
    companion object {
        val Default = CycleConfig()
    }
}
