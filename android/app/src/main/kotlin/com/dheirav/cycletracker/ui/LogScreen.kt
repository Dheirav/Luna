package com.dheirav.cycletracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheirav.cycletracker.core.DayTag
import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Source
import com.dheirav.cycletracker.core.Symptom
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The logging screen. Everything analytical in this app is worthless without adherence, so the
 * design constraint is one screen, five taps, under ten seconds.
 *
 * Consequences of that constraint, visible throughout:
 *  - Taps, never sliders. A slider is a drag with a target; a segmented button is one tap.
 *  - Only three symptoms by default. The other four sit behind "More".
 *  - Every field optional, and tapping a selected level again unsets it — a mistake costs one tap.
 *  - Retro-logging is arrows on the date, not a picker. Yesterday is the common case.
 */
@Composable
fun LogScreen(viewModel: LogViewModel, onSaved: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val entry = ui.entry

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DateStepper(
            date = entry.date,
            onShift = viewModel::shiftDay,
        )

        if (entry.exists && entry.source == Source.ASSUMED) {
            BackfillBanner(
                onConfirm = { viewModel.confirmBackfill {} },
                onDiscard = { viewModel.discardBackfill {} },
            )
        }

        HorizontalDivider()

        // -- bleeding ----------------------------------------------------
        Text("Bleeding", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = entry.isBleeding,
                onClick = { viewModel.setBleeding(!entry.isBleeding) },
                label = { Text(if (entry.isBleeding) "Bleeding" else "No") },
            )
            FlowLevel.entries.forEach { level ->
                FilterChip(
                    selected = entry.flow == level,
                    onClick = { viewModel.setFlow(level) },
                    label = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        HorizontalDivider()

        // -- core symptoms -----------------------------------------------
        Symptom.core.forEach { symptom ->
            SymptomRow(
                symptom = symptom,
                value = entry.symptoms[symptom],
                onSelect = { viewModel.setSymptom(symptom, it) },
            )
        }

        TextButton(onClick = viewModel::toggleExtended) {
            Text(if (ui.showExtended) "Fewer" else "More — mood, stress")
        }

        if (ui.showExtended) {
            Symptom.extended.forEach { symptom ->
                SymptomRow(
                    symptom = symptom,
                    value = entry.symptoms[symptom],
                    onSelect = { viewModel.setSymptom(symptom, it) },
                )
            }
        }

        HorizontalDivider()

        // -- confounders --------------------------------------------------
        Text("Anything unusual?", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DayTag.entries.take(3).forEach { tag -> TagChip(tag, entry.tags, viewModel) }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DayTag.entries.drop(3).forEach { tag -> TagChip(tag, entry.tags, viewModel) }
        }

        OutlinedTextField(
            value = entry.notes,
            onValueChange = viewModel::setNotes,
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Button(
            onClick = { viewModel.save(onSaved) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text("Save", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            "Leave anything blank that you don't know. Blank is recorded as unknown, never as zero.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Marks a day the backfill invented.
 *
 * Eleven of the thirteen seeded periods were extrapolated backwards at a uniform 28 days — nobody
 * observed them. Without this the user cannot tell those days from ones they actually lived, which
 * makes "correct your own history" impossible: you cannot fix what you cannot see.
 */
@Composable
private fun BackfillBanner(onConfirm: () -> Unit, onDiscard: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Estimated, not observed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Backfill guessed this day by counting back 28 days. It is excluded from your " +
                    "variability and confidence figures until you say otherwise.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirm) { Text("That's right") }
                TextButton(onClick = onDiscard) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun DateStepper(date: LocalDate, onShift: (Long) -> Unit) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onShift(-1) }) { Text("‹ Earlier") }
        Text(label, style = MaterialTheme.typography.titleLarge)
        TextButton(
            onClick = { onShift(1) },
            enabled = date.isBefore(today),
        ) { Text("Later ›") }
    }
}

@Composable
private fun SymptomRow(symptom: Symptom, value: Int?, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(symptom.label, style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            symptom.levels.forEachIndexed { index, level ->
                SegmentedButton(
                    selected = value == index,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, symptom.levels.size),
                    label = {
                        Text(
                            level,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            fontSize = 11.sp,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TagChip(tag: DayTag, selected: Set<DayTag>, viewModel: LogViewModel) {
    FilterChip(
        selected = tag in selected,
        onClick = { viewModel.toggleTag(tag) },
        label = { Text(tag.label, maxLines = 1, fontSize = 12.sp) },
    )
}
