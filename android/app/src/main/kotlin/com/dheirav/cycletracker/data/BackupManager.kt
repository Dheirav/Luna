package com.dheirav.cycletracker.data

import android.content.Context
import android.net.Uri
import com.dheirav.cycletracker.core.BackupCodec
import com.dheirav.cycletracker.core.BackupDay
import com.dheirav.cycletracker.core.BackupException
import com.dheirav.cycletracker.core.BackupPrediction
import com.dheirav.cycletracker.core.BackupSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

/**
 * Moves the whole database in and out of an encrypted file the user controls.
 *
 * Restore replaces rather than merges. Merging two versions of the same day has no correct
 * answer, and quietly guessing one would put fabricated observations into the record.
 */
class BackupManager(private val dao: LogDao) {

    suspend fun export(context: Context, uri: Uri, passphrase: CharArray): Int =
        withContext(Dispatchers.IO) {
            val logs = dao.allLogsOnce()
            val symptoms = dao.allSymptomsOnce().groupBy { it.date }
            val tags = dao.allTagsOnce().groupBy { it.date }

            val snapshot = BackupSnapshot(
                exportedAt = Instant.now().toString(),
                days = logs.map { log ->
                    BackupDay(
                        date = log.date.toString(),
                        isBleeding = log.isBleeding,
                        flow = log.flow,
                        notes = log.notes,
                        source = log.source,
                        symptoms = symptoms[log.date].orEmpty().associate { it.key to it.value },
                        tags = tags[log.date].orEmpty().map { it.tag },
                    )
                },
                predictions = dao.allPredictionsForBackup().map { p ->
                    BackupPrediction(
                        madeOn = p.madeOn.toString(),
                        cycleStart = p.cycleStart.toString(),
                        predictedNextPeriod = p.predictedNextPeriod.toString(),
                        expectedCycleLength = p.expectedCycleLength,
                        variability = p.variability,
                    )
                },
            )

            val blob = BackupCodec.encrypt(BackupCodec.encode(snapshot), passphrase)
            context.contentResolver.openOutputStream(uri)?.use { it.write(blob) }
                ?: throw BackupException("Could not open the file for writing")

            snapshot.days.size
        }

    suspend fun import(context: Context, uri: Uri, passphrase: CharArray): Int =
        withContext(Dispatchers.IO) {
            val blob = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw BackupException("Could not open the file for reading")

            // Decrypt and parse *before* touching the database, so a bad passphrase or a corrupt
            // file cannot leave the user with neither their old data nor the new.
            val snapshot = BackupCodec.decode(BackupCodec.decrypt(blob, passphrase))

            val logs = snapshot.days.map { day ->
                DailyLogEntity(
                    date = LocalDate.parse(day.date),
                    isBleeding = day.isBleeding,
                    flow = day.flow,
                    notes = day.notes,
                    source = day.source,
                )
            }
            val symptoms = snapshot.days.flatMap { day ->
                day.symptoms.map { (k, v) -> SymptomValueEntity(LocalDate.parse(day.date), k, v) }
            }
            val tags = snapshot.days.flatMap { day ->
                day.tags.map { DayTagEntity(LocalDate.parse(day.date), it) }
            }

            val predictions = snapshot.predictions.map { p ->
                PredictionEntity(
                    madeOn = LocalDate.parse(p.madeOn),
                    cycleStart = LocalDate.parse(p.cycleStart),
                    predictedNextPeriod = LocalDate.parse(p.predictedNextPeriod),
                    expectedCycleLength = p.expectedCycleLength,
                    variability = p.variability,
                )
            }

            dao.replaceAll(logs, symptoms, tags, predictions)
            logs.size
        }

    companion object {
        fun suggestedFileName(): String = "cycle-backup-${LocalDate.now()}.cyc"
        const val MIME_TYPE = "application/octet-stream"
    }
}
