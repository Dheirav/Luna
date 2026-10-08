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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import java.time.temporal.ChronoUnit
import com.dheirav.cycletracker.ui.theme.SparkleCluster
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheirav.cycletracker.core.HealthFlagKind
import com.dheirav.cycletracker.core.LateGuidance
import com.dheirav.cycletracker.core.LengthSource
import com.dheirav.cycletracker.core.MoodFace
import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.core.Phase
import com.dheirav.cycletracker.core.PredictionAccuracy
import com.dheirav.cycletracker.core.PredictionBasis
import com.dheirav.cycletracker.core.WindowBasis
import com.dheirav.cycletracker.reminder.ReminderScheduler
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
 * The hero's point at which to see a doctor, from the secondary amenorrhea definition (see
 * [LateGuidance]): three months since the last period began, or six when cycles have varied widely.
 * It replaced "window end plus six weeks", which was a number this app made up.
 */
fun doctorLine(point: LateGuidance.DoctorPoint): String =
    " Worth raising with a doctor if it has not come by ${point.date.format(dayMonth)}, " +
        if (point.irregular) "six months since your last period began, as your cycles vary." else "three months since your last period began."

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
    /** Opens the log form on each of these days in turn, oldest first. */
    onCatchUp: (List<java.time.LocalDate>) -> Unit = {},
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onPhaseGuide: () -> Unit,
    /** Answers the bleeding question for a day, with Undo. */
    onAnswerBleeding: (java.time.LocalDate, Boolean) -> Unit = { _, _ -> },
    /** Marks a run of days as a period, with Undo. */
    onMarkPeriod: (java.time.LocalDate, java.time.LocalDate) -> Unit = { _, _ -> },
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

        // Each tour target is tagged where it is drawn; the tag does nothing unless a tour is running.
        val silentSince = ui.silentSince
        if (silentSince != null) {
            Box(Modifier.tourTarget(TourTarget.HERO)) {
                SilentHero(lastLogged = silentSince, window = ui.window, mood = mascotMoodFor(ui.todayMood))
            }
        } else Box(Modifier.tourTarget(TourTarget.HERO)) {
            CycleHero(
                cycleDay = state.cycleDay ?: 0,
                lengthPhrase = cycleLengthPhrase(state.expectedCycleLength, ui.basis?.source),
                phase = state.phase,
                isBleeding = state.isBleeding,
                // Said only once the whole window has passed, so it never contradicts the card below;
                // then counted from the expected date, the way clinicians count (LateGuidance).
                daysPast = state.cycleStart
                    ?.takeIf { ui.window?.hasPassed(ui.today) == true }
                    ?.let { LateGuidance.daysPastExpected(it, state.expectedCycleLength, ui.today) } ?: 0,
                doctorPoint = state.cycleStart?.let { LateGuidance.doctorPoint(ui.projection, it) },
                mood = mascotMoodFor(ui.todayMood),
                ring = state.cycleStart?.let { start ->
                    // The window as cycle days, and only while it is still ahead or open.
                    val window = ui.window?.takeUnless { it.hasPassed(ui.today) }
                    ringGeometry(
                        cycleDay = state.cycleDay ?: 1,
                        expectedLength = state.expectedCycleLength,
                        periodLength = state.periodLength,
                        // Days of this period the user actually logged; the rest of periodLength is
                        // the expected length standing in until the period is answered as over.
                        loggedPeriodDays = ui.projection.periods.lastOrNull { it.start == start }
                            ?.let { if (it.whollyObserved) it.spanDays else it.loggedDayCount } ?: 0,
                        ovulationDay = state.ovulationDay,
                        windowStartDay = window?.let { ChronoUnit.DAYS.between(start, it.earliest).toInt() + 1 },
                        windowEndDay = window?.let { ChronoUnit.DAYS.between(start, it.latest).toInt() + 1 },
                    )
                },
                onClick = onPhaseGuide,
            )
        }

        // The daily action sits directly under the hero, ahead of every card that can appear. Cards
        // used to come first, and on the Redmi Note 15 Pro a reminder warning plus one health flag
        // pushed "Log today" below the fold, so the one thing this screen is for needed a scroll.
        // Side by side, the day's action taking two thirds and History one: they are the screen's two
        // ways out, and as a full-width text button History read as a footnote under the log rather
        // than a destination. Stacked again when large text would squeeze "History" into a third.
        if (LocalDensity.current.fontScale > 1.3f) {
            Box(Modifier.tourTarget(TourTarget.LOG_BUTTON)) {
                LogTodayButton(logged = ui.loggedToday, onClick = onLog)
            }
            OutlinedButton(
                onClick = onHistory,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tourTarget(TourTarget.HISTORY_BUTTON),
            ) { Text("History") }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(2f).fillMaxHeight().tourTarget(TourTarget.LOG_BUTTON)) {
                    LogTodayButton(logged = ui.loggedToday, onClick = onLog, modifier = Modifier.fillMaxHeight())
                }
                OutlinedButton(
                    onClick = onHistory,
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 58.dp)
                        .tourTarget(TourTarget.HISTORY_BUTTON),
                ) { Text("History", style = MaterialTheme.typography.titleSmall) }
            }
        }

        // The period question, when there is one. Directly under the day's action, because it is the
        // same kind of thing: something to answer, in one tap.
        PeriodPromptCard(
            prompt = ui.periodPrompt,
            today = ui.today,
            onAnswerToday = { bleeding -> onAnswerBleeding(ui.today, bleeding) },
            onMarkPeriod = onMarkPeriod,
        )

        // Missed days, said once, with a way to fill them in one after another. Catching up used to be
        // a loop of open, step back, save, land on Today, repeat, with nothing saying days were missed
        // (council review F4).
        // Not while silent: the hero and the question above already say nothing has been logged.
        ui.unloggedLine?.takeIf { ui.unlogged.isNotEmpty() && silentSince == null }?.let { line ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // Says how many it will walk through when that is fewer than were missed.
                TextButton(onClick = { onCatchUp(ui.unlogged) }) {
                    Text(if (ui.unloggedRun > ui.unlogged.size) "Fill in the last ${ui.unlogged.size}" else "Fill in")
                }
            }
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

        ui.window?.let {
            Box(Modifier.tourTarget(TourTarget.WINDOW_CARD)) { NextPeriodCard(it, today = ui.today) }
        }
        // So the tour's caption for this card says what it shows: a forecast, or one that has passed.
        LocalTour.current?.let { tour ->
            val passed = ui.window?.hasPassed(ui.today) == true
            LaunchedEffect(passed) { tour.windowPassed = passed }
        }

        // After the forecast, not before it. A stopped reminder is a fault in the app, not news about
        // the body, and as a full card above the window it pushed the forecast down the screen on the
        // days it mattered most. It stays above the flags and the Why card, in the error colour, so it
        // is still seen; a reminder that merely might stop gets a quieter line at the foot.
        if (ui.reminderBroken) ReminderStopped(batteryRestricted = ui.batteryRestricted)

        // The late-period flag is not repeated as a card here. The hero already says how late, and
        // the card's opening line restated the hero's day and cycle length; its reassurance moved
        // into the hero instead. The flag itself is unchanged and still reaches the doctor summary.
        ui.flags.filter { it.kind != HealthFlagKind.PERIOD_LATE }.forEach { HealthFlagCard(it) }

        Box(Modifier.tourTarget(TourTarget.WHY_CARD)) {
            WhyCard(
                basis = ui.basis,
                accuracy = ui.accuracy,
                state = state,
                // The hero's phase, so the two cards always share a palette.
                phase = if (silentSince != null) null else if (state.isBleeding) Phase.MENSTRUATION else state.phase,
            )
        }

        if (!ui.reminderBroken && ui.batteryRestricted) ReminderAtRisk()

        ui.backupDueSince?.let { BackupNudge(lastBackup = it, onBackUp = onSettings) }
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
            Text("Today", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(
                today.format(fullDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The header sparkles moved to the hero, where the mascot now anchors them. Two decorated
        // areas stacked was the start of the clutter the brief warned about.
        TextButton(onClick = onSettings, modifier = Modifier.tourTarget(TourTarget.SETTINGS_BUTTON)) { Text("Settings") }
    }
}

/**
 * The hero when nothing has been logged since before the expected window opened.
 *
 * The cycle hero would say "Day 40, Luteal, 11 days past the expected date" here, and every part
 * of that assumes no period came in weeks nobody logged. So this says what is actually known: when
 * it was expected, and when anything was last logged. The question card below asks the rest. The
 * mascot stays, resting, because its face comes from today's log and there is none.
 */
@Composable
private fun SilentHero(lastLogged: java.time.LocalDate, window: PeriodWindow?, mood: MascotMood) {
    val cycle = MaterialTheme.cycleColors
    val scheme = MaterialTheme.colorScheme
    val ink = cycle.onPhase
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ScallopedBottomShape(bumps = 9, topRadius = 30.dp))
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer, lerp(scheme.primaryContainer, scheme.secondaryContainer, 0.6f)))),
    ) {
        Sparkle(
            color = ink.copy(alpha = 0.4f),
            size = 13.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 16.dp),
        )
        Row(
            modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "NOTHING LOGGED SINCE ${lastLogged.format(dayMonth).uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.85f),
                )
                ShrinkToFit("Welcome back", style = MaterialTheme.typography.displaySmall, color = ink)
                // The fact in four words every day; why, for the first few days and behind ⓘ after.
                val explain = rememberExplanation("silent")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Not counting days late",
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                        modifier = Modifier.weight(1f),
                    )
                    ExplainButton(explain, about = "why", tint = ink.copy(alpha = 0.85f))
                }
                if (explain.showText) {
                    Text(
                        (window?.let { "Your period was expected ${it.earliest.format(dayMonth)} to ${it.latest.format(dayMonth)}. " } ?: "") +
                            "With nothing logged since, Luna can't tell whether it came.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            MascotCloud(
                body = cycle.mascotBody,
                face = cycle.mascotFace,
                blush = cycle.bleeding.copy(alpha = 0.45f),
                mood = mood,
                width = 76.dp,
                shadow = cycle.mascotShadow,
            )
        }
    }
}

