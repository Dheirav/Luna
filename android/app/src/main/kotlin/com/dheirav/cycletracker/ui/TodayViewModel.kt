package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.CycleEngine
import com.dheirav.cycletracker.core.CycleProjector
import com.dheirav.cycletracker.core.CycleState
import com.dheirav.cycletracker.core.Projection
import com.dheirav.cycletracker.data.SeedImporter
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TodayUiState(
    val loading: Boolean = true,
    val state: CycleState? = null,
    val projection: Projection = Projection.Empty,
    /** The reminder was due and never ran — almost always the vendor ROM killing background work. */
    val reminderBroken: Boolean = false,
    val batteryRestricted: Boolean = false,
)

class TodayViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as CycleTrackerApp).database.logDao()
    private val engine = CycleEngine()
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
        _ui.value = TodayUiState(
            loading = false,
            state = engine.stateFor(
                today, projection,
                bleedingDays = bleeding.toSet(),
                userTypicalCycleLength = settings.typicalCycleLength,
            ),
            projection = projection,
            reminderBroken = settings.reminderLooksBroken(),
            batteryRestricted = !ReminderScheduler.isBatteryUnrestricted(getApplication()),
        )
    }
}
