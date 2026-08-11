package com.dheirav.cycletracker.data

import com.dheirav.cycletracker.core.CycleProjector
import com.dheirav.cycletracker.core.DayTag
import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Projection
import com.dheirav.cycletracker.core.Symptom
import java.time.LocalDate

/** One day as the UI edits it. Every field nullable — absent is never zero. */
data class DayEntry(
    val date: LocalDate,
    val isBleeding: Boolean = false,
    val flow: FlowLevel? = null,
    val symptoms: Map<Symptom, Int> = emptyMap(),
    val tags: Set<DayTag> = emptySet(),
    val notes: String = "",
)

class LogRepository(private val dao: LogDao) {

    suspend fun load(date: LocalDate): DayEntry {
        val log = dao.logFor(date)
        val symptoms = dao.symptomsFor(date).mapNotNull { row ->
            Symptom.byKey(row.key)?.let { it to row.value }
        }.toMap()
        val tags = dao.tagsFor(date).mapNotNull { DayTag.byKey(it.tag) }.toSet()


        return DayEntry(
            date = date,
            isBleeding = log?.isBleeding ?: false,
            flow = log?.flow?.let { runCatching { FlowLevel.valueOf(it) }.getOrNull() },
            symptoms = symptoms,
            tags = tags,
            notes = log?.notes.orEmpty(),
        )
    }

    /**
     * Writes the whole day atomically.
     *
     * Symptom and tag rows are deleted then reinserted rather than merged, so *unsetting* a
     * value genuinely removes it. Merging would leave a stale row behind, and a stale row is
     * indistinguishable from a real observation once it reaches the statistics.
     *
     * A day the user has emptied entirely is deleted outright — that keeps "never logged" and
     * "logged as nothing" from collapsing into the same state.
     */
    suspend fun save(entry: DayEntry) {
        val isEmpty = !entry.isBleeding &&
            entry.symptoms.isEmpty() &&
            entry.tags.isEmpty() &&
            entry.notes.isBlank()

        if (isEmpty) {
            dao.deleteDay(entry.date)
            return
        }

        // Anything the user touches by hand is an observation, never a backfill estimate.
        dao.upsertLog(
            DailyLogEntity(
                date = entry.date,
                isBleeding = entry.isBleeding,
                flow = entry.flow?.name,
                notes = entry.notes,
                source = "OBSERVED",
            ),
        )
        dao.deleteSymptoms(entry.date)
        dao.upsertSymptoms(entry.symptoms.map { (s, v) -> SymptomValueEntity(entry.date, s.key, v) })
        dao.deleteTags(entry.date)
        dao.upsertTags(entry.tags.map { DayTagEntity(entry.date, it.key) })
    }

    /** Rebuilds the full projection from the logs. Cheap, and the reason corrections just work. */
    suspend fun projection(): Pair<Projection, Set<LocalDate>> {
        val logs = dao.allLogsOnce()
        val bleeding = logs.filter { it.isBleeding }.map { it.date }
        val assumed = logs.filter { it.isBleeding && it.source == "ASSUMED" }.map { it.date }.toSet()
        return CycleProjector.project(bleeding, assumedDays = assumed) to bleeding.toSet()
    }
}
