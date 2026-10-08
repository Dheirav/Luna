package com.dheirav.cycletracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.mutableStateMapOf
import com.dheirav.cycletracker.ui.theme.currentPhaseAccent
import com.dheirav.cycletracker.ui.theme.currentPhaseColors
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.Dp
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
fun HistoryScreen(
    viewModel: HistoryViewModel,
    /** Marks a run of days as a period, with Undo. */
    onMarkPeriod: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    onPickDate: (LocalDate) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // A range waiting for confirmation: from a drag across the calendar, or empty from the button.
    var marking by remember { mutableStateOf<Pair<LocalDate?, LocalDate?>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackBar(title = "History")

        // The calendar sits on the hero's phase colours at a whisper, as the Why card does, so the
        // screen reads as part of the same app rather than a grey form (device review p1). Blended
        // towards the card colour so the day numbers keep their contrast.
        val scheme = MaterialTheme.colorScheme
        val (phaseTop, phaseBottom) = MaterialTheme.currentPhaseColors
            ?: (scheme.secondaryContainer to scheme.primaryContainer)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(
                    Brush.linearGradient(
                        listOf(lerp(scheme.surfaceVariant, phaseTop, 0.45f), lerp(scheme.surfaceVariant, phaseBottom, 0.45f)),
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MonthHeader(
                month = ui.month,
                canGoForward = ui.canGoForward,
                onShift = viewModel::shiftMonth,
            )

            WeekdayLabels()

            Box(Modifier.tourTarget(TourTarget.CALENDAR)) {
                MonthGrid(
                    month = ui.month,
                    days = ui.days,
                    window = ui.window,
                    today = ui.today,
                    onPickDate = onPickDate,
                    onDragRange = { start, end -> marking = start to end },
                )
            }

            Legend(window = ui.window, today = ui.today)
        }

        Text(
            "Tap a day to log or correct it, or press and drag across days to mark a period. Days " +
                "you never logged stay blank: blank means unknown, not zero.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The same thing without a drag, for a screen reader, a switch, or a period across months.
        OutlinedButton(onClick = { marking = null to null }, modifier = Modifier.fillMaxWidth()) {
            Text("Mark a period")
        }

        marking?.let { (start, end) ->
            MarkPeriodDialog(
                today = ui.today,
                initialStart = start,
                initialEnd = end,
                onDismiss = { marking = null },
                onMark = { s, e ->
                    marking = null
                    onMarkPeriod(s, e)
                },
            )
        }

        MonthLog(month = ui.month, days = ui.days, onPickDate = onPickDate)
    }
}

/**
 * What was actually logged this month, day by day.
 *
 * Fills the space under the calendar, and closes a real gap: symptoms could be entered but never
 * read back. The calendar showed a dot meaning "something here" and the values themselves were
 * write-only. Logging that visibly goes nowhere is logging that stops, and adherence is the
 * constraint everything else depends on.
 *
 * Newest first, because the recent days are the ones being corrected.
 */
@Composable
private fun MonthLog(
    month: YearMonth,
    days: Map<LocalDate, DaySummary>,
    onPickDate: (LocalDate) -> Unit,
) {
    val entries = days
        .filterKeys { YearMonth.from(it) == month }
        .toList()
        .sortedByDescending { it.first }

    // Estimated days are listed apart from logged ones. Under one "Logged this month" heading, a
    // month with nothing logged showed a column of backfilled bleeding days, the app's guesses
    // filed under the word it reserves for what the person recorded (device review M5).
    val (estimated, logged) = entries.partition { it.second.isAssumed }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Logged this month",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { heading() },
        )

        if (logged.isEmpty()) {
            Text(
                "Nothing logged in ${month.format(DateTimeFormatter.ofPattern("MMMM"))}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        logged.forEach { (date, summary) -> LogRow(date, summary, onPickDate) }

        if (estimated.isNotEmpty()) {
            Text(
                "Estimated by Luna",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { heading() },
            )
            Text(
                "Worked out by counting back, not logged by you. Open a day to confirm or remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            estimated.forEach { (date, summary) -> LogRow(date, summary, onPickDate) }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LogRow(date: LocalDate, summary: DaySummary, onPickDate: (LocalDate) -> Unit) {
    val cycle = MaterialTheme.cycleColors
    val estimatedRow = summary.isAssumed
    val shape = MaterialTheme.shapes.medium
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Dashed, as an estimated day is on the calendar, rather than the solid card a logged
            // day gets: the list and the grid draw the same distinction the same way.
            .then(if (estimatedRow) Modifier.dashedOutline(cycle.estimated, shape) else Modifier)
            .semantics(mergeDescendants = true) { }
            .clickable(onClickLabel = if (estimatedRow) "Confirm or remove this estimate" else "Edit this day") { onPickDate(date) },
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (estimatedRow) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                    style = MaterialTheme.typography.titleSmall,
                )
                // An answered "No" is shown too: it is an observation, and without it a day logged
                // as no bleeding looked the same as one where the question was skipped.
                if (summary.bleedingAnswered && !summary.isBleeding) {
                    Text(
                        "No bleeding",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.isBleeding) {
                    Text(
                        buildString {
                            append(summary.flow?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Bleeding")
                            // Estimated days are marked here too — the list must not quietly
                            // present backfill's guesses as things the user recorded.
                            if (summary.isAssumed) append(" · estimated")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        // The word carries "estimated", so the colour no longer has to. In
                        // `cycle.estimated` it measured about 2.6:1 on the cream card, short of AA for
                        // an 11sp label; the normal secondary text colour is legible and still quieter
                        // than an observed day's bleeding colour.
                        color = if (summary.isAssumed) MaterialTheme.colorScheme.onSurfaceVariant else cycle.bleeding,
                    )
                }
            }

            if (summary.symptoms.isNotEmpty()) {
                // Anchor words, not numbers. A stored 2 means nothing on its own, and the whole
                // point of the anchored scales is that "OK" still means "OK" six months later.
                Text(
                    summary.symptoms.entries
                        .sortedBy { it.key.ordinal }
                        .joinToString("   ") { (symptom, value) ->
                            "${symptom.label} ${symptom.levelLabel(value) ?: value}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (summary.tags.isNotEmpty()) {
                Text(
                    summary.tags.sortedBy { it.ordinal }.joinToString(" · ") { it.label },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (summary.notes.isNotBlank()) {
                Text(
                    summary.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!summary.bleedingAnswered && !summary.hasDetail) {
                Text(
                    "Logged, nothing recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, canGoForward: Boolean, onShift: (Long) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon buttons, as the log form's day arrows are. The "‹" glyph was 15px wide and faint, easy
        // to miss though its target was fine (device review p5).
        FilledTonalIconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleLarge,
                // Announced when the month changes; the arrows keep focus on themselves.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Sparkle(
                color = MaterialTheme.currentPhaseAccent.copy(alpha = 0.6f),
                size = 11.dp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        FilledTonalIconButton(onClick = { onShift(1) }, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
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
    today: LocalDate,
    onPickDate: (LocalDate) -> Unit,
    onDragRange: (LocalDate, LocalDate) -> Unit = { _, _ -> },
) {
    val first = month.atDay(1)
    // Press and hold a day, then drag across the rest of the period. Cells report where they are
    // drawn, in root coordinates, and the drag is mapped back onto them. Future days are left out,
    // as they are everywhere else.
    val cellRects = remember(month) { mutableStateMapOf<LocalDate, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragFrom by remember(month) { mutableStateOf<LocalDate?>(null) }
    var dragTo by remember(month) { mutableStateOf<LocalDate?>(null) }
    val haptics = LocalHapticFeedback.current
    fun cellAt(local: Offset): LocalDate? {
        val p = local + origin
        return cellRects.entries.firstOrNull { it.value.contains(p) }?.key?.takeUnless { it.isAfter(today) }
    }
    val range = dragFrom?.let { a -> dragTo?.let { b -> if (a <= b) a..b else b..a } }
    // How many blanks before the 1st, given weeks start Monday.
    val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val cells = leading + month.lengthOfMonth()
    val rows = (cells + 6) / 7

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .pointerInput(month, today) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        cellAt(at)?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragFrom = it
                            dragTo = it
                        }
                    },
                    onDrag = { change, _ -> cellAt(change.position)?.let { dragTo = it } },
                    onDragEnd = {
                        val a = dragFrom
                        val b = dragTo
                        dragFrom = null
                        dragTo = null
                        if (a != null && b != null) onDragRange(minOf(a, b), maxOf(a, b))
                    },
                    onDragCancel = {
                        dragFrom = null
                        dragTo = null
                    },
                )
            },
    ) {
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
                            Box(
                                Modifier
                                    .onGloballyPositioned { cellRects[date] = it.boundsInRoot() }
                                    .then(
                                        // The days being dragged over, so the range is visible as it grows.
                                        if (range != null && date in range) {
                                            Modifier.border(2.dp, MaterialTheme.cycleColors.bleeding, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
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
        // A faint wash plus a dotted ring, not a 0.3 wash: in dark mode that read as a solid disc,
        // while the tour teaches "filled was logged". A forecast, even a past one, is never filled
        // (device review M6).
        inPredictedWindow -> cycle.predicted.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val content = when {
        observedBleed -> cycle.onBleeding
        // A day in the window is in the future by definition, so the future dimming below used to
        // apply to every cell of the forecast — the most useful information on this screen rendered
        // as its least legible text, at 0.3 alpha over a lavender wash. Two rules were firing on the
        // same cells and always would: the wash saying *look here*, the dimming saying *unavailable*.
        // The wash already carries "expected, not certain". The number does not have to whisper too.
        inPredictedWindow -> scheme.onSurface.copy(alpha = 0.8f)
        // Still lighter than a past day, because a future day cannot be logged — but 0.3 was below
        // anything readable on a pale ground, and it applied to a third of the month.
        // 0.8, not 0.55: at 0.55 the future dates measured about 3.6:1 (device review m11). Still
        // quieter than a past day's full onSurface, which is the distinction that matters.
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.8f)
        else -> scheme.onSurface
    }

    // Everything this cell conveys is visual: a fill, an outline, a wash, a ring, a dot. Spoken
    // aloud it was just a number. mergeDescendants folds the day number in so the whole cell is
    // one announcement rather than a digit followed by unexplained decoration.
    val spoken = buildString {
        append(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")))
        if (isToday) append(", today")
        when {
            observedBleed -> append(", bleeding logged")
            bleeding -> append(", bleeding estimated, not logged by you")
            summary != null -> append(", logged")
            else -> append(", nothing logged")
        }
        if (inPredictedWindow) append(", inside the expected period window")
        if (!enabled) append(", future date")
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .background(fill)
            .then(
                if (bleeding && summary?.isAssumed == true) {
                    Modifier.estimatedRing(cycle.estimated)
                } else {
                    Modifier
                },
            )
            // Inside the estimated ring's inset, so a day that is both shows both.
            .then(if (inPredictedWindow) Modifier.expectedRing(cycle.predicted, inset = 6.dp) else Modifier)
            .then(
                if (isToday) Modifier.border(2.dp, scheme.primary, CircleShape) else Modifier,
            )
            .clickable(enabled = enabled, onClickLabel = "Log or correct this day", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
            // A short bar under days in the expected window, so the window does not rest on its
            // wash alone: the wash measured about 1.2:1 against the background, which low vision
            // and many colour-vision differences cannot see (council review A4, WCAG 1.4.1).
            if (inPredictedWindow) {
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(width = 10.dp, height = 2.dp)
                        .background(content.copy(alpha = 0.8f)),
                )
            }
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

/**
 * The dashed ring that marks an estimated bleeding day.
 *
 * Inset inside where today's ring lands, so a day that is **both** shows both. Before this, the two
 * were `Modifier.border` calls on the same circle and the second one simply painted over the first —
 * an estimated day that happened to be today lost its estimated marker with nothing to show it had.
 * Rare, and precisely the distinction this app will not blur anywhere else.
 *
 * Drawn rather than bordered because `Modifier.border` has no dash. See `CycleColors.estimated` for
 * why a dash and not another solid outline.
 */
private fun Modifier.estimatedRing(
    color: Color,
    inset: Dp = 3.dp,
    // Scaled to the circle it goes round, not fixed. A 3dp dash reads as broken on a 56dp calendar
    // cell — about 25 segments — and as a smudge on a 12dp legend swatch, where it manages five.
    // A marker whose whole job is to look dashed has to look dashed at both sizes.
    dash: Dp = 3.dp,
): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    val dashPx = dash.toPx()
    drawCircle(
        color = color,
        radius = (size.minDimension - stroke) / 2f - inset.toPx(),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx), 0f),
        ),
    )
}

/**
 * The dotted ring that marks a day in the expected window, past or future.
 *
 * Round dots where an estimated bleeding day has dashes, and lavender where that is dusty pink, so
 * the two kinds of "not logged" never read as one: an estimate of something that happened, and a
 * forecast of something that might.
 */
private fun Modifier.expectedRing(color: Color, inset: Dp = 3.dp): Modifier = drawBehind {
    val stroke = 1.8.dp.toPx()
    drawCircle(
        color = color,
        radius = (size.minDimension - stroke) / 2f - inset.toPx(),
        style = Stroke(
            width = stroke,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, stroke * 2.2f), 0f),
        ),
    )
}

/** A dashed rounded outline, for list cards that hold an estimate rather than a log. */
private fun Modifier.dashedOutline(color: Color, shape: androidx.compose.ui.graphics.Shape): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    val dash = 4.dp.toPx()
    drawOutline(
        shape.createOutline(size, layoutDirection, this),
        color,
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f)),
    )
}

/** What a cell can be marked with. Five now, which is why this is not four booleans. */
private enum class Marker { FILL, DASHED, WASH, DOT, RING }

// `FlowRow` is still `ExperimentalLayoutApi` on Compose BOM 2024.12.01 — stable from 1.8, which this
// project is not on yet. Opted in for the same reason as `TimePicker` in SettingsScreen: the
// alternative is hand-rolling wrapping layout, and a hand-rolled one would be the thing that breaks.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(window: PeriodWindow?, today: LocalDate) {
    // FlowRow, not Row: five items no longer fit one line at 440dpi, and at a large font scale even
    // four did not. A legend that runs off the edge documents nothing.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendItem("Bleeding", Marker.FILL)
        LegendItem("Estimated", Marker.DASHED)
        // Named for what the shading is: a typical spread until this person's cycles have been
        // measured, and a record rather than a forecast once its days are past.
        LegendItem(
            when {
                window?.hasPassed(today) == true -> "Was expected"
                window?.basis == com.dheirav.cycletracker.core.WindowBasis.ASSUMED -> "Expected (typical range)"
                else -> "Expected"
            },
            Marker.WASH,
        )
        LegendItem("Logged", Marker.DOT)
        // Today was drawn on the calendar and missing from the legend, so the legend's only ring
        // entry was "Estimated" — teaching the wrong reading of the ring around today's date.
        LegendItem("Today", Marker.RING)
    }
}

@Composable
private fun LegendItem(label: String, marker: Marker) {
    val scheme = MaterialTheme.colorScheme
    val cycle = MaterialTheme.cycleColors
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clearAndSetSemantics { }
                .size(if (marker == Marker.DOT) 6.dp else 12.dp)
                .clip(CircleShape)
                .background(
                    when (marker) {
                        Marker.FILL -> cycle.bleeding
                        Marker.DOT -> cycle.logged
                        Marker.WASH -> cycle.predicted.copy(alpha = 0.12f)
                        Marker.DASHED, Marker.RING -> Color.Transparent
                    },
                )
                .then(
                    when (marker) {
                        // The same function the calendar draws with, so the swatch is a sample
                        // rather than an approximation of one. No inset: 12dp has none to spare,
                        // and a shorter dash so a 12dp circle still reads as broken.
                        Marker.DASHED -> Modifier.estimatedRing(
                            cycle.estimated,
                            inset = 0.dp,
                            dash = 1.5.dp,
                        )
                        Marker.RING -> Modifier.border(1.5.dp, scheme.primary, CircleShape)
                        Marker.WASH -> Modifier.expectedRing(cycle.predicted, inset = 0.dp)
                        else -> Modifier
                    },
                ),
        ) {
            // The window's bar, as the calendar draws it, so the swatch matches the cell.
            if (marker == Marker.WASH) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .size(width = 6.dp, height = 1.5.dp)
                        .background(scheme.onSurface.copy(alpha = 0.8f)),
                )
            }
        }
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
