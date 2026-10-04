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

/** The pages before the tour, in order. */
enum class OnboardingPage { PRIVATE, SETUP }

/**
 * Which pages to show before the tour.
 *
 * [OnboardingPage.SETUP] writes data, so it appears only when no periods are logged. On a phone that
 * already has history (an update, a restore) it would invite entering a period twice.
 */
fun onboardingPages(hasPeriods: Boolean): List<OnboardingPage> =
    OnboardingPage.entries.filter { it != OnboardingPage.SETUP || !hasPeriods }

private val rangeLabel = DateTimeFormatter.ofPattern("d MMM")

/**
 * The welcome before the tour: one page on privacy and, with no data yet, one optional setup page.
 *
 * Everything else that was explained here in paragraphs is now shown in the app itself by the guided
 * tour ([TourOverlay]), asked for on 5 Oct 2026 as "show where to click, not a lot of tell". What is
 * left here is what cannot be pointed at: that nothing leaves the phone, and the first period, which
 * would otherwise cost a tap per day in History. The notification permission moved to the tour's
 * reminder step, next to the reminder it is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    hasPeriods: Boolean,
    onLogPeriod: (LocalDate, LocalDate) -> Unit,
    /** True to go on into the tour, false to skip it. */
    onFinish: (startTour: Boolean) -> Unit,
) {
    val pages = onboardingPages(hasPeriods)
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index.coerceIn(0, pages.lastIndex)]
    val last = index >= pages.lastIndex

    // The setup page's answers, held until the walkthrough ends.
    var range by rememberSaveable { mutableStateOf<Pair<Long, Long>?>(null) }

    // A period chosen on the setup page is kept whether or not the tour follows: skipping a tour is
    // not a reason to lose an answer.
    fun finish(startTour: Boolean) {
        range?.let { (from, to) -> onLogPeriod(fromUtc(from), fromUtc(to)) }
        onFinish(startTour)
    }

    // Back steps through the pages, and from the first page leaves without the tour.
    BackHandler { if (index > 0) index-- else onFinish(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.heightIn(min = 48.dp))
            when (page) {
                OnboardingPage.PRIVATE -> Page(
                    "Welcome to Luna",
                    "No account and no internet. Everything you log stays on this phone unless you " +
                        "export it yourself.",
                    "Next, a short tour shows you where everything is, on the real screens.",
                )
                OnboardingPage.SETUP -> SetupPage(
                    range = range,
                    onRange = { range = it },
                )
            }
        }

        if (pages.size > 1) {
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
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { finish(startTour = false) }) { Text("Skip the tour") }
            Button(
                onClick = { if (!last) index++ else finish(startTour = true) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (!last) "Next" else "Show me around") }
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
