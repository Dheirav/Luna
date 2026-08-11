package com.dheirav.cycletracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.CycleEngine
import com.dheirav.cycletracker.core.CycleProjector
import com.dheirav.cycletracker.core.Guidance
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PhaseGuidance
import com.dheirav.cycletracker.core.PhaseObservation
import com.dheirav.cycletracker.core.PhaseSymptomSummary
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.core.SymptomPatterns
import com.dheirav.cycletracker.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class GuideUiState(
    val phase: Phase = Phase.MENSTRUATION,
    val guidance: PhaseGuidance = Guidance.forPhase(Phase.MENSTRUATION),
    /** Empty until enough symptoms have been logged in this phase. That is the normal state for
     *  months, and the screen has to read well while it is. */
    val yours: List<PhaseSymptomSummary> = emptyList(),
    /** Days in this phase carrying any symptom at all — drives the "keep logging" prompt. */
    val loggedDaysInPhase: Int = 0,
    val loading: Boolean = true,
)

/**
 * Backs the phase guide: what is typical, and separately what this user's own logs show.
 *
 * The mapping from logged day to phase runs through [CycleEngine] rather than being recomputed —
 * the same engine the rest of the app uses, so a day cannot be filed under one phase here and a
 * different one on the calendar.
 */
class GuideViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = (app as CycleTrackerApp).database.logDao()
    private val engine = CycleEngine()
    private val settings = Settings(app)

    private val _ui = MutableStateFlow(GuideUiState())
    val ui: StateFlow<GuideUiState> = _ui.asStateFlow()

    fun load(phase: Phase?) {
        viewModelScope.launch {
            val logs = dao.allLogsOnce()
            val bleeding = logs.filter { it.isBleeding }.map { it.date }
            val assumed = logs.filter { it.isBleeding && it.source == "ASSUMED" }
                .map { it.date }.toSet()
            val projection = CycleProjector.project(bleeding, assumedDays = assumed)

            val target = phase
                ?: engine.stateFor(
                    LocalDate.now(), projection,
                    bleedingDays = bleeding.toSet(),
                    userTypicalCycleLength = settings.typicalCycleLength,
                    userTypicalPeriodLength = settings.typicalPeriodLength,
                ).phase
                ?: Phase.MENSTRUATION

            val symptomsByDate = dao.allSymptomsOnce()
                .groupBy { it.date }
                .mapValues { (_, rows) ->
                    rows.mapNotNull { row -> Symptom.byKey(row.key)?.let { it to row.value } }.toMap()
                }

            // One engine call per logged day. Sixty-odd rows today and a few thousand after years,
            // which is still trivial next to the disk read that produced them.
            val observations = logs.map { log ->
                PhaseObservation(
                    phase = engine.stateFor(
                        log.date, projection,
                        bleedingDays = bleeding.toSet(),
                        userTypicalCycleLength = settings.typicalCycleLength,
                        userTypicalPeriodLength = settings.typicalPeriodLength,
                    ).phase,
                    symptoms = symptomsByDate[log.date].orEmpty(),
                )
            }

            _ui.value = GuideUiState(
                phase = target,
                guidance = Guidance.forPhase(target),
                yours = SymptomPatterns.summarise(observations, target),
                loggedDaysInPhase = observations.count {
                    it.phase == target && it.symptoms.isNotEmpty()
                },
                loading = false,
            )
        }
    }
}
