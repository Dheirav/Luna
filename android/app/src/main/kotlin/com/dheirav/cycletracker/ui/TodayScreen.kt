package com.dheirav.cycletracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.OutlinedCard
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
import com.dheirav.cycletracker.core.HealthFlagKind
import com.dheirav.cycletracker.core.LengthSource
import com.dheirav.cycletracker.core.MoodFace
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
 * How the hero names the cycle length, saying where the number came from.
 *
 * "of a 28-day cycle" read the same whether 28 was the median of cycles the user logged, a figure
 * they typed in, or a population default describing nobody. The window card below already qualified
 * its own number, so the most prominent figure on the screen was the least earned one.
 */
fun cycleLengthPhrase(length: Int, source: LengthSource?): String = when (source) {
    LengthSource.USER_STATED -> "of a $length-day cycle, from your setting"
    LengthSource.MEDIAN_WITH_ESTIMATES, LengthSource.APP_DEFAULT -> "of a cycle assumed to be $length days"
    LengthSource.MEDIAN_OF_OBSERVED, null -> "of a $length-day cycle"
}

/**
 * "Were you bleeding on Sat 4 and Sun 5 Oct?" Each day by weekday and date, the month written once
 * unless the gap crosses a month end. Weekdays are kept so the person can place the days in their
 * week, which is how they will remember them.
 */
fun gapQuestion(days: List<java.time.LocalDate>): String {
    val dayOnly = DateTimeFormatter.ofPattern("EEE d")
    val withMonth = DateTimeFormatter.ofPattern("EEE d MMM")
    val oneMonth = days.map { it.month }.distinct().size == 1
    val names = days.mapIndexed { i, day ->
        if (!oneMonth || i == days.lastIndex) day.format(withMonth) else day.format(dayOnly)
    }
    val joined = if (names.size == 1) names.single()
    else names.dropLast(1).joinToString(", ") + " and " + names.last()
    return "Were you bleeding on $joined?"
}

/**
 * Asks about days left unlogged in the middle of a period.
 *
 * Two or three forgotten days split a period under CYCLE_RULES §2.1, so a one-day period appears
 * followed by "spotting". Asking costs one tap and keeps the rule honest, where guessing either way
 * would put a claim in the record the person never made. Either answer writes those days, so the
 * question does not come back.
 */
@Composable
private fun GapQuestionCard(question: String, onYes: () -> Unit, onNo: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                question,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "These days were not logged, so the period around them looks split in two.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onYes) { Text("Yes, bleeding") }
                TextButton(onClick = onNo) { Text("No") }
            }
        }
    }
}

/**
 * How long after the window closes the hero suggests raising a late period with a doctor.
 *
 * Guidance, not a measurement: the app has no basis for its own threshold here, and six weeks is
 * the point the council review proposed, well short of the 90-day absent flag. It is phrased as
 * "worth raising", never as a finding.
 */
private const val DOCTOR_AFTER_WEEKS = 6L

/** The mascot's face from today's logged mood. See [MascotMood] for why nothing else moves it. */
fun mascotMoodFor(today: MoodFace?): MascotMood = when (today) {
    MoodFace.SETTLED -> MascotMood.CALM
    MoodFace.HEAVY -> MascotMood.TENDER
    MoodFace.STEADY, MoodFace.UNKNOWN, null -> MascotMood.RESTING
}

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
        Header(today = ui.today, onSettings = onSettings)

        if (state == null || !state.hasData) {
            EmptyState(onLog = onLog, onHistory = onHistory)
            return@Column
        }

        CycleHero(
            cycleDay = state.cycleDay ?: 0,
            lengthPhrase = cycleLengthPhrase(state.expectedCycleLength, ui.basis?.source),
            phase = state.phase,
            isBleeding = state.isBleeding,
            daysPast = ui.window?.daysPast(ui.today) ?: 0,
            doctorBy = ui.window?.latest?.plusWeeks(DOCTOR_AFTER_WEEKS),
            mood = mascotMoodFor(ui.todayMood),
            onClick = onPhaseGuide,
        )

        // The daily action sits directly under the hero, ahead of every card that can appear. Cards
        // used to come first, and on the Redmi Note 15 Pro a reminder warning plus one health flag
        // pushed "Log today" below the fold, so the one thing this screen is for needed a scroll.
        LogTodayButton(logged = ui.loggedToday, onClick = onLog)

        TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Text("History")
        }

        // A question only the person can answer, so it sits with the actions rather than below the
        // forecast it changes.
        ui.gap?.let { gap ->
            GapQuestionCard(
                question = gapQuestion(gap.days),
                onYes = { viewModel.answerGap(gap, bleeding = true) },
                onNo = { viewModel.answerGap(gap, bleeding = false) },
            )
        }

        // A reminder that has stopped is a fault with something to do about it, so it comes before
        // the forecast. A reminder that merely might be stopped is a risk, and gets one quiet line
        // at the bottom instead of a card on every launch.
        if (ui.reminderBroken) ReminderStopped()

        ui.window?.let { NextPeriodCard(it, today = ui.today) }

        // The late-period flag is not repeated as a card here. The hero already says how late, and
        // the card's opening line restated the hero's day and cycle length; its reassurance moved
        // into the hero instead. The flag itself is unchanged and still reaches the doctor summary.
        ui.flags.filter { it.kind != HealthFlagKind.PERIOD_LATE }.forEach { HealthFlagCard(it) }

                WhyCard(basis = ui.basis, accuracy = ui.accuracy, state = state)

        if (!ui.reminderBroken && ui.batteryRestricted) ReminderAtRisk()
    }
}

