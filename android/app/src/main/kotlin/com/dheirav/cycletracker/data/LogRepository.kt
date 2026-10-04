package com.dheirav.cycletracker.data

import com.dheirav.cycletracker.core.DayTag
import com.dheirav.cycletracker.core.FlowLevel
import com.dheirav.cycletracker.core.Source
import com.dheirav.cycletracker.core.Symptom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** One day as the UI edits it. Every field nullable — absent is never zero. */
data class DayEntry(
    val date: LocalDate,
    val isBleeding: Boolean = false,
    /**
     * Whether the bleeding question was answered at all. With [isBleeding] this makes three states:
     * not answered, no, and yes. "No" and "not answered" used to be one stored value, so a day
     * answered "No bleeding" with nothing else counted as empty and was deleted on Save, and a
     * symptom-only day was shown as "No bleeding", a claim the person never made.
     */
    val bleedingAnswered: Boolean = false,
    val flow: FlowLevel? = null,
    val symptoms: Map<Symptom, Int> = emptyMap(),
    val tags: Set<DayTag> = emptySet(),
    val notes: String = "",
    /**
     * Where the bleeding claim came from. [Source.ASSUMED] means backfill extrapolated it and
     * nobody ever observed it — the UI must say so before the user builds on it.
     */
    val source: Source = Source.OBSERVED,
    /** False when this date has no row at all, which is distinct from a row saying "no bleeding". */
    val exists: Boolean = false,
) {
    /** Null when not answered. A bleeding row counts as answered even from before the flag existed. */
    val bleeding: Boolean? get() = if (bleedingAnswered || isBleeding) isBleeding else null

    /** Nothing at all recorded, so the row is deleted rather than kept. An answered "No" is not this. */
    val isEmpty: Boolean
        get() = bleeding == null && symptoms.isEmpty() && tags.isEmpty() && notes.isBlank()

    /** Sets the bleeding answer: true, false, or null to clear it. Flow only survives a "yes". */
    fun answeringBleeding(answer: Boolean?): DayEntry = copy(
        isBleeding = answer == true,
        bleedingAnswered = answer != null,
        flow = if (answer == true) flow else null,
    )
}

/**
 * One logged day, as the history screen needs it.
 *
 * Carries the symptoms and tags as well as the cell state, because logged symptoms had nowhere to
 * be seen: the calendar showed a dot meaning "something here" and the values themselves went into
 * the database and never came back out. Logging for weeks with nothing to show for it is how the
 * habit dies, and adherence is the constraint everything else depends on (rule 4).
 *
 * Cheap enough to hold every logged day in memory — a decade of daily logs is a few thousand rows.
 */
