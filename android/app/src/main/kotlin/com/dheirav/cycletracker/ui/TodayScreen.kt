package com.dheirav.cycletracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheirav.cycletracker.core.LengthSource
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PredictionAccuracy
import com.dheirav.cycletracker.core.PredictionBasis
import com.dheirav.cycletracker.core.WindowBasis
import com.dheirav.cycletracker.reminder.ReminderScheduler
import com.dheirav.cycletracker.ui.theme.Cloud
import com.dheirav.cycletracker.ui.theme.Heart
import com.dheirav.cycletracker.ui.theme.MascotCloud
import com.dheirav.cycletracker.ui.theme.MascotMood
import com.dheirav.cycletracker.ui.theme.ScallopedBottomShape
import com.dheirav.cycletracker.ui.theme.Sparkle
import com.dheirav.cycletracker.ui.theme.cycleColors
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val dayMonth = DateTimeFormatter.ofPattern("d MMM")
private val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * The home screen: where you are in the cycle, when the next period is likely, and one button.
 *
 * Rebuilt around two constraints pulling in opposite directions. The design brief asked for neat
 * over busy — the one note repeated across every reference — while rule 3 demands the app show its
 * working rather than assert numbers. Those reconcile by putting the *claims* on the surface and
 * the *evidence* one tap away, rather than by dropping either.
 *
 * What that changed, concretely:
 *  - The bare "Phase confidence 25%" bar is gone. It was never measured — with too few observed
 *    cycles the engine falls back to a fixed 0.5 regularity — so it dressed an assumption as a
 *    reading. What replaces it is a count of the cycles behind the estimate, which is a fact.
 *  - The next period is a range, not a date. A single day printed above "not enough observed
 *    cycles" was a contradiction the user could see.
 *  - Settings moved out. App lock and backup are things you touch twice a year; they were taking
 *    up a third of the screen you look at daily.
 */
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onLog: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onPhaseGuide: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    if (ui.loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val state = ui.state
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(onSettings = onSettings)

        if (state == null || !state.hasData) {
            EmptyState(onLog = onLog, onHistory = onHistory)
            return@Column
        }

        if (ui.reminderBroken || ui.batteryRestricted) {
            ReminderWarning(broken = ui.reminderBroken)
        }

        CycleHero(
            cycleDay = state.cycleDay ?: 0,
            expectedLength = state.expectedCycleLength,
            phase = state.phase,
            isBleeding = state.isBleeding,
            daysLate = state.daysLate,
            onClick = onPhaseGuide,
        )

        ui.window?.let { NextPeriodCard(it) }

        ui.flags.forEach { HealthFlagCard(it) }

        Button(
            onClick = onLog,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp),
        ) {
            Text("Log today", style = MaterialTheme.typography.titleMedium)
        }

        TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Text("History")
        }

        WhyCard(basis = ui.basis, accuracy = ui.accuracy, state = state)
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Today", style = MaterialTheme.typography.headlineMedium)
            Text(
                java.time.LocalDate.now().format(fullDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The header sparkles moved to the hero, where the mascot now anchors them. Two decorated
        // areas stacked was the start of the clutter the brief warned about.
        TextButton(onClick = onSettings) { Text("Settings") }
    }
}

/**
 * The one thing worth reading from across the room, and the only decorated surface in the app.
 *
 * All the ornament lives here on purpose. Every reference that read as "neat" concentrated its
 * decoration in a hero and left the content below plain; the one criticised as cluttered spread
 * it everywhere. So: gradient, scalloped edge, clouds and sparkles here — and nothing below.
 *
 * §5.2 — an observed bleed beats any computed phase, so bleeding takes the menstruation palette
 * whatever the arithmetic says.
 *
 * **No character or face here, deliberately.** A smiling mascot reacting to your cycle is charming
 * on a good day and grating on a painful one, and this card is the thing you see first on both.
 * The ornament stays abstract — clouds do not have opinions about your body.
 */
