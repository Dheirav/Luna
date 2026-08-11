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
import com.dheirav.cycletracker.data.LogDao
import com.dheirav.cycletracker.data.PredictionLedger
import com.dheirav.cycletracker.data.Settings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "daily-log-reminder"
private const val CHANNEL_ID = "reminders"
const val EXTRA_OPEN_LOG = "open_log"

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

        NotificationManagerCompat.from(context).notify(
            1,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("How was today?")
                .setContentText("Ten seconds now beats guessing later.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }
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
