package com.dheirav.cycletracker.core

import java.time.LocalDate

/**
 * The one period question Today asks, if any.
 *
 * A period has a start and an end, and asking the daily bleeding question is a roundabout way to
 * record two dates. These are the two moments most trackers are built around, plus the one this app
 * adds because of its own rules: when nothing has been logged for long enough that it cannot tell
 * whether a period came at all.
 */
enum class PeriodPrompt {
    NONE,

    /** The window has opened or passed and no period has started: "Has your period started?" */
    STARTED,

    /** A period is running and today is unanswered: "Has it stopped?" */
    STOPPED,

    /**
     * The window passed with nothing logged since before it opened. Luna cannot tell a period that
     * did not come from one nobody recorded, so it asks rather than counting days late.
     */
    SILENT,
    ;

    companion object {
        /** How recent a bleeding day must be for a period to count as still running. */
        private const val RUNNING_WITHIN_DAYS = 2L

        fun forToday(
            today: LocalDate,
            bleedingDays: Set<LocalDate>,
            noBleedingDays: Set<LocalDate>,
            window: PeriodWindow?,
            silentSince: LocalDate?,
        ): PeriodPrompt {
            // Today already answered: nothing to ask.
            if (today in bleedingDays || today in noBleedingDays) return NONE
            if (silentSince != null) return SILENT

            val lastBleed = bleedingDays.filter { !it.isAfter(today) }.maxOrNull()
            val running = lastBleed != null &&
                !lastBleed.isBefore(today.minusDays(RUNNING_WITHIN_DAYS)) &&
                noBleedingDays.none { it.isAfter(lastBleed) && !it.isAfter(today) }
            if (running) return STOPPED

            if (window != null && !today.isBefore(window.earliest)) return STARTED
            return NONE
        }

        /**
         * The last day with any log, when the forecast window has passed and nothing at all was
         * logged on or after the day it opened. Null otherwise.
         *
         * Lateness is only earned by someone who was logging. Day 40 of a 28-day cycle with no
         * entries since day 22 does not mean the period has not come; it means nobody said. The
         * Today screen said "11 days past the expected date" and raised a doctor point in exactly
         * that state, on a phone that had simply not been used.
         */
        fun silentSince(window: PeriodWindow?, loggedDays: Set<LocalDate>, today: LocalDate): LocalDate? {
            if (window == null || !window.hasPassed(today)) return null
            val last = loggedDays.filter { !it.isAfter(today) }.maxOrNull() ?: return null
            return last.takeIf { it.isBefore(window.earliest) }
        }
    }
}
