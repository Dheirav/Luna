package com.dheirav.cycletracker.ui

import com.dheirav.cycletracker.core.Phase
import java.time.LocalDate

/**
 * What a phase is called on screen, the same everywhere.
 *
 * The hero said "Period" while bleeding was logged and "Menstruation" otherwise, and the phase guide
 * opened from it said "Menstruation" either way, so tapping "Period" led to a page with a different
 * name for the same thing (council review F5). "Period" is the word people use, so it is the one.
 */
fun phaseName(phase: Phase): String = when (phase) {
    Phase.MENSTRUATION -> "Period"
    else -> phase.name.lowercase().replaceFirstChar { it.uppercase() }
}

/** How far back Today looks for missed days. A week: further back, History is the better tool. */
private const val CATCH_UP_WINDOW_DAYS = 7

/**
 * The run of unlogged days just before today, oldest first, for "3 days not logged · Fill in".
 *
 * Only consecutive days ending yesterday, and only after the first logged day, because a person who
 * started yesterday has missed nothing. A single day is left out: the evening reminder already asks
 * about it, and a nudge for one day on top of that would be noise.
 */
fun unloggedRecentDays(loggedDays: Set<LocalDate>, today: LocalDate): List<LocalDate> {
    val first = loggedDays.minOrNull() ?: return emptyList()
    val missed = generateSequence(today.minusDays(1)) { it.minusDays(1) }
        .takeWhile { it.isAfter(first) && it !in loggedDays }
        .take(CATCH_UP_WINDOW_DAYS)
        .toList()
        .reversed()
    return if (missed.size >= 2) missed else emptyList()
}

/**
 * What Today says about missed days. The count is the whole run, not the capped list: on the Redmi,
 * five weeks unlogged read "7 days not logged" because the list stops at a week, which undercounted
 * exactly the way this app promises never to. "Over a week" fixed the undercount and still hid the
 * size of it, over two weeks on the Redmi (device review p3), so the line now gives the number.
 */
fun catchUpLine(loggedDays: Set<LocalDate>, today: LocalDate): String? {
    if (unloggedRecentDays(loggedDays, today).isEmpty()) return null
    return "${unloggedRun(loggedDays, today)} days not logged"
}

/** Every consecutive unlogged day before today, back to the last log. Not capped. */
fun unloggedRun(loggedDays: Set<LocalDate>, today: LocalDate): Int {
    val first = loggedDays.minOrNull() ?: return 0
    return generateSequence(today.minusDays(1)) { it.minusDays(1) }
        .takeWhile { it.isAfter(first) && it !in loggedDays }
        .count()
}
