package com.dheirav.cycletracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheirav.cycletracker.core.Guidance
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PhaseSymptomSummary
import com.dheirav.cycletracker.core.SymptomPatterns
import com.dheirav.cycletracker.ui.theme.Cloud
import com.dheirav.cycletracker.ui.theme.Heart
import com.dheirav.cycletracker.ui.theme.ScallopedBottomShape
import com.dheirav.cycletracker.ui.theme.Sparkle
import com.dheirav.cycletracker.ui.theme.cycleColors

/**
 * What tends to happen in a phase, and what this user's own logs say about it.
 *
 * The screen is built around one distinction, and everything else follows from it: **"Typically"
 * is population-level information that describes nobody, and "Yours" is measured from the logs.**
 * They are separate cards, separately labelled, and the typical section never borrows the
 * authority of the measured one. Every cycle app that tells you how you feel today collapses
 * exactly this distinction.
 *
 * The "Yours" card is empty for months — until five days in the phase carry symptoms — and it says
 * so plainly rather than hiding. That absence is the honest state, and it doubles as the only
 * visible reason to log symptoms at all, which is what Phase 4's correlation work will need.
 */
@Composable
fun PhaseGuideScreen(viewModel: GuideViewModel, initialPhase: Phase?) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(initialPhase) { viewModel.load(initialPhase) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GuideHero(phase = ui.phase, summary = ui.guidance.summary)

        Column(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PhasePicker(selected = ui.phase, onSelect = viewModel::load)

            YoursCard(
                summaries = ui.yours,
                loggedDays = ui.loggedDaysInPhase,
                phase = ui.phase,
            )

            Section(title = "Mood and energy, typically", body = ui.guidance.mood)

            TipsCard("Movement", ui.guidance.movement)
            TipsCard("Food", ui.guidance.nourishment)
            TipsCard("Looking after yourself", ui.guidance.selfCare)

            Text(
                Guidance.DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun GuideHero(phase: Phase, summary: String) {
    val cycle = MaterialTheme.cycleColors
    val (top, bottom) = cycle.phase.getValue(phase)
    val ink = cycle.onPhase

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ScallopedBottomShape(bumps = 9, topRadius = 0.dp))
            .background(Brush.verticalGradient(listOf(top, bottom))),
    ) {
        Cloud(
            color = ink.copy(alpha = 0.16f),
            width = 52.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-20).dp, y = 24.dp),
        )
        Sparkle(
            color = ink.copy(alpha = 0.4f),
            size = 13.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-84).dp, y = 40.dp),
        )
        Heart(
            color = ink.copy(alpha = 0.22f),
            size = 11.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-30).dp, y = 68.dp),
        )

        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 34.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                phase.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.headlineMedium,
                color = ink,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Reading ahead is the point — knowing luteal is coming is more useful than being told you are in
 * it.
 *
 * The row scrolls rather than truncating. An earlier version clipped each label to five characters
 * to fit four chips across, which rendered "Menst", "Folli", "Ovula" and "Lutea" — unreadable on
 * screen and, spoken by a screen reader, meaningless. Scrolling also survives a large font scale,
 * which a fixed four-across row does not.
 */
@Composable
private fun PhasePicker(selected: Phase, onSelect: (Phase) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Phase.entries.forEach { phase ->
            val name = phase.name.lowercase().replaceFirstChar { it.uppercase() }
            FilterChip(
                selected = phase == selected,
                onClick = { onSelect(phase) },
                label = { Text(name, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/**
 * The measured half.
 *
 * Wording matters more here than anywhere else on the screen. It reports what was *logged* — it
 * never says the phase caused anything. Establishing that is Phase 4's correlation work and needs
 * far more data than five days; "your energy averaged Low across the 8 luteal days you logged" is
 * a fact, while "luteal makes you tired" is a claim this cannot support.
 */
@Composable
private fun YoursCard(summaries: List<PhaseSymptomSummary>, loggedDays: Int, phase: Phase) {
    val cycle = MaterialTheme.cycleColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Yours",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )

            if (summaries.isEmpty()) {
                Text(
                    "Nothing measured yet. Once you have logged symptoms on " +
                        "${SymptomPatterns.MIN_DAYS_IN_PHASE} days in this phase, what you " +
                        "actually recorded appears here instead of the general notes below." +
                        if (loggedDays > 0) " You are at $loggedDays." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            summaries.forEach { summary ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        // Colour is the only thing this dot carries, and the line beside it
                        // already says "worse than your other phases" in words.
                        modifier = Modifier
                            .clearAndSetSemantics { }
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when (summary.worseHere) {
                                    true -> cycle.bleeding
                                    false -> cycle.logged
                                    null -> MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            "${summary.symptom.label}: ${summary.label()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            buildString {
                                append("across ${summary.daysObserved} days you logged in ")
                                append(phase.name.lowercase())
                                when (summary.worseHere) {
                                    true -> append(" — worse than your other phases")
                                    false -> append(" — better than your other phases")
                                    null -> append("")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                "A summary of what you logged, not a claim about cause. Plenty of things other " +
                    "than your cycle move these.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun TipsCard(title: String, items: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            items.forEach { item ->
                Row {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}
