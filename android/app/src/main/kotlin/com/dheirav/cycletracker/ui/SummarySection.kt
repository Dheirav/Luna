package com.dheirav.cycletracker.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.ClinicalSummary
import com.dheirav.cycletracker.core.SummaryDocument
import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PhaseObservation
import com.dheirav.cycletracker.core.Symptom
import com.dheirav.cycletracker.core.SymptomPatterns
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.data.painByDate
import com.dheirav.cycletracker.data.snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The doctor summary's entry in Settings. The summary itself is [SummaryScreen].
 *
 * **Deliberately unencrypted, unlike the backup.** A file only this app can decrypt is useless in
 * an appointment. The trade is real and stated in the card rather than buried — the user chooses
 * where it lands, and it is readable by anything that opens it.
 */
@Composable
fun SummarySection(onOpen: () -> Unit) {
    SettingsCard(
        "Summary for a doctor",
        about = "Cycle and period lengths, flow, pain during periods, anything the app flagged, and " +
            "symptoms by phase. Estimated days are marked as estimated, and nothing is interpreted.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Stays visible rather than going behind the info button: it is the one trade this card
            // asks you to accept before pressing it.
            Text(
                "Not encrypted, unlike a backup — a file only this app can open is no use in an " +
                    "appointment. Share it as a PDF only where you are happy for it to be read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open summary") }
        }
    }
}

/**
 * Assembles the summary from the same engine everything else uses.
 *
 * Nothing is recomputed with its own rules here — if the summary disagreed with the app screen it
 * was generated from, the app would be handing a doctor a contradiction.
 */
internal suspend fun buildSummaryDocument(context: android.content.Context): SummaryDocument {
    val app = context.applicationContext as CycleTrackerApp
    val dao = app.database.logDao()
    val today = LocalDate.now()

    val logs = dao.allLogsOnce()
    val symptomRows = dao.allSymptomsOnce()
    val pain = symptomRows.painByDate()
    val snapshot = logs.snapshot(Settings(context).forEngine(), today, pain)
    val state = snapshot.state

    val symptomsByDate = symptomRows
        .groupBy { it.date }
        .mapValues { (_, rows) ->
            rows.mapNotNull { row -> Symptom.byKey(row.key)?.let { it to row.value } }.toMap()
        }
    val observations = logs.map { log ->
        PhaseObservation(
            phase = snapshot.stateOn(log.date).phase,
            symptoms = symptomsByDate[log.date].orEmpty(),
        )
    }

    return ClinicalSummary.document(
        projection = snapshot.projection,
        today = today,
        expectedCycleLength = state.expectedCycleLength,
        flags = snapshot.flags,
        // Read from the logs rather than from the summaries, because that is the whole point: the
        // summaries are empty both when nothing was logged and when the phase could not be worked
        // out, and only this side can tell those apart.
        anySymptomsLogged = symptomsByDate.values.any { it.isNotEmpty() },
        // Every phase, not only today's: the section used to cover whichever phase the person happened
        // to be in when they pressed the button.
        symptomsByPhase = Phase.entries.associateWith { SymptomPatterns.summarise(observations, it) },
        flowByDate = logs.mapNotNull { log ->
            log.flow?.let { name -> runCatching { FlowLevel.valueOf(name) }.getOrNull()?.let { log.date to it } }
        }.toMap(),
        painByDate = pain,
        lengthSource = snapshot.basis.source,
    )
}
