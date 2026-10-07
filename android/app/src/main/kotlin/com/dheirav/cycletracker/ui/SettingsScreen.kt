package com.dheirav.cycletracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
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
import com.dheirav.cycletracker.widget.refreshWidgets
import java.time.Instant
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
 *
 * Each card shows what its controls do *now* and keeps the reasoning behind an info button. The
 * reasoning was always on screen, and after the first read it was only scrolling between switches.
 */
@Composable
fun SettingsScreen(onHowItWorks: () -> Unit, onSummary: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BackBar(title = "Settings")

        // Grouped by what a setting changes, so the two that alter the numbers on Today are not
        // buried among the twice-a-year ones. It was one flat run of seven cards.
        SettingsGroup("Predictions")
        YourCyclesCard(settings = settings)
        PredictionCard(settings = settings)

        // The widget sits with the reminder because it is the reminder's fallback: it keeps working
        // when the phone kills background work.
        SettingsGroup("Reminder and widget")
        Box(Modifier.tourTarget(TourTarget.REMINDER_CARD)) { ReminderCard(settings = settings) }
        WidgetCard(settings = settings)

        SettingsGroup("Privacy and data")
        AppLockSection()
        BackupSection()
        SummarySection(onOpen = onSummary)

        SettingsGroup("Help")
        SettingsCard("How Luna works") {
            Text(
                "The short walkthrough from the first time you opened the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onHowItWorks, modifier = Modifier.fillMaxWidth()) {
                Text("Show the walkthrough")
            }
        }

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
private fun YourCyclesCard(settings: Settings) {
    var cycle by remember { mutableStateOf(settings.typicalCycleLength) }
    var period by remember { mutableStateOf(settings.typicalPeriodLength) }

    SettingsCard(
        "Your cycles",
        about = "Used until three of your own cycles have been observed — after that the app goes " +
            "by what it measured, and these stop applying. They still outrank the app's own " +
            "estimates, which used 28 and 5.",
    ) {
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
        Stepper(
            label = "Usual period length",
            value = period,
            unset = "not set",
            range = 1..12,
            default = 5,
            onChange = {
                period = it
                settings.typicalPeriodLength = it
            },
        )
        Text(
            "Only used until three of your cycles have been observed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PredictionCard(settings: Settings) {
    var width by remember { mutableStateOf(settings.windowWidth) }

    SettingsCard(
        "Prediction window",
        about = "This chooses how much of the uncertainty to show, not how much there is. A narrow " +
            "setting cannot make an erratic history look regular.",
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            WindowWidth.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = width == option,
                    onClick = {
                        width = option
                        settings.windowWidth = option
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
                // Relative, not rates. "Right two times in three" was a figure nobody had measured:
                // it holds for a bell curve, real cycles are skewed, and an assumed window has no
                // measured spread at all. Measured accuracy appears in "Why these numbers?" once
                // there is a track record.
                WindowWidth.NARROW -> "A tighter range that will be wrong more often. Useful if a " +
                    "broad window is too vague to plan around."
                WindowWidth.BALANCED -> "The default: wide enough to be right most months."
                WindowWidth.WIDE -> "Right more often, at the cost of a noticeably broader range."
            },
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

    val headline = status?.let { reminderHeadline(it) { at -> LocalDateTime.ofInstant(at, ZoneId.systemDefault()).format(stamp) } }
    var showDetails by remember { mutableStateOf(false) }
    // A fault opens the details by itself; nobody should have to know to look.
    val detailsOpen = showDetails || headline?.problem == true

    SettingsCard(
        "Daily reminder",
        about = "Skips days you have already logged. The heads-up comes once per cycle, before the " +
            "window opens.",
    ) {
        SwitchRow(
            label = "Remind me to log",
            style = MaterialTheme.typography.bodyMedium,
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
        HorizontalDivider()

        SwitchRow(
            label = "Warn me before it's due",
            style = MaterialTheme.typography.bodyMedium,
            checked = warn,
            onCheckedChange = {
                warn = it
                settings.periodWarningEnabled = it
            },
        )
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
        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                headline?.text ?: "Checking…",
                style = MaterialTheme.typography.bodySmall,
                color = if (headline?.problem == true) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            // No toggle while a fault holds the details open: a "Hide" that did nothing would be
            // worse than none.
            if (headline?.problem != true) {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide" else "Details")
                }
            }
        }

        AnimatedVisibility(visible = detailsOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReminderDetails(
                    status = status,
                    time = time,
                    sent = sent,
                    onSend = {
                        ReminderScheduler.sendTestReminder(context)
                        sent = true
                        probe++
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

/** The reminder's one-line state, and whether it is a fault that should open the details. */
data class ReminderHeadline(val text: String, val problem: Boolean)

/**
 * Summarises [ReminderStatus] for the folded card.
 *
 * Faults are checked first and always win, so a broken reminder can never sit under a reassuring
 * line. A restricted battery is appended rather than promoted: it is a risk, and treating it as a
 * fault would force the details open on every visit for a phone that may never kill anything.
 */
fun reminderHeadline(status: ReminderStatus, stamp: (Instant) -> String): ReminderHeadline {
    if (!status.enabled) return ReminderHeadline("Off", problem = false)
    if (!status.notificationsAllowed) {
        return ReminderHeadline("Blocked: notifications are off for this app", problem = true)
    }
    if (status.looksBroken) {
        return ReminderHeadline("Stopped: a reminder was due and did not fire", problem = true)
    }
    val base = status.lastFired?.let { "Working · last fired ${stamp(it)}" } ?: "Set, has not fired yet"
    val text = if (status.batteryUnrestricted) base else "$base · battery restricted"
    return ReminderHeadline(text, problem = false)
}

/**
 * Everything behind "Details": the raw status, the test button, and the fixes.
 *
 * Folded because it is what you need when something is wrong and noise when it is not, and most
 * visits are the second kind. [reminderHeadline] decides when it opens itself.
 */
@Composable
private fun ReminderDetails(
    status: ReminderStatus?,
    time: LocalTime,
    sent: Boolean,
    onSend: () -> Unit,
) {
    val context = LocalContext.current

    ReminderStatusBlock(status)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSend) { Text("Send one now") }
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
                // A risk, not a fault: nothing has failed yet. Same weight as Today's footnote.
                severe = false,
                action = "Open battery settings",
                onClick = {
                    runCatching { context.startActivity(ReminderScheduler.batterySettingsIntent()) }
                },
            )
        }
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

/**
 * A problem the user can actually do something about, with the something attached.
 *
 * [severe] is for faults that stop the reminder outright, and is the only case drawn in the error
 * colour, so red keeps meaning the same thing here as on Today.
 */
@Composable
private fun FixRow(message: String, action: String, severe: Boolean = true, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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

    SettingsCard(
        "Home screen widget",
        about = "Add it by long-pressing your home screen. Worth having: it needs no background " +
            "permission, so it keeps working even when this phone kills the daily reminder.",
    ) {
        SwitchRow(
            label = "Show cycle details",
            style = MaterialTheme.typography.bodyMedium,
            checked = details,
            onCheckedChange = {
                details = it
                settings.widgetShowsDetails = it
                refreshWidgets(context)
            },
        )
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
    }
}

/**
 * A labelled switch where the whole row is the control.
 *
 * The switches were siblings of a plain `Text`, so TalkBack landed on each one and said "Off,
 * switch" with no name. On "Require unlock" and "Allow screenshots" that meant guessing which
 * privacy setting was about to change. Making the row toggleable gives one node, "Require unlock,
 * switch, on", and a larger target than the switch alone.
 */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = style, modifier = Modifier.weight(1f))
        // No handler of its own: the row takes the tap, so there is one control, not two.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A heading over a run of cards, so the screen reads as three sections rather than one list. */
@Composable
private fun SettingsGroup(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 8.dp, start = 4.dp)
            .semantics { heading() },
    )
}

/**
 * One settings card.
 *
 * [about] is the reasoning behind the card's controls, shown on demand from the info button. The card
 * body keeps only what a control does now; why it works that way is worth reading once, and was
 * costing a scroll on every visit after.
 */
@Composable
internal fun SettingsCard(title: String, about: String? = null, content: @Composable () -> Unit) {
    var showAbout by rememberSaveable(title) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .semantics { heading() },
                )
                if (about != null) {
                    IconButton(onClick = { showAbout = !showAbout }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = if (showAbout) "Hide details about $title" else "About $title",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (about != null) {
                    AnimatedVisibility(visible = showAbout) {
                        Text(
                            about,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                content()
            }
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
internal fun Stepper(
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
