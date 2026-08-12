package com.dheirav.cycletracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dheirav.cycletracker.core.WindowWidth
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.reminder.ReminderScheduler
import com.dheirav.cycletracker.reminder.ReminderStatus
import com.dheirav.cycletracker.widget.refreshCycleWidgets
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")

/** Day and time, for the reminder's own history — "21:00" alone cannot say *which* 21:00. */
private val stamp = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/**
 * App lock, backup, and — for the first time — the numbers the engine runs on.
 *
 * Three settings existed in `Settings.kt` with no way to reach them: reminder time, reminder
 * on/off, and typical cycle length. The last had been read by the engine on every refresh and had
 * been null since it was written. Period length did not exist at all, despite feeding
 * `ovulationDay` through the `periodLength + 4` floor.
 *
 * Moved off Today at the same time, per the design brief — these are touched about twice a year
 * and were occupying a third of the screen looked at daily.
 */
@Composable
fun SettingsScreen(onChanged: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        YourCyclesCard(settings = settings, onChanged = onChanged)

        PredictionCard(settings = settings, onChanged = onChanged)

        ReminderCard(settings = settings)

        WidgetCard(settings = settings)

        AppLockSection()

        BackupSection(onRestored = onChanged)

        SummarySection()

        Text(
            "This app has no internet permission and never will. Nothing here leaves the phone " +
                "unless you export it yourself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The two figures the user knows better than the app does — until it has measured enough.
 *
 * Both say plainly that they are superseded by real data. A setting that quietly stops applying
 * reads as a bug, and the honest framing is also the useful one: it tells the user their own logs
 * are worth more than their recollection.
 */
@Composable
private fun YourCyclesCard(settings: Settings, onChanged: () -> Unit) {
    var cycle by remember { mutableStateOf(settings.typicalCycleLength) }
    var period by remember { mutableStateOf(settings.typicalPeriodLength) }

    SettingsCard("Your cycles") {
        Stepper(
            label = "Usual cycle length",
            value = cycle,
            unset = "not set",
            range = 15..60,
            default = 28,
            onChange = {
                cycle = it
                settings.typicalCycleLength = it
                onChanged()
            },
        )
        Stepper(
            label = "Usual period length",
            value = period,
            unset = "not set",
            range = 1..12,
            default = 5,
            onChange = {
                period = it
                settings.typicalPeriodLength = it
                onChanged()
            },
        )
        Text(
            "Used until three of your own cycles have been observed — after that the app goes by " +
                "what it measured, and these stop applying. They still outrank the app's own " +
                "estimates, which used 28 and 5.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PredictionCard(settings: Settings, onChanged: () -> Unit) {
    var width by remember { mutableStateOf(settings.windowWidth) }

    SettingsCard("Prediction window") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            WindowWidth.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = width == option,
                    onClick = {
                        width = option
                        settings.windowWidth = option
                        onChanged()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, WindowWidth.entries.size),
                    label = {
                        Text(
                            option.name.lowercase().replaceFirstChar { it.uppercase() },
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }
        }
        Text(
            when (width) {
                WindowWidth.NARROW -> "A tight range that will be wrong more often — about half " +
                    "the time. Useful if a broad window is too vague to plan around."
                WindowWidth.BALANCED -> "Right roughly two times in three. The default."
                WindowWidth.WIDE -> "Right about nine times in ten, at the cost of a noticeably " +
                    "broader range."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "This chooses how much of the uncertainty to show, not how much there is. A narrow " +
                "setting cannot make an erratic history look regular.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The daily nudge and the pre-period heads-up, with the state of both on show.
 *
 * The switches and the time picker were here already; what was missing was any way to tell whether
 * any of it works. Three independent things can silently stop the reminder — a denied notification
 * permission, an empty WorkManager queue, a ROM throttling the wakeup — and none of them was
 * visible, so the only way to check the reminder was to wait until 21:00 and find out. Worse, the
 * app's own verdict on that (`reminderLooksBroken`) surfaced on Today as a warning card and needed
 * 36 hours before it would say anything at all.
 *
 * So: next due, last fired, the queue's own state, and a button that fires one now.
 *
 * `TimePicker` is still `ExperimentalMaterial3Api`. Opted in rather than hand-rolling one: the
 * alternative is reimplementing a clock face, and the API churn here is renames, not behaviour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderCard(settings: Settings) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.reminderEnabled) }
    var time by remember { mutableStateOf(settings.reminderTime) }
    var warn by remember { mutableStateOf(settings.periodWarningEnabled) }
    var lead by remember { mutableIntStateOf(settings.periodWarningLeadDays) }
    var picking by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }

    // Bumped by anything that could change the answer, so the status block is never stale.
    var probe by remember { mutableIntStateOf(0) }

    // Recheck on every return to the screen: granting the notification permission or the battery
    // exemption happens in system settings, so the app comes back to a changed world.
    LifecycleResumeEffect(Unit) {
        probe++
        onPauseOrDispose { }
    }

    val status by produceState<ReminderStatus?>(null, probe, enabled, time) {
        value = ReminderScheduler.status(context)
    }

    SettingsCard("Daily reminder") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Remind me to log", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    settings.reminderEnabled = it
                    // Reschedules or cancels immediately — a reminder setting that waits for the
                    // next app launch to take effect is one the user will believe is broken.
                    ReminderScheduler.schedule(context)
                    probe++
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("At", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { picking = true }, enabled = enabled) {
                Text(time.format(clock), style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            "Skips days you have already logged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Warn me before it's due", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = warn,
                onCheckedChange = {
                    warn = it
                    settings.periodWarningEnabled = it
                },
            )
        }
        if (warn) {
            Stepper(
                label = "Days of notice",
                value = lead,
                unset = "",
                range = Settings.MIN_WARNING_LEAD_DAYS..Settings.MAX_WARNING_LEAD_DAYS,
                default = Settings.DEFAULT_WARNING_LEAD_DAYS,
                onChange = {
                    lead = it
                    settings.periodWarningLeadDays = it
                },
            )
        }
        Text(
            "Once per cycle, before the window opens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        ReminderStatusBlock(status)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                ReminderScheduler.sendTestReminder(context)
                sent = true
                probe++
            }) { Text("Send one now") }
            if (sent) {
                Text(
                    "Sent — check your shade",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Two claims survive the trim, and only two: what the test cannot establish, and that its
        // buttons are live. Everything else about it — that it goes through WorkManager rather than
        // posting directly, that it skips the bookkeeping — is in Reminders.kt, where the next person
        // to touch this will actually be looking.
        Text(
            "Its buttons write a real entry for today. It cannot tell you whether the " +
                "${time.format(clock)} one survives the night — only a few days can.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        status?.let { s ->
            if (!s.notificationsAllowed) {
                FixRow(
                    message = "Notifications are blocked. No reminder can arrive however these " +
                        "switches are set.",
                    action = "Open notification settings",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                ReminderScheduler.appNotificationSettingsIntent(context),
                            )
                        }
                    },
                )
            }
            if (!s.batteryUnrestricted) {
                FixRow(
                    message = "Battery use is restricted. Autostart, if this ROM has it, needs " +
                        "granting by hand too.",
                    action = "Open battery settings",
                    onClick = {
                        runCatching { context.startActivity(ReminderScheduler.batterySettingsIntent()) }
                    },
                )
            }
        }
    }

    if (picking) {
        val picker = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(picker.hour, picker.minute)
                    settings.reminderTime = time
                    ReminderScheduler.schedule(context)
                    picking = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            text = { TimePicker(state = picker) },
        )
    }
}

