package com.dheirav.cycletracker.core

import java.time.LocalDate

/**
 * A run of never-logged days between two bleeding days, too long for §2.1 to bridge.
 *
 * [resumesOn] is the bleeding day after the gap: the start of what the projection calls a spotting
 * event or a new span, and the date a held spotting flag is keyed on.
 */
data class UnloggedGap(val days: List<LocalDate>, val resumesOn: LocalDate)

/**
 * Finds the gaps worth asking about: "Were you bleeding on Sat and Sun?"
 *
 * CYCLE_RULES §2.1 tolerates one non-bleeding day inside a period, and an unlogged day counts as
 * non-bleeding there. So two forgotten days in the middle of a period split it into a one-day period
 * plus spotting, which shows a "bleeding between periods" card during the period itself. The rule
 * stays, by decision on 5 Oct; the app asks instead of guessing either way.
 *
 * Only days that were **never logged** form a gap. A day answered "no bleeding" is an answer, and so
 * is any row at all, since that day was opened and saved. And only gaps of
 * `maxIntraPeriodGapDays + 1` to [maxGapDays] days: shorter ones the spec already bridges, longer ones
 * are far more likely to be a real gap than a forgotten stretch.
 */
object UnloggedGaps {

    fun find(
        bleedingDays: Collection<LocalDate>,
        loggedDays: Set<LocalDate>,
        config: CycleConfig = CycleConfig.Default,
        maxGapDays: Int = 3,
    ): List<UnloggedGap> {
        val sorted = bleedingDays.toSortedSet().toList()
        return sorted.zipWithNext().mapNotNull { (before, after) ->
            val gapLength = daysBetween(before, after) - 1
            if (gapLength <= config.maxIntraPeriodGapDays || gapLength > maxGapDays) return@mapNotNull null
            val days = (1..gapLength).map { before.plusDays(it.toLong()) }
            if (days.any { it in loggedDays }) null else UnloggedGap(days, resumesOn = after)
        }
    }
}
