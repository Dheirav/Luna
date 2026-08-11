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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dheirav.cycletracker.core.WindowWidth
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.reminder.ReminderScheduler
import com.dheirav.cycletracker.widget.refreshCycleWidgets
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")

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
                "what it measured, and these stop applying. They still outrank the backfill's " +
                "guesses, which assumed 28 and 5.",
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

/** `TimePicker` is still `ExperimentalMaterial3Api`. Opted in rather than hand-rolling one: the
 *  alternative is reimplementing a clock face, and the API churn here is renames, not behaviour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderCard(settings: Settings) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.reminderEnabled) }
    var time by remember { mutableStateOf(settings.reminderTime) }
    var picking by remember { mutableStateOf(false) }

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
            "The reminder skips days you have already logged, so it only fires when it has " +
                "something to ask for.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            Text(title, style = MaterialTheme.typography.titleSmall)
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
            ) { Text("−") }
            Text(
                value?.let { "$it days" } ?: unset,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(76.dp),
            )
            FilledTonalIconButton(
                onClick = { onChange(((value ?: default - 1) + 1).coerceIn(range)) },
            ) { Text("+") }
        }
    }
}
