package com.dheirav.cycletracker.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * CYCLE_RULES.md §2 — derives periods, spotting events and cycles from bleeding days.
 *
 * This is a pure function of the daily logs. Nothing here is stored incrementally, which is
 * the whole fix for DEFECT 1: the old code inserted a cycle record on *every* bleeding day,
 * producing one-day cycles that dragged mean cycle length to 14.
 *
 * Because it is a projection, retro-logging, corrections, deletions and bulk backfill all work
 * with no special handling — recompute and the answer is right. Incremental mutation cannot
 * do that.
 */
object CycleProjector {

    /**
     * Full derivation from logged bleeding days.
     *
     * [assumedDays] marks dates that came from backfill extrapolation rather than observation;
     * a period whose start date is in that set is [Source.ASSUMED]. See §3.2 — the distinction
     * is what stops a uniform synthetic backfill from reporting zero variability.
     */
    fun project(
        bleedingDays: Collection<LocalDate>,
        config: CycleConfig = CycleConfig.Default,
        assumedDays: Set<LocalDate> = emptySet(),
        /**
         * Days answered "Spotting" (§2.5). Never bleeding days: they cannot start or extend a period,
         * and each run of them becomes a spotting event alongside those inferred from short spans.
         */
        spottingDays: Set<LocalDate> = emptySet(),
    ): Projection {
        val projected = projectBleeding(bleedingDays, config, assumedDays)
        if (spottingDays.isEmpty()) return projected
        val inPeriods = projected.periods.flatMap { p -> generateSequence(p.start) { it.plusDays(1) }.takeWhile { !it.isAfter(p.end) }.toList() }.toSet()
        val explicit = spottingDays.filter { it !in inPeriods }.sorted()
            .fold(mutableListOf<MutableList<LocalDate>>()) { runs, day ->
                val last = runs.lastOrNull()
                if (last != null && daysBetween(last.last(), day) == 1) last += day else runs += mutableListOf(day)
                runs
            }
            .map { SpottingEvent(it.first(), it.last(), it.size) }
        return projected.copy(spotting = (projected.spotting + explicit).sortedBy { it.start })
    }

    private fun projectBleeding(
        bleedingDays: Collection<LocalDate>,
        config: CycleConfig,
        assumedDays: Set<LocalDate>,
    ): Projection {
        val days = bleedingDays.distinct().sorted()
        if (days.isEmpty()) return Projection.Empty

        val spans = groupIntoSpans(days, config)
        val periods = mutableListOf<Period>()
        val spotting = mutableListOf<SpottingEvent>()
        var lastAcceptedStart: LocalDate? = null

        for (span in spans) {
            val startsNewCycle = lastAcceptedStart == null ||
                daysBetween(lastAcceptedStart, span.start) >= config.minDaysBetweenCycleStarts

            if (startsNewCycle) {
                periods += Period(
                    start = span.start,
                    end = span.end,
                    spanDays = daysBetween(span.start, span.end) + 1,
                    bleedingDayCount = span.count,
                    source = if (span.start in assumedDays) Source.ASSUMED else Source.OBSERVED,
                    assumedDayCount = span.days.count { it in assumedDays },
                )
                lastAcceptedStart = span.start
            } else {
                spotting += SpottingEvent(
                    start = span.start,
                    end = span.end,
                    spanDays = daysBetween(span.start, span.end) + 1,
                )
            }
        }

        return Projection(periods, spotting, cyclesFrom(periods))
    }

    /**
     * Derives cycles from an already-known period list. Used for backfill seeds, where periods
     * are given directly rather than inferred from daily logs.
     */
    fun fromPeriods(periods: List<Period>): Projection =
        Projection(periods, emptyList(), cyclesFrom(periods.sortedBy { it.start }))

    // -- internals --------------------------------------------------------

    private data class Span(val days: List<LocalDate>) {
        val start: LocalDate get() = days.first()
        val end: LocalDate get() = days.last()
        val count: Int get() = days.size
    }

    /** §2.1 — consecutive days join; a single missing day is bridged; two or more close the span. */
    private fun groupIntoSpans(days: List<LocalDate>, config: CycleConfig): List<Span> {
        val spans = mutableListOf<Span>()
        var current = mutableListOf(days.first())

        for (day in days.drop(1)) {
            val gap = daysBetween(current.last(), day) - 1
            if (gap <= config.maxIntraPeriodGapDays) {
                current += day
            } else {
                spans += Span(current)
                current = mutableListOf(day)
            }
        }
        spans += Span(current)
        return spans
    }

    /**
     * §2.4 — cycle i runs from period i's start to the day before period i+1's start.
     * The final cycle is open and must never be counted in length statistics.
     *
     * A cycle counts as [Source.OBSERVED] only when both bounding periods were observed;
     * one assumed endpoint makes the measured length an artefact of extrapolation.
     */
    private fun cyclesFrom(periods: List<Period>): List<Cycle> =
        periods.mapIndexed { i, period ->
            val next = periods.getOrNull(i + 1)
            if (next == null) {
                Cycle(period.start, end = null, length = null, source = period.source)
            } else {
                val end = next.start.minusDays(1)
                Cycle(
                    start = period.start,
                    end = end,
                    length = daysBetween(period.start, end) + 1,
                    source = if (period.source == Source.OBSERVED && next.source == Source.OBSERVED) {
                        Source.OBSERVED
                    } else {
                        Source.ASSUMED
                    },
                )
            }
        }
}

internal fun daysBetween(from: LocalDate, to: LocalDate): Int =
    ChronoUnit.DAYS.between(from, to).toInt()
