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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class LogUiState(
    val entry: DayEntry = DayEntry(LocalDate.now()),
    /** Position in a catch-up run, as (this, of total), or null outside one. */
    val catchUp: Pair<Int, Int>? = null,
    /** The day as it was loaded, so the form can tell an edit from a visit. */
    val original: DayEntry = entry,
    val showExtended: Boolean = false,
    val saved: Boolean = false,
    val loading: Boolean = true,
) {
    /**
     * True when leaving would throw something away.
     *
     * Compared by value rather than tracked as "was anything tapped", so an edit undone by hand (the
     * form unsets a level when it is tapped twice) does not trigger a prompt for nothing to lose.
     */
    val dirty: Boolean get() = !loading && entry != original
}

/**
 * How the log form and its snackbar name a day: "Today", "Yesterday", or "Mon 3 Mar".
 * The weekday is kept so a save to the day next to the intended one is visible at a glance.
 */
fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

/** A write that can be taken back: what to say about it, and each day as it was before. */
data class Undoable(val message: String, val previous: List<DayEntry>) {
    constructor(message: String, previous: DayEntry) : this(message, listOf(previous))
}

class LogViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LogRepository((app as CycleTrackerApp).database.logDao())

    private val _ui = MutableStateFlow(LogUiState())
    val ui: StateFlow<LogUiState> = _ui.asStateFlow()

    /**
     * One event per write, collected by the activity's snackbar. A shared flow rather than state,
     * because the form has usually been left by the time it shows, and a replayed "Saved" on the
     * next visit would be wrong.
     */
    private val _undoable = MutableSharedFlow<Undoable>(extraBufferCapacity = 1)
    val undoable: SharedFlow<Undoable> = _undoable.asSharedFlow()

    init {
        open(LocalDate.now())
    }

    /** Loads a date for editing. Retro-logging is the same path as today — no special case. */
    /**
     * Returns false, and changes nothing, for a date the form will not open; callers must not
     * navigate to the form then. It used to return silently, and the form appeared showing whatever
     * it held last, including edits the user had chosen to discard.
     */
    /** Days still to come in a catch-up run, and its length, for "2 of 3". */
    private var catchUpQueue: List<LocalDate> = emptyList()
    private var catchUpTotal = 0

    /**
     * Opens each of [days] in turn: Save on one opens the next, and only the last returns to where the
     * form was opened from. Leaving any other way ends the run.
     */
    fun startCatchUp(days: List<LocalDate>): Boolean {
        if (days.isEmpty()) return false
        catchUpQueue = days.drop(1)
        catchUpTotal = days.size
        return openDate(days.first(), catchUp = 1 to catchUpTotal)
    }

    fun open(date: LocalDate): Boolean {
        catchUpQueue = emptyList()
        return openDate(date, catchUp = null)
    }

    private fun openDate(date: LocalDate, catchUp: Pair<Int, Int>?): Boolean {
        // No logging the future — there is nothing to observe yet.
        if (date.isAfter(LocalDate.now())) return false
        // Cleared at once rather than when the load lands, so the previous day's entry is never
        // shown under the new date, even for a frame.
        _ui.value = LogUiState(entry = DayEntry(date), loading = true, catchUp = catchUp)
        viewModelScope.launch {
            val entry = repo.load(date)
            _ui.value = LogUiState(
                entry = entry,
                original = entry,
                // If a day already has extended symptoms, show them rather than hide the data.
                showExtended = entry.symptoms.keys.any { !it.isCore },
                loading = false,
                catchUp = catchUp,
            )
        }
        return true
    }

    /** The walkthrough's "When did your last period start?". See [LogRepository.logPeriod]. */
    fun logPeriod(start: LocalDate, end: LocalDate) {
        viewModelScope.launch { repo.logPeriod(start, end) }
    }

    /**
     * Marks [start] to [end] as a period from Today or History, in one step and with Undo. A period
     * has two dates, and before this the only way to give them was a form per day.
     */
    fun markPeriod(start: LocalDate, end: LocalDate) {
        viewModelScope.launch {
            val before = repo.logPeriod(start, end)
            val what = if (start == end) dayLabel(start) else "${dayLabel(start)} to ${dayLabel(end)}"
            _undoable.tryEmit(Undoable("Period marked · $what", before))
            reloadIfShowing(before)
        }
    }

    /** One answer to the bleeding question for one day, from Today's period prompt. */
    fun answerBleeding(date: LocalDate, bleeding: Boolean) {
        viewModelScope.launch {
            val before = repo.answerBleeding(listOf(date), bleeding)
            _undoable.tryEmit(Undoable(if (bleeding) "Logged: bleeding, ${dayLabel(date)}" else "Logged: no bleeding, ${dayLabel(date)}", before))
            reloadIfShowing(before)
        }
    }

    /** If the form is open on a day just written, with nothing typed since, reload it. */
    private fun reloadIfShowing(days: List<DayEntry>) {
        val open = _ui.value
        if (!open.dirty && days.any { it.date == open.entry.date }) open(open.entry.date)
    }

    /** Throws away unsaved edits, so nothing chosen to be discarded can resurface later. */
    fun discardEdits() {
        _ui.value = _ui.value.copy(entry = _ui.value.original)
    }

    /** True, false, or null to clear the answer. Tapping the selected chip again clears it. */
    fun setBleeding(answer: Boolean?) = edit { it.answeringBleeding(answer) }

    /** Tapping Spotting again clears the answer, as every answer on the form does. */
    fun toggleSpotting() = edit { if (it.spotting) it.answeringBleeding(null) else it.answeringSpotting() }

    fun setFlow(flow: FlowLevel) = edit {
        // A flow is a "yes". Tapping the selected level again clears the flow, not the yes: flow is
        // optional even while bleeding.
        it.answeringBleeding(true).copy(flow = if (it.flow == flow) null else flow)
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
            val state = _ui.value
            repo.save(state.entry)
            _ui.value = state.copy(saved = true)
            // Saving gave no sign it had happened: the form just closed. The snackbar names the day,
            // which also catches a save to the wrong one, and makes a mistaken save one tap to undo.
            _undoable.tryEmit(Undoable("Saved · ${dayLabel(state.entry.date)}", state.original))
            // In a catch-up run, the next missed day opens instead of leaving the form.
            val next = catchUpQueue.firstOrNull()
            if (next != null) {
                catchUpQueue = catchUpQueue.drop(1)
                openDate(next, catchUp = (catchUpTotal - catchUpQueue.size) to catchUpTotal)
            } else {
                onDone()
            }
        }
    }

    /** Puts the day back as it was before the write being undone. See [LogRepository.restore]. */
    fun undo(undoable: Undoable) {
        viewModelScope.launch {
            undoable.previous.forEach { repo.restore(it) }
            // If the form is open on that day with nothing typed since, reload it. Otherwise it keeps
            // showing the undone state as unchanged, and the next Save writes it straight back.
            reloadIfShowing(undoable.previous)
        }
    }

    /**
     * "Yes, this date is right" — promotes a backfilled guess to an observation.
     *
     * Kept separate from [save] on purpose. A confirmation is a statement about the *bleeding*,
     * and it feeds variability and confidence; it must never be a side effect of adding a note.
     */
    fun confirmBackfill(onDone: () -> Unit) {
        viewModelScope.launch {
            val original = _ui.value.original
            repo.save(_ui.value.entry, confirmed = true)
            _undoable.tryEmit(Undoable("Marked as observed", original))
            open(_ui.value.entry.date)
            onDone()
        }
    }

    /** "No, I made that up" — deletes the day so an extrapolated guess stops feeding the engine. */
    fun discardBackfill(onDone: () -> Unit) {
        viewModelScope.launch {
            val original = _ui.value.original
            repo.discard(_ui.value.entry.date)
            // Remove deleted the day with no confirmation. Undo is the cheaper safeguard: a dialog
            // would add a tap to every correct removal in a loop built for removing many.
            _undoable.tryEmit(Undoable("Estimated day removed", original))
            open(_ui.value.entry.date)
            onDone()
        }
    }

    private inline fun edit(block: (DayEntry) -> DayEntry) {
        _ui.value = _ui.value.copy(entry = block(_ui.value.entry), saved = false)
    }
}