@Composable
private fun CycleHero(
    cycleDay: Int,
    expectedLength: Int,
    phase: Phase?,
    isBleeding: Boolean,
    daysLate: Int,
    onClick: () -> Unit,
) {
    val cycle = MaterialTheme.cycleColors
    val effectivePhase = if (isBleeding) Phase.MENSTRUATION else phase
    val (top, bottom) = effectivePhase?.let { cycle.phase[it] }
        ?: (MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primaryContainer)
    val ink = cycle.onPhase

    // Ornament derives from the text colour, so it stays legible on all four gradients in both
    // schemes. Hardcoded white worked on pale pastels and turned to grey smudges on dark ones.
    val ornament = ink.copy(alpha = 0.26f)

    // The bleeding phase gets the sleepiest face, not the happiest. See MascotMood.
    val mood = when (effectivePhase) {
        Phase.MENSTRUATION -> MascotMood.SLEEPY
        Phase.OVULATION -> MascotMood.BRIGHT
        else -> MascotMood.CALM
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ScallopedBottomShape(bumps = 9, topRadius = 30.dp))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            // The card already names the phase, which makes it the obvious place to ask what the
            // phase means — better than another button competing with "Log today".
            //
            // The label matters: without it a screen reader announces the card's contents and
            // gives no hint that tapping does anything at all.
            .clickable(onClickLabel = "Read about this phase", onClick = onClick),
    ) {
        Cloud(
            color = ornament.copy(alpha = 0.18f),
            width = 44.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-96).dp, y = 20.dp),
        )
        Sparkle(
            color = ink.copy(alpha = 0.5f),
            size = 15.dp,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 150.dp, y = 20.dp),
        )
        Sparkle(
            color = ink.copy(alpha = 0.32f),
            size = 9.dp,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 176.dp, y = 44.dp),
        )
        Heart(
            color = ink.copy(alpha = 0.30f),
            size = 12.dp,
            modifier = Modifier.align(Alignment.BottomStart).offset(x = 26.dp, y = (-52).dp),
        )
        Heart(
            color = ink.copy(alpha = 0.18f),
            size = 8.dp,
            modifier = Modifier.align(Alignment.BottomStart).offset(x = 46.dp, y = (-66).dp),
        )

        // The mascot. Sits clear of the text column and carries no information of its own.
        MascotCloud(
            body = ink.copy(alpha = 0.92f),
            face = bottom,
            blush = cycle.bleeding.copy(alpha = 0.45f),
            mood = mood,
            width = 84.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-18).dp, y = 30.dp),
        )

        Column(
            // Extra bottom padding clears the scallop, which eats into the lower edge.
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "DAY $cycleDay",
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = 0.7f),
            )
            Text(
                if (isBleeding) "Period" else {
                    phase?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Unknown"
                },
                style = MaterialTheme.typography.displaySmall,
                color = ink,
            )
            Text(
                "of a $expectedLength-day cycle",
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.78f),
            )
            Text(
                "What this phase is like →",
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 8.dp),
            )
            if (daysLate > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "$daysLate day${if (daysLate == 1) "" else "s"} later than expected",
                    style = MaterialTheme.typography.labelLarge,
                    color = ink,
                )
            }
        }
    }
}

/**
 * The next period as a range.
 *
 * An `ASSUMED` window says so in as many words. Presenting a default spread as though it were
 * measured from this user is the exact dishonesty [PeriodWindow.basis] exists to prevent, and it
 * would be invisible to anyone who did not already know how the app works.
 */
