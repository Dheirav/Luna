package com.dheirav.cycletracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.RemoteViews
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.MainActivity
import com.dheirav.cycletracker.R
import com.dheirav.cycletracker.core.LateGuidance
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.core.WindowBasis
import com.dheirav.cycletracker.ui.phaseName
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.data.snapshot
import com.dheirav.cycletracker.reminder.EXTRA_OPEN_LOG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayMonth = DateTimeFormatter.ofPattern("d MMM")

/**
 * A home-screen widget showing the cycle day, phase and predicted window.
 *
 * The reason it exists is not decoration. The daily reminder has never been proven to survive
 * either test phone's ROM — Funtouch hid Autostart entirely, and HyperOS throttles background work
 * on its own schedule — and the reminder is the whole adherence mechanism (rule 4). A widget needs
 * **no background execution to stay on screen**, so it keeps working exactly where the reminder
 * fails. Tapping it opens the log form directly, not the app's front door.
 *
 * Built on [RemoteViews] rather than Glance: Glance would add a few hundred kilobytes to a 2.3 MB
 * app to render two text views.
 */
class CycleWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context, manager, appWidgetIds)
    }

    /**
     * Also refresh when the date rolls over or the clock is adjusted.
     *
     * Without this the widget would keep claiming yesterday's cycle day until something else
     * happened to update it — a wrong number displayed confidently, which is worse than a stale
     * one the user knows is stale.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in DATE_ACTIONS) {
            val manager = AppWidgetManager.getInstance(context)
            refresh(context, manager, manager.getAppWidgetIds(component(context)))
        }
    }

    private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return

        // onUpdate runs on the main thread with roughly ten seconds before the broadcast is
        // considered stuck, and this reads the database. goAsync keeps the process alive while a
        // coroutine does the work off the main thread.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = buildViews(context)
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

private fun component(context: Context) = ComponentName(context, CycleWidget::class.java)

/**
 * Renders the current state.
 *
 * Suspending and called off the main thread — it opens Room. Every string here is derived from the
 * same engine the app screens use rather than recomputed, so the widget cannot drift into
 * disagreeing with what the app says.
 */
private suspend fun buildViews(context: Context): RemoteViews {
    val app = context.applicationContext as CycleTrackerApp
    val settings = Settings(context)
    val dao = app.database.logDao()

    val views = RemoteViews(context.packageName, R.layout.widget_cycle)

    // The tap target is the whole widget, and it lands in the log form.
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_OPEN_LOG, true)
    }
    views.setOnClickPendingIntent(
        R.id.widget_root,
        PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    /**
     * Discreet mode. A widget sits on a home screen other people can see over your shoulder —
     * it leaks exactly what the launcher icon was deliberately designed not to. When off, the
     * widget still works as a one-tap logging shortcut, which is its real purpose anyway.
     */
    val today = LocalDate.now()

    if (!settings.widgetShowsDetails) {
        // Whether today is logged says nothing about the cycle, and it is the question this widget
        // exists to answer at a glance, so discreet mode keeps it.
        val logged = dao.logFor(today) != null
        views.setTextViewText(R.id.widget_headline, "Today")
        views.setTextViewText(R.id.widget_detail, if (logged) "Logged ✓" else "Tap to log")
        return views
    }

    // The same snapshot the app's screens render, so the widget cannot disagree with them.
    val snapshot = dao.snapshot(settings, today)
    val state = snapshot.state

    if (!state.hasData) {
        // §6 — never invent a cycle day for someone with no periods logged.
        views.setTextViewText(R.id.widget_headline, "No periods yet")
        views.setTextViewText(R.id.widget_detail, "Tap to log one")
        return views
    }

    val window = snapshot.window

    // Nothing logged since before the window opened: as on Today, the cycle day and any lateness
    // are unknown, so the widget says when it was last logged instead of "Day 40 · 11 days late".
    snapshot.silentSince?.let { last ->
        views.setTextViewText(R.id.widget_headline, "Welcome back")
        views.setTextViewText(R.id.widget_detail, "Last logged ${last.format(dayMonth)}")
        views.setContentDescription(
            R.id.widget_root,
            "Luna. Nothing logged since ${last.format(dayMonth)}. Tap to log today.",
        )
        return views
    }

    // Tint the background to the phase, matching the app's hero card.
    //
    // A background *tint* rather than a background colour: setting the colour outright would
    // replace the shape drawable and take the rounded corners with it. `setColorStateList` on a
    // RemoteViews is API 31, which is minSdk.
    val phase = if (state.isBleeding) Phase.MENSTRUATION else state.phase
    views.setColorStateList(
        R.id.widget_root,
        "setBackgroundTintList",
        ColorStateList.valueOf(context.getColor(phaseTint(phase))),
    )

    val phaseName = if (state.isBleeding) {
        "Period"
    } else {
        state.phase?.let(::phaseName) ?: "—"
    }
    // The eyebrow and the phase name used to be separate views, stacked. One cell tall has room for
    // two lines, and the phase means little without the day beside it.
    views.setTextViewText(R.id.widget_headline, "Day ${state.cycleDay} · $phaseName")

    val loggedToday = dao.logFor(today) != null
    val detail = buildString {
        when {
            // Lateness beats the window, but only once the window has passed, and counted from
            // the expected date, the same as Today's hero, so the two can never disagree.
            window != null && window.hasPassed(today) && state.cycleStart != null ->
                LateGuidance.daysPastExpected(state.cycleStart!!, state.expectedCycleLength, today)
                    .let { append("$it day${if (it == 1) "" else "s"} late") }
            // "Est." for a window drawn from a typical spread rather than this person's cycles,
            // the distinction Today's card spells out and the widget used to drop.
            window != null -> append(
                (if (window.basis == WindowBasis.ASSUMED) "Est. " else "Next ") + windowLabel(window),
            )
            else -> append("Tap to log")
        }
        // The tick is what the removed action line uniquely carried. "Tap to log today" was
        // instruction — the whole widget has always been the tap target — but "have I logged
        // today" is the question this widget exists to answer at a glance, and adherence is the
        // constraint everything analytical here depends on. So the tick survives the shrink.
        if (loggedToday) append(" · logged ✓")
    }
    views.setTextViewText(R.id.widget_detail, detail)

    // Without this a screen reader reads the lines as disconnected fragments — "Day 16 · Luteal",
    // "Next 1–9 Jan" — with no indication they are one tappable card.
    //
    // It spells the window out in full rather than reusing [windowLabel]. That function drops the
    // repeated month to save horizontal space, which a screen reader does not have to care about,
    // and "21 to 29 Aug" spoken aloud is worse than the two complete dates.
    // Spoken from the same strings that are drawn, so what is heard is what is shown: it used to
    // announce the expected window while the screen said "3 days late", and the raw phase name
    // while the screen said "Period" (council review A7, WCAG 2.5.3).
    views.setContentDescription(
        R.id.widget_root,
        "Luna. Day ${state.cycleDay}, $phaseName. " +
            detail.replace(" · logged ✓", ". Today is logged").replace("–", " to ") +
            ". Tap to log today.",
    )
    return views
}

