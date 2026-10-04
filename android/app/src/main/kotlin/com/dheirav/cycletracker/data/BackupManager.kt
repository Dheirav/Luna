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
 * Moves the whole database, and since 5 Oct 2026 the settings, in and out of an encrypted file the
 * user controls.
 *
 * Restore replaces rather than merges. Merging two versions of the same day has no correct
 * answer, and quietly guessing one would put fabricated observations into the record.
 *
 * Restore is split into [read] and [apply] so the screen can show what a backup holds before it
 * replaces anything. It used to decrypt and replace in one step, so picking last year's file threw
 * away months of logs with nothing shown in between and no way back. [snapshot] exists so the
 * screen can keep the current data and undo a restore.
 */
class BackupManager(private val dao: LogDao, private val settings: Settings) {

    /** Everything a backup holds, read from the phone as it is now. */
    suspend fun snapshot(): BackupSnapshot = withContext(Dispatchers.IO) {
        val logs = dao.allLogsOnce()
        val symptoms = dao.allSymptomsOnce().groupBy { it.date }
        val tags = dao.allTagsOnce().groupBy { it.date }

        BackupSnapshot(
            exportedAt = Instant.now().toString(),
            days = logs.map { log ->
                BackupDay(
                    date = log.date.toString(),
                    isBleeding = log.isBleeding,
                    bleedingAnswered = log.bleedingAnswered || log.isBleeding,
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
            settings = settings.toBackup(),
        )
    }

    /**
     * Writes an encrypted backup, then reads it back and decrypts it before reporting success.
     *
     * The passphrase is unrecoverable by design, and a file that does not decrypt is discovered on
     * exactly the day it is needed. Reading it back proves the bytes on disk open with this
     * passphrase; only then is [Settings.lastBackupAt] set, so "last backup" never names a broken one.
     */
    suspend fun export(context: Context, uri: Uri, passphrase: CharArray): Int =
        withContext(Dispatchers.IO) {
            val snapshot = snapshot()
            val blob = BackupCodec.encrypt(BackupCodec.encode(snapshot), passphrase.copyOf())
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob) }
                ?: throw BackupException("Could not open the file for writing")

            val written = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw BackupException("The backup was written but could not be read back")
            val check = BackupCodec.decode(BackupCodec.decrypt(written, passphrase))
            if (check.days.size != snapshot.days.size) {
                throw BackupException("The backup did not read back complete")
            }

            settings.lastBackupAt = Instant.now()
            snapshot.days.size
        }

    /**
     * Decrypts and parses a backup without touching the phone's data, so it can be previewed.
     * A wrong passphrase or a damaged file fails here, with nothing changed.
     */
    suspend fun read(context: Context, uri: Uri, passphrase: CharArray): BackupSnapshot =
        withContext(Dispatchers.IO) {
            val blob = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw BackupException("Could not open the file for reading")
            BackupCodec.decode(BackupCodec.decrypt(blob, passphrase))
        }

    /** Replaces the phone's data, and its settings if the backup carries them. Returns days restored. */
    suspend fun apply(snapshot: BackupSnapshot): Int = withContext(Dispatchers.IO) {
        val logs = snapshot.days.map { day ->
            DailyLogEntity(
                date = LocalDate.parse(day.date),
                isBleeding = day.isBleeding,
                // Older backups carry no answer: bleeding is one, anything else stays unanswered.
                bleedingAnswered = day.bleedingAnswered ?: day.isBleeding,
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
        snapshot.settings?.let(settings::applyBackup)
        logs.size
    }

    companion object {
        fun suggestedFileName(): String = "cycle-backup-${LocalDate.now()}.cyc"
        const val MIME_TYPE = "application/octet-stream"
    }
}
