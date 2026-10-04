package com.dheirav.cycletracker.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.core.BackupSnapshot
import com.dheirav.cycletracker.data.BackupManager
import com.dheirav.cycletracker.data.Settings
import com.dheirav.cycletracker.reminder.ReminderScheduler
import com.dheirav.cycletracker.widget.refreshWidgets
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy")

/** How long without a backup before Today mentions it. */
private const val BACKUP_NUDGE_DAYS = 30L

/**
 * What a restore will do, said before it does it.
 *
 * Restore replaces everything and cannot merge, so the one thing worth saying loudly is whether the
 * backup is missing days the phone has: that is what an old file silently throws away.
 */
fun restorePreview(
    backup: BackupSnapshot,
    phoneDays: Int,
    phoneNewest: LocalDate?,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val made = runCatching { Instant.parse(backup.exportedAt).atZone(zone).toLocalDate().format(fullDate) }
        .getOrDefault("an unknown date")
    val backupNewest = backup.days.maxOfOrNull { LocalDate.parse(it.date) }
    val size = "${backup.days.size} day${if (backup.days.size == 1) "" else "s"}"
    val phone = if (phoneNewest == null) {
        "This phone has nothing logged."
    } else {
        "This phone has $phoneDays day${if (phoneDays == 1) "" else "s"}, the newest ${phoneNewest.format(fullDate)}."
    }
    val missing = if (backupNewest != null && phoneNewest != null && phoneNewest.isAfter(backupNewest)) {
        " You would lose anything logged after ${backupNewest.format(fullDate)}."
    } else {
        ""
    }
    val scope = if (backup.settings != null) {
        "Restoring replaces everything on this phone, including settings."
    } else {
        "Restoring replaces everything logged on this phone, but keeps this phone's settings."
    }
    return "Backup made $made, with $size. $phone$missing $scope"
}

/**
 * Whether Today should mention backing up.
 *
 * Never on day one: a brand-new user is only nudged once there is a month of history to lose, and
 * anyone who has backed up is left alone for 30 days after.
 */
fun backupDue(
    lastBackup: Instant?,
    earliestLog: LocalDate?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (earliestLog == null) return false
    if (lastBackup != null) return Duration.between(lastBackup, now).toDays() >= BACKUP_NUDGE_DAYS
    return !earliestLog.isAfter(now.atZone(zone).toLocalDate().minusDays(BACKUP_NUDGE_DAYS))
}

/** Where the backup flow is. Each step is one dialog, so the order cannot be skipped. */
private sealed interface Step {
    data object Idle : Step
    data object ExportPassphrase : Step
    data class RestorePassphrase(val uri: Uri, val error: String? = null) : Step
    data class RestorePreview(val snapshot: BackupSnapshot, val text: String) : Step
}

private data class Status(val text: String, val error: Boolean)