/**
 * The one thing worth reading from across the room, and the only decorated surface in the app.
 *
 * All the ornament lives here on purpose. Every reference that read as "neat" concentrated its
 * decoration in a hero and left the content below plain; the one criticised as cluttered spread
 * it everywhere. So: gradient, scalloped edge, the ring and sparkles here, and nothing below.
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
    doctorPoint: LateGuidance.DoctorPoint?,
    mood: MascotMood,
    ring: RingGeometry?,
    onClick: () -> Unit,
) {
    val cycle = MaterialTheme.cycleColors
    val effectivePhase = if (isBleeding) Phase.MENSTRUATION else phase
    val (top, bottom) = effectivePhase?.let { cycle.phase[it] }
        ?: (MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primaryContainer)
    val ink = cycle.onPhase

    // Ornament below derives from the text colour, so it stays legible on all four gradients in both
    // schemes. Hardcoded white worked on pale pastels and turned to grey smudges on dark ones.

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
        // Ornament anchors to the edges the text does not occupy: around the ring on the right, never
        // the bottom-left, where the text column ends up and where it grows at larger font scales.
        // The hearts once sat at BottomStart and ran straight through "of a 28-day cycle" on the Redmi.
        Sparkle(
            color = ink.copy(alpha = 0.5f),
            size = 14.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-12).dp, y = 14.dp),
        )
        Sparkle(
            color = ink.copy(alpha = 0.32f),
            size = 9.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-146).dp, y = 132.dp),
        )
        Heart(
            color = ink.copy(alpha = 0.30f),
            size = 11.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 150.dp),
        )
        Heart(
            color = ink.copy(alpha = 0.18f),
            size = 7.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-30).dp, y = 164.dp),
        )

        Column(
            // Extra bottom padding clears the scallop, which eats into the lower edge.
            modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
           Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                // Past the usual length the phase is the open-ended luteal by rule (§5), with its
                // confidence at the floor, and the biggest type on the screen stated it flat (device
                // review p4). A logged bleed is never hedged: it is observed.
                if (ring?.onSecondLap == true && !isBleeding) "DAY $cycleDay · MOST LIKELY" else "DAY $cycleDay",
                style = MaterialTheme.typography.labelSmall,
                // 0.85 at least, for every secondary line on the hero: at 0.70 to 0.78 the smaller
                // text measured 3.75 to 4.5:1 on the lighter gradients (council review A3).
                color = ink.copy(alpha = 0.85f),
            )
            // One line that shrinks rather than breaking "Follicular" mid-word beside the ring.
            ShrinkToFit(
                if (isBleeding) "Period" else {
                    phase?.let(::phaseName) ?: "Unknown"
                },
                style = MaterialTheme.typography.displaySmall,
                color = ink,
            )
            Text(
                lengthPhrase,
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.85f),
            )
            Text(
                "What this phase is like →",
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 8.dp),
            )
           }
            Spacer(Modifier.width(8.dp))
            // The mascot sits inside the ring. Its colours are stated in the theme rather than taken
            // from `ink` here, which made it invert with the text: right in dark mode, wrong in light
            // (see CycleColors.mascotBody). Its face comes from today's logged mood, never the phase.
            val mascot = @Composable { size: androidx.compose.ui.unit.Dp ->
                MascotCloud(
                    body = cycle.mascotBody,
                    face = cycle.mascotFace,
                    blush = cycle.bleeding.copy(alpha = 0.45f),
                    mood = mood,
                    width = size,
                    shadow = cycle.mascotShadow,
                )
            }
            if (ring != null) {
                CycleRing(
                    geometry = ring,
                    ink = effectivePhase?.let { cycle.phaseAccent[it] } ?: ink,
                    period = cycle.bleeding,
                    halo = lerp(top, bottom, 0.3f),
                ) { mascot(52.dp) }
            } else {
                mascot(84.dp)
            }
          }
            if (daysPast > 0) {
                // The count every day, the reassurance and the doctor point for the first few days
                // and behind ⓘ after. Except the doctor point once it is two weeks away or past:
                // that is the one line here whose cost of being missed is real.
                val explain = rememberExplanation("late")
                val doctorSoon = doctorPoint != null &&
                    !java.time.LocalDate.now().isBefore(doctorPoint.date.minusDays(14))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$daysPast day${if (daysPast == 1) "" else "s"} past the expected date",
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                        modifier = Modifier.weight(1f),
                    )
                    ExplainButton(explain, about = "the late period", tint = ink.copy(alpha = 0.85f))
                }
                when {
                    explain.showText -> Text(
                        "Stress, illness, travel and sleep all shift this. One late cycle is common." +
                            (doctorPoint?.let { doctorLine(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = 0.85f),
                    )
                    doctorSoon && doctorPoint != null -> Text(
                        doctorLine(doctorPoint).trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/**
 * One line that steps its size down until it fits, to 60% of the style at most, and only draws once
 * it does. Compose on this BOM has no auto-size text.
 */
