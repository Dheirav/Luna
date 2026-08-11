package com.dheirav.cycletracker.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * One-shot import of spec/seed_periods.json (vendored into assets).
 *
 * Backfill is the highest-leverage data available: at one user you accumulate roughly thirteen
 * cycles a year, so starting cold means months before anything means anything.
 *
 * Periods marked `assumed` were extrapolated, not remembered. That flag rides through to the
 * projection so a uniform synthetic sequence cannot report zero variability and maximum
 * confidence — see CYCLE_RULES.md §3.2.
 */
class SeedImporter(private val dao: LogDao) {

    @Serializable
    private data class SeedFile(val periods: List<SeedPeriod>)

    @Serializable
    private data class SeedPeriod(val start: String, val spanDays: Int, val source: String)

    /** Imports only into an empty database, so it can be called on every launch without harm. */
    suspend fun importIfEmpty(context: Context, assetName: String = "seed_periods.json"): Int {
        if (dao.count() > 0) return 0

        val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val seed = json.decodeFromString<SeedFile>(text)

        val rows = seed.periods.flatMap { period ->
            val start = LocalDate.parse(period.start)
            (0 until period.spanDays).map { offset ->
                DailyLogEntity(
                    date = start.plusDays(offset.toLong()),
                    isBleeding = true,
                    source = period.source.uppercase(),
                    notes = if (period.source == "assumed") "backfill estimate" else "",
                )
            }
        }

        dao.upsertLogs(rows)
        return rows.size
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
