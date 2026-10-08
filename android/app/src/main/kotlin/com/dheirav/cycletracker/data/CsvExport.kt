package com.dheirav.cycletracker.data

import com.dheirav.cycletracker.core.DayTag
import com.dheirav.cycletracker.core.Symptom

/**
 * Every logged day as a plain spreadsheet, for the person's own records.
 *
 * Unlike the backup it is readable by anything, and unlike the doctor summary it is the raw days
 * rather than a digest. Words, not codes: a symptom is written as its anchor word ("Low"), the same
 * one the log form showed, because a stored 1 means nothing six months later. Blank is blank: a
 * question not answered is an empty cell, never "no" or 0 (rule 2), and estimated days say so.
 */
object CsvExport {

    const val MIME_TYPE = "text/csv"

    /** The symptom columns, in the order the log form asks them. */
    private val SYMPTOMS = Symptom.entries.sortedWith(compareBy({ !it.isCore }, { it.ordinal }))

    fun build(logs: List<DailyLogEntity>, symptoms: List<SymptomValueEntity>, tags: List<DayTagEntity>): String {
        val symptomsByDate = symptoms.groupBy { it.date }
        val tagsByDate = tags.groupBy { it.date }
        val header = listOf("Date", "Bleeding", "Flow", "Recorded by") + SYMPTOMS.map { it.label } + listOf("Tags", "Notes")
        val rows = logs.sortedBy { it.date }.map { log ->
            val values = symptomsByDate[log.date].orEmpty().associate { it.key to it.value }
            listOf(
                log.date.toString(),
                bleeding(log),
                log.flow?.takeIf { log.isBleeding }?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty(),
                if (log.source == "ASSUMED") "Estimated by Luna" else "You",
            ) + SYMPTOMS.map { s -> values[s.key]?.let { s.levelLabel(it) ?: it.toString() }.orEmpty() } + listOf(
                tagsByDate[log.date].orEmpty().mapNotNull { DayTag.byKey(it.tag)?.label }.sorted().joinToString("; "),
                log.notes,
            )
        }
        // CRLF line ends, which RFC 4180 specifies and every spreadsheet reads.
        return (listOf(header) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { escape(it) } }
    }

    private fun bleeding(log: DailyLogEntity): String = when {
        log.isBleeding -> "Yes"
        !log.bleedingAnswered -> ""
        log.flow == SPOTTING_FLOW -> "Spotting"
        else -> "No"
    }

    /** Quoted only when it has to be: a comma, a quote, or a line break inside. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
