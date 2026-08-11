package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.CycleStats
import com.dheirav.cycletracker.core.Forecast
import com.dheirav.cycletracker.core.ForecastConfig
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.data.DaySummary
import com.dheirav.cycletracker.data.LogRepository
import com.dheirav.cycletracker.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val days: Map<LocalDate, DaySummary> = emptyMap(),
    /**
     * The predicted next-period range, shaded on the calendar.
     *
     * The one place a forecast and the record of what happened sit side by side, which is the
     * whole argument for showing it here — next month you can see at a glance whether the shading
     * landed on the days you actually bled. It is drawn softer than a logged day so the two can
     * never be confused.
     */
    val window: PeriodWindow? = null,
    val loading: Boolean = true,
) {
    /** Nothing to log in the future, so there is nothing to page forward to. */
    val canGoForward: Boolean get() = month < YearMonth.now()
}

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LogRepository((app as CycleTrackerApp).database.logDao())
    private val settings = Settings(app)

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    init {
        // A Flow rather than a one-shot read: editing a day from the calendar has to be visible
        // when the user comes back to it, and Room emits on every write.
        viewModelScope.launch {
            repo.summaries().collect { days ->
                _ui.value = _ui.value.copy(days = days, window = currentWindow(), loading = false)
            }
        }
    }

    /**
     * Recomputed on every emission rather than cached.
     *
     * Correcting one backfilled day can move the whole projection — that is the point of §1.1 —
     * so a window computed once at load would go stale the moment the calendar was used for what
     * it is for. Rebuilding costs a pass over a few dozen rows.
     */
    private suspend fun currentWindow(): PeriodWindow? {
        val (projection, _) = repo.projection()
        val cycleStart = projection.currentCycle?.start ?: return null
        return Forecast.periodWindow(
            cycleStart = cycleStart,
            expectedCycleLength = CycleStats.expectedCycleLength(
                projection.cycles,
                settings.typicalCycleLength,
            ),
            cycles = projection.cycles,
            forecastConfig = ForecastConfig(spreadMultiplier = settings.windowWidth.multiplier),
        )
    }

    fun shiftMonth(months: Long) {
        val target = _ui.value.month.plusMonths(months)
        if (target > YearMonth.now()) return
        _ui.value = _ui.value.copy(month = target)
    }

    /**
     * Jump back to this month.
     *
     * The view model outlives the screen — it is scoped to the activity — so without this the
     * calendar reopened wherever it was last left, which could be months back after a session
     * spent correcting backfill. Opening a calendar and not seeing today is disorienting, and
     * worse, it makes the app look broken.
     *
     * Deliberately *not* called when returning from editing a day: that flow is a loop through
     * one month's estimated days, and resetting mid-loop would undo the user's navigation on
     * every single edit. See the origin tracking in `MainActivity`.
     */
    fun showCurrentMonth() {
        val now = YearMonth.now()
        if (_ui.value.month != now) _ui.value = _ui.value.copy(month = now)
    }
}
