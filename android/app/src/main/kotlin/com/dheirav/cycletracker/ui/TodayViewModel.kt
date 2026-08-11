package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.CycleEngine
import com.dheirav.cycletracker.core.CycleProjector
import com.dheirav.cycletracker.core.CycleState
import com.dheirav.cycletracker.core.Forecast
import com.dheirav.cycletracker.core.ForecastConfig
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.core.PredictionAccuracy
import com.dheirav.cycletracker.core.PredictionBasis
import com.dheirav.cycletracker.core.Projection
import com.dheirav.cycletracker.data.PredictionLedger
import com.dheirav.cycletracker.data.SeedImporter
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.reminder.ReminderScheduler
import com.dheirav.cycletracker.widget.refreshCycleWidgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TodayUiState(
    val loading: Boolean = true,
    val state: CycleState? = null,
    val projection: Projection = Projection.Empty,
    /**
     * Measured prediction accuracy, **null until there is a real track record** (rule 3).
     *
     * Expect null for months: it needs three cycles that were both predicted in advance and
     * observed afterwards, and recording only began now. Nothing renders it yet — the display is
     * deliberately the last piece, so there is no temptation to invent a figure to fill the space.
     */
    val accuracy: PredictionAccuracy? = null,
    /**
     * The range the next period is likely to land in, replacing the single date currently shown.
     *
     * Not yet rendered. `TodayScreen` still prints `nextPeriodExpected` as one exact day on the
     * same screen as "not enough observed cycles" — the two contradict each other, and this is
     * what fixes it in the UI pass. Check [PeriodWindow.basis] before phrasing anything: an
     * `ASSUMED` window is a default spread, not this user's.
     */
    val window: PeriodWindow? = null,
    /** Why the prediction says what it says. Null only before the first load completes. */
    val basis: PredictionBasis? = null,
    /** The reminder was due and never ran — almost always the vendor ROM killing background work. */
    val reminderBroken: Boolean = false,
    val batteryRestricted: Boolean = false,
)

class TodayViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as CycleTrackerApp).database.logDao()
    private val engine = CycleEngine()
    private val ledger = PredictionLedger(dao)
    private val settings = Settings(app)

    private val _ui = MutableStateFlow(TodayUiState())
    val ui: StateFlow<TodayUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            SeedImporter(dao).importIfEmpty(getApplication())
            refresh()
        }
    }

    /** Called on returning from the log screen — the projection is rebuilt, never patched. */
    fun reload() {
        viewModelScope.launch { refresh() }
    }

    /**
     * Rebuilds the whole projection from the logs, every time.
     *
     * This is the point of §1.1 — there is no incremental cycle state to keep in sync, so a
     * retro-logged day or a correction simply produces a different, correct answer.
     */
    suspend fun refresh(today: LocalDate = LocalDate.now()) {
        val logs = dao.allLogsOnce()
        val bleeding = logs.filter { it.isBleeding }.map { it.date }
        val assumed = logs.filter { it.isBleeding && it.source == "ASSUMED" }.map { it.date }.toSet()

        val projection = CycleProjector.project(bleeding, assumedDays = assumed)
        val state = engine.stateFor(
            today, projection,
            bleedingDays = bleeding.toSet(),
            userTypicalCycleLength = settings.typicalCycleLength,
            userTypicalPeriodLength = settings.typicalPeriodLength,
        )

        // Write the prediction down before rendering it. A prediction that was shown but never
        // recorded is one the app can never be held to.
        ledger.record(state, today)

        // Push the widget rather than waiting on updatePeriodMillis, which the system clamps and
        // vendor ROMs throttle. A widget disagreeing with the screen beside it is worse than one
        // that is simply plain.
        refreshCycleWidgets(getApplication())

        _ui.value = TodayUiState(
            loading = false,
            state = state,
            projection = projection,
            accuracy = ledger.accuracy(projection),
            window = Forecast.periodWindow(
                cycleStart = state.cycleStart,
                expectedCycleLength = state.expectedCycleLength,
                cycles = projection.cycles,
                forecastConfig = ForecastConfig(
                    spreadMultiplier = settings.windowWidth.multiplier,
                ),
            ),
            basis = Forecast.basis(projection.cycles, settings.typicalCycleLength),
            reminderBroken = settings.reminderLooksBroken(),
            batteryRestricted = !ReminderScheduler.isBatteryUnrestricted(getApplication()),
        )
    }
}
