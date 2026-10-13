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
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.core.WindowBasis
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.data.snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * When the next period is expected, at a glance from the home screen.
 *
 * Says what Today's window card says and nothing more: a range, never one date, marked "Est." while
 * the spread is typical rather than this person's; "was expected" once the range is behind; and when
 * nothing has been logged since before the window opened, that it cannot tell, rather than counting
 * days late (spec §4). The countdown is a range too, "in 5 to 9 days", because a single number of
 * days is a single date by another name.
 *
 * In discreet mode the label is "Next" rather than "Next period". Adding this widget is a choice to
 * see the date on the home screen; naming what the date is for is a separate one.
 */
class NextPeriodWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, manager, appWidgetIds)
    }

    /** "In 5 to 9 days" changes at midnight even when nothing is logged. */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in DATE_ACTIONS) {
            val manager = AppWidgetManager.getInstance(context)
            refresh(context, manager, manager.getAppWidgetIds(component(context)))
        }
    }

    private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = buildNextViews(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val DATE_ACTIONS = setOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}

private fun component(context: Context) = ComponentName(context, NextPeriodWidget::class.java)

private val dayMonth = DateTimeFormatter.ofPattern("d MMM")

private suspend fun buildNextViews(context: Context): RemoteViews {
    val app = context.applicationContext as CycleTrackerApp
    val settings = Settings(context)
    val today = LocalDate.now()
    val views = RemoteViews(context.packageName, R.layout.widget_next)

    // Opens Today, not the log form. Its own action, so this intent can never share an identity with
    // the others that open the app and have its extras overwritten (as the mood widget's once was).
    views.setOnClickPendingIntent(
        R.id.next_root,
        PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java).apply {
                action = "com.dheirav.cycletracker.OPEN_TODAY"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    val snapshot = app.database.logDao().snapshot(settings, today)
    val named = settings.widgetShowsDetails
    val window = snapshot.window
    val (label, range, detail) = when {
        !snapshot.state.hasData || window == null -> Triple("NEXT", "No periods yet", "Tap to log one")
        snapshot.silentSince != null -> Triple(
            "NEXT", "Log to update",
            "Nothing logged since ${snapshot.silentSince!!.format(dayMonth)}",
        )
        window.hasPassed(today) -> Triple("WAS EXPECTED", rangeLabel(window), "Log it when it comes")
        else -> Triple(
            (if (named) "NEXT PERIOD" else "NEXT") + if (window.basis == WindowBasis.ASSUMED) " · EST." else "",
            rangeLabel(window),
            countdown(window, today),
        )
    }
    views.setTextViewText(R.id.next_label, label)
    views.setTextViewText(R.id.next_range, range)
    views.setTextViewText(R.id.next_detail, detail)
    views.setContentDescription(
        R.id.next_root,
        "Luna. ${label.lowercase().replaceFirstChar { it.uppercase() }}: ${range.replace("–", " to ")}. $detail. Tap to open.",
    )
    return views
}

/** "3–7 Nov" within a month, "28 Oct – 3 Nov" across two. */
private fun rangeLabel(window: PeriodWindow): String =
    if (window.earliest.month == window.latest.month) {
        "${window.earliest.dayOfMonth}–${window.latest.format(dayMonth)}"
    } else {
        "${window.earliest.format(dayMonth)} – ${window.latest.format(dayMonth)}"
    }

/** A range of days until the window, or that it is open now. Never one number. */
internal fun countdown(window: PeriodWindow, today: LocalDate): String {
    if (!today.isBefore(window.earliest)) return "Could start any day now"
    val from = ChronoUnit.DAYS.between(today, window.earliest)
    val to = ChronoUnit.DAYS.between(today, window.latest)
    return "in $from to $to days"
}

/** Pushes a refresh to every placed next-period widget. A no-op when none is placed. */
fun refreshNextWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(component(context))
    if (ids.isEmpty()) return
    context.sendBroadcast(
        Intent(context, NextPeriodWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        },
    )
}