@Composable
private fun Header(today: java.time.LocalDate, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Today", style = MaterialTheme.typography.headlineMedium)
            Text(
                today.format(fullDate),
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
 * The mascot's face comes from mood logged today, never from the phase (see [MascotMood]). It used
 * to follow the phase, so it smiled on day 41 of a 28-day cycle; a face reacting to a calendar is
 * the app deciding how someone feels. With nothing logged it rests, without a smile.
 */
@Composable
private fun CycleHero(
    cycleDay: Int,
    lengthPhrase: String,
    phase: Phase?,
    isBleeding: Boolean,
    daysPast: Int,
    doctorBy: java.time.LocalDate?,
    mood: MascotMood,
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
        // Hearts belong in the right-hand margin, under the mascot — **not** anchored to
        // BottomStart, which is where they were and which put them straight through
        // "of a 28-day cycle" and "What this phase is like →" on the Redmi at 440dpi.
        //
        // The rule this card follows: ornament anchors to the edge the text does not occupy. The
        // text column is left-aligned and its longest line is short, so the right side is safe while
        // the bottom-left is exactly where the text ends up — and it moves further into that corner
        // at larger font scales, which is what made the collision certain rather than unlucky.
        Heart(
            color = ink.copy(alpha = 0.30f),
            size = 12.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-52).dp, y = 106.dp),
        )
        Heart(
            color = ink.copy(alpha = 0.18f),
            size = 8.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-30).dp, y = 122.dp),
        )

        // The mascot. Sits clear of the text column and carries no information of its own.
        //
        // Its colours are stated in the theme rather than taken from `ink` and `bottom` here. Those
        // made it invert with the text, which is right in dark mode and wrong in light — see
        // CycleColors.mascotBody.
        MascotCloud(
            body = cycle.mascotBody,
            face = cycle.mascotFace,
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
                // Clears the mascot in the top-right corner. Without it, "Menstruation" at large text
                // ran underneath the cloud.
                modifier = Modifier.padding(end = 96.dp),
            )
            Text(
                lengthPhrase,
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.78f),
            )
            Text(
                "What this phase is like →",
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 8.dp),
            )
            // Late means past the whole window, counted from its last day. It used to count from the
            // centre date, so the hero said "2 days later than expected" while the card beneath still
            // showed the window open.
            if (daysPast > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "$daysPast day${if (daysPast == 1) "" else "s"} past the expected window",
                    style = MaterialTheme.typography.labelLarge,
                    color = ink,
                )
                // What the late-period card used to add, without its repeat of the numbers above,
                // and a point at which to act, which nothing gave before the 90-day absent flag.
                Text(
                    "Stress, illness, travel and sleep all shift this. One late cycle is common." +
                        (doctorBy?.let { " Worth raising with a doctor if it has not come by ${it.format(dayMonth)}." } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.78f),
                    modifier = Modifier.padding(end = 96.dp),
                )
            }
        }
    }
}

/**
 * The next period as a range, or, once that range is behind us, the range that was expected.
 *
 * An `ASSUMED` window says so in as many words. Presenting a default spread as though it were
 * measured from this user is the exact dishonesty [PeriodWindow.basis] exists to prevent, and it
 * would be invisible to anyone who did not already know how the app works.
 *
 * A passed window changes its label rather than its dates. It used to stay headed "Next period" while
 * the hero above said the period was twelve days late, so the card named dates the app already knew
 * were wrong. How late is the hero's job, and is not repeated here as a second, different count.
 */
