package com.dheirav.cycletracker.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * The persisted schema is deliberately tiny: **daily logs only**.
 *
 * Periods, cycles and spotting events are a projection over these rows (CYCLE_RULES.md §1.1),
 * recomputed on demand rather than stored. Storing them was the root cause of DEFECT 1 — the old
 * code wrote a cycle record per bleeding day and could never recover from a correction.
 *
 * Symptoms are key/value rows rather than fixed columns, so what gets tracked can change without
 * a migration. A missing row means "not logged", which is distinct from a logged zero.
 */

@Entity(tableName = "daily_logs")
data class DailyLogEntity(
    @PrimaryKey val date: LocalDate,
    @ColumnInfo(name = "is_bleeding") val isBleeding: Boolean = false,
    /** LIGHT / MEDIUM / HEAVY, or null when bleeding is logged without a flow level. */
    val flow: String? = null,
    val notes: String = "",
    /** Raw natural-language input, when logged that way. Extraction is lossy; the text is not. */
    @ColumnInfo(name = "raw_text") val rawText: String? = null,
    /**
     * OBSERVED or ASSUMED. Backfilled dates you extrapolated rather than remembered are ASSUMED,
     * and are excluded from variability so they cannot manufacture confidence (§3.2).
     */
    val source: String = "OBSERVED",
)

@Entity(
    tableName = "symptom_values",
    primaryKeys = ["date", "key"],
    indices = [Index("key")],
)
data class SymptomValueEntity(
    val date: LocalDate,
    /** e.g. "energy", "pain", "irritability", "anxiety", "low_mood". */
    val key: String,
    val value: Int,
)

@Entity(tableName = "day_tags", primaryKeys = ["date", "tag"])
data class DayTagEntity(
    val date: LocalDate,
    /** External factors that confound the cycle signal: travel, illness, alcohol, deadline. */
    val tag: String,
)

/**
 * A prediction, frozen as it was made. See [com.dheirav.cycletracker.core.PredictionRecord].
 *
 * The one table here that is **not** derivable from the daily logs, and the reason it must exist
 * rather than be recomputed: a prediction is a function of the data *as it stood that day*. Once
 * the user retro-logs a bleed the inputs are gone, and with them any chance of grading the app
 * honestly. Recomputing "what would I have said" from today's logs would score the engine against
 * knowledge it did not have.
 *
 * Keyed by [madeOn], so re-recording within a day overwrites — the day's best call, not its first.
 */
@Entity(tableName = "predictions")
data class PredictionEntity(
    @PrimaryKey @ColumnInfo(name = "made_on") val madeOn: LocalDate,
    @ColumnInfo(name = "cycle_start") val cycleStart: LocalDate,
    @ColumnInfo(name = "predicted_next_period") val predictedNextPeriod: LocalDate,
    @ColumnInfo(name = "expected_cycle_length") val expectedCycleLength: Int,
    /** Null when there were too few observed cycles — kept distinct from a variability of zero. */
    val variability: Double?,
)

class Converters {
    @TypeConverter
    fun toDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun fromDate(date: LocalDate?): String? = date?.toString()
}

@Dao
interface LogDao {

    @Query("SELECT date FROM daily_logs WHERE is_bleeding = 1 ORDER BY date")
    fun bleedingDays(): Flow<List<LocalDate>>

    @Query("SELECT date FROM daily_logs WHERE is_bleeding = 1 ORDER BY date")
    suspend fun bleedingDaysOnce(): List<LocalDate>

    @Query("SELECT * FROM daily_logs ORDER BY date")
    fun allLogs(): Flow<List<DailyLogEntity>>

    @Query("SELECT * FROM daily_logs ORDER BY date")
    suspend fun allLogsOnce(): List<DailyLogEntity>

    @Query("SELECT * FROM daily_logs WHERE date = :date")
    suspend fun logFor(date: LocalDate): DailyLogEntity?

