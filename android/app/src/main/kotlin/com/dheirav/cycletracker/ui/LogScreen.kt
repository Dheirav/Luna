package com.dheirav.cycletracker.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import java.time.Instant
import java.time.ZoneOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheirav.cycletracker.core.DayTag
import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Source
import com.dheirav.cycletracker.core.Symptom
import java.time.LocalDate

/**
 * The logging screen. Everything analytical in this app is worthless without adherence, so the
 * design constraint is one screen, five taps, under ten seconds.
 *
 * Consequences of that constraint, visible throughout:
 *  - Taps, never sliders. A slider is a drag with a target; a segmented button is one tap.
 *  - Four symptoms by default. The other three sit behind "More".
 *  - Every field optional, and tapping a selected level again unsets it — a mistake costs one tap.
 *  - Retro-logging is chevrons either side of the date, because yesterday is the common case.
 *    The date opens a calendar for anything further back.
 */
// FlowRow is still ExperimentalLayoutApi on this BOM; HistoryScreen opts in for the same reason.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogScreen(viewModel: LogViewModel, onDone: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val entry = ui.entry

    // What the user was trying to do when the form stopped them to ask about unsaved changes.
    var leaving by remember { mutableStateOf<Leave?>(null) }

    // Registered after MainActivity's handler, so it takes priority while it is enabled, and only
    // then: an untouched form still leaves on the first Back.
    BackHandler(enabled = ui.dirty) { leaving = Leave.Back }

    leaving?.let { attempt ->
        UnsavedDialog(
            onSave = {
                leaving = null
                viewModel.save { attempt.proceed(viewModel, onDone) }
            },
            onDiscard = {
                leaving = null
                viewModel.discardEdits()
                attempt.proceed(viewModel, onDone)
            },
            onKeepEditing = { leaving = null },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DayHeader(
                date = entry.date,
                // Changing day loads another day over this one, which loses edits exactly as Back did.
                onPick = { date ->
                    if (date == entry.date || date.isAfter(LocalDate.now())) return@DayHeader
                    if (ui.dirty) {
                        leaving = Leave.To(date)
                    } else {
                        viewModel.open(date)
                    }
                },
            )

            if (entry.exists && entry.source == Source.ASSUMED) {
                BackfillBanner(
                    // Both are terminal decisions about this day, so they leave the form the way a
                    // save does — back to wherever it was opened from. Going through the backfill is
                    // a loop of tap-day, decide, next day; making the user press Back after every
                    // verdict would double the work on the screen built for exactly this.
                    onConfirm = { viewModel.confirmBackfill(onDone) },
                    onDiscard = { viewModel.discardBackfill(onDone) },
                )
            }

            HorizontalDivider()

            // -- bleeding ----------------------------------------------------
            Text("Bleeding", style = MaterialTheme.typography.titleSmall)
            // Wraps rather than squeezing: four chips need about 480dp at 200% text size.
            FlowRow(
                modifier = Modifier.tourTarget(TourTarget.LOG_BLEEDING),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Two explicit answers, as the reminder has. This was one toggle whose unselected
                // label read "No" while meaning "not answered", so tapping "No" to answer it turned
                // it into "Bleeding", and leaving it alone saved nothing. Tapping a selected answer
                // clears it back to unanswered, so a mistake still costs one tap.
                FilterChip(
                    selected = entry.bleeding == false,
                    onClick = { viewModel.setBleeding(if (entry.bleeding == false) null else false) },
                    label = { Text("No bleeding") },
                )
                FilterChip(
                    selected = entry.bleeding == true,
                    onClick = { viewModel.setBleeding(if (entry.bleeding == true) null else true) },
                    label = { Text("Bleeding") },
                )
                FlowLevel.entries.forEach { level ->
                    val name = level.name.lowercase().replaceFirstChar { it.uppercase() }
                    FilterChip(
                        selected = entry.flow == level,
                        onClick = { viewModel.setFlow(level) },
                        // Spoken with what it grades; "Light, not checked" alone says nothing.
                        modifier = Modifier.semantics { contentDescription = "Flow: $name" },
                        label = { Text(name) },
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
                // Named for what is actually behind it. "mood" moved to the core rows on 2026-08-12,
                // so leaving it in this label would send someone hunting for a field already on screen.
                Text(if (ui.showExtended) "Fewer" else "More — irritability, anxiety, stress")
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
            // One wrapping row, not two fixed rows of three: at large text "Off routine" and "Med
            // change" were clipped to fit.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DayTag.entries.forEach { tag -> TagChip(tag, entry.tags, viewModel) }
            }

            OutlinedTextField(
                value = entry.notes,
                onValueChange = viewModel::setNotes,
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Text(
                "Leave anything blank that you don't know. Blank is recorded as unknown, never as zero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Pinned below the scrolling form rather than at the end of it. At the end it sat at or below
        // the fold on the Redmi Note 15 Pro, and well below it once "More" was open, so finishing an
        // entry meant scrolling to find the finish line.
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.save(onDone) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .heightIn(min = 52.dp)
                    .tourTarget(TourTarget.LOG_SAVE),
            ) {
                // Names the day, so a save after the date arrows were tapped says where it is going.
                Text("Save · ${dayLabel(entry.date)}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Ways of leaving the form that would discard an unsaved edit. */
private sealed interface Leave {
    fun proceed(viewModel: LogViewModel, onDone: () -> Unit)

    data object Back : Leave {
        override fun proceed(viewModel: LogViewModel, onDone: () -> Unit) = onDone()
    }

    data class To(val date: LocalDate) : Leave {
        override fun proceed(viewModel: LogViewModel, onDone: () -> Unit) {
            viewModel.open(date)
        }
    }
}

/**
 * Asks before throwing an edit away.
 *
 * Save comes first because it is almost always the intent: someone who filled in a day and pressed
 * Back usually assumed it saved itself. Tapping outside keeps editing, the one choice that loses
 * nothing.
 */
@Composable
private fun UnsavedDialog(onSave: () -> Unit, onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Save this day?") },
        text = { Text("You changed this day and have not saved it yet.") },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
    )
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
                "Worked out by counting back 28 days. Left out of your variability and accuracy " +
                    "figures until you confirm it.",
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

/**
 * Leaving and changing day, in one row with one meaning per side.
 *
 * These were two stacked rows: a back arrow, and under it "‹ Earlier". Two left-pointing controls a
 * few millimetres apart that did unrelated things, one leaving the form and one loading yesterday.
 * Now the arrow at the far left is the only way out, and the chevrons sit either side of the date
 * they change, so their scope is visible.
 *
 * The date itself opens a calendar. The chevrons stay for yesterday, which is the common case; the
 * calendar is for anything further back, which used to cost a tap per day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayHeader(date: LocalDate, onPick: (LocalDate) -> Unit) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val today = LocalDate.now()
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Presses the system Back, so the unsaved-changes prompt applies to it as to a gesture.
        IconButton(onClick = { dispatcher?.onBackPressed() }, modifier = Modifier.tourTarget(TourTarget.BACK)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onPick(date.minusDays(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        TextButton(
            onClick = { picking = true },
            modifier = Modifier.semantics { contentDescription = "${dayLabel(date)}. Choose another day" },
        ) {
            Text(dayLabel(date), style = MaterialTheme.typography.titleLarge)
        }
        IconButton(onClick = { onPick(date.plusDays(1)) }, enabled = date.isBefore(today)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
        Spacer(Modifier.weight(1f))
        // Balances the back arrow, so the date sits in the true centre of the screen.
        Spacer(Modifier.width(48.dp))
    }

    if (picking) {
        val zone = ZoneOffset.UTC
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
            // No logging the future: there is nothing to observe yet.
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(zone).toLocalDate().isAfter(today)

                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    state.selectedDateMillis?.let {
                        onPick(Instant.ofEpochMilli(it).atZone(zone).toLocalDate())
                    }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun SymptomRow(symptom: Symptom, value: Int?, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(symptom.label, style = MaterialTheme.typography.titleSmall)
        LevelRow(symptom = symptom, value = value, onSelect = onSelect)
    }
}

/**
 * Five levels in one row, every word whole.
 *
 * Material's segmented button pads each label by 12dp a side and adds a check icon, which on a phone
 * leaves about 46dp of text per segment. "Moderate" and "Overwhelming" came out as "Modera…" and
 * "Overwh…", and wrapping them with hyphens produced "Over-/whelm-" with the end cut off. These are the
 * scale's anchor words, so the fix has to keep the word, not shorten it: padding drops to 2dp and a
 * word too long for its cell shrinks until it fits on one line.
 *
 * Selection is shown by fill **and** weight, so it never rests on colour alone, and a screen reader
 * hears each cell's state and can clear a selected one, as a tap can.
 */
@Composable
private fun LevelRow(symptom: Symptom, value: Int?, onSelect: (Int) -> Unit) {
    // Above about 130% text size five words no longer fit across a phone, and shrinking them to fit
    // undoes the size the person chose. So the scale turns into a list, top to bottom, at full size.
    if (LocalDensity.current.fontScale > LARGE_TEXT_SCALE) {
        LevelList(symptom = symptom, value = value, onSelect = onSelect)
        return
    }
    val shape = RoundedCornerShape(50)
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, outline, shape)
            .height(IntrinsicSize.Min),
    ) {
        symptom.levels.forEachIndexed { index, level ->
            if (index > 0) VerticalDivider(color = outline)
            val selected = value == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    )
                    // One accessibility node per cell, built here rather than inherited. Left to
                    // `selectable`, the cell exposed its description, its visible word and a radio stub
                    // as three children, so TalkBack read "Energy: OK, OK, radio button". The symptom's
                    // name is a separate Text above the row, which is why the description carries it.
                    //
                    // No radio role, on purpose. Android reports a *selected* radio button as not
                    // clickable, since radios cannot be unset, so TalkBack could choose a level but never
                    // clear one, while a finger tap on the selected level clears it. A plain clickable
                    // with a stated state and a "clear" action keeps both ways of using the form equal.
                    .clearAndSetSemantics {
                        contentDescription = "${symptom.label}: $level"
                        stateDescription = if (selected) "Selected" else "Not selected"
                        onClick(label = if (selected) "clear" else "select") { onSelect(index); true }
                    }
                    .clickable(onClick = { onSelect(index) })
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                FitText(
                    level,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    bold = selected,
                )
            }
        }
    }
}

private const val LARGE_TEXT_SCALE = 1.3f

/**
 * The level scale as a vertical list, for large text. Same semantics as the row: one node per
 * level, a state description, and a "clear" action on the selected one.
 */
@Composable
private fun LevelList(symptom: Symptom, value: Int?, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        symptom.levels.forEachIndexed { index, level ->
            val selected = value == index
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .clearAndSetSemantics {
                        contentDescription = "${symptom.label}: $level"
                        stateDescription = if (selected) "Selected" else "Not selected"
                        onClick(label = if (selected) "clear" else "select") { onSelect(index); true }
                    }
                    .clickable(onClick = { onSelect(index) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    level,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/**
 * One line of text that shrinks until it fits, never below 8sp.
 *
 * Compose on this BOM has no auto-size text, so this steps the size down on overflow and only draws
 * once the text fits. The step is invisible because the first frames are not drawn.
 */
@Composable
private fun FitText(text: String, color: Color, bold: Boolean) {
    var size by remember(text) { mutableStateOf(12f) }
    var fits by remember(text) { mutableStateOf(false) }
    Text(
        text,
        color = color,
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && size > 8f) size -= 0.5f else fits = true
        },
        modifier = Modifier.drawWithContent { if (fits) drawContent() },
    )
}

@Composable
private fun TagChip(tag: DayTag, selected: Set<DayTag>, viewModel: LogViewModel) {
    FilterChip(
        selected = tag in selected,
        onClick = { viewModel.toggleTag(tag) },
        // The user's text size, not a forced 12sp.
        label = { Text(tag.label) },
    )
}
