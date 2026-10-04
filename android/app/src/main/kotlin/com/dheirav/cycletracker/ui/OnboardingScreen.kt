package com.dheirav.cycletracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dheirav.cycletracker.data.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The walkthrough's pages, in order. */
enum class OnboardingPage { PRIVATE, LOGGING, WINDOW, OBSERVED, REMINDER, SETUP }

/**
 * Which pages to show.
 *
 * [OnboardingPage.SETUP] writes data, so it appears only on a first run with no periods logged. A
 * replay from Settings, or a first run on a phone that already has history (an update, a restore),
 * would otherwise invite entering a period twice.
 */
fun onboardingPages(replay: Boolean, hasPeriods: Boolean): List<OnboardingPage> =
    OnboardingPage.entries.filter { it != OnboardingPage.SETUP || (!replay && !hasPeriods) }

private val rangeLabel = DateTimeFormatter.ofPattern("d MMM")

/**
 * How Luna works, as a short walkthrough: on first run, and again from Settings.
 *
 * It exists because nothing in the app explained itself. The council review of 5 Oct found that a
 * first open was two system prompts over a screen saying "No periods logged yet", with no mention of
 * the reminder, the widget, or why the prediction is a range. Each page is one idea, said once, in
 * the words the screens themselves use, so what it teaches is what the user then sees.
 *
 * Two things moved here from elsewhere, deliberately:
 *  - **The notification permission.** It was requested on the first frame of the app, before anyone
 *    knew what it was for. It is asked on the reminder page now, behind a button that says why.
 *  - **The first period.** Entering past periods cost a tap per day in History. The optional setup
 *    page takes a date range and a usual cycle length, which makes the first prediction useful on
 *    day one rather than after three cycles of logging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    replay: Boolean,
    hasPeriods: Boolean,
    onRequestNotifications: () -> Unit,
    onLogPeriod: (LocalDate, LocalDate) -> Unit,
    onFinish: () -> Unit,
) {
    val pages = onboardingPages(replay, hasPeriods)
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val last = index >= pages.lastIndex

    // Back steps through the pages, and from the first page leaves, the same as Skip.
    BackHandler { if (index > 0) index-- else onFinish() }

    // The setup page's answers, held until Done so that Skip writes nothing.
    var range by rememberSaveable { mutableStateOf<Pair<Long, Long>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = onFinish) { Text(if (replay) "Close" else "Skip") }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.heightIn(min = 24.dp))
            when (page) {
                OnboardingPage.PRIVATE -> Page(
                    "Private by design",
                    "Luna has no account and no internet permission. Everything you log stays on " +
                        "this phone unless you export it yourself.",
                    "An app lock, a blank card in recent apps and a discreet widget keep it off " +
                        "screens other people can see.",
                )
                OnboardingPage.LOGGING -> Page(
                    "Log in ten seconds",
                    "Tap Log today, answer what you know and save. Every field is optional: " +
                        "anything you leave blank is recorded as unknown, never as zero.",
                    "The evening reminder has Bleeding and No bleeding buttons, so most days take " +
                        "one tap. Undo appears after every save, and any past day can be fixed " +
                        "from History.",
                )
                OnboardingPage.WINDOW -> Page(
                    "A window, not a date",
                    "The next period is shown as a range of days, because cycles vary. With few " +
                        "cycles logged the range is wide, and it says so. It narrows as your own " +
                        "cycles are observed.",
                    "If a period runs past the window, Today says how many days past, and when it " +
                        "would be worth raising with a doctor.",
                )
                OnboardingPage.OBSERVED -> Page(
                    "What you logged, and what was worked out",
                    "Days you logged are filled in. Anything the app estimated is marked as " +
                        "estimated, on the calendar, on Today and in the doctor summary.",
                    "Tap the cycle card on Today to read about your phase, and \"Why these " +
                        "numbers?\" to see exactly what each figure is based on.",
                )
                OnboardingPage.REMINDER -> {
                    Page(
                        "A nudge, if you want one",
                        "Luna can remind you at 21:00 each evening, and skips days you have " +
                            "already logged. You can change the time in Settings.",
                        "The home-screen widget is a one-tap shortcut that keeps working even if " +
                            "the phone stops the reminder. It shows nothing about your cycle " +
                            "unless you turn details on.",
                    )
                    OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow the reminder")
                    }
                }
                OnboardingPage.SETUP -> SetupPage(
                    range = range,
                    onRange = { range = it },
                )
            }
        }

        // Progress, so the walkthrough visibly has an end.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .semantics { contentDescription = "Page ${index + 1} of ${pages.size}" },
            horizontalArrangement = Arrangement.Center,
        ) {
            pages.indices.forEach { i ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) TextButton(onClick = { index-- }) { Text("Back") } else Spacer(Modifier)
            Button(
                onClick = {
                    if (!last) {
                        index++
                    } else {
                        range?.let { (from, to) -> onLogPeriod(fromUtc(from), fromUtc(to)) }
                        onFinish()
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        !last -> "Next"
                        replay -> "Close"
                        else -> "Start"
                    },
                )
            }
        }
    }
}

@Composable
private fun Page(title: String, vararg paragraphs: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
}

/**
 * The optional first-run setup: the last period as a range, and a usual cycle length.
 *
 * Both are optional and both say what they are for. The cycle length is written straight to
 * Settings, which is where it lives and where it can be changed later; the period is held until
 * the walkthrough's Start, so Skip writes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupPage(range: Pair<Long, Long>?, onRange: (Pair<Long, Long>?) -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var cycle by remember { mutableStateOf(settings.typicalCycleLength) }
    var picking by remember { mutableStateOf(false) }

    Page(
        "Get started",
        "Both of these are optional. Your own logged cycles replace them once three have been " +
            "observed.",
    )

    Text("When did your last period start?", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
        Text(
            range?.let { (from, to) ->
                "${fromUtc(from).format(rangeLabel)} – ${fromUtc(to).format(rangeLabel)}"
            } ?: "Choose the days",
        )
    }

    Stepper(
        label = "Usual cycle length",
        value = cycle,
        unset = "not set",
        range = 15..60,
        default = 28,
        onChange = {
            cycle = it
            settings.typicalCycleLength = it
        },
    )

    if (picking) {
        val today = LocalDate.now()
        val state = rememberDateRangePickerState(
            // No logging the future: there is nothing to observe yet.
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = !fromUtc(utcTimeMillis).isAfter(today)

                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        picking = false
                        val start = state.selectedStartDateMillis ?: return@TextButton
                        // A start with no end is one day, not a guessed length: only what was said.
                        onRange(start to (state.selectedEndDateMillis ?: start))
                    },
                ) { Text("Use these days") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

/** Material's pickers work in UTC midnights; this turns one back into the calendar date picked. */
private fun fromUtc(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
