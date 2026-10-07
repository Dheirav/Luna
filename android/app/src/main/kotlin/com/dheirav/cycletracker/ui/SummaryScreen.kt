package com.dheirav.cycletracker.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dheirav.cycletracker.core.ClinicalSummary
import com.dheirav.cycletracker.core.SummaryDocument
import com.dheirav.cycletracker.core.SummaryItem
import com.dheirav.cycletracker.data.SummaryPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter

private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy")

/**
 * The doctor summary as a page to read, and the way out of the app as a PDF.
 *
 * It was a dialog of monospaced text, laid out for a terminal: dot leaders, capitalised headings and
 * "ESTIMATED" trailing off the end of lines. Asked on 5 Oct 2026 to be much better looking and to go
 * out as a PDF. It now draws the same [SummaryDocument] the PDF and the plain text are made from, so
 * the three cannot say different things. Estimated and in-progress items are labelled in words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val document by produceState<SummaryDocument?>(null) {
        value = withContext(Dispatchers.IO) { buildSummaryDocument(context) }
    }

    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val doc = document
        if (uri == null || doc == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { SummaryPdf().write(doc, it) }
                        ?: error("Could not open the file for writing")
                }
            }
            Toast.makeText(
                context,
                result.fold({ "Summary saved as PDF" }, { "Could not save: ${it.message}" }),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackBar(title = "Summary for a doctor")

            val doc = document
            if (doc == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                HeaderCard(doc)
                doc.sections.forEach { SectionCard(it) }
            }
        }

        // Pinned, like the log form's Save: the reason to open this screen is to send it somewhere.
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "A PDF is not encrypted. Share it only where you are happy for it to be read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = document != null && !busy,
                        onClick = {
                            val doc = document ?: return@Button
                            busy = true
                            scope.launch {
                                runCatching { sharePdf(context, withContext(Dispatchers.IO) { pdfInCache(context, doc) }) }
                                    .onFailure {
                                        Toast.makeText(context, "Could not make the PDF: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("Share PDF") }
                    OutlinedButton(
                        enabled = document != null && !busy,
                        onClick = { document?.let { savePdf.launch("cycle-summary-${it.generatedOn}.pdf") } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("Save PDF") }
                }
                TextButton(
                    enabled = document != null,
                    onClick = { document?.let { shareText(context, ClinicalSummary.text(it)) } },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Share as plain text") }
            }
        }
    }
}

@Composable
private fun HeaderCard(doc: SummaryDocument) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(doc.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "Generated ${doc.generatedOn.format(longDate)} from the Luna app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    doc.preamble.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionCard(section: com.dheirav.cycletracker.core.SummarySection) {
    // "CYCLES (most recent last)" reads as a heading "Cycles" with the qualifier quietly beside it.
    val parts = section.title.split(" (", limit = 2)
    val title = parts[0].lowercase().replaceFirstChar { it.uppercase() }
    val qualifier = parts.getOrNull(1)?.removeSuffix(")")

    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                qualifier?.let {
                    Text(
                        "  $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            section.items.forEach { SummaryItemView(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryItemView(item: SummaryItem) {
    when (item) {
        is SummaryItem.Figure -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Only the label is indented. Indenting the whole row moved the value column too, so the
            // "of which" counts sat 20px right of the total above them (device review m6); the PDF
            // already kept one value column.
            Text(
                item.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = ((item.indent - 2).coerceAtLeast(0) * 6).dp),
            )
            Column(modifier = Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                item.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Tags(item.tags)
            }
        }

        is SummaryItem.Row -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The date takes the wider share: "23 Sept 2025 to 20 Oct 2025" wrapped mid-range at
                // an even split, while the detail beside it is short.
                // A single date ("9 Mar 2026") does not need that share, and giving it one squeezed the
                // period detail beside it onto three lines (device review p8).
                val dateWeight = if (item.primary.length > 14) 1.5f else 0.8f
                Text(item.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(dateWeight))
                item.secondary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Tags(item.tags)
        }

        is SummaryItem.Note -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = ((item.indent - 2) * 6).dp),
        )

        is SummaryItem.Group -> Text(
            item.title,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )

        is SummaryItem.Flag -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

/** ESTIMATED and IN PROGRESS as outlined labels, in words, never colour alone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(tags: List<String>) {
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(50),
                border = BorderStroke(
                    1.dp,
                    if (tag == ClinicalSummary.IN_PROGRESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                ),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Writes the PDF to the one cache folder the FileProvider exposes, emptying it first.
 *
 * Emptied every time so a summary never outlives the next one: it is health data, and the cache is
 * the one place an old copy could otherwise sit unnoticed.
 */
private fun pdfInCache(context: Context, doc: SummaryDocument): File {
    val dir = File(context.cacheDir, "summary").apply {
        deleteRecursively()
        mkdirs()
    }
    val file = File(dir, "cycle-summary-${doc.generatedOn}.pdf")
    file.outputStream().use { SummaryPdf().write(doc, it) }
    return file
}

private fun sharePdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Cycle summary")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The grant has to ride on the chooser's clip data too, or some targets cannot open the file.
    send.clipData = ClipData.newRawUri("Cycle summary", uri)
    context.startActivity(Intent.createChooser(send, "Share summary"))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Cycle summary")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share summary"))
}
