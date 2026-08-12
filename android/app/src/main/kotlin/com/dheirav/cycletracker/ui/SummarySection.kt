package com.dheirav.cycletracker.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.ClinicalSummary
import com.dheirav.cycletracker.core.CycleEngine
import com.dheirav.cycletracker.core.CycleProjector
import com.dheirav.cycletracker.core.HealthFlags
import com.dheirav.cycletracker.core.PhaseObservation
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.core.SymptomPatterns
import com.dheirav.cycletracker.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Writes a plain-text summary a clinician can read.
 *
 * The health flags say a pattern is "worth mentioning to a doctor" and then leave you with
 * nothing to bring: the only export was an encrypted blob no other software can open. This closes
 * that loop.
 *
 * **Deliberately unencrypted, unlike the backup.** A file only this app can decrypt is useless in
 * an appointment. The trade is real and stated in the card rather than buried — the user chooses
 * where it lands, and it is readable by anything that opens text.
 */
@Composable
fun SummarySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) { buildSummary(context) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray())
                    } ?: error("Could not open the file for writing")
                }
            }
            Toast.makeText(
                context,
                result.fold({ "Summary saved" }, { "Could not save: ${it.message}" }),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Summary for a doctor",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Your cycle lengths, period lengths, anything the app flagged, and symptom " +
                    "averages — as plain text you can print or email. Estimated days are marked " +
                    "as estimated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Not encrypted, unlike a backup — a file only this app can open is no use in an " +
                    "appointment. Save it somewhere you are happy for it to be readable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { save.launch("cycle-summary-${LocalDate.now()}.txt") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save summary") }
        }
    }
}

/**
 * Assembles the summary from the same engine everything else uses.
 *
 * Nothing is recomputed with its own rules here — if the summary disagreed with the app screen it
 * was generated from, the app would be handing a doctor a contradiction.
 */
private suspend fun buildSummary(context: android.content.Context): String {
    val app = context.applicationContext as CycleTrackerApp
    val dao = app.database.logDao()
    val settings = Settings(context)
    val engine = CycleEngine()
    val today = LocalDate.now()

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

    val symptomsByDate = dao.allSymptomsOnce()
        .groupBy { it.date }
        .mapValues { (_, rows) ->
            rows.mapNotNull { row -> Symptom.byKey(row.key)?.let { it to row.value } }.toMap()
        }
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

    return ClinicalSummary.build(
        projection = projection,
        today = today,
        expectedCycleLength = state.expectedCycleLength,
        flags = HealthFlags.evaluate(projection, today, state.expectedCycleLength),
        symptomSummaries = state.phase?.let { SymptomPatterns.summarise(observations, it) }.orEmpty(),
        // Read from the logs rather than from the summaries, because that is the whole point: the
        // summaries are empty both when nothing was logged and when the phase could not be worked
        // out, and only this side can tell those apart.
        anySymptomsLogged = symptomsByDate.values.any { it.isNotEmpty() },
    )
}