/**
 * "Next 1–9 Jan" inside one month, "Next 28 Jan – 3 Feb" across two.
 *
 * Naming the month twice cost roughly a third of the line, which a one-cell-tall widget cannot
 * spare. The spaces around the dash are kept only when both sides carry a month, so a same-month
 * range never reads as one hyphenated date.
 */
private fun windowLabel(window: PeriodWindow): String =
    if (window.earliest.month == window.latest.month) {
        "${window.earliest.dayOfMonth}–${window.latest.format(dayMonth)}"
    } else {
        "${window.earliest.format(dayMonth)} – ${window.latest.format(dayMonth)}"
    }

/** Day/night resolution comes from the resource qualifier, so this needs no theme lookup. */
private fun phaseTint(phase: Phase?): Int = when (phase) {
    Phase.MENSTRUATION -> R.color.phase_menstruation
    Phase.FOLLICULAR -> R.color.phase_follicular
    Phase.OVULATION -> R.color.phase_ovulation
    Phase.LUTEAL -> R.color.phase_luteal
    null -> R.color.phase_unknown
}

/**
 * Pushes a refresh to **every** widget the app has.
 *
 * Called after any log edit and from the reminder worker, because `updatePeriodMillis` is a
 * backstop that vendor ROMs throttle — the same reason the reminder's health is measured rather
 * than assumed. Safe to call when no widget is placed; it is a no-op.
 *
 * One function for all widgets rather than one per widget, and named for what it does rather than
 * for the cycle widget it started as. Five call sites push refreshes; if each had to remember to
 * call a second function, the mood widget would go stale at whichever site someone forgot, and it
 * would go stale silently — a face showing yesterday's answer looks exactly like a face showing
 * today's.
 */
fun refreshWidgets(context: Context) {
    refreshCycleWidgets(context)
    refreshMoodWidgets(context)
    // The Quick Settings tile shows whether today is logged, so it is refreshed on the same writes.
    com.dheirav.cycletracker.tile.LogTileService.refresh(context)
}

private fun refreshCycleWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(component(context))
    if (ids.isEmpty()) return
    context.sendBroadcast(
        Intent(context, CycleWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        },
    )
}
