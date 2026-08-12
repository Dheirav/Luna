package com.dheirav.cycletracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.MainActivity
import com.dheirav.cycletracker.R
import com.dheirav.cycletracker.core.CycleEngine
import com.dheirav.cycletracker.core.CycleProjector
import com.dheirav.cycletracker.core.MoodFace
import com.dheirav.cycletracker.core.MoodReading
import com.dheirav.cycletracker.core.MoodReadings
import com.dheirav.cycletracker.core.MoodSource
import com.dheirav.cycletracker.core.PhaseObservation
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.reminder.EXTRA_OPEN_LOG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * A home-screen face showing what was logged, or what is usually logged around now.
 *
 * **It never infers a mood from the phase.** All the judgement lives in [MoodReadings], which either
 * reports today's own entry, or a tendency drawn from previous logs in this phase with the day count
 * attached, or says it does not know. A calendar cannot observe a feeling, and "day 24, so you must
 * be irritable" is a stereotype with an app's authority behind it.
 *
 * Only the four burden-scaled symptoms count. Energy, pain and sleep are logged far more often, which
 * would light this up sooner, but they are not moods — and a "mood" that was really a pain reading
 * would be the same category error wearing a friendlier face.
 *
 * Expect the unknown state for a while. Those four sit behind the log form's "More" button, so a
 * fresh install has nothing to draw on for weeks. That state is designed rather than tolerated: it
 * says what is missing and offers the tap that fixes it.
 */
class MoodWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, manager, appWidgetIds)
    }

    /** A tendency is phase-relative, so it changes at midnight even when nothing is logged. */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in DATE_ACTIONS) {
            val manager = AppWidgetManager.getInstance(context)
            refresh(context, manager, manager.getAppWidgetIds(component(context)))
        }
    }

    private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        // Same reasoning as CycleWidget: onUpdate runs on the main thread with about ten seconds
        // before the broadcast is considered stuck, and this reads Room.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = buildMoodViews(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val DATE_ACTIONS = setOf(
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}

private fun component(context: Context) = ComponentName(context, MoodWidget::class.java)

private suspend fun buildMoodViews(context: Context): RemoteViews {
    val app = context.applicationContext as CycleTrackerApp
    val dao = app.database.logDao()
    val settings = Settings(context)
    val today = LocalDate.now()

    val views = RemoteViews(context.packageName, R.layout.widget_mood)
    views.setOnClickPendingIntent(
        R.id.mood_root,
        PendingIntent.getActivity(
            context,
            // A distinct request code from the cycle widget's. Sharing 0 with an identical-looking
            // intent lets FLAG_UPDATE_CURRENT hand both widgets the same PendingIntent.
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_LOG, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    // Discreet mode covers this widget too. A face captioned "you often log irritability around now"
    // discloses more about a cycle than the cycle widget's own detail line does.
    if (!settings.widgetShowsDetails) {
        return views.showing(MoodFace.UNKNOWN, "How are you?", "Tap to log")
    }

    val logs = dao.allLogsOnce()
    val bleeding = logs.filter { it.isBleeding }.map { it.date }
    val assumed = logs.filter { it.isBleeding && it.source == "ASSUMED" }.map { it.date }.toSet()
    val projection = CycleProjector.project(bleeding, assumedDays = assumed)

    val engine = CycleEngine()
    fun stateOn(date: LocalDate) = engine.stateFor(
        date, projection,
        bleedingDays = bleeding.toSet(),
        userTypicalCycleLength = settings.typicalCycleLength,
        userTypicalPeriodLength = settings.typicalPeriodLength,
    )

    val symptomsByDate = dao.allSymptomsOnce()
        .groupBy { it.date }
        .mapValues { (_, rows) ->
            rows.mapNotNull { row -> Symptom.byKey(row.key)?.let { it to row.value } }.toMap()
        }

    val reading = MoodReadings.read(
        todaysSymptoms = symptomsByDate[today].orEmpty(),
        observations = logs.map { PhaseObservation(stateOn(it.date).phase, symptomsByDate[it.date].orEmpty()) },
        phase = stateOn(today).phase,
    )

    val (headline, evidence) = wording(reading)
    return views.showing(reading.face, headline, evidence)
}

/**
 * The sentence, and the evidence under it.
 *
 * **The grammar is the honesty.** A tendency says "you often log X", not "you are X" — the subject is
 * the act of logging, which is a fact, rather than a state of mind, which the app has no access to.
 * Today's entry is the one case allowed to be flat, because it is the user's own report of their own
 * day and hedging it would be strange.
 */
private fun wording(reading: MoodReading): Pair<String, String> = when (reading.source) {
    MoodSource.TODAY -> {
        val symptom = reading.symptom
        val level = reading.level
        if (symptom == null || level == null) {
            "How are you?" to "Tap to log"
        } else {
            val label = symptom.levelLabel(level)?.lowercase() ?: "logged"
            "Today: $label ${symptom.label.lowercase()}" to "From what you logged today"
        }
    }

    MoodSource.TENDENCY -> {
        val days = reading.daysObserved
        val symptom = reading.symptom
        if (symptom == null) {
            // STEADY. A real finding, and worth saying plainly: nothing about this phase looks
            // different from the rest for you.
            "Nothing unusual for you around now" to
                "From $days day${plural(days)} you logged in this phase"
        } else {
            // No "— not a prediction" tail here any more. It made the line wrap on a one-cell-tall
            // widget, and the headline's grammar already carries it: "you often log" is a statement
            // about the logs, not a forecast. The hedge is in the verb, which is cheaper than a
            // clause and harder to truncate away.
            "You often log ${symptom.label.lowercase()} around now" to
                "From $days day${plural(days)} you logged in this phase"
        }
    }

    MoodSource.NOTHING ->
        "Not enough logged yet" to "Tap to log mood, stress, anxiety or irritability"
}

private fun plural(n: Int) = if (n == 1) "" else "s"

/**
 * Applies a reading to the views, including the spoken description.
 *
 * The face is `importantForAccessibility="no"` in the layout, so the root carries one announcement
 * for the whole widget. Without that a screen reader reads an unlabelled image followed by two
 * fragments, and the hedge — the part that makes this honest — is the easiest thing to lose.
 */
private fun RemoteViews.showing(face: MoodFace, headline: String, evidence: String): RemoteViews {
    setImageViewResource(R.id.mood_face, faceDrawable(face))
    setTextViewText(R.id.mood_headline, headline)
    setTextViewText(R.id.mood_evidence, evidence)
    setContentDescription(R.id.mood_root, "Luna. $headline. $evidence. Tap to log.")
    return this
}

private fun faceDrawable(face: MoodFace): Int = when (face) {
    MoodFace.UNKNOWN -> R.drawable.ic_mood_unknown
    MoodFace.SETTLED -> R.drawable.ic_mood_settled
    MoodFace.STEADY -> R.drawable.ic_mood_steady
    MoodFace.HEAVY -> R.drawable.ic_mood_heavy
}

/** Pushes a refresh to every placed mood widget. A no-op when none is placed. */
fun refreshMoodWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(component(context))
    if (ids.isEmpty()) return
    context.sendBroadcast(
        Intent(context, MoodWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        },
    )
}
