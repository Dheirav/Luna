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
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.MainActivity
import com.dheirav.cycletracker.core.CycleEngine
import com.dheirav.cycletracker.core.CycleProjector
import com.dheirav.cycletracker.core.Forecast
import com.dheirav.cycletracker.core.ForecastConfig
import com.dheirav.cycletracker.data.LogDao
import com.dheirav.cycletracker.data.PredictionLedger
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.widget.refreshCycleWidgets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "daily-log-reminder"
private const val CHANNEL_ID = "reminders"
private const val CHANNEL_ID_FORECAST = "forecast"
const val EXTRA_OPEN_LOG = "open_log"

/** Stable so a notification action can dismiss the notification that carried it. */
const val REMINDER_NOTIFICATION_ID = 1
private const val FORECAST_NOTIFICATION_ID = 2

/** Days before the predicted window opens that the heads-up fires. */
private const val WARN_DAYS_BEFORE = 2L

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

    private fun durationUntilNext(settings: Settings): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(settings.reminderTime)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
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
}

class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = Settings(context)

        settings.lastReminderFired = Instant.now()

        // Only nag if today has not been logged. A reminder for something already done is noise,
        // and noise is how notifications get muted.
        val dao = (context as CycleTrackerApp).database.logDao()
        val alreadyLogged = dao.logFor(LocalDate.now()) != null
        if (!alreadyLogged && settings.reminderEnabled) {
            notify(context)
        }

        recordPrediction(dao)

        maybeWarnPeriodDue(context, dao, settings)

        // The worker runs daily, which makes it the most reliable clock the app has for rolling
        // the widget's cycle day over. ACTION_DATE_CHANGED covers midnight; this covers the case
        // where the ROM swallowed that broadcast too.
        refreshCycleWidgets(context)

        // Chain the next one. Doing this last means a crash above cannot silently end the chain.
        ReminderScheduler.schedule(context)
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
            // Only in the short run-up. Already bleeding means the answer has arrived.
            if (state.isBleeding) return@runCatching
            if (today.isBefore(window.earliest.minusDays(WARN_DAYS_BEFORE))) return@runCatching
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

    private fun channel(context: Context, id: String, name: String, description: String) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)
                .apply { this.description = description },
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
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openAppIntent(context, openLog = false))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notify(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Daily reminder",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "A nudge to log today" },
        )

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
                .setSmallIcon(android.R.drawable.ic_dialog_info)
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
