package com.dheirav.cycletracker.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The reminder's wall-clock arithmetic.
 *
 * The first test here is the one that matters, and it exists because of a bug that shipped: the
 * status block said "Next due 12 Aug, 20:59" while the setting two rows above it said 21:00. The
 * cause was reading the clock twice — `now().plus(durationUntilNext())`, where Kotlin evaluates the
 * receiver first, making the outer read the earlier one and putting the result a few hundred
 * microseconds *before* the target. Nothing as arithmetic; a whole minute once `HH:mm` floors it.
 *
 * So the assertion is deliberately made through the formatter rather than on the instant. Comparing
 * `LocalDateTime`s directly would have passed against the broken code, because the error was smaller
 * than the thing it broke. What the user reads is the invariant worth defending.
 */
class ReminderScheduleTest {

    private val displayed = DateTimeFormatter.ofPattern("HH:mm")

    @Test
    fun `next occurrence displays the exact time that was set`() {
        val time = LocalTime.of(21, 0)
        // Several times of day, including ones with sub-second components, since the original bug
        // only surfaced when the clock was mid-second.
        listOf(
            LocalDateTime.of(2026, 8, 12, 16, 26, 20, 500_000_000),
            LocalDateTime.of(2026, 8, 12, 20, 59, 59, 999_000_000),
            LocalDateTime.of(2026, 8, 12, 0, 0, 0, 1),
            LocalDateTime.of(2026, 8, 12, 21, 0, 0, 1),
        ).forEach { now ->
            assertEquals(
                "wrong displayed time for now=$now",
                "21:00",
                ReminderScheduler.nextOccurrence(time, now).format(displayed),
            )
        }
    }

    @Test
    fun `next occurrence is today when the time is still ahead`() {
        val now = LocalDateTime.of(2026, 8, 12, 16, 26)
        val next = ReminderScheduler.nextOccurrence(LocalTime.of(21, 0), now)
        assertEquals(LocalDateTime.of(2026, 8, 12, 21, 0), next)
    }

    @Test
    fun `next occurrence rolls to tomorrow once the time has passed`() {
        val now = LocalDateTime.of(2026, 8, 12, 21, 30)
        val next = ReminderScheduler.nextOccurrence(LocalTime.of(21, 0), now)
        assertEquals(LocalDateTime.of(2026, 8, 13, 21, 0), next)
    }

    /**
     * Exactly on the reminder time counts as passed, not as due.
     *
     * Otherwise the worker — which reschedules as its last act, at the moment it fires — would
     * queue a job for the instant it had just run, and fire again immediately.
     */
    @Test
    fun `the reminder time itself rolls to tomorrow`() {
        val now = LocalDateTime.of(2026, 8, 12, 21, 0)
        val next = ReminderScheduler.nextOccurrence(LocalTime.of(21, 0), now)
        assertEquals(LocalDateTime.of(2026, 8, 13, 21, 0), next)
    }

    /** The delay handed to WorkManager is always positive and never more than a day. */
    @Test
    fun `the gap to the next occurrence stays within a day`() {
        val time = LocalTime.of(21, 0)
        var now = LocalDateTime.of(2026, 8, 12, 0, 0)
        repeat(24 * 4) {
            val gap = Duration.between(now, ReminderScheduler.nextOccurrence(time, now))
            assertTrue("non-positive gap at $now", !gap.isNegative && !gap.isZero)
            assertTrue("gap over a day at $now", gap <= Duration.ofDays(1))
            now = now.plusMinutes(15)
        }
    }
}