@Composable
private fun NextPeriodCard(window: PeriodWindow, today: java.time.LocalDate) {
    val cycle = MaterialTheme.cycleColors
    val passed = window.hasPassed(today)
    val range = "${window.earliest.format(dayMonth)} – ${window.latest.format(dayMonth)}"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = cycle.predicted.copy(alpha = if (passed) 0.08f else 0.18f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (passed) "WAS EXPECTED" else "NEXT PERIOD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                range,
                style = MaterialTheme.typography.headlineSmall,
                color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                // A bare "1 Jan – 9 Jan" reads as two dates and a dash out loud.
                modifier = Modifier.semantics {
                    contentDescription = (if (passed) "Was expected between " else "Expected between ") +
                        "${window.earliest.format(dayMonth)} and ${window.latest.format(dayMonth)}"
                },
            )
            Text(
                when {
                    passed ->
                        "That window has passed. The next prediction starts from the period you log next."
                    window.basis == WindowBasis.MEASURED ->
                        "A ${window.spanDays}-day window, from how much your own cycles have varied " +
                            "across ${window.observedCycles} observed."
                    else ->
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
 * The day's one job, in its two states.
 *
 * It read "Log today" whether or not today had been logged, so the screen never answered the
 * question it is opened to answer, and a finished entry left no trace on it. Once logged it becomes
 * a record of what was saved, still one tap from the same form: the work is visibly done, which is
 * the cheapest reward logging can get, and "Edit" makes clear a second tap changes this entry rather
 * than adding another.
 *
 * Filled while there is something to do, tonal once there is not, so the screen's loudest element is
 * always the thing still undone.
 */
@Composable
private fun LogTodayButton(logged: String?, onClick: () -> Unit) {
    if (logged == null) {
        Button(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp),
        ) {
            Text("Log today", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .semantics { contentDescription = "Today's log: $logged. Edit." },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "LOGGED TODAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    logged,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                "Edit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp),
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
                                LengthSource.MEDIAN_WITH_ESTIMATES -> "mostly estimated"
                                LengthSource.APP_DEFAULT -> "app default"
                            },
                        )
                        Detail("Cycles observed", it.observedCycles.toString())
                        Detail(
                            "Cycles estimated",
                            it.assumedCycles.toString() +
                                if (it.mostlyAssumed) "  ← most of them" else "",
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
                    // The one place the vocabulary is defined.
                    //
                    // "Observed" and "estimated" appear on six surfaces — this panel, the window card,
                    // the calendar's cells and month list, the log form's banner, and the doctor
                    // summary. Each used to explain the distinction in its own words, which meant
                    // learning it six times. It is explained here, in the panel whose whole job is the
                    // working behind the numbers, and everywhere else is a label using those two words.
                    // If the wording changes, it changes here and nowhere else.
                    Text(
                        "Estimated cycles were worked out by counting backwards, not from anything " +
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
 * The reminder was due and did not run.
 *
 * Vivo, Oppo, Xiaomi and Samsung all terminate background work on their own schedule, and none
 * of it can be fixed programmatically — Autostart and background-power allowances live in vendor
 * settings screens with no public API. The honest response is to detect that the reminder stopped
 * firing and say so, since a silently dead reminder ends the logging habit without warning.
 *
 * Deliberately not styled like a health flag. Both used the tertiary card, so a fault the user has to
 * fix looked exactly like an observation to mention to a doctor, and the one needing action did not
 * stand out. This one is outlined, with the title in the error colour, matching how Settings already
 * reports the same condition. Health flags stay out of red on purpose; this is about the app, not the
 * body.
 */
@Composable
private fun ReminderStopped() {
    val context = LocalContext.current
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Your daily reminder has stopped firing",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "This phone stopped it in the background. Allow unrestricted battery use, and enable " +
                    "Autostart for this app if the phone has it.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(ReminderScheduler.batterySettingsIntent()) }
            }) { Text("Open battery settings") }
        }
    }
}

/**
 * Battery use is restricted, so the reminder might be stopped, but nothing has failed yet.
 *
 * One line at the foot of the screen rather than a card at the top. It showed on every launch for as
 * long as the restriction stood, with no way to dismiss it, and at the top it was the reason the log
 * button fell below the fold. If the reminder actually stops, [ReminderStopped] takes over.
 */
@Composable
private fun ReminderAtRisk() {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Battery settings may stop the daily reminder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            runCatching { context.startActivity(ReminderScheduler.batterySettingsIntent()) }
        }) { Text("Fix") }
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
