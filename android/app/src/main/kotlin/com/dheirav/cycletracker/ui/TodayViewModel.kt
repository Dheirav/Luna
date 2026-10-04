package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.CycleSnapshot
import com.dheirav.cycletracker.core.CycleState
import com.dheirav.cycletracker.core.HealthFlag
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.core.PredictionAccuracy
import com.dheirav.cycletracker.core.PredictionBasis
import com.dheirav.cycletracker.core.Projection
import com.dheirav.cycletracker.data.DaySummary
import com.dheirav.cycletracker.data.LogRepository
import com.dheirav.cycletracker.data.PredictionLedger
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.reminder.ReminderScheduler
import com.dheirav.cycletracker.widget.refreshWidgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TodayUiState(
    val loading: Boolean = true,
    /** The date this state was computed for, so the header cannot show a different day from the hero. */
    val today: LocalDate = LocalDate.now(),
    val state: CycleState? = null,
    val projection: Projection = Projection.Empty,
    /**
     * Measured prediction accuracy, **null until there is a real track record** (rule 3).
     *
     * Expect null for months: it needs three cycles that were both predicted in advance and
     * observed afterwards. Rendered inside "Why these numbers?", which shows nothing in its place.
     */
    val accuracy: PredictionAccuracy? = null,
    /**
     * The range the next period is likely to land in. Check [PeriodWindow.basis] before phrasing
     * anything: an `ASSUMED` window is a default spread, not this user's.
     */
    val window: PeriodWindow? = null,
    /** Why the prediction says what it says. Null only before the first load completes. */
    val basis: PredictionBasis? = null,
    /**
     * Patterns worth noticing — a late period, repeated long cycles, bleeding between periods.
     *
     * Usually empty, and rendered only when it is not. These are observations the user can take
     * to a doctor, never a diagnosis; see [HealthFlags].
     */
    val flags: List<HealthFlag> = emptyList(),
    /** The reminder was due and never ran — almost always the vendor ROM killing background work. */
    val reminderBroken: Boolean = false,
    val batteryRestricted: Boolean = false,
    /** What was logged for today, or null when nothing was. Drives the log button's two states. */
    val loggedToday: String? = null,
)

/**
 * Today's entry in one line, for the button that opens it.
 *
 * At most three items, then a count, because it has to fit one button. Bleeding comes first and is
 * always stated, including "No bleeding": a logged no is an observation (rule 2), and the line would
 * otherwise read as though bleeding had not been considered.
 */
fun loggedSummary(day: DaySummary): String {
    val items = buildList {
        add(
            when {
                !day.isBleeding -> "No bleeding"
                day.flow != null -> "Bleeding, ${day.flow.name.lowercase()}"
                else -> "Bleeding"
            },
        )
        day.symptoms.entries.sortedBy { it.key.ordinal }.forEach { (symptom, value) ->
            val level = symptom.levelLabel(value) ?: return@forEach
            // "OK" stays as written; every other anchor word reads naturally in lower case mid-line.
            add("${symptom.label} ${if (level == level.uppercase()) level else level.lowercase()}")
        }
        day.tags.sortedBy { it.ordinal }.forEach { add(it.label) }
        if (day.notes.isNotBlank()) add("note")
    }
    val shown = items.take(3)
    val rest = items.size - shown.size
    return (shown + if (rest > 0) listOf("+$rest more") else emptyList()).joinToString(" · ")
}

class TodayViewModel(app: Application) : AndroidViewModel(app) {

    private val cycles = (app as CycleTrackerApp).cycles
    private val ledger = PredictionLedger((app as CycleTrackerApp).database.logDao())
    private val repo = LogRepository((app as CycleTrackerApp).database.logDao())
    private val settings = Settings(app)

    private val _ui = MutableStateFlow(TodayUiState())
    val ui: StateFlow<TodayUiState> = _ui.asStateFlow()

    init {
        // Follows the snapshot rather than reading once. This screen used to rebuild only when the
        // log form or settings told it to, so a day logged from a notification, or a screen left
        // open past midnight, kept showing a state that was no longer true.
        viewModelScope.launch {
            combine(cycles.snapshots, repo.summaries()) { snapshot, days -> snapshot to days }
                .collect { (snapshot, days) -> render(snapshot, days[snapshot.today]) }
        }
    }

    /**
     * Renders one snapshot. The projection is rebuilt whole upstream (§1.1), never patched here.
     */
    private suspend fun render(snapshot: CycleSnapshot, today: DaySummary?) {
        // Write the prediction down before rendering it, from the same snapshot that is rendered. A
        // prediction that was shown but never recorded is one the app can never be held to.
        ledger.record(snapshot.state, snapshot.today)

        // Push the widget rather than waiting on updatePeriodMillis, which the system clamps and
        // vendor ROMs throttle. A widget disagreeing with the screen beside it is worse than one
        // that is simply plain.
        refreshWidgets(getApplication())

        _ui.value = TodayUiState(
            loading = false,
            today = snapshot.today,
            state = snapshot.state,
            projection = snapshot.projection,
            accuracy = ledger.accuracy(snapshot.projection),
            window = snapshot.window,
            basis = snapshot.basis,
            flags = snapshot.flags,
            reminderBroken = settings.reminderLooksBroken(),
            batteryRestricted = !ReminderScheduler.isBatteryUnrestricted(getApplication()),
            loggedToday = today?.let(::loggedSummary),
        )
    }
}