    @Query("SELECT COUNT(*) FROM daily_logs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLog(log: DailyLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLogs(logs: List<DailyLogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSymptoms(values: List<SymptomValueEntity>)

    @Query("SELECT * FROM symptom_values WHERE date = :date")
    suspend fun symptomsFor(date: LocalDate): List<SymptomValueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(tags: List<DayTagEntity>)

    @Query("SELECT * FROM day_tags WHERE date = :date")
    suspend fun tagsFor(date: LocalDate): List<DayTagEntity>

    @Query("DELETE FROM symptom_values WHERE date = :date")
    suspend fun deleteSymptoms(date: LocalDate)

    @Query("DELETE FROM day_tags WHERE date = :date")
    suspend fun deleteTags(date: LocalDate)

    @Query("DELETE FROM daily_logs WHERE date = :date")
    suspend fun deleteLog(date: LocalDate)

    /** Removes a day entirely, so "never logged" stays distinct from "logged as nothing". */
    @Transaction
    suspend fun deleteDay(date: LocalDate) {
        deleteSymptoms(date)
        deleteTags(date)
        deleteLog(date)
    }

    // -- predictions -------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrediction(prediction: PredictionEntity)

    @Query("SELECT * FROM predictions ORDER BY made_on")
    suspend fun allPredictionsOnce(): List<PredictionEntity>

    @Query("SELECT * FROM predictions WHERE made_on = :date")
    suspend fun predictionFor(date: LocalDate): PredictionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPredictions(predictions: List<PredictionEntity>)

    @Query("SELECT * FROM predictions ORDER BY made_on")
    suspend fun allPredictionsForBackup(): List<PredictionEntity>

    @Query("DELETE FROM predictions")
    suspend fun clearPredictions()

    // -- backup ----------------------------------------------------------

    @Query("SELECT * FROM symptom_values")
    suspend fun allSymptomsOnce(): List<SymptomValueEntity>

    @Query("SELECT * FROM day_tags")
    suspend fun allTagsOnce(): List<DayTagEntity>

    @Query("DELETE FROM daily_logs")
    suspend fun clearLogs()

    @Query("DELETE FROM symptom_values")
    suspend fun clearSymptoms()

    @Query("DELETE FROM day_tags")
    suspend fun clearTags()

    /**
     * Restore is all-or-nothing. A half-applied backup would be worse than a failed one, so this
     * runs in a single transaction — a crash mid-restore rolls back to the previous data.
     *
     * Predictions are replaced along with everything else, including when the backup carries none.
     * Keeping the old ledger against restored logs would score predictions about one history using
     * the periods of another — a number that looks like measured accuracy and is not.
     */
    @Transaction
    suspend fun replaceAll(
        logs: List<DailyLogEntity>,
        symptoms: List<SymptomValueEntity>,
        tags: List<DayTagEntity>,
        predictions: List<PredictionEntity> = emptyList(),
    ) {
        clearSymptoms()
        clearTags()
        clearLogs()
        clearPredictions()
        upsertLogs(logs)
        upsertSymptoms(symptoms)
        upsertTags(tags)
        upsertPredictions(predictions)
    }
}

@Database(
    entities = [
        DailyLogEntity::class,
        SymptomValueEntity::class,
        DayTagEntity::class,
        PredictionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        /**
         * Adds the predictions table. Purely additive — no existing row is read or rewritten.
         *
         * Written by hand rather than relying on `fallbackToDestructiveMigration`, which would
         * delete the user's logs on any schema change. For a tracker whose entire value is a
         * multi-year history that is not a fallback, it is data loss with a friendly name.
         *
         * The SQL must match Room's expectation exactly or it throws on first open. Verify
         * against `app/schemas/…/2.json` after changing the entity, not by eye.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `predictions` (
                        `made_on` TEXT NOT NULL,
                        `cycle_start` TEXT NOT NULL,
                        `predicted_next_period` TEXT NOT NULL,
                        `expected_cycle_length` INTEGER NOT NULL,
                        `variability` REAL,
                        PRIMARY KEY(`made_on`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
