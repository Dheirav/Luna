package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.data.DaySummary
import com.dheirav.cycletracker.data.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val days: Map<LocalDate, DaySummary> = emptyMap(),
    val loading: Boolean = true,
) {
    /** Nothing to log in the future, so there is nothing to page forward to. */
    val canGoForward: Boolean get() = month < YearMonth.now()
}

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LogRepository((app as CycleTrackerApp).database.logDao())

    private val _ui = MutableStateFlow(HistoryUiState())
    val ui: StateFlow<HistoryUiState> = _ui.asStateFlow()

    init {
        // A Flow rather than a one-shot read: editing a day from the calendar has to be visible
        // when the user comes back to it, and Room emits on every write.
        viewModelScope.launch {
            repo.summaries().collect { days ->
                _ui.value = _ui.value.copy(days = days, loading = false)
            }
        }
    }

    fun shiftMonth(months: Long) {
        val target = _ui.value.month.plusMonths(months)
        if (target > YearMonth.now()) return
        _ui.value = _ui.value.copy(month = target)
    }
}