@Composable
private fun NextPeriodCard(window: PeriodWindow) {
    val cycle = MaterialTheme.cycleColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = cycle.predicted.copy(alpha = 0.18f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "NEXT PERIOD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${window.earliest.format(dayMonth)} – ${window.latest.format(dayMonth)}",
                style = MaterialTheme.typography.headlineSmall,
                // "21 Aug – 29 Aug" reads as two dates and a dash out loud.
                modifier = Modifier.semantics {
                    contentDescription = "Expected between ${window.earliest.format(dayMonth)} " +
                        "and ${window.latest.format(dayMonth)}"
                },
            )
            Text(
                when (window.basis) {
                    WindowBasis.MEASURED ->
                        "A ${window.spanDays}-day window, from how much your own cycles have varied " +
                            "across ${window.observedCycles} observed."
                    WindowBasis.ASSUMED ->
                        "A ${window.spanDays}-day window based on a typical spread — not yours yet. " +
                            "It narrows once three cycles have been observed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A pattern worth noticing.
 *
 * Rendered in the tertiary container rather than the error one, deliberately. These are
 * observations, not alarms — a late period or a long cycle is usually nothing, and painting them
 * red would make the app frightening to open. The detail line carries the actual numbers so the
 * user has something concrete to take to a doctor rather than a colour and a verdict.
 */
@Composable
private fun HealthFlagCard(flag: com.dheirav.cycletracker.core.HealthFlag) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                flag.headline,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                flag.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * The receipts, collapsed by default.
 *
 * Collapsed because of the brief, present because of rule 3: a number nobody can interrogate is
 * indistinguishable from one that was made up. The count that matters most is observed versus
 * estimated — the seeded database has twelve completed cycles behind its 28-day figure and one of
 * them was ever observed, and "from 12 cycles" alone would be true and thoroughly misleading.
 */
@Composable
private fun WhyCard(basis: PredictionBasis?, accuracy: PredictionAccuracy?, state: com.dheirav.cycletracker.core.CycleState) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Why these numbers?", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics {
                        contentDescription = if (expanded) {
                            "Hide the working behind these numbers"
                        } else {
                            "Show the working behind these numbers"
                        }
                    },
                ) { Text(if (expanded) "Hide" else "Show") }
            }

            // The honest headline, visible without expanding: measured accuracy if it exists,
            // and a plain statement of its absence if it does not.
            Text(
                accuracy?.let {
                    "Predictions have been ${it.meanAbsoluteError.roundToInt()} day" +
                        "${if (it.meanAbsoluteError.roundToInt() == 1) "" else "s"} out on average " +
                        "across ${it.sampleSize} scored cycles."
                } ?: "Accuracy is not known yet — it needs three cycles that were predicted in " +
                    "advance and then observed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    basis?.let {
                        Detail(
                            "Cycle length",
                            "${it.expectedCycleLength} days, " + when (it.source) {
                                LengthSource.MEDIAN_OF_OBSERVED ->
                                    "median of ${it.observedCycles} you logged"
                                LengthSource.USER_STATED -> "as you stated"
                                // Named for what it is. This branch means the figure is largely
                                // the app repeating an assumption backfill made earlier.
                                LengthSource.MEDIAN_WITH_ESTIMATES -> "mostly from estimates"
                                LengthSource.APP_DEFAULT -> "app default"
                            },
                        )
                        Detail("Cycles observed", it.observedCycles.toString())
                        Detail(
                            "Cycles estimated",
                            it.assumedCycles.toString() +
                                if (it.mostlyAssumed) "  ← mostly guesswork" else "",
                        )
                        Detail(
                            "Your variability",
                            it.variability?.let { v -> "±%.1f days".format(v) }
                                ?: "not measurable yet",
                        )
                    }
                    Detail("Cycle started", state.cycleStart?.format(fullDate) ?: "—")
                    Detail("Ovulation day", state.ovulationDay?.toString() ?: "—")
                    accuracy?.let {
                        Detail(
                            "Typical miss",
                            if (it.bias.roundToInt() == 0) {
                                "no consistent direction"
                            } else if (it.bias > 0) {
                                "${abs(it.bias).roundToInt()} days late"
                            } else {
                                "${abs(it.bias).roundToInt()} days early"
                            },
                        )
                        Detail("Within 2 days", "${(it.hitRate * 100).roundToInt()}% of the time")
                    }
                    Text(
                        "Estimated cycles come from backfill counting backwards, not from anything " +
                            "you logged. Correct them in History.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onLog: () -> Unit, onHistory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // §6 — no periods logged means no cycle day. Never invent day 14.
        Text("No periods logged yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Log a period to begin. Predictions stay hidden until there is something real to " +
                "predict from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onLog,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
        ) {
            Text("Log today", style = MaterialTheme.typography.titleMedium)
        }
        // Reachable with no data on purpose — an empty database is exactly when someone wants to
        // enter the periods they already remember.
        TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Add past periods")
        }
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (broken) "Your daily reminder has stopped firing" else "Reminders may be killed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "This phone restricts background apps. Allow unrestricted battery use, and enable " +
                    "Autostart for this app in the system settings — the reminder is what keeps " +
                    "the habit going.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(ReminderScheduler.batterySettingsIntent()) }
            }) { Text("Open battery settings") }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
