package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.data.DaySummary
import com.dheirav.cycletracker.data.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** From the snapshot, so the ring on today's cell moves at midnight with everything else. */
    val today: LocalDate = LocalDate.now(),
    val loading: Boolean = true,
) {
    /** Nothing to log in the future, so there is nothing to page forward to. */
    val canGoForward: Boolean get() = month < YearMonth.from(today)
}

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LogRepository((app as CycleTrackerApp).database.logDao())
    private val cycles = (app as CycleTrackerApp).cycles

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    init {
        // Flows rather than one-shot reads: editing a day from the calendar has to be visible when
        // the user comes back to it, and Room emits on every write.
        //
        // The window comes from the shared snapshot rather than being worked out here. It used to be
        // recomputed only when Room emitted, so changing the window width in Settings, which Room
        // never sees, left this calendar shading the old range while Today showed the new one.
        viewModelScope.launch {
            combine(repo.summaries(), cycles.snapshots) { days, snapshot ->
                _ui.value.copy(
                    days = days,
                    window = snapshot.window,
                    today = snapshot.today,
                    loading = false,
                )
            }.collect { _ui.value = it }
        }
    }

    fun shiftMonth(months: Long) {
        val target = _ui.value.month.plusMonths(months)
        if (target > YearMonth.from(_ui.value.today)) return
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
        val now = YearMonth.from(_ui.value.today)
        if (_ui.value.month != now) _ui.value = _ui.value.copy(month = now)
    }
}
