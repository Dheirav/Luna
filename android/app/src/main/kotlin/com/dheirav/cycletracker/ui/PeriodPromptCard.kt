package com.dheirav.cycletracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dheirav.cycletracker.core.PeriodPrompt
import com.dheirav.cycletracker.ui.theme.currentPhaseColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The period question, asked on Today when there is one to ask (see [PeriodPrompt]).
 *
 * Two answers each, both one tap, and each goes through the log form's Undo. "Earlier" and "It came"
 * open [MarkPeriodDialog], because a start date alone is not enough: a period that started four days
 * ago and is still going is four bleeding days, and marking only the first would leave a gap the
 * engine reads as two separate spans.
 */
@Composable
fun PeriodPromptCard(
    prompt: PeriodPrompt,
    today: LocalDate,
    onAnswerToday: (bleeding: Boolean) -> Unit,
    onMarkPeriod: (LocalDate, LocalDate) -> Unit,
) {
    if (prompt == PeriodPrompt.NONE) return
    var picking by remember { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    // The phase's wash, as the Why card and History's calendar use, so the question looks like part
    // of the same page as the hero above it.
    val (top, bottom) = MaterialTheme.currentPhaseColors ?: (scheme.secondaryContainer to scheme.primaryContainer)
    val wash = Brush.linearGradient(listOf(lerp(scheme.surfaceVariant, top, 0.6f), lerp(scheme.surfaceVariant, bottom, 0.6f)))

    val (question, yes, no) = when (prompt) {
        PeriodPrompt.SILENT -> Triple("Did your period come?", "It came…", "Not yet")
        PeriodPrompt.STARTED -> Triple("Has your period started?", "Today", "Earlier…")
        PeriodPrompt.STOPPED -> Triple("Is your period still going?", "Still going", "It's stopped")
        PeriodPrompt.NONE -> return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(wash)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (prompt == PeriodPrompt.SILENT) {
            Text(
                "If it did, mark the days it ran. \"Not yet\" logs today as no bleeding.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    when (prompt) {
                        PeriodPrompt.SILENT -> picking = true
                        PeriodPrompt.STARTED -> onMarkPeriod(today, today)
                        else -> onAnswerToday(true)
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(yes) }
            OutlinedButton(
                onClick = {
                    when (prompt) {
                        PeriodPrompt.STARTED -> picking = true
                        else -> onAnswerToday(false)
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text(no) }
        }
    }

    if (picking) {
        MarkPeriodDialog(
            today = today,
            onDismiss = { picking = false },
            onMark = { start, end ->
                picking = false
                onMarkPeriod(start, end)
            },
        )
    }
}

/**
 * Pick the first and last day of a period, up to today, and mark them all at once.
 *
 * Shared by Today and History. The last day may be left unpicked for a single day, or set to today
 * for a period still going. Future days cannot be chosen, as the log form refuses them too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkPeriodDialog(
    today: LocalDate,
    onDismiss: () -> Unit,
    onMark: (LocalDate, LocalDate) -> Unit,
    initialStart: LocalDate? = null,
    initialEnd: LocalDate? = null,
) {
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val todayMillis = millis(today)

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart?.let(::millis),
        initialSelectedEndDateMillis = initialEnd?.let(::millis),
        initialDisplayedMonthMillis = millis(initialStart ?: today),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    val start = state.selectedStartDateMillis?.let(::date)
    val end = state.selectedEndDateMillis?.let(::date) ?: start
    val count = if (start != null && end != null) ChronoUnit.DAYS.between(start, end).toInt() + 1 else 0

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = start != null,
                onClick = { if (start != null && end != null) onMark(start, end) },
            ) {
                // Says how much it is about to write, so a slip of the finger is visible before it lands.
                Text(if (count <= 1) "Mark 1 day" else "Mark $count days")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            title = {
                Text(
                    "First and last day of the period",
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
            showModeToggle = false,
            colors = DatePickerDefaults.colors(),
            modifier = Modifier.heightIn(max = 560.dp),
        )
    }
}
