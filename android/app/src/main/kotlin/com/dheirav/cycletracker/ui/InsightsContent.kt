package com.dheirav.cycletracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dheirav.cycletracker.core.Insights
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PhaseSymptomSummary
import com.dheirav.cycletracker.ui.theme.SparkleCluster
import com.dheirav.cycletracker.ui.theme.currentPhaseAccent
import com.dheirav.cycletracker.ui.theme.currentPhaseColors
import com.dheirav.cycletracker.ui.theme.cycleColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private val shortDate = DateTimeFormatter.ofPattern("d MMM yyyy")
private val monthYear = DateTimeFormatter.ofPattern("MMM yyyy")

/**
 * Patterns: what the logs add up to, drawn.
 *
 * Built to the same rules as everything else. Estimated cycles and days are dashed, as on the ring
 * and the calendar. A "typical" figure appears only once the data has earned it, and until then the
 * screen says how much more is needed instead of drawing a line through too little. The usual range
 * is a reference band, labelled as one, and never a verdict on a single cycle. Every chart can also
 * be read as a list, and is described in words for a screen reader.
 */
@Composable
fun InsightsContent(ui: InsightsUiState) {
    val insights = ui.insights ?: return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (insights.isEmpty) {
            Text(
                "Your patterns appear here once a period has been logged.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Headline(insights)
        CycleChartCard(insights)
        PeriodChartCard(insights)
        SymptomTable(ui.symptomsByPhase)
    }
}

/** The two figures, or how far each is from meaning something. On the phase wash, with a sparkle. */
@Composable
private fun Headline(insights: Insights) {
    val scheme = MaterialTheme.colorScheme
    val (top, bottom) = MaterialTheme.currentPhaseColors ?: (scheme.secondaryContainer to scheme.primaryContainer)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(lerp(scheme.surfaceVariant, top, 0.6f), lerp(scheme.surfaceVariant, bottom, 0.6f)))),
    ) {
        SparkleCluster(
            color = MaterialTheme.currentPhaseAccent.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 10.dp),
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your patterns", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "Typical cycle",
                    value = insights.typicalCycle?.let { "$it days" },
                    note = if (insights.typicalCycle != null) {
                        insights.cycleSpread?.let { "give or take %.1f days".format(it) } ?: "from your logged cycles"
                    } else {
                        "${insights.cyclesNeeded} more logged ${if (insights.cyclesNeeded == 1) "cycle" else "cycles"} until this means something"
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                StatTile(
                    label = "Typical period",
                    value = insights.typicalPeriod?.let { "$it days" },
                    note = if (insights.typicalPeriod != null) "from periods logged day by day" else "needs two periods logged day by day",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String?, note: String, modifier: Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // "Not yet" rather than a number the data has not earned.
        Text(value ?: "Not yet", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One bar: a solid part (logged) and a dashed part on top of it (estimated). */
private data class Bar(val solid: Int, val dashed: Int, val detail: String) {
    val total: Int get() = solid + dashed
}

@Composable
private fun CycleChartCard(insights: Insights) {
    // A cycle's length is known only once the next period starts, so the first period alone has none.
    if (insights.cycles.isEmpty()) {
        Text(
            "Cycle lengths appear once your next period starts. A cycle runs from one period to the next.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val bars = insights.cycles.map {
        Bar(
            solid = if (it.observed) it.length else 0,
            dashed = if (it.observed) 0 else it.length,
            detail = "Cycle from ${it.start.format(shortDate)}: ${it.length} days, " +
                if (it.observed) "logged" else "estimated, worked out by counting back",
        )
    }
    ChartCard(
        title = "Cycle lengths",
        subtitle = "Your last ${bars.size} completed ${if (bars.size == 1) "cycle" else "cycles"}, oldest first",
        bars = bars,
        color = MaterialTheme.cycleColors.chartCycle,
        tickStep = 7,
        minMax = 42,
        band = Insights.USUAL_CYCLE_RANGE,
        bandLabel = "Usual range for adults, 24 to 38 days",
        typical = insights.typicalCycle,
        firstDate = insights.cycles.firstOrNull()?.start,
        lastDate = insights.cycles.lastOrNull()?.start,
        unit = "days",
        spoken = "Cycle lengths, oldest first: " + insights.cycles.joinToString { "${it.length} days ${if (it.observed) "logged" else "estimated"}" },
    )
}

@Composable
private fun PeriodChartCard(insights: Insights) {
    // Says why the latest period is missing, rather than leaving it to look forgotten.
    val unfinished = insights.unfinishedPeriod?.let {
        "Your latest period, from ${it.format(shortDate)}, isn't shown: it hasn't been logged as over, " +
            "so its length isn't known yet."
    }
    if (insights.periods.isEmpty()) {
        unfinished?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val bars = insights.periods.map {
        Bar(
            solid = it.loggedDays,
            dashed = it.estimatedDays,
            detail = "Period from ${it.start.format(shortDate)}: " + when {
                it.estimatedDays == 0 -> "${it.loggedDays} days, all logged"
                it.loggedDays == 0 -> "${it.estimatedDays} days, all estimated"
                else -> "${it.totalDays} days, ${it.loggedDays} logged and ${it.estimatedDays} estimated"
            },
        )
    }
    ChartCard(
        title = "Period lengths",
        subtitle = "Bleeding days per period, oldest first",
        bars = bars,
        color = MaterialTheme.cycleColors.chartPeriod,
        tickStep = 2,
        minMax = 8,
        // From 0, not 1: a band starting at a day left a strip under every bar that read as data.
        band = 0..Insights.USUAL_PERIOD_MAX,
        bandLabel = "Usual range, up to 8 days",
        typical = insights.typicalPeriod,
        firstDate = insights.periods.firstOrNull()?.start,
        lastDate = insights.periods.lastOrNull()?.start,
        unit = "days",
        spoken = "Period lengths, oldest first: " + insights.periods.joinToString {
            "${it.totalDays} days" + if (it.estimatedDays > 0) ", ${it.estimatedDays} estimated" else ""
        },
        footnote = unfinished,
    )
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    bars: List<Bar>,
    color: Color,
    tickStep: Int,
    minMax: Int,
    band: IntRange,
    bandLabel: String,
    typical: Int?,
    firstDate: LocalDate?,
    lastDate: LocalDate?,
    unit: String,
    spoken: String,
    footnote: String? = null,
) {
    var selected by remember(bars) { mutableIntStateOf(bars.lastIndex) }
    var asList by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        // The surface the chart colours were validated against.
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                TextButton(onClick = { asList = !asList }) { Text(if (asList) "Show chart" else "Show as list") }
            }

            // Two kinds of bar, so a legend: logged solid, estimated dashed, and the reference band.
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(color, dashed = false, label = "Logged")
                LegendSwatch(color, dashed = true, label = "Estimated")
                LegendSwatch(color.copy(alpha = 0.12f), dashed = false, label = "Usual range", wash = true)
            }

            if (asList) {
                bars.forEach { Text(it.detail, style = MaterialTheme.typography.bodySmall) }
            } else {
                BarChart(
                    bars = bars,
                    color = color,
                    tickStep = tickStep,
                    yMax = maxOf(minMax, (bars.maxOfOrNull { it.total } ?: 0) + 1).let { ceil(it / tickStep.toDouble()).toInt() * tickStep },
                    band = band,
                    typical = typical,
                    selected = selected,
                    onSelect = { selected = it },
                    spoken = spoken,
                )
                if (firstDate != null && lastDate != null) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(firstDate.format(monthYear), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(lastDate.format(monthYear), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                // The tapped bar, in words. Starts on the most recent.
                bars.getOrNull(selected)?.let {
                    Text(it.detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                }
            }
            Text(
                bandLabel + (typical?.let { ". The line marks your typical, $it $unit." } ?: "."),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            footnote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, dashed: Boolean, label: String, wash: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(width = 12.dp, height = 12.dp).clearAndSetSemantics { }) {
            val r = CornerRadius(2.dp.toPx())
            when {
                wash -> drawRoundRect(color, cornerRadius = r)
                dashed -> drawRoundRect(
                    color,
                    cornerRadius = r,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
                )
                else -> drawRoundRect(color, cornerRadius = r)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Columns from one baseline, at most 24dp wide with 4dp rounded tops and square feet, hairline solid
 * gridlines, a faint reference band, a hairline for "typical", and the value on the selected bar
 * only. Dashes mean estimated and nothing else, so no gridline or reference line is ever dashed.
 */
@Composable
private fun BarChart(
    bars: List<Bar>,
    color: Color,
    tickStep: Int,
    yMax: Int,
    band: IntRange,
    typical: Int?,
    selected: Int,
    onSelect: (Int) -> Unit,
    spoken: String,
) {
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val grid = scheme.outlineVariant
    val muted = scheme.onSurfaceVariant
    val ink = scheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val axisWidth = with(density) { 24.dp.toPx() }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = spoken }
            .pointerInput(bars) {
                detectTapGestures { at ->
                    val slot = (size.width - axisWidth) / bars.size
                    val i = ((at.x - axisWidth) / slot).toInt()
                    if (i in bars.indices) onSelect(i)
                }
            },
    ) {
        val top = 18.dp.toPx()
        val plotH = size.height - top
        val plotW = size.width - axisWidth
        fun y(v: Int) = top + plotH * (1f - v / yMax.toFloat())

        // Reference band, a ~10% wash of the series hue.
        drawRect(color.copy(alpha = 0.12f), topLeft = Offset(axisWidth, y(band.last)), size = Size(plotW, y(band.first) - y(band.last)))

        // Gridlines and ticks: hairline, solid, recessive.
        var v = 0
        while (v <= yMax) {
            drawLine(grid, Offset(axisWidth, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(v.toString(), labelStyle.copy(color = muted))
            drawText(t, topLeft = Offset(axisWidth - t.size.width - 4.dp.toPx(), y(v) - t.size.height / 2f))
            v += tickStep
        }

        val slot = plotW / bars.size
        val barW = minOf(24.dp.toPx(), slot - 4.dp.toPx()).coerceAtLeast(3.dp.toPx())
        val corner = 4.dp.toPx()
        val gap = 2.dp.toPx()
        bars.forEachIndexed { i, bar ->
            val left = axisWidth + slot * i + (slot - barW) / 2f
            val dim = if (i == selected) 1f else 0.55f
            if (bar.solid > 0) {
                columnPath(left, y(bar.solid), barW, y(0), if (bar.dashed > 0) 0f else corner)
                    .let { drawPath(it, color.copy(alpha = dim)) }
            }
            if (bar.dashed > 0) {
                // Stacked on the logged part with a 2dp surface gap, outlined and dashed.
                val base = if (bar.solid > 0) y(bar.solid) - gap else y(0)
                val stroke = 1.5.dp.toPx()
                val path = columnPath(left + stroke / 2, y(bar.total) + stroke / 2, barW - stroke, base, corner)
                drawPath(path, color.copy(alpha = 0.12f * dim))
                drawPath(path, color.copy(alpha = dim), style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()))))
            }
            if (i == selected) {
                val t = measurer.measure(bar.total.toString(), labelStyle.copy(color = ink))
                drawText(t, topLeft = Offset(left + barW / 2 - t.size.width / 2f, y(bar.total) - t.size.height - 2.dp.toPx()))
            }
        }

        // Typical, as a hairline across the bars in the text colour, labelled at its end.
        typical?.let {
            drawLine(muted.copy(alpha = 0.8f), Offset(axisWidth, y(it)), Offset(size.width, y(it)), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/** A column with rounded top corners and a square foot on the baseline. */
private fun DrawScope.columnPath(left: Float, top: Float, width: Float, bottom: Float, corner: Float): Path = Path().apply {
    val h = (bottom - top).coerceAtLeast(0f)
    val r = minOf(corner, h, width / 2)
    addRoundRect(
        RoundRect(
            left = left, top = top, right = left + width, bottom = bottom,
            topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
            bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
        ),
    )
}

/**
 * What was logged in each phase, in the scale's own words, as a table rather than a chart: the
 * values are anchor words on an ordinal scale, and a colour ramp over them would claim a precision a
 * self-reported 0 to 4 does not have.
 */
@Composable
private fun SymptomTable(byPhase: Map<Phase, List<PhaseSymptomSummary>>) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Symptoms by phase", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            if (byPhase.isEmpty()) {
                Text(
                    "Appears once a symptom has been logged on enough days in a phase. Each phase needs " +
                        "a few logged days before a summary of it is worth showing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                return@Column
            }
            val phases = Phase.entries.filter { it in byPhase }
            val symptoms = byPhase.values.flatten().map { it.symptom }.distinct().sortedBy { it.ordinal }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1.2f))
                phases.forEach { Text(phaseName(it), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
            }
            symptoms.forEach { symptom ->
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                    Text(symptom.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
                    phases.forEach { phase ->
                        val s = byPhase[phase]?.firstOrNull { it.symptom == symptom }
                        Column(Modifier.weight(1f)) {
                            Text(s?.label() ?: "—", style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                            s?.let { Text("${it.daysObserved} days", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Text(
                "What you logged on days in each phase, not a claim about cause. The phases are worked out by the app.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
