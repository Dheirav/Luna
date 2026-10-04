package com.dheirav.cycletracker.reminder

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
            } finally {
                pending.finish()
            }
        }
    }
}
