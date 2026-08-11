package com.dheirav.cycletracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.data.DaySummary
import com.dheirav.cycletracker.ui.theme.Sparkle
import com.dheirav.cycletracker.ui.theme.cycleColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A month at a time, so history is editable.
 *
 * The log form can already reach any past date, but only one day per tap on its stepper —
 * correcting a period from three months back cost about ninety taps. Adherence is the binding
 * constraint on this whole app (rule 4), and that includes the adherence cost of *fixing* data,
 * not just entering it.
 *
 * It doubles as the only place the observed/assumed split is visible at a glance. Eleven of the
 * thirteen seeded periods were extrapolated; until they are shown as such, "correct your own
 * history" is not something the user can actually act on.
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onPickDate: (LocalDate) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonthHeader(
            month = ui.month,
            canGoForward = ui.canGoForward,
            onShift = viewModel::shiftMonth,
        )

        WeekdayLabels()

        MonthGrid(
            month = ui.month,
            days = ui.days,
            window = ui.window,
            onPickDate = onPickDate,
        )

        Legend()

        Text(
            "Tap any day to log or correct it. Days you never logged stay blank — blank means " +
                "unknown, not zero.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonthHeader(month: YearMonth, canGoForward: Boolean, onShift: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onShift(-1) }) { Text("‹") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleLarge,
            )
            Sparkle(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                size = 11.dp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        TextButton(onClick = { onShift(1) }, enabled = canGoForward) { Text("›") }
    }
}

@Composable
private fun WeekdayLabels() {
    Row(modifier = Modifier.fillMaxWidth()) {
        weekDays.forEach { day ->
            Text(
                day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * Built as fixed rows of seven rather than a `LazyVerticalGrid`.
 *
 * The whole screen already scrolls, and nesting a lazy grid inside a scrolling column throws at
 * runtime on an infinite-height constraint. A month is at most six rows — there is nothing here
 * worth virtualising.
 */
@Composable
private fun MonthGrid(
    month: YearMonth,
    days: Map<LocalDate, DaySummary>,
    window: PeriodWindow?,
    onPickDate: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val first = month.atDay(1)
    // How many blanks before the 1st, given weeks start Monday.
    val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val cells = leading + month.lengthOfMonth()
    val rows = (cells + 6) / 7

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(rows) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(7) { column ->
                    val dayOfMonth = row * 7 + column - leading + 1
                    Box(modifier = Modifier.weight(1f)) {
                        if (dayOfMonth in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayOfMonth)
                            DayCell(
                                date = date,
                                summary = days[date],
                                isToday = date == today,
                                inPredictedWindow = window != null && date in window,
                                // The log form refuses future dates; the calendar should not
                                // offer them either, rather than opening a screen that says no.
                                enabled = !date.isAfter(today),
                                onClick = { onPickDate(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    summary: DaySummary?,
    isToday: Boolean,
    inPredictedWindow: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val cycle = MaterialTheme.cycleColors
    val bleeding = summary?.isBleeding == true
    val observedBleed = bleeding && summary?.isAssumed == false

    // Assumed bleeding is drawn as an outline, observed bleeding as a fill. The difference has to
    // survive a glance: it is the difference between data and a guess.
    //
    // A predicted day gets a wash at a fraction of the opacity, and always loses to a real one —
    // a forecast must never be as loud as an observation, and the two must never be mistakable.
    val fill = when {
        observedBleed -> cycle.bleeding
        inPredictedWindow -> cycle.predicted.copy(alpha = 0.30f)
        else -> Color.Transparent
    }
    val content = when {
        observedBleed -> cycle.onBleeding
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.3f)
        else -> scheme.onSurface
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(fill)
            .then(
                if (bleeding && summary?.isAssumed == true) {
                    Modifier.border(1.5.dp, cycle.estimated, CircleShape)
                } else {
                    Modifier
                },
            )
            .then(
                if (isToday) Modifier.border(2.dp, scheme.primary, CircleShape) else Modifier,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
            // A dot for a day that holds symptoms or notes but no bleeding — otherwise a
            // fully-logged non-bleeding day looks identical to one never opened.
            if (summary != null && !bleeding) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(cycle.logged),
                )
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LegendItem("Bleeding", filled = true)
        LegendItem("Estimated", filled = false)
        LegendItem("Expected", predicted = true)
        LegendItem("Logged", dot = true)
    }
}

@Composable
private fun LegendItem(
    label: String,
    filled: Boolean = false,
    dot: Boolean = false,
    predicted: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val cycle = MaterialTheme.cycleColors
    val swatch = when {
        dot -> cycle.logged
        filled -> cycle.bleeding
        predicted -> cycle.predicted.copy(alpha = 0.30f)
        else -> Color.Transparent
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(if (dot) 6.dp else 12.dp)
                .clip(CircleShape)
                .background(swatch)
                .then(
                    if (!filled && !dot && !predicted) {
                        Modifier.border(1.5.dp, cycle.estimated, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        )
        Text(label, fontSize = 11.sp, color = scheme.onSurfaceVariant)
    }
}

private val weekDays = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY,
)
