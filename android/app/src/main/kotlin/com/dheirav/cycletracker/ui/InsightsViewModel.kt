package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.Insights
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PhaseObservation
import com.dheirav.cycletracker.core.PhaseSymptomSummary
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.core.SymptomPatterns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class InsightsUiState(
    val loading: Boolean = true,
    val insights: Insights? = null,
    /** What was logged in each phase, where at least the guide's minimum was. */
    val symptomsByPhase: Map<Phase, List<PhaseSymptomSummary>> = emptyMap(),
)

/**
 * Feeds the Patterns view from the same snapshot every other screen renders, so a chart can never
 * disagree with Today or the doctor summary. Symptoms are filed under phases the same way the phase
 * guide's "Yours" card files them.
 */
class InsightsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as CycleTrackerApp).database.logDao()
    private val cycles = (app as CycleTrackerApp).cycles

    private val _ui = MutableStateFlow(InsightsUiState())
    val ui: StateFlow<InsightsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(cycles.snapshots, dao.allLogs(), dao.allSymptoms()) { snapshot, logs, symptoms ->
                val symptomsByDate = symptoms.groupBy { it.date }.mapValues { (_, rows) ->
                    rows.mapNotNull { row -> Symptom.byKey(row.key)?.let { it to row.value } }.toMap()
                }
                val observations = logs.map { log ->
                    PhaseObservation(phase = snapshot.stateOn(log.date).phase, symptoms = symptomsByDate[log.date].orEmpty())
                }
                InsightsUiState(
                    loading = false,
                    insights = Insights.build(snapshot.projection, noBleedingDays = snapshot.noBleedingDays),
                    symptomsByPhase = Phase.entries.associateWith { SymptomPatterns.summarise(observations, it) }
                        .filterValues { it.isNotEmpty() },
                )
            }.collect { _ui.value = it }
        }
    }
}
