package com.dheirav.cycletracker.core

import java.time.LocalDate

/**
 * Domain model for docs/CYCLE_RULES.md.
 *
 * Nothing here is Android-specific — this module is a plain Kotlin library so the engine
 * can be unit-tested on the JVM with no emulator and no SDK involvement.
 */

enum class Phase { MENSTRUATION, FOLLICULAR, OVULATION, LUTEAL }

enum class FlowLevel { LIGHT, MEDIUM, HEAVY }

/**
 * Whether a period was actually logged or extrapolated during backfill.
 * See CYCLE_RULES.md §3.2 — assumed cycles seed the length estimate but are excluded
 * from variability, so a uniform synthetic sequence cannot fake high confidence.
 */
enum class Source { OBSERVED, ASSUMED }

/** A span of bleeding. Derived from daily logs; never stored as authoritative state (§1.1). */
data class Period(
    val start: LocalDate,
    val end: LocalDate,
    val spanDays: Int,
    val bleedingDayCount: Int,
    val source: Source = Source.OBSERVED,
    /**
     * Bleeding days in this period that came from backfill rather than a log.
     *
     * [source] is decided by the start day alone, which is right for what it feeds: a cycle's
     * length is measured from start to start, so a logged start is a genuine observation however
     * the rest was filled in. A period's *length* is not. A logged first day followed by four
     * backfilled ones was counted as a five-day observed period, in the period-length median, the
     * prolonged-bleeding flag and the doctor summary (device review B1, 8 Oct 2026).
     */
    val assumedDayCount: Int = 0,
) {
    init {
        require(!end.isBefore(start)) { "period end $end precedes start $start" }
    }

    /**
     * Bleeding days that were estimated. A period given directly as [Source.ASSUMED] (a backfill
     * seed, which carries no per-day detail) is estimated throughout.
     */
    val estimatedDayCount: Int
        get() = if (source == Source.ASSUMED && assumedDayCount == 0) bleedingDayCount else assumedDayCount

    /** Every day of it was logged. The test for anything that uses the period's length. */
    val whollyObserved: Boolean get() = source == Source.OBSERVED && estimatedDayCount == 0

    /** Bleeding days the user logged themselves. */
    val loggedDayCount: Int get() = bleedingDayCount - estimatedDayCount
}

/** A bleeding span rejected as a cycle start by §2.2. Recorded, not discarded — it is a health-flag input. */
data class SpottingEvent(
    val start: LocalDate,
    val end: LocalDate,
    val spanDays: Int,
)

/** Period start to the day before the next period start. The most recent cycle is open. */
data class Cycle(
    val start: LocalDate,
    val end: LocalDate?,
    val length: Int?,
    val source: Source = Source.OBSERVED,
) {
    val isOpen: Boolean get() = end == null
}

/** The full derivation from daily logs. Recomputed on every change, never mutated incrementally. */
data class Projection(
    val periods: List<Period>,
    val spotting: List<SpottingEvent>,
    val cycles: List<Cycle>,
) {
    val completedCycles: List<Cycle> get() = cycles.filter { it.length != null }
    val currentCycle: Cycle? get() = cycles.lastOrNull()

    companion object {
        val Empty = Projection(emptyList(), emptyList(), emptyList())
    }
}

/** An inclusive 1-based day range. A null [endInclusive] means open-ended (luteal runs past a late period). */
data class PhaseRange(val start: Int, val endInclusive: Int?) {
    operator fun contains(cycleDay: Int): Boolean =
        cycleDay >= start && (endInclusive == null || cycleDay <= endInclusive)
}

data class PhaseBoundaries(
    val ovulationDay: Int,
    val ranges: Map<Phase, PhaseRange>,
) {
    fun phaseFor(cycleDay: Int): Phase? =
        ranges.entries.firstOrNull { cycleDay in it.value }?.key
}

/**
 * Everything the UI needs about one date.
 *
 * [cycleDay] and [phase] are null when no period has ever been logged. That is deliberate —
 * CYCLE_RULES.md §6 forbids inventing day 14 for a user with no data.
 */
data class CycleState(
    val date: LocalDate,
    val cycleDay: Int?,
    val cycleStart: LocalDate?,
    val expectedCycleLength: Int,
    val daysLate: Int,
    val phase: Phase?,
    val phaseConfidence: Double,
    val ovulationDay: Int?,
    val nextPeriodExpected: LocalDate?,
    val cycleLengthVariability: Double?,
    val isBleeding: Boolean,
    /**
     * The period length the phase boundaries were built from: the period's own span once it has
     * closed, at least the expected length while it may still be going (§5.1). Carried so that
     * anything drawing the phases uses the boundaries [phase] came from, not a second guess at them.
     */
    val periodLength: Int? = null,
) {
    val hasData: Boolean get() = cycleDay != null
}
