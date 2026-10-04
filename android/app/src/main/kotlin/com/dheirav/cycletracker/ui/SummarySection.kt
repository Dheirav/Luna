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
 * A plain-text summary a clinician can read, previewed in the app and shared from there.
 *
 * The health flags say a pattern is "worth mentioning to a doctor" and then leave you with
 * nothing to bring: the only export was an encrypted blob no other software can open. This closes
 * that loop.
 *
 * **Deliberately unencrypted, unlike the backup.** A file only this app can decrypt is useless in
 * an appointment. The trade is real and stated in the card rather than buried — the user chooses
 * where it lands, and it is readable by anything that opens text.
 *
 * Since 5 Oct 2026 it is shown before it goes anywhere, and shared through Android's share sheet
 * (print, email, a notes app) as well as saved to a file. It used to go straight to a file picker,
 * unseen. Sharing uses no permission of this app's: the person picks where the text goes.
 */
@Composable
fun SummarySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = preview
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
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
                    "appointment. Share or save it somewhere you are happy for it to be readable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        preview = runCatching { withContext(Dispatchers.IO) { buildSummary(context) } }
                            .getOrElse { "Could not build the summary: ${it.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Preview summary") }
        }
    }

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Summary for a doctor") },
            text = {
                // Monospaced, because the summary lines up its figures with dot leaders, and
                // scrollable both ways so no line wraps out of alignment on a narrow screen.
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Cycle summary")
                            .putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Share summary"))
                    }) { Text("Share") }
                    TextButton(onClick = { save.launch("cycle-summary-${LocalDate.now()}.txt") }) {
                        Text("Save as file")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
        )
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

    return ClinicalSummary.build(
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
