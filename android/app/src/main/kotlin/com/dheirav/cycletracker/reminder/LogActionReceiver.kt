package com.dheirav.cycletracker.reminder

import java.time.format.DateTimeFormatter
import com.dheirav.cycletracker.R
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.data.DailyLogEntity
import com.dheirav.cycletracker.widget.refreshWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

const val ACTION_LOG_BLEEDING = "com.dheirav.cycletracker.LOG_BLEEDING"
const val ACTION_LOG_NO_BLEEDING = "com.dheirav.cycletracker.LOG_NO_BLEEDING"

/** The day the reminder asked about, as ISO text. Absent on intents from before it existed. */
const val EXTRA_LOG_DATE = "com.dheirav.cycletracker.LOG_DATE"

/** Takes back an answer given from the notification. Carries the day as it was before. */
const val ACTION_UNDO_ANSWER = "com.dheirav.cycletracker.UNDO_ANSWER"
private const val EXTRA_PREV_EXISTED = "prev_existed"
private const val EXTRA_PREV_BLEEDING = "prev_bleeding"
private const val EXTRA_PREV_ANSWERED = "prev_answered"
private const val EXTRA_PREV_FLOW = "prev_flow"
private const val EXTRA_PREV_SOURCE = "prev_source"
private const val CONFIRMATION_ID = 3

/**
 * "Logged: no bleeding, Sun 4 Oct". Names the day, because the answer may land on the evening
 * before (see [EXTRA_LOG_DATE]) and the person should see which day it went to.
 */
fun answerConfirmation(bleeding: Boolean, date: LocalDate): String =
    "Logged: ${if (bleeding) "bleeding" else "no bleeding"}, ${date.format(DateTimeFormatter.ofPattern("EEE d MMM"))}"

/**
 * Answers the daily reminder without opening the app.
 *
 * Adherence is the binding constraint on everything this app computes (rule 4), and the gap
 * between "I should log this" and a saved row was: unlock the phone, tap the notification, wait
 * for the app, pass the biometric gate, tap a chip, tap save. Six steps, several seconds, and a
 * biometric prompt — on a task whose entire design budget was ten seconds. Most days the answer
 * is one bit, and one bit should cost one tap.
 *
 * Since 2026-10-05 the actions require the phone to be unlocked (`setAuthenticationRequired`), so
 * one tap still answers on an unlocked phone but nobody holding a locked one can write a day. They
 * also carry the date the reminder was for, so an answer given after midnight lands on that day.
 *
 * **A "no bleeding" answer writes a real row.** It is an observation — "I checked, nothing today" —
 * and rule 2 makes that a different thing from a day never logged. It also marks the day answered,
 * so the reminder does not ask again tomorrow about today.
 */
class LogActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_UNDO_ANSWER) {
            undo(context, intent)
            return
        }
        val bleeding = when (intent.action) {
            ACTION_LOG_BLEEDING -> true
            ACTION_LOG_NO_BLEEDING -> false
            else -> return
        }

        // Dismiss straight away. Leaving the notification up while the write happens invites a
        // second tap, and the write is idempotent but the confusion is not worth it.
        NotificationManagerCompat.from(context).cancel(REMINDER_NOTIFICATION_ID)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as CycleTrackerApp
                val dao = app.database.logDao()
                // The day the reminder was for, not the day of the tap: the 21:00 nudge answered at
                // 00:30 is still about the evening before. Never a future day, whatever arrives.
                val now = LocalDate.now()
                val today = intent.getStringExtra(EXTRA_LOG_DATE)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?.takeIf { !it.isAfter(now) }
                    ?: now

                // Preserve anything already recorded for today rather than overwriting it — the
                // user may have logged symptoms this morning and be answering the bleeding
                // question this evening.
                val existing = dao.logFor(today)
                dao.upsertLog(
                    DailyLogEntity(
                        date = today,
                        isBleeding = bleeding,
                        // Either button is an answer, and "No bleeding" is the one that matters here.
                        bleedingAnswered = true,
                        flow = if (bleeding) existing?.flow else null,
                        notes = existing?.notes.orEmpty(),
                        rawText = existing?.rawText,
                        // Answered by hand, so it is an observation whatever backfill had guessed.
                        source = "OBSERVED",
                    ),
                )
                refreshWidgets(context)
                confirm(context, today, bleeding, existing)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * A quiet confirmation with Undo, for a few seconds.
     *
     * The notification used to vanish on the tap with nothing to say the answer had registered, and
     * nothing to take it back: a mistaken "Bleeding" ten or more days into a cycle starts a new one
     * and moves every prediction (council review F3). The in-app path has had Undo since 5 Oct; this
     * is the same promise for the one-tap path. Silent, private on the lock screen, gone in 15 s.
     */
    private fun confirm(context: Context, date: LocalDate, bleeding: Boolean, previous: DailyLogEntity?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val undo = PendingIntent.getBroadcast(
            context,
            12,
            Intent(context, LogActionReceiver::class.java)
                .setAction(ACTION_UNDO_ANSWER)
                .putExtra(EXTRA_LOG_DATE, date.toString())
                .putExtra(EXTRA_PREV_EXISTED, previous != null)
                .putExtra(EXTRA_PREV_BLEEDING, previous?.isBleeding ?: false)
                .putExtra(EXTRA_PREV_ANSWERED, previous?.bleedingAnswered ?: false)
                .putExtra(EXTRA_PREV_FLOW, previous?.flow)
                .putExtra(EXTRA_PREV_SOURCE, previous?.source),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Luna")
            .setContentText("Logged")
            .build()
        NotificationManagerCompat.from(context).notify(
            CONFIRMATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(answerConfirmation(bleeding, date))
                .setSilent(true)
                .setTimeoutAfter(15_000)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .addAction(
                    NotificationCompat.Action.Builder(0, "Undo", undo)
                        .setAuthenticationRequired(true)
                        .build(),
                )
                .build(),
        )
    }

    /** Puts the day back exactly as it was before the answer: deleted if it did not exist. */
    private fun undo(context: Context, intent: Intent) {
        NotificationManagerCompat.from(context).cancel(CONFIRMATION_ID)
        val date = intent.getStringExtra(EXTRA_LOG_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = (context.applicationContext as CycleTrackerApp).database.logDao()
                if (!intent.getBooleanExtra(EXTRA_PREV_EXISTED, false)) {
                    dao.deleteDay(date)
                } else {
                    dao.logFor(date)?.let { current ->
                        dao.upsertLog(
                            current.copy(
                                isBleeding = intent.getBooleanExtra(EXTRA_PREV_BLEEDING, false),
                                bleedingAnswered = intent.getBooleanExtra(EXTRA_PREV_ANSWERED, false),
                                flow = intent.getStringExtra(EXTRA_PREV_FLOW),
                                source = intent.getStringExtra(EXTRA_PREV_SOURCE) ?: current.source,
                            ),
                        )
                    }
                }
                refreshWidgets(context)
            } finally {
                pending.finish()
            }
        }
    }
}
