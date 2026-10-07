package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.CycleSnapshot
import com.dheirav.cycletracker.core.Guidance
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PhaseGuidance
import com.dheirav.cycletracker.core.PhaseObservation
import com.dheirav.cycletracker.core.PhaseSymptomSummary
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.core.SymptomPatterns
import com.dheirav.cycletracker.data.DailyLogEntity
import com.dheirav.cycletracker.data.SymptomValueEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class GuideUiState(
    val phase: Phase = Phase.MENSTRUATION,
    val guidance: PhaseGuidance = Guidance.forPhase(Phase.MENSTRUATION),
    /** Empty until enough symptoms have been logged in this phase. That is the normal state for
     *  months, and the screen has to read well while it is. */
    val yours: List<PhaseSymptomSummary> = emptyList(),
    /** Days in this phase carrying any symptom at all — drives the "keep logging" prompt. */
    val loggedDaysInPhase: Int = 0,
    val loading: Boolean = true,
    /** Today's phase, marked in the picker so reading ahead keeps "you are here". */
    val todayPhase: Phase? = null,
)

/**
 * Backs the phase guide: what is typical, and separately what this user's own logs show.
 *
 * Every logged day is filed under a phase by the shared snapshot rather than by a projection of its
 * own, so a day cannot be filed under one phase here and a different one on the calendar or the
 * hero, and the filing moves when the logs, the settings or the date do.
 */
class GuideViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as CycleTrackerApp).database.logDao()
    private val cycles = (app as CycleTrackerApp).cycles

    /** The phase being read about. Null means "whatever today is", resolved from the snapshot. */
    private val selected = MutableStateFlow<Phase?>(null)

    private val _ui = MutableStateFlow(GuideUiState())
    val ui: StateFlow<GuideUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(cycles.snapshots, dao.allLogs(), dao.allSymptoms(), selected) { snapshot, logs, symptoms, phase ->
                build(snapshot, logs, symptoms, phase)
            }.collect { _ui.value = it }
        }
    }

    fun load(phase: Phase?) {
        selected.value = phase
    }

    private fun build(
        snapshot: CycleSnapshot,
        logs: List<DailyLogEntity>,
        symptoms: List<SymptomValueEntity>,
        phase: Phase?,
    ): GuideUiState {
        val target = phase ?: snapshot.state.phase ?: Phase.MENSTRUATION

        val symptomsByDate = symptoms
            .groupBy { it.date }
            .mapValues { (_, rows) ->
                rows.mapNotNull { row -> Symptom.byKey(row.key)?.let { it to row.value } }.toMap()
            }

        // One engine call per logged day. Sixty-odd rows today and a few thousand after years,
        // which is still trivial next to the disk read that produced them.
        val observations = logs.map { log ->
            PhaseObservation(
                phase = snapshot.stateOn(log.date).phase,
                symptoms = symptomsByDate[log.date].orEmpty(),
            )
        }

        return GuideUiState(
            phase = target,
            guidance = Guidance.forPhase(target),
            yours = SymptomPatterns.summarise(observations, target),
            loggedDaysInPhase = observations.count {
                it.phase == target && it.symptoms.isNotEmpty()
            },
            loading = false,
            todayPhase = snapshot.state.phase,
        )
    }
}
