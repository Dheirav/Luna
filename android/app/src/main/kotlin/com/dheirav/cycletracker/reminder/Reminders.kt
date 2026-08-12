package com.dheirav.cycletracker.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.MainActivity
import com.dheirav.cycletracker.R
import com.dheirav.cycletracker.core.CycleEngine
import com.dheirav.cycletracker.core.CycleProjector
import com.dheirav.cycletracker.core.Forecast
import com.dheirav.cycletracker.core.ForecastConfig
import com.dheirav.cycletracker.data.LogDao
import com.dheirav.cycletracker.data.PredictionLedger
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.widget.refreshWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "daily-log-reminder"

/**
 * Channel ids carry a version, because **a channel's settings are immutable once created.**
 *
 * `createNotificationChannel` on an existing id updates the name and description and silently
 * ignores importance, sound and vibration. The v1 channels were created without
 * `enableVibration(true)` — `NotificationChannel` defaults it to *false*, which is not obvious — and
 * on a phone kept on silent that made the 21:00 reminder soundless and vibrationless: it appeared in
 * the shade and nowhere else, and went unnoticed for a day. Changing the flag alone would have fixed
 * nothing on any device the app had already run on.
 *
 * **If a channel's importance, sound or vibration ever needs to change again, bump the id.** Adding
 * the old id to [LEGACY_CHANNEL_IDS] retires it so the settings screen does not accumulate dead
 * channels.
 *
 * The cost of a bump is that any per-channel customisation the user made in system settings is lost.
 * That was checked before doing it — `dumpsys notification` reported `mUserLockedFields=0`, so there
 * was nothing to lose. Check the same thing next time rather than assuming.
 */
private const val CHANNEL_ID = "reminders-v2"
private const val CHANNEL_ID_FORECAST = "forecast-v2"
private val LEGACY_CHANNEL_IDS = listOf("reminders", "forecast")

const val EXTRA_OPEN_LOG = "open_log"

/** Stable so a notification action can dismiss the notification that carried it. */
const val REMINDER_NOTIFICATION_ID = 1
private const val FORECAST_NOTIFICATION_ID = 2

/**
 * Marks a run triggered by the settings screen's "Send one now".
 *
 * A test must not touch the bookkeeping. Writing `lastReminderFired` would tell
 * [Settings.reminderLooksBroken] the reminder is alive for the next 36 hours — a test that
 * suppresses the very warning it exists to check is worse than no test. Consuming the cycle's
 * one heads-up would be the same mistake in the other direction.
 */
private const val KEY_TEST_RUN = "test_run"

/**
 * The daily nudge.
 *
 * A self-rescheduling one-time worker rather than [androidx.work.PeriodicWorkRequest]: periodic
 * work has a minimum interval and drifts, and this needs to land at a specific wall-clock time.
 * Each run schedules the next.
 */
object ReminderScheduler {

    fun schedule(context: Context) {
        val settings = Settings(context)
        if (!settings.reminderEnabled) {
            cancel(context)
            return
        }

        val delay = durationUntilNext(settings)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            // REPLACE so a changed reminder time takes effect immediately.
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .build(),
        )

        if (settings.reminderScheduledSince == null) {
            settings.reminderScheduledSince = Instant.now()
        }
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Runs the reminder now, through WorkManager, exactly as the scheduled job would.
     *
     * Whether the reminder survives this phone's ROM has been an open question since it was
     * written, and the only way to answer it was to wait a day and see — which conflates three
     * different failures: the notification permission missing, the notification itself being
     * malformed, and the OS killing the delayed job. This settles the first two in two seconds.
     *
     * It deliberately goes through WorkManager rather than posting the notification directly. A
     * test that calls the notify path straight would prove the notification builds, and nothing
     * about whether the worker can run at all.
     *
     * **It cannot prove the 21:00 job survives overnight** — nothing but a few real days can, since
     * that failure is a doze/vendor-kill of a *delayed* job and this one is not delayed.
     */
    fun sendTestReminder(context: Context) {
        WorkManager.getInstance(context).enqueue(
            // No unique name: this must not replace or cancel the real chain.
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(workDataOf(KEY_TEST_RUN to true))
                .build(),
        )
    }

