package com.dheirav.cycletracker.data

import android.content.Context
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Small local settings store. SharedPreferences rather than DataStore — this is a handful of
 * scalars and adding a dependency for them is not worth the APK bytes.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("cycle-settings", Context.MODE_PRIVATE)

    /** When the daily nudge should fire. */
    var reminderTime: LocalTime
        get() = LocalTime.ofSecondOfDay(prefs.getLong(KEY_REMINDER_TIME, DEFAULT_REMINDER_SECONDS))
        set(value) = prefs.edit().putLong(KEY_REMINDER_TIME, value.toSecondOfDay().toLong()).apply()

    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    /**
     * When the reminder worker last actually ran.
     *
     * This exists because vendor ROMs — Funtouch, One UI, MIUI — kill background work
     * aggressively and silently. The reminder is the entire adherence mechanism, so the app must
     * *measure* whether it fires rather than assume it does. See [reminderLooksBroken].
     */
    var lastReminderFired: Instant?
        get() = prefs.getLong(KEY_LAST_FIRED, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(value) = prefs.edit().putLong(KEY_LAST_FIRED, value?.toEpochMilli() ?: 0L).apply()

    /** When a reminder was first scheduled, so a fresh install is not immediately accused of breakage. */
    var reminderScheduledSince: Instant?
        get() = prefs.getLong(KEY_SCHEDULED_SINCE, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        set(value) = prefs.edit().putLong(KEY_SCHEDULED_SINCE, value?.toEpochMilli() ?: 0L).apply()

    var typicalCycleLength: Int?
        get() = prefs.getInt(KEY_CYCLE_LENGTH, 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_CYCLE_LENGTH, value ?: 0).apply()

    /**
     * Require a biometric or device PIN before the app's contents are shown.
     *
     * Defaults on. This is the only measure that addresses the realistic threat — someone with
     * your unlocked phone opening the app — and it cannot lock you out: the prompt always accepts
     * the device credential, and with no screen lock configured the gate disables itself.
     */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    /**
     * True when a reminder should have fired by now and didn't.
     *
     * Deliberately conservative — it waits for a missed window plus most of a day, so a phone
     * that was simply off overnight does not trigger a false accusation. When this goes true the
     * UI tells the user their reminder is being killed and points at the battery settings, which
     * is the only real remedy on these ROMs.
     */
    fun reminderLooksBroken(now: Instant = Instant.now()): Boolean {
        if (!reminderEnabled) return false
        val since = reminderScheduledSince ?: return false
        // Give it a full cycle of the schedule before judging.
        if (Duration.between(since, now) < Duration.ofHours(36)) return false
        val fired = lastReminderFired ?: return true
        return Duration.between(fired, now) > Duration.ofHours(36)
    }

    private companion object {
        const val KEY_REMINDER_TIME = "reminder_time_seconds"
        const val KEY_REMINDER_ENABLED = "reminder_enabled"
        const val KEY_LAST_FIRED = "reminder_last_fired"
        const val KEY_SCHEDULED_SINCE = "reminder_scheduled_since"
        const val KEY_CYCLE_LENGTH = "typical_cycle_length"
        const val KEY_APP_LOCK = "app_lock_enabled"

        /** 21:00 — late enough that the day is done, early enough not to be asleep. */
        val DEFAULT_REMINDER_SECONDS = LocalTime.of(21, 0).toSecondOfDay().toLong()
    }
}
