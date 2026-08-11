package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.DayTag
import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.data.DayEntry
import com.dheirav.cycletracker.data.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class LogUiState(
    val entry: DayEntry = DayEntry(LocalDate.now()),
    val showExtended: Boolean = false,
    val saved: Boolean = false,
    val loading: Boolean = true,
)

class LogViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LogRepository((app as CycleTrackerApp).database.logDao())

    private val _ui = MutableStateFlow(LogUiState())
    val ui: StateFlow<LogUiState> = _ui.asStateFlow()

    init {
        open(LocalDate.now())
    }

    /** Loads a date for editing. Retro-logging is the same path as today — no special case. */
    fun open(date: LocalDate) {
        viewModelScope.launch {
            val entry = repo.load(date)
            _ui.value = LogUiState(
                entry = entry,
                // If a day already has extended symptoms, show them rather than hide the data.
                showExtended = entry.symptoms.keys.any { !it.isCore },
                loading = false,
            )
        }
    }

    fun shiftDay(days: Long) {
        val target = _ui.value.entry.date.plusDays(days)
        // No logging the future — there is nothing to observe yet.
        if (target.isAfter(LocalDate.now())) return
        open(target)
    }

    fun setBleeding(bleeding: Boolean) = edit {
        it.copy(isBleeding = bleeding, flow = if (bleeding) it.flow else null)
    }

    fun setFlow(flow: FlowLevel) = edit {
        // Tapping the selected level again clears it — flow is optional even while bleeding.
        it.copy(isBleeding = true, flow = if (it.flow == flow) null else flow)
    }

    /** Tapping the selected level again unsets the symptom, so a mistake costs one tap to undo. */
    fun setSymptom(symptom: Symptom, value: Int) = edit { entry ->
        val next = entry.symptoms.toMutableMap()
        if (next[symptom] == value) next.remove(symptom) else next[symptom] = value
        entry.copy(symptoms = next)
    }

    fun toggleTag(tag: DayTag) = edit { entry ->
        entry.copy(tags = if (tag in entry.tags) entry.tags - tag else entry.tags + tag)
    }

    fun setNotes(notes: String) = edit { it.copy(notes = notes) }

    fun toggleExtended() {
        _ui.value = _ui.value.copy(showExtended = !_ui.value.showExtended)
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.save(_ui.value.entry)
            _ui.value = _ui.value.copy(saved = true)
            onDone()
        }
    }

    private inline fun edit(block: (DayEntry) -> DayEntry) {
        _ui.value = _ui.value.copy(entry = block(_ui.value.entry), saved = false)
    }
}