    /** When the next reminder is due, or null when reminders are switched off. */
    fun nextFireAt(context: Context): LocalDateTime? {
        val settings = Settings(context)
        if (!settings.reminderEnabled) return null
        return nextOccurrence(settings.reminderTime, LocalDateTime.now())
    }

    /**
     * Everything known about whether the reminder will actually arrive, in one read.
     *
     * Assembled in one place because the failure modes are independent and each is silent on its
     * own: the switch can be on while the permission is denied, the permission can be granted
     * while WorkManager holds no job, and both can be fine while the ROM throttles the wakeup.
     * Showing only the parts the app can act on would leave the user watching a reminder that
     * never comes with nothing to look at.
     */
    suspend fun status(context: Context): ReminderStatus {
        val settings = Settings(context)
        return ReminderStatus(
            enabled = settings.reminderEnabled,
            nextFireAt = nextFireAt(context),
            lastFired = settings.lastReminderFired,
            workState = queuedWorkState(context),
            notificationsAllowed = context.notificationsAllowed(),
            batteryUnrestricted = isBatteryUnrestricted(context),
            looksBroken = settings.reminderLooksBroken(),
        )
    }

    /**
     * WorkManager's own view of the queued job.
     *
     * This is the one signal that separates "the ROM cleared the queue" from "the job is sitting
     * there and never runs" — the two look identical from the notification shade, and they have
     * different remedies. A blocking `get()` on the IO dispatcher rather than the Flow API: this is
     * read once per visit to the settings screen, and a collector would be more machinery for a
     * value that does not change while it is on screen.
     */
    private suspend fun queuedWorkState(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME).get()
                .firstOrNull { !it.state.isFinished }
                ?.state?.name
        }.getOrNull()
    }

    /**
     * The next wall-clock moment the reminder is due, measured against a caller-supplied `now`.
     *
     * **It takes `now` rather than reading the clock, and that is the whole point.** [nextFireAt]
     * used to be `LocalDateTime.now().plus(durationUntilNext(settings))`, which read the clock
     * twice. Kotlin evaluates a receiver before the argument, so the outer read was the *earlier*
     * of the two, and the result landed a few hundred microseconds *before* the target time.
     *
     * That is nothing as arithmetic and everything on screen, because `HH:mm` floors rather than
     * rounds: 20:59:59.9998 rendered as "20:59" two rows under a setting that said 21:00. The app
     * appeared to disagree with itself about when it would speak.
     *
     * One clock read, shared by both callers, is the only fix that cannot come back.
     *
     * Takes a [LocalTime] rather than [Settings] so it is pure `java.time` and can be tested
     * without an emulator — the bug lived in two lines of arithmetic, and reaching them should not
     * require Robolectric and a SharedPreferences fake.
     */
    internal fun nextOccurrence(reminderTime: LocalTime, now: LocalDateTime): LocalDateTime {
        val todayAtTime = now.toLocalDate().atTime(reminderTime)
        return if (todayAtTime.isAfter(now)) todayAtTime else todayAtTime.plusDays(1)
    }

    private fun durationUntilNext(settings: Settings): Duration {
        val now = LocalDateTime.now()
        return Duration.between(now, nextOccurrence(settings.reminderTime, now))
    }

    /**
     * Whether the OS will let background work run unthrottled.
     *
     * Necessary but not sufficient on Vivo, Oppo and Xiaomi — their Autostart and background
     * power settings are separate, undocumented, and cannot be granted programmatically. Hence
     * [Settings.reminderLooksBroken], which measures what actually happens.
     */
    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batterySettingsIntent(): Intent =
        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * The system page where a denied notification permission can be granted again.
     *
     * The runtime prompt is one-shot: Android stops showing it after two refusals, and from then on
     * the app can only point at this screen. Without a route here, a denied permission leaves every
     * reminder switch in the app looking on while [notify] returns early and posts nothing — the
     * failure the notification pipeline was least able to explain.
     */
    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
}