@Composable
private fun ShrinkToFit(text: String, style: androidx.compose.ui.text.TextStyle, color: Color) {
    val full = style.fontSize.value
    var size by remember(text, full) { mutableStateOf(full) }
    var fits by remember(text, full) { mutableStateOf(false) }
    Text(
        text,
        style = style.copy(fontSize = size.sp),
        color = color,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && size > full * 0.6f) size -= 1f else fits = true
        },
        modifier = Modifier.drawWithContent { if (fits) drawContent() },
    )
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
            // The qualifier that changes what the dates mean stays on the label every day; the
            // explanation of the window goes behind ⓘ after the first few.
            val explain = rememberExplanation("window")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (passed) "WAS EXPECTED" else "NEXT PERIOD") +
                        if (!passed && window.basis == WindowBasis.ASSUMED) " · TYPICAL SPREAD" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ExplainButton(explain, about = "this window")
            }
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
            if (explain.showText) Text(
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
private fun LogTodayButton(logged: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (logged == null) {
        Button(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp),
        ) {
            Text("Log today", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    Card(
        onClick = onClick,
        modifier = modifier
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
private fun WhyCard(
    basis: PredictionBasis?,
    accuracy: PredictionAccuracy?,
    state: com.dheirav.cycletracker.core.CycleState,
    phase: Phase?,
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val cycle = MaterialTheme.cycleColors
    // The hero's own gradient at a whisper, so the two cards read as one page and change together
    // with the phase. It was a fixed lavender-to-rose wash, which matched the hero only on luteal
    // days and clashed with it on the green and gold ones. Blended towards the card colour rather
    // than used outright: the secondary text was chosen against cream, and on the full phase tints
    // it fell under 4.5:1.
    val (phaseTop, phaseBottom) = phase?.let { cycle.phase[it] } ?: (scheme.secondaryContainer to scheme.primaryContainer)
    val wash = Brush.linearGradient(
        listOf(lerp(scheme.surfaceVariant, phaseTop, 0.6f), lerp(scheme.surfaceVariant, phaseBottom, 0.6f)),
    )
    val accent = phase?.let { cycle.phaseAccent[it] } ?: scheme.primary
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(wash),
    ) {
        SparkleCluster(
            color = accent.copy(alpha = 0.45f),
            // Clear of the Show/Hide button's text, which its lowest dot touched (device review p2).
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 104.dp),
        )
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Why these numbers?", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics {
                        contentDescription = if (expanded) {
                            "Hide the working behind these numbers"
                        } else {
                            "Show the working behind these numbers"
                        }
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                ) {
                    Text(if (expanded) "Hide" else "Show")
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).rotate(chevron),
                    )
                }
            }

            basis?.let { EvidenceStrip(observed = it.observedCycles, estimated = it.assumedCycles, mostlyAssumed = it.mostlyAssumed) }

            // The honest headline, visible without expanding: measured accuracy if it exists,
            // and a plain statement of its absence if it does not.
            Text(
                accuracy?.let {
                    "Predictions ${it.meanAbsoluteError.roundToInt()} day" +
                        "${if (it.meanAbsoluteError.roundToInt() == 1) "" else "s"} out on average, " +
                        "over ${it.sampleSize} cycles"
                } ?: "Accuracy not known yet: needs 3 cycles predicted, then logged",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val tiles = buildList {
                        basis?.let {
                            add(
                                Tile(
                                    "Cycle length",
                                    "${it.expectedCycleLength} days",
                                    when (it.source) {
                                        LengthSource.MEDIAN_OF_OBSERVED -> "median of ${it.observedCycles} you logged"
                                        LengthSource.USER_STATED -> "as you stated"
                                        // Named for what it is. This branch means the figure is largely
                                        // the app repeating an assumption backfill made earlier.
                                        LengthSource.MEDIAN_WITH_ESTIMATES -> "mostly estimated"
                                        LengthSource.APP_DEFAULT -> "app default"
                                    },
                                ),
                            )
                            add(
                                it.variability?.let { v -> Tile("Your variability", "±%.1f days".format(v), "how much cycles differ") }
                                    ?: Tile("Your variability", "Not yet", "not measurable yet"),
                            )
                        }
                        add(Tile("Cycle started", state.cycleStart?.format(dayMonth) ?: "—", state.cycleStart?.year?.toString()))
                        // Worked out by counting back a 14-day luteal phase, which the app assumes and has
                        // not measured (CYCLE_RULES §5.1, §7). Shown as a bare number it read as a finding.
                        add(
                            Tile(
                                "Ovulation",
                                state.ovulationDay?.let { "Around day $it" } ?: "—",
                                state.ovulationDay?.let { "assuming a 14-day luteal phase" },
                            ),
                        )
                        accuracy?.let {
                            add(
                                Tile(
                                    "Typical miss",
                                    if (it.bias.roundToInt() == 0) {
                                        "No pattern"
                                    } else if (it.bias > 0) {
                                        "${abs(it.bias).roundToInt()} days late"
                                    } else {
                                        "${abs(it.bias).roundToInt()} days early"
                                    },
                                    if (it.bias.roundToInt() == 0) "no consistent direction" else "on average",
                                ),
                            )
                            add(Tile("Within 2 days", "${(it.hitRate * 100).roundToInt()}%", "of the time"))
                        }
                    }
                    // Two to a row while they fit; one per row at large text, where a half-width tile
                    // would break "Around day 14" mid-phrase.
                    val perRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
                    tiles.chunked(perRow).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            row.forEach { WhyTile(it, Modifier.weight(1f).fillMaxHeight()) }
                            if (row.size < perRow) Spacer(Modifier.weight(1f))
                        }
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
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One mark per cycle the estimate rests on: solid for each one logged, a dashed ring for each one
 * estimated, the same pair the calendar uses for observed and estimated days.
 *
 * The split was two lines of a table, "Cycles observed 1" over "Cycles estimated 11", which is the
 * most important fact on the card set in the smallest type on it. Drawn, it is visible at a glance
 * that a 28-day figure stands on one real cycle and eleven guesses, and as logging continues the
 * row fills in solid. Capped at twelve marks, with the count in words beside it either way, so a
 * long history does not wrap and the number never depends on counting dots.
 */
@Composable
private fun EvidenceStrip(observed: Int, estimated: Int, mostlyAssumed: Boolean) {
    if (observed + estimated == 0) return
    val cycle = MaterialTheme.cycleColors
    val shownObserved = observed.coerceAtMost(12)
    val shownEstimated = estimated.coerceAtMost(12 - shownObserved)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(shownObserved) { CyclePip(estimated = false, color = cycle.bleeding) }
            repeat(shownEstimated) { CyclePip(estimated = true, color = cycle.estimated) }
        }
        // Says which cycles it counts. Unqualified, "3 logged · 3 estimated" sat against the doctor
        // summary's "13 recorded, 3 observed, 10 estimated", and one of them looked wrong (device
        // review M4). Both are right: this is the sample the prediction actually uses.
        Text(
            "From your last ${observed + estimated} cycles: $observed logged, $estimated estimated" +
                if (mostlyAssumed) ". Mostly estimated so far." else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CyclePip(estimated: Boolean, color: Color) {
    Canvas(Modifier.size(14.dp).clearAndSetSemantics { }) {
        if (estimated) {
            val stroke = 1.5.dp.toPx()
            val dash = 2.dp.toPx()
            drawCircle(
                color = color,
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f)),
            )
        } else {
            drawCircle(color = color)
        }
    }
}

