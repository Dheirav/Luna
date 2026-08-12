package com.dheirav.cycletracker.data

import android.content.Context
import com.dheirav.cycletracker.core.WindowWidth
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

    /**
     * A heads-up a couple of days before the predicted window opens.
     *
     * Separate from the daily log reminder because they answer different questions — one asks for
     * data, the other gives some back — and someone may reasonably want one without the other.
     */
    var periodWarningEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERIOD_WARNING, true)
        set(value) = prefs.edit().putBoolean(KEY_PERIOD_WARNING, value).apply()

    /**
     * How many days before the window opens the heads-up fires.
     *
     * Was a hardcoded 2. How much notice is useful is a personal question — it depends on what the
     * warning is *for*, and packing a bag, rescheduling something or simply not being caught out
     * are different amounts of lead time. Two days remains the default.
     *
     * Bounded at [MAX_WARNING_LEAD_DAYS] because past a point the warning stops being one: the
     * window is itself several days wide, so a lead time approaching the window's own span fires
     * a "soon" notice for something a week off.
     */
    var periodWarningLeadDays: Int
        get() = prefs.getInt(KEY_PERIOD_WARNING_LEAD, DEFAULT_WARNING_LEAD_DAYS)
            .coerceIn(MIN_WARNING_LEAD_DAYS, MAX_WARNING_LEAD_DAYS)
        set(value) = prefs.edit()
            .putInt(KEY_PERIOD_WARNING_LEAD, value.coerceIn(MIN_WARNING_LEAD_DAYS, MAX_WARNING_LEAD_DAYS))
            .apply()

    /**
     * Cycle start of the last cycle a heads-up was sent for.
     *
     * Keyed by cycle rather than by date so the notification fires **once per cycle**. Without it
     * the warning would repeat every evening the window stayed open, which is how a useful
     * notification becomes one that gets switched off.
     */
    var lastPeriodWarningFor: java.time.LocalDate?
        get() = prefs.getString(KEY_PERIOD_WARNING_FOR, null)
            ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_PERIOD_WARNING_FOR, value?.toString()).apply()

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

    /**
     * What the user says their cycle usually runs to. Null means "not stated".
     *
     * Ranks **below** three observed cycles and **above** extrapolated backfill — see
     * [com.dheirav.cycletracker.core.CycleStats.expectedCycleLength]. So this stops mattering once
     * the app has measured enough real cycles, which is the correct outcome and needs saying in
     * the UI, or a user who sets 31 and later sees 29 will think it was ignored.
     */
    var typicalCycleLength: Int?
        get() = prefs.getInt(KEY_CYCLE_LENGTH, 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_CYCLE_LENGTH, value ?: 0).apply()

    /**
     * How many days the user's period usually lasts. Null means "not stated".
     *
     * Not cosmetic: `spanDays` feeds `ovulationDay` through the `periodLength + 4` floor in §5.1.
     * Every backfilled period assumed five days, so if the real figure differs, every ovulation
     * estimate derived from backfill is off by the difference.
     */
    var typicalPeriodLength: Int?
        get() = prefs.getInt(KEY_PERIOD_LENGTH, 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_PERIOD_LENGTH, value ?: 0).apply()

    /**
     * Whether the home-screen widget shows the cycle day, phase and window.
     *
     * A widget is visible to anyone who glances at the phone, so it leaks precisely what the
     * launcher icon was deliberately designed not to — the icon is a bloom rather than a droplet
     * or a calendar for exactly this reason. Off, the widget still works as a one-tap logging
     * shortcut, which is the point of it.
     *
     * Defaults on: the user placed it deliberately, and defaulting to a blank card would look
     * broken. The trade-off is stated in the settings screen rather than assumed either way.
     */
    var widgetShowsDetails: Boolean
        get() = prefs.getBoolean(KEY_WIDGET_DETAILS, true)
        set(value) = prefs.edit().putBoolean(KEY_WIDGET_DETAILS, value).apply()

    /** How wide a prediction window to show. A coverage preference, not a data override — the
     *  width still scales with measured variability. */
    var windowWidth: WindowWidth
        get() = runCatching { WindowWidth.valueOf(prefs.getString(KEY_WINDOW_WIDTH, null) ?: "") }
            .getOrDefault(WindowWidth.BALANCED)
        set(value) = prefs.edit().putString(KEY_WINDOW_WIDTH, value.name).apply()

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
     * Whether screenshots and the recents thumbnail are allowed. **Off by default.**
     *
     * `FLAG_SECURE` was unconditional in release builds, which blocks screen recording and screenshots
     * and blanks the app-switcher preview. Blanking the preview is the part worth having: the recents
     * screen would otherwise show the cycle day and phase to anyone flicking through it, which is the
     * same over-the-shoulder threat the bloom launcher icon and the widget's discreet mode address.
     *
     * A setting rather than a fixed rule because the cost is real and lands on people the threat model
     * does not fit — anyone who wants to screenshot a window to send to a partner, or record a screen
     * for someone helping them, or keep a copy of the doctor summary as an image. The app should not
     * decide that a person's home life is the one it imagined.
     *
     * Default stays private, so nobody has to know about this to be protected. Turning it on is an
     * informed choice, and the switch says what it costs.
     */
    var allowScreenshots: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SCREENSHOTS, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_SCREENSHOTS, value).apply()

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

    companion object {
        /** Bounds on [periodWarningLeadDays], shared with the settings UI so the two cannot drift. */
        const val MIN_WARNING_LEAD_DAYS = 1
        const val MAX_WARNING_LEAD_DAYS = 7
        const val DEFAULT_WARNING_LEAD_DAYS = 2

        private const val KEY_REMINDER_TIME = "reminder_time_seconds"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_PERIOD_WARNING = "period_warning_enabled"
        private const val KEY_PERIOD_WARNING_LEAD = "period_warning_lead_days"
        private const val KEY_PERIOD_WARNING_FOR = "period_warning_for"
        private const val KEY_LAST_FIRED = "reminder_last_fired"
        private const val KEY_SCHEDULED_SINCE = "reminder_scheduled_since"
        private const val KEY_CYCLE_LENGTH = "typical_cycle_length"
        private const val KEY_PERIOD_LENGTH = "typical_period_length"
        private const val KEY_WINDOW_WIDTH = "window_width"
        private const val KEY_WIDGET_DETAILS = "widget_shows_details"
        private const val KEY_APP_LOCK = "app_lock_enabled"
        private const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"

        /** 21:00 — late enough that the day is done, early enough not to be asleep. */
        private val DEFAULT_REMINDER_SECONDS = LocalTime.of(21, 0).toSecondOfDay().toLong()
    }
}