/**
 * Encrypted export and restore.
 *
 * Offline means one lost phone from gone. The passphrase is asked for every time and never
 * stored — a key kept next to the data it protects is decoration.
 *
 * A restore needs no refresh hook: it writes through Room, and every screen follows the snapshot.
 *
 * Reworked on 5 Oct 2026 after the council review found no safeguard at either irreversible step:
 *  - **Export** asks for the passphrase twice, and only reports success once the written file has been
 *    read back and decrypted. One typo used to make a backup permanently useless, found out only on
 *    the day it was needed.
 *  - **Restore** picks the file first, then the passphrase, then shows what the backup holds against
 *    what the phone holds, before replacing anything. A wrong passphrase keeps the dialog open to
 *    retry instead of throwing the whole flow away.
 *  - **Undo restore** puts back the data and settings from just before, while this screen is open.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(context) }
    val manager = remember {
        BackupManager((context.applicationContext as CycleTrackerApp).database.logDao(), settings)
    }

    var step by remember { mutableStateOf<Step>(Step.Idle) }
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Status?>(null) }
    var lastBackup by remember { mutableStateOf(settings.lastBackupAt) }
    // The phone's data from just before a restore, so the restore can be taken back.
    var beforeRestore by remember { mutableStateOf<BackupSnapshot?>(null) }

    fun clearSecrets() {
        passphrase = ""
        repeat = ""
        show = false
    }

    // Whatever a restore changed, the reminder and widgets follow it at once.
    fun afterDataChanged() {
        ReminderScheduler.schedule(context)
        refreshWidgets(context)
    }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE),
    ) { uri ->
        val pass = passphrase.toCharArray()
        clearSecrets()
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching { manager.export(context, uri, pass) }.fold(
                {
                    lastBackup = settings.lastBackupAt
                    Status("Exported $it days, and checked that the file opens.", error = false)
                },
                { Status("Export failed: ${it.message}", error = true) },
            )
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) step = Step.RestorePassphrase(uri)
    }

    SettingsCard(
        "Backup",
        about = "Restoring replaces everything in the app; there is no merge, because two versions " +
            "of the same day have no correct answer. The passphrase is never stored.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Encrypted with a passphrase you choose. Nothing leaves the phone unless you put " +
                    "it somewhere yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                lastBackup?.let { "Last backup: ${it.atZone(ZoneId.systemDefault()).toLocalDate().format(fullDate)}" }
                    ?: "Not backed up yet",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { status = null; step = Step.ExportPassphrase }) { Text("Export") }
                TextButton(onClick = { status = null; openFile.launch(arrayOf("*/*")) }) { Text("Restore") }
            }
            status?.let {
                Text(
                    it.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            beforeRestore?.let { previous ->
                TextButton(onClick = {
                    scope.launch {
                        status = runCatching { manager.apply(previous) }.fold(
                            {
                                beforeRestore = null
                                afterDataChanged()
                                Status("Restore undone. The phone is back as it was.", error = false)
                            },
                            { Status("Could not undo: ${it.message}", error = true) },
                        )
                    }
                }) { Text("Undo restore") }
            }
        }
    }

    when (val current = step) {
        Step.Idle -> Unit

        Step.ExportPassphrase -> AlertDialog(
            onDismissRequest = { step = Step.Idle; clearSecrets() },
            title = { Text("Export backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Choose a passphrase. Without it the backup cannot be recovered, by you or " +
                            "anyone else, so it is asked for twice.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PassphraseField("Passphrase", passphrase, show, onShow = { show = !show }) { passphrase = it }
                    PassphraseField("Repeat it", repeat, show, onShow = { show = !show }) { repeat = it }
                    if (repeat.isNotEmpty() && repeat != passphrase) {
                        Text(
                            "These do not match yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.isNotEmpty() && passphrase == repeat,
                    onClick = {
                        step = Step.Idle
                        createFile.launch(BackupManager.suggestedFileName())
                    },
                ) { Text("Choose location") }
            },
            dismissButton = {
                TextButton(onClick = { step = Step.Idle; clearSecrets() }) { Text("Cancel") }
            },
        )

        is Step.RestorePassphrase -> AlertDialog(
            onDismissRequest = { step = Step.Idle; clearSecrets() },
            title = { Text("Open backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The passphrase this backup was made with. Nothing changes until you have " +
                            "seen what it holds.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PassphraseField("Passphrase", passphrase, show, onShow = { show = !show }) { passphrase = it }
                    current.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.isNotEmpty(),
                    onClick = {
                        val pass = passphrase.toCharArray()
                        scope.launch {
                            runCatching {
                                val backup = manager.read(context, current.uri, pass)
                                val phone = manager.snapshot().days
                                backup to restorePreview(
                                    backup,
                                    phoneDays = phone.size,
                                    phoneNewest = phone.maxOfOrNull { LocalDate.parse(it.date) },
                                )
                            }.fold(
                                { (backup, text) ->
                                    clearSecrets()
                                    step = Step.RestorePreview(backup, text)
                                },
                                // Stays open: a typo should cost a retype, not the whole flow.
                                {
                                    step = current.copy(
                                        error = "That passphrase did not open this file, or it is not a Luna backup.",
                                    )
                                },
                            )
                        }
                    },
                ) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { step = Step.Idle; clearSecrets() }) { Text("Cancel") }
            },
        )

        is Step.RestorePreview -> AlertDialog(
            onDismissRequest = { step = Step.Idle },
            title = { Text("Replace with this backup?") },
            text = { Text(current.text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    step = Step.Idle
                    scope.launch {
                        status = runCatching {
                            val previous = manager.snapshot()
                            val restored = manager.apply(current.snapshot)
                            beforeRestore = previous
                            restored
                        }.fold(
                            {
                                afterDataChanged()
                                Status("Restored $it days. You can undo this while this screen is open.", error = false)
                            },
                            { Status("Restore failed: ${it.message}", error = true) },
                        )
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { step = Step.Idle }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PassphraseField(
    label: String,
    value: String,
    show: Boolean,
    onShow: () -> Unit,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = onShow) { Text(if (show) "Hide" else "Show") } },
    )
}
