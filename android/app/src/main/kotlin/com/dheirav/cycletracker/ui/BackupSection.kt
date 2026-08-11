package com.dheirav.cycletracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dheirav.cycletracker.CycleTrackerApp
import com.dheirav.cycletracker.data.BackupManager
import kotlinx.coroutines.launch

private enum class Pending { NONE, EXPORT, IMPORT }

/**
 * Encrypted export and restore.
 *
 * Offline means one lost phone from gone. The passphrase is asked for every time and never
 * stored — a key kept next to the data it protects is decoration.
 */
@Composable
fun BackupSection(onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember {
        BackupManager((context.applicationContext as CycleTrackerApp).database.logDao())
    }

    var pending by remember { mutableStateOf(Pending.NONE) }
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE),
    ) { uri ->
        val pass = passphrase.toCharArray()
        passphrase = ""
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching { manager.export(context, uri, pass) }
                .fold({ "Exported $it days" }, { "Export failed: ${it.message}" })
        }
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val pass = passphrase.toCharArray()
        passphrase = ""
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = runCatching { manager.import(context, uri, pass) }
                .fold({ onRestored(); "Restored $it days" }, { "Restore failed: ${it.message}" })
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Backup", style = MaterialTheme.typography.titleSmall)
            Text(
                "Encrypted with a passphrase you choose. Nothing leaves the device unless you " +
                    "put it somewhere else yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pending = Pending.EXPORT }) { Text("Export") }
                TextButton(onClick = { pending = Pending.IMPORT }) { Text("Restore") }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (pending != Pending.NONE) {
        val importing = pending == Pending.IMPORT
        AlertDialog(
            onDismissRequest = { pending = Pending.NONE; passphrase = "" },
            title = { Text(if (importing) "Restore from backup" else "Export backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (importing) {
                            "This replaces everything currently in the app. There is no merge — " +
                                "two versions of the same day have no correct answer."
                        } else {
                            "Choose a passphrase. Without it the backup cannot be recovered, " +
                                "by you or anyone else."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.isNotEmpty(),
                    onClick = {
                        pending = Pending.NONE
                        status = null
                        if (importing) openFile.launch(arrayOf("*/*"))
                        else createFile.launch(BackupManager.suggestedFileName())
                    },
                ) { Text(if (importing) "Choose file" else "Choose location") }
            },
            dismissButton = {
                TextButton(onClick = { pending = Pending.NONE; passphrase = "" }) { Text("Cancel") }
            },
        )
    }
}
