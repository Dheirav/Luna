package com.dheirav.cycletracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dheirav.cycletracker.reminder.ReminderScheduler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheirav.cycletracker.core.CycleState
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * A first vertical slice: the corrected engine's answer for today, from the backfilled seed.
 *
 * Deliberately plain. The logging screen in Phase 2 is where design effort belongs, because
 * adherence is the constraint everything else depends on.
 */
@Composable
fun TodayScreen(viewModel: TodayViewModel, onLog: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    if (ui.loading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    val state = ui.state
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state == null || !state.hasData) {
            // §6 — no periods logged means no cycle day. Never invent day 14.
            Text("No periods logged yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Log a period to begin. Predictions stay hidden until there is something real to predict from.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onLog, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Log today", style = MaterialTheme.typography.titleMedium)
            }
            return@Column
        }

        Text(
            text = "Day ${state.cycleDay} of ${state.expectedCycleLength}",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = state.phase?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Unknown phase",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        if (ui.reminderBroken || ui.batteryRestricted) {
            ReminderWarning(broken = ui.reminderBroken)
        }

        // Logging is the primary action — adherence is the constraint everything else depends on.
        Button(
            onClick = onLog,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text("Log today", style = MaterialTheme.typography.titleMedium)
        }

        ConfidenceBar(state.phaseConfidence)

        if (state.daysLate > 0) {
            Text(
                "${state.daysLate} day${if (state.daysLate == 1) "" else "s"} later than expected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
                Detail("Cycle started", state.cycleStart?.format(fmt) ?: "—")
                Detail("Next period expected", state.nextPeriodExpected?.format(fmt) ?: "—")
                Detail("Ovulation day", state.ovulationDay?.toString() ?: "—")
                Detail(
                    "Cycle variability",
                    state.cycleLengthVariability
                        ?.let { "±%.1f days".format(it) }
                        ?: "not enough observed cycles",
                )
                Detail("Completed cycles", ui.projection.completedCycles.size.toString())
                Detail("Bleeding today", if (state.isBleeding) "yes" else "no")
            }
        }

        Text(
            "Confidence reflects phase timing only. Symptom predictions get their own measured " +
                "accuracy once there is a track record to score against.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AppLockSection()

        BackupSection(onRestored = viewModel::reload)
    }
}

/**
 * Surfaces a killed reminder rather than letting it fail silently.
 *
 * Vivo, Oppo, Xiaomi and Samsung all terminate background work on their own schedule, and none
 * of it can be fixed programmatically — Autostart and background-power allowances live in vendor
 * settings screens with no public API. The honest response is to detect that the reminder stopped
 * firing and say so, since a silently dead reminder ends the logging habit without warning.
 */
@Composable
private fun ReminderWarning(broken: Boolean) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (broken) "Your daily reminder has stopped firing" else "Reminders may be killed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "This phone restricts background apps. Allow unrestricted battery use, and enable " +
                    "Autostart for this app in the system settings — the reminder is what keeps " +
                    "the habit going.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(ReminderScheduler.batterySettingsIntent()) }
            }) { Text("Open battery settings") }
        }
    }
}

@Composable
private fun ConfidenceBar(confidence: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Phase confidence ${(confidence * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(
            progress = { confidence.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(170.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