/**
 * What the app knows about whether the reminder will arrive.
 *
 * Reports the queue's own state rather than paraphrasing it: `ENQUEUED` with nothing ever firing
 * means the ROM is dropping the wakeup, while no job at all means the queue was cleared and
 * rescheduling is the fix. Those need different responses and the shade cannot tell them apart.
 */
@Composable
private fun ReminderStatusBlock(status: ReminderStatus?) {
    if (status == null) {
        Text(
            "Checking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val lastFired = status.lastFired
        ?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()).format(stamp) }
        ?: "not yet"

    StatusLine("Next due", status.nextFireAt?.format(stamp) ?: "off")
    StatusLine("Last fired", lastFired)
    StatusLine(
        "In the queue",
        // These are `WorkInfo.State` names, and lowercasing one is not translating it — "enqueued"
        // is Android's word, and next to the label it read "in the queue: in the queue". Only the
        // unfinished states can appear here; `queuedWorkState` filters the rest out.
        when {
            !status.enabled -> "nothing — reminders are off"
            status.workState == null -> "nothing queued"
            status.workState == "RUNNING" -> "running now"
            status.workState == "BLOCKED" -> "waiting on another job"
            status.workState == "ENQUEUED" -> "waiting"
            // A state the filter was not expecting is worth seeing verbatim rather than smoothing
            // into "waiting", which would claim more than is known.
            else -> status.workState.lowercase()
        },
    )

    if (status.looksBroken) {
        Text(
            "A reminder was due and did not fire. That is this phone killing background work, not " +
                "a setting you have wrong.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** A problem the user can actually do something about, with the something attached. */
@Composable
private fun FixRow(message: String, action: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onClick) { Text(action) }
    }
}

/**
 * The privacy trade the widget forces, stated rather than decided silently.
 *
 * A home screen gets seen by people who are not you. The launcher icon is a bloom rather than a
 * droplet or calendar precisely so the app does not announce itself; a widget reading
 * "Day 15 · Ovulation" undoes that. Turning it off keeps the one-tap logging shortcut, which is
 * the widget's actual justification — it works where the reminder gets killed.
 */
@Composable
private fun WidgetCard(settings: Settings) {
    val context = LocalContext.current
    var details by remember { mutableStateOf(settings.widgetShowsDetails) }

    SettingsCard("Home screen widget") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show cycle details", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = details,
                onCheckedChange = {
                    details = it
                    settings.widgetShowsDetails = it
                    refreshCycleWidgets(context)
                },
            )
        }
        Text(
            if (details) {
                "The widget shows your cycle day, phase and next window — visible to anyone who " +
                    "glances at your home screen."
            } else {
                "The widget shows only \"Today\" and stays a one-tap shortcut to logging. Nothing " +
                    "about your cycle appears on the home screen."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Add it by long-pressing your home screen. Worth having: it needs no background " +
                "permission, so it keeps working even when this phone kills the daily reminder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

/**
 * Taps, not typing.
 *
 * The log screen already established the rule — a keyboard for a two-digit number is friction for
 * no gain, and it invites a stray "280" that the plausibility filter would then silently discard.
 * [range] bounds it at the source instead.
 */
@Composable
private fun Stepper(
    label: String,
    value: Int?,
    unset: String,
    range: IntRange,
    default: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { onChange(((value ?: default) - 1).coerceIn(range)) },
                modifier = Modifier.semantics { contentDescription = "Decrease $label" },
            ) { Text("−") }
            Text(
                value?.let { "$it days" } ?: unset,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(76.dp),
            )
            FilledTonalIconButton(
                onClick = { onChange(((value ?: default - 1) + 1).coerceIn(range)) },
                modifier = Modifier.semantics { contentDescription = "Increase $label" },
            ) { Text("+") }
        }
    }
}