data class DaySummary(
    val isBleeding: Boolean,
    val isAssumed: Boolean,
    val flow: FlowLevel?,
    val hasNotes: Boolean,
    val notes: String = "",
    val symptoms: Map<Symptom, Int> = emptyMap(),
    val tags: Set<DayTag> = emptySet(),
    /** See [DayEntry.bleedingAnswered]. A bleeding day is always an answer. */
    val bleedingAnswered: Boolean = isBleeding,
) {
    /** Whether there is anything to show beyond the bleeding state. */
    val hasDetail: Boolean get() = symptoms.isNotEmpty() || tags.isNotEmpty() || notes.isNotBlank()
}

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
            bleedingAnswered = log?.let { it.bleedingAnswered || it.isBleeding } ?: false,
            flow = log?.flow?.let { runCatching { FlowLevel.valueOf(it) }.getOrNull() },
            symptoms = symptoms,
            tags = tags,
            notes = log?.notes.orEmpty(),
            source = if (log?.source == "ASSUMED") Source.ASSUMED else Source.OBSERVED,
            exists = log != null,
        )
    }

    /**
     * Everything logged, for the history calendar and the log list beneath it.
     *
     * Presence of a row *is* "this day was logged" — [save] deletes the row outright when a day is
     * emptied. Three flows are combined rather than one joined query so that editing a symptom
     * re-emits without touching the daily-log table.
     */
    fun summaries(): Flow<Map<LocalDate, DaySummary>> =
        combine(dao.allLogs(), dao.allSymptoms(), dao.allDayTags()) { logs, symptoms, tags ->
            val symptomsByDate = symptoms.groupBy { it.date }
            val tagsByDate = tags.groupBy { it.date }

            logs.associate { log ->
                log.date to DaySummary(
                    isBleeding = log.isBleeding,
                    bleedingAnswered = log.bleedingAnswered || log.isBleeding,
                    isAssumed = log.source == "ASSUMED",
                    flow = log.flow?.let { runCatching { FlowLevel.valueOf(it) }.getOrNull() },
                    hasNotes = log.notes.isNotBlank(),
                    notes = log.notes,
                    symptoms = symptomsByDate[log.date].orEmpty()
                        .mapNotNull { row -> Symptom.byKey(row.key)?.let { it to row.value } }
                        .toMap(),
                    tags = tagsByDate[log.date].orEmpty()
                        .mapNotNull { DayTag.byKey(it.tag) }
                        .toSet(),
                )
            }
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
     *
     * [confirmed] promotes a backfilled day to [Source.OBSERVED]. It exists because the promotion
     * must be a deliberate act: see [sourceFor].
     */
    suspend fun save(entry: DayEntry, confirmed: Boolean = false) {
        write(entry, sourceFor(entry, confirmed))
    }

    /**
     * Puts a day back exactly as it was, for Undo.
     *
     * Not [save]: that decides the source afresh, and undoing an edit to an estimated day would then
     * find the edited row, see it as observed, and keep it observed, so Undo would quietly turn a guess
     * into a measurement. The previous state carries its own source, and this writes it as it was. A
     * day that did not exist before is deleted again.
     */
    suspend fun restore(previous: DayEntry) {
        if (!previous.exists) dao.deleteDay(previous.date) else write(previous, previous.source)
    }

    private suspend fun write(entry: DayEntry, source: Source) {
        // An answered "No bleeding" is a record, not an empty day. See DayEntry.isEmpty.
        if (entry.isEmpty) {
            dao.deleteDay(entry.date)
            return
        }

        dao.replaceDay(
            log = DailyLogEntity(
                date = entry.date,
                isBleeding = entry.isBleeding,
                bleedingAnswered = entry.bleeding != null,
                flow = entry.flow?.name,
                notes = entry.notes,
                source = source.name,
            ),
            symptoms = entry.symptoms.map { (s, v) -> SymptomValueEntity(entry.date, s.key, v) },
            tags = entry.tags.map { DayTagEntity(entry.date, it.key) },
        )
    }

    /**
     * Decides whether a saved day counts as observed.
     *
     * The rule that matters: **editing a backfilled day does not, by itself, make it real.** The
     * 11 extrapolated periods in the seed are guesses at a uniform 28 days. If adding a symptom or
     * a note to one of those days silently flipped it to [Source.OBSERVED], the guess would start
     * counting toward `cycleLengthVariability` — and §3.2 excludes assumed data precisely so a
     * synthetic uniform sequence cannot manufacture zero variability and maximum confidence.
     *
     * So an assumed day stays assumed unless the user says something about the bleeding itself:
     * changing the bleeding flag, or explicitly confirming the date was right.
     */
    private suspend fun sourceFor(entry: DayEntry, confirmed: Boolean): Source {
        if (confirmed) return Source.OBSERVED
        val existing = dao.logFor(entry.date) ?: return Source.OBSERVED
        if (existing.source != "ASSUMED") return Source.OBSERVED
        return if (existing.isBleeding == entry.isBleeding) Source.ASSUMED else Source.OBSERVED
    }

    /**
     * Logs every day from [start] to [end] as bleeding, keeping whatever else each day already holds.
     *
     * For the walkthrough's "When did your last period start?", where the alternative was a tap per
     * day in History, about fifteen per period. Each day is saved as an observation, because the
     * person is stating it, and each goes through [save] so an existing day's symptoms and notes
     * survive.
     */
    suspend fun logPeriod(start: LocalDate, end: LocalDate) {
        var day = start
        while (!day.isAfter(end)) {
            save(load(day).answeringBleeding(true))
            day = day.plusDays(1)
        }
    }

    /** Answers the bleeding question for several days at once, keeping whatever else each holds. */
    suspend fun answerBleeding(days: List<LocalDate>, bleeding: Boolean) {
        days.forEach { day -> save(load(day).answeringBleeding(bleeding)) }
    }

    /** Throws a backfilled guess away entirely, rather than leaving it to pollute the statistics. */
    suspend fun discard(date: LocalDate) = dao.deleteDay(date)
}