private data class Tile(val label: String, val value: String, val note: String?)

/** A figure from the working, large enough to read as a figure. Was a two-column table row. */
@Composable
private fun WhyTile(tile: Tile, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            tile.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(tile.value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        tile.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).tourTarget(TourTarget.LOG_BUTTON),
        ) {
            Text("Log today", style = MaterialTheme.typography.titleMedium)
        }
        // Reachable with no data on purpose — an empty database is exactly when someone wants to
        // enter the periods they already remember.
        TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth().tourTarget(TourTarget.HISTORY_BUTTON)) {
            Text("Add past periods")
        }
    }
}

/**
 * The reminder was due and did not run.
 *
 * Vivo, Oppo, Xiaomi and Samsung all terminate background work on their own schedule, and none
 * of it can be fixed programmatically: Autostart and background-power allowances live in vendor
 * settings with no public API. The honest response is to detect that the reminder stopped firing
 * and say so, since a silently dead reminder ends the logging habit without warning.
 *
 * A row, not a card. As an outlined card it sat above the forecast and took more of the screen than
 * the window it displaced; the explanation it carried belongs to Settings, which has it. The title
 * keeps the error colour, so a fault still reads differently from a health flag, which stays out of
 * red on purpose: this is about the app, not the body.
 */
@Composable
private fun ReminderStopped(batteryRestricted: Boolean) {
    val context = LocalContext.current
    // One line and the fix every day; what to change, for the first few days and behind ⓘ after.
    // Fix opens the right screen either way, so the how-to is a help, not the instruction.
    val explain = rememberExplanation("reminder-stopped")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Daily reminder stopped",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            ExplainButton(explain, about = "the stopped reminder")
            TextButton(onClick = { ReminderScheduler.openReminderFix(context, batteryRestricted) }) { Text("Fix") }
        }
        if (explain.showText) {
            // The action first. "Battery is unrestricted" led, and read as good news before the
            // problem (device review m9).
            Text(
                if (batteryRestricted) {
                    "Set battery saver to No restrictions so it can fire."
                } else {
                    "Turn on Autostart for Luna so it can fire. Battery is already unrestricted."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A quiet line when there has been no backup for 30 days, or none at all after a month of logging.
 *
 * Offline means one lost phone from gone, and nothing used to say how long it had been. A line at the
 * foot of the screen rather than a card or a notification, by decision: it should be findable, not
 * insistent. [lastBackup] is EPOCH when there has never been one.
 */
@Composable
private fun BackupNudge(lastBackup: java.time.Instant, onBackUp: () -> Unit) {
    val text = if (lastBackup == java.time.Instant.EPOCH) {
        "Not backed up yet."
    } else {
        "Last backup ${lastBackup.atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dayMonth)}."
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBackUp) { Text("Back up") }
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
        TextButton(onClick = { ReminderScheduler.openReminderFix(context, batteryRestricted = true) }) { Text("Fix") }
    }
}

