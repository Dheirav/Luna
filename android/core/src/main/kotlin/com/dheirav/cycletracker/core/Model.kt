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
) {
    init {
        require(!end.isBefore(start)) { "period end $end precedes start $start" }
    }
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
) {
    val hasData: Boolean get() = cycleDay != null
}
