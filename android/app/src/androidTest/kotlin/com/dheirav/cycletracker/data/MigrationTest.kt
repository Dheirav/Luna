package com.dheirav.cycletracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Room migrations, replayed against real SQLite.
 *
 * **Why this is the highest-consequence test in the project.** `CycleTrackerApp` opens the database
 * with no `fallbackToDestructiveMigration`, deliberately: a tracker whose value is a multi-year
 * history must never silently wipe it to recover from a schema mistake. The consequence is that a
 * broken migration does not degrade — **it stops the app opening at all**, with the user's history
 * intact and unreachable, and the only remedy is shipping a fix.
 *
 * 1→2 was verified by hand on the Redmi against a real v1 database on 2026-08-11. That was good
 * evidence and it was not repeatable, which is what this replaces.
 *
 * **This needs a device or emulator and is therefore not in CI.** `MigrationTestHelper` replays an
 * exported schema through the real SQLite implementation, which cannot be done on the JVM. Run it
 * with `./gradlew :app:connectedDebugAndroidTest` while a device is attached. Everything else in the
 * suite is emulator-free on purpose; this is the one exception, and it is worth the exception.
 *
 * The exported schema JSON in `app/schemas/` is what makes this possible — it is checked in, and it
 * must stay checked in. `validateMigration` compares the migrated database against the schema Room
 * expects for that version, so a migration that runs without error but produces the wrong shape still
 * fails here. That is the failure mode a hand-check on a phone is worst at spotting.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val databaseClass = TrackerDatabase::class.java

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        databaseClass,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * 1→2 adds the `predictions` table and must leave everything else untouched.
     *
     * Rows are written *before* migrating and read back after, because "the migration ran" and "the
     * user's history survived it" are different claims, and only the second one matters. A migration
     * that recreated `daily_logs` correctly and dropped its contents would pass a schema check.
     */
    @Test
    fun migrate1To2_addsPredictionsAndKeepsExistingRows() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO daily_logs (date, is_bleeding, flow, notes, raw_text, source)
                VALUES ('2025-03-01', 1, 'MEDIUM', 'a note', NULL, 'OBSERVED')
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO symptom_values (date, key, value) VALUES ('2025-03-01', 'energy', 2)",
            )
            db.execSQL("INSERT INTO day_tags (date, tag) VALUES ('2025-03-01', 'travel')")
        }

        // validateMigration = true: the result is checked against schemas/2.json, not merely executed.
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, TrackerDatabase.MIGRATION_1_2)

        db.query("SELECT date, is_bleeding, flow, notes, source FROM daily_logs").use { c ->
            assertTrue("the logged day did not survive the migration", c.moveToFirst())
            assertEquals("2025-03-01", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals("MEDIUM", c.getString(2))
            assertEquals("a note", c.getString(3))
            assertEquals("OBSERVED", c.getString(4))
            assertEquals("daily_logs gained or lost rows", 1, c.count)
        }

        db.query("SELECT key, value FROM symptom_values").use { c ->
            assertTrue("the symptom did not survive", c.moveToFirst())
            assertEquals("energy", c.getString(0))
            assertEquals(2, c.getInt(1))
        }

        db.query("SELECT tag FROM day_tags").use { c ->
            assertTrue("the day tag did not survive", c.moveToFirst())
            assertEquals("travel", c.getString(0))
        }

        // The point of the migration: the ledger's table now exists and is empty rather than absent.
        db.query("SELECT count(*) FROM predictions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("predictions should exist and start empty", 0, c.getInt(0))
        }
    }

    /**
     * A v1 database with **no** rows migrates too.
     *
     * A fresh install that never logged anything before upgrading is a real case, and an empty table
     * is exactly where a migration written against populated test data can behave differently.
     */
    @Test
    fun migrate1To2_worksOnAnEmptyDatabase() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, TrackerDatabase.MIGRATION_1_2)

        db.query("SELECT count(*) FROM predictions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    /**
     * 2→3 adds `bleeding_answered`, so "no bleeding" and "not answered" stop being one value.
     *
     * The rule under test is the decided one (docs/COUNCIL_REVIEW.md, D2): a bleeding day becomes
     * answered, and a non-bleeding day stays unanswered, because nothing in v2 recorded whether a
     * stored 0 was a deliberate "No". And, as with 1→2, the rows themselves must survive intact.
     */
    @Test
    fun migrate2To3_marksBleedingDaysAnsweredAndLeavesTheRestUnanswered() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO daily_logs (date, is_bleeding, flow, notes, raw_text, source)
                VALUES ('2025-03-01', 1, 'MEDIUM', 'a note', NULL, 'OBSERVED'),
                       ('2025-03-10', 0, NULL, '', NULL, 'OBSERVED')
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO symptom_values (date, key, value) VALUES ('2025-03-10', 'energy', 1)",
            )
        }

        // validateMigration = true: the result is checked against schemas/3.json.
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, true, TrackerDatabase.MIGRATION_2_3,
        )

        db.query("SELECT date, is_bleeding, bleeding_answered, flow, notes FROM daily_logs ORDER BY date").use { c ->
            assertEquals("daily_logs gained or lost rows", 2, c.count)

            assertTrue(c.moveToFirst())
            assertEquals("2025-03-01", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals("a bleeding day is an answer", 1, c.getInt(2))
            assertEquals("MEDIUM", c.getString(3))
            assertEquals("a note", c.getString(4))

            assertTrue(c.moveToNext())
            assertEquals("2025-03-10", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertEquals("a stored 0 was never known to be a deliberate No", 0, c.getInt(2))
        }

        db.query("SELECT key, value FROM symptom_values").use { c ->
            assertTrue("the symptom did not survive", c.moveToFirst())
            assertEquals("energy", c.getString(0))
        }
    }

    /** A v1 database reaches v3 through both migrations in order, as an old install would. */
    @Test
    fun migrate1To3_runsBothInOrder() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO daily_logs (date, is_bleeding, flow, notes, raw_text, source)
                VALUES ('2025-03-01', 1, NULL, '', NULL, 'ASSUMED')
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 3, true, TrackerDatabase.MIGRATION_1_2, TrackerDatabase.MIGRATION_2_3,
        )

        db.query("SELECT bleeding_answered, source FROM daily_logs").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals("an estimated day must stay estimated", "ASSUMED", c.getString(1))
        }
    }

    /**
     * Migrating twice is not attempted; migrating an already-current database is.
     *
     * Room decides which migrations to run from the recorded user_version, so the guard that matters
     * is that opening a v3 database at v3 needs no migration at all. If this ever starts failing, some
     * migration has begun mutating state it does not own.
     */
    @Test
    fun aCurrentDatabaseNeedsNoMigration() {
        helper.createDatabase(TEST_DB, 3).close()

        // No migrations supplied. Room must be satisfied with the schema as it stands.
        helper.runMigrationsAndValidate(TEST_DB, 3, true)
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