/** A snapshot of reminder health for the settings screen. See [ReminderScheduler.status]. */
data class ReminderStatus(
    val enabled: Boolean,
    val nextFireAt: LocalDateTime?,
    val lastFired: Instant?,
    val workState: String?,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val looksBroken: Boolean,
)

class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = Settings(context)
        val isTest = inputData.getBoolean(KEY_TEST_RUN, false)

        if (!isTest) settings.lastReminderFired = Instant.now()

        // Only nag if today has not been logged. A reminder for something already done is noise,
        // and noise is how notifications get muted.
        //
        // A test ignores both that and the enable switch: someone pressing "Send one now" wants to
        // see the notification, and "nothing happened because today is already logged" is
        // indistinguishable from the failure they are testing for.
        val dao = (context as CycleTrackerApp).database.logDao()
        val alreadyLogged = dao.logFor(LocalDate.now()) != null
        if (isTest || (!alreadyLogged && settings.reminderEnabled)) {
            notify(context)
        }

        recordPrediction(dao)

        if (!isTest) maybeWarnPeriodDue(context, dao, settings)

        // The worker runs daily, which makes it the most reliable clock the app has for rolling
        // the widget's cycle day over. ACTION_DATE_CHANGED covers midnight; this covers the case
        // where the ROM swallowed that broadcast too.
        refreshWidgets(context)

        // Chain the next one. Doing this last means a crash above cannot silently end the chain.
        // A test must not touch the chain at all — rescheduling from an off-schedule run is how a
        // 21:00 reminder quietly becomes a 15:40 one.
        if (!isTest) ReminderScheduler.schedule(context)
        return Result.success()
    }

    /**
     * Records the day's prediction even when the app is never opened.
     *
     * The ledger is otherwise written by [com.dheirav.cycletracker.ui.TodayViewModel], which only
     * runs when someone looks at the screen. Since the scoring history can only ever be built
     * going forward, a day nobody opened the app is a gap that cannot be filled in later.
     *
     * Failures are swallowed on purpose. This is bookkeeping; it must never be the reason the
     * reminder chain breaks, and the reminder is what adherence depends on.
     */
    private suspend fun recordPrediction(dao: LogDao) {
        runCatching {
            val logs = dao.allLogsOnce()
            val bleeding = logs.filter { it.isBleeding }.map { it.date }
            val assumed = logs.filter { it.isBleeding && it.source == "ASSUMED" }
                .map { it.date }.toSet()
            val projection = CycleProjector.project(bleeding, assumedDays = assumed)
            val state = CycleEngine().stateFor(
                LocalDate.now(), projection, bleedingDays = bleeding.toSet(),
            )
            PredictionLedger(dao).record(state)
        }
    }

    /**
     * A heads-up a couple of days before the window opens.
     *
     * The app had a predicted window and a working notification pipeline, and the only thing it
     * ever said was "log today" — it asked for data and never gave any back. This is the one
     * notification that is genuinely useful rather than merely dutiful.
     *
     * Fires **once per cycle**, keyed on the cycle start rather than the date. Repeating it every
     * evening the window stayed open is how a useful notification becomes one that gets muted.
     */
    private suspend fun maybeWarnPeriodDue(context: Context, dao: LogDao, settings: Settings) {
        if (!settings.periodWarningEnabled) return
        runCatching {
            val logs = dao.allLogsOnce()
            val bleeding = logs.filter { it.isBleeding }.map { it.date }
            val assumed = logs.filter { it.isBleeding && it.source == "ASSUMED" }
                .map { it.date }.toSet()
            val projection = CycleProjector.project(bleeding, assumedDays = assumed)
            val state = CycleEngine().stateFor(
                LocalDate.now(), projection,
                bleedingDays = bleeding.toSet(),
                userTypicalCycleLength = settings.typicalCycleLength,
                userTypicalPeriodLength = settings.typicalPeriodLength,
            )
            val cycleStart = state.cycleStart ?: return@runCatching
            if (settings.lastPeriodWarningFor == cycleStart) return@runCatching

            val window = Forecast.periodWindow(
                cycleStart = cycleStart,
                expectedCycleLength = state.expectedCycleLength,
                cycles = projection.cycles,
                forecastConfig = ForecastConfig(spreadMultiplier = settings.windowWidth.multiplier),
            ) ?: return@runCatching

            val today = LocalDate.now()
            val lead = settings.periodWarningLeadDays.toLong()
            // Only in the short run-up. Already bleeding means the answer has arrived.
            if (state.isBleeding) return@runCatching
            if (today.isBefore(window.earliest.minusDays(lead))) return@runCatching
            if (today.isAfter(window.earliest)) return@runCatching

            val fmt = DateTimeFormatter.ofPattern("d MMM")
            notifyForecast(
                context,
                "Period expected soon",
                "Likely between ${window.earliest.format(fmt)} and ${window.latest.format(fmt)}.",
            )
            settings.lastPeriodWarningFor = cycleStart
        }
    }

    /**
     * Creates a channel, retiring the previous generation of ids on the way past.
     *
     * **Vibration is opt-in.** `NotificationChannel` defaults it to false, so a channel that says
     * nothing about vibration is a channel that does not vibrate — which on a silent phone means a
     * notification with no perceptible arrival at all. Both channels here vibrate: the daily nudge
     * because a reminder nobody notices is not a reminder, and the heads-up because it fires once per
     * cycle and there is no second chance to catch it.
     */
    private fun channel(context: Context, id: String, name: String, description: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        LEGACY_CHANNEL_IDS.forEach { manager.deleteNotificationChannel(it) }
        manager.createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                this.description = description
                enableVibration(true)
            },
        )
    }

    private fun openAppIntent(context: Context, openLog: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_LOG, openLog)
        }
        return PendingIntent.getActivity(
            context, if (openLog) 0 else 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifyForecast(context: Context, title: String, body: String) {
        channel(context, CHANNEL_ID_FORECAST, "Period forecast", "A heads-up before a period is due")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(
            FORECAST_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID_FORECAST)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openAppIntent(context, openLog = false))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notify(context: Context) {
        // Same helper as the forecast channel, so the vibration and retirement rules cannot drift
        // between the two. This used to build its channel inline and was the one that shipped
        // without vibration.
        channel(context, CHANNEL_ID, "Daily reminder", "A nudge to log today")

        // Opens straight into the log form — not the app's front door. Every extra tap between
        // the notification and a saved entry costs adherence.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_LOG, true)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Two actions so the common case — one bit of information — costs one tap, with no
        // unlock and no biometric gate. See LogActionReceiver.
        NotificationManagerCompat.from(context).notify(
            REMINDER_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle("How was today?")
                .setContentText("Ten seconds now beats guessing later.")
                .setContentIntent(pending)
                .addAction(0, "Bleeding", logAction(context, ACTION_LOG_BLEEDING, 10))
                .addAction(0, "No bleeding", logAction(context, ACTION_LOG_NO_BLEEDING, 11))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Distinct request codes, or the second action would silently reuse the first's intent. */
    private fun logAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, LogActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

}

/**
 * Reschedules after a reboot.
 *
 * WorkManager normally restores its own queue, but vendor ROMs routinely clear it. Rescheduling
 * explicitly costs nothing and covers the case where it does not come back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            ReminderScheduler.schedule(context)
        }
    }
}

/** Convenience for the UI layer. */
fun Context.notificationsAllowed(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
