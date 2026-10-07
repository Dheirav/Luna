package com.dheirav.cycletracker.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The summary as structure: sections of figures, rows, notes and flags.
 *
 * Built once by [ClinicalSummary.document] and drawn three ways: as plain text ([ClinicalSummary.text]),
 * as the in-app preview, and as a PDF. It used to exist only as text, so a better-looking preview or a
 * PDF would have meant parsing the text back apart or computing the content twice, and two copies of
 * a medical summary are two chances to disagree. One structure means every rendering says the same.
 */
data class SummaryDocument(
    val title: String,
    val generatedOn: LocalDate,
    /** Provenance, stated before anything else: self-reported, and what ESTIMATED means. */
    val preamble: List<String>,
    val sections: List<SummarySection>,
)

data class SummarySection(val title: String, val items: List<SummaryItem>)

sealed interface SummaryItem {
    /** A labelled figure: "Median observed length: 28 days". [note] qualifies the value. */
    data class Figure(
        val label: String,
        val value: String,
        val note: String? = null,
        val tags: List<String> = emptyList(),
        val indent: Int = 2,
    ) : SummaryItem

    /** One row of a list: a date or range, its detail, and tags such as ESTIMATED. */
    data class Row(
        val primary: String,
        val secondary: String? = null,
        val tags: List<String> = emptyList(),
        val indent: Int = 2,
    ) : SummaryItem

    /** A plain statement, a "none recorded", or a note such as a truncation. */
    data class Note(val text: String, val indent: Int = 2) : SummaryItem

    /** A flagged pattern with its evidence. */
    data class Flag(val headline: String, val detail: String) : SummaryItem

    /** A subheading inside a section, such as a phase name. */
    data class Group(val title: String) : SummaryItem
}

/**
 * A plain-text summary to hand to a clinician.
 *
 * The health flags tell the user something is "worth mentioning to a doctor" and then give them
 * no way to bring anything — the only export the app had was an encrypted blob that nothing but
 * this app can read. This closes that gap: text you can print, paste into an email, or hold up on
 * a screen in a ten-minute appointment.
 *
 * Written for someone with no context and very little time, which drives every choice here:
 *
 *  - **Observed and estimated are separated everywhere.** A doctor reading "12 cycles, median 28
 *    days" would reasonably assume twelve measurements. Backfill can manufacture that from two
 *    remembered dates, and a clinical decision resting on it would rest on nothing.
 *  - **No interpretation.** It reports lengths, spans and counts. It does not say what they mean;
 *    that is the clinician's job and the app is not qualified to pre-empt it.
 *  - **It states its own provenance.** Self-reported app data is not a medical record, and the
 *    summary says so rather than leaving a reader to assume otherwise.
 *  - **Every section appears, empty or not.** See [none] — silence is the one thing this document
 *    must never use to mean "nothing".
 */
object ClinicalSummary {

    private val short = DateTimeFormatter.ofPattern("d MMM yyyy")

    /** How many recent cycles and periods to list individually. */
    private const val DETAIL_ROWS = 12

    /** Pain at or above this level (Severe) counts as severe. */
    private const val SEVERE_PAIN = 3

    const val ESTIMATED = "ESTIMATED"
    const val IN_PROGRESS = "IN PROGRESS"

    /**
     * Says a section is empty, rather than leaving the section out.
     *
     * Every list here used to be wrapped in `if (isNotEmpty())`, so an absent section could mean
     * "nothing was recorded" or "this app does not track that" — indistinguishable to a reader with
     * no context, which is exactly who this document is written for. `absent ≠ zero` is the app's
     * own rule: the log form says "blank is recorded as unknown, never as zero" and the calendar says
     * "blank means unknown, not zero". This is the one artefact that leaves the phone and gets read
     * by someone making decisions, so it is the last place that rule should have been dropped.
     *
     * A heading with "none recorded" under it is a finding. A missing heading is an unanswered
     * question the reader does not know they should be asking.
     */
    private fun none(reason: String) = SummaryItem.Note(reason)

    /**
     * Notes when a list was cut short.
     *
     * `takeLast(DETAIL_ROWS)` is a display limit, and an unannounced one reads as "this is
     * everything". A clinician counting twelve cycles in a summary built from twenty would be
     * counting the wrong number.
     */
    private fun MutableList<SummaryItem>.truncationNote(shown: Int, total: Int) {
        if (total > shown) add(SummaryItem.Note("(most recent $shown of $total shown)"))
    }

    /** The summary as plain text. Same arguments as [document]; see there. */
    fun build(
        projection: Projection,
        today: LocalDate,
        expectedCycleLength: Int,
        flags: List<HealthFlag> = emptyList(),
        symptomSummaries: List<PhaseSymptomSummary> = emptyList(),
        anySymptomsLogged: Boolean = false,
        config: CycleConfig = CycleConfig.Default,
        symptomsByPhase: Map<Phase, List<PhaseSymptomSummary>> = emptyMap(),
        flowByDate: Map<LocalDate, FlowLevel> = emptyMap(),
        painByDate: Map<LocalDate, Int> = emptyMap(),
        lengthSource: LengthSource? = null,
    ): String = text(
        document(
            projection, today, expectedCycleLength, flags, symptomSummaries, anySymptomsLogged,
            config, symptomsByPhase, flowByDate, painByDate, lengthSource,
        ),
    )

    fun document(
        projection: Projection,
        today: LocalDate,
        expectedCycleLength: Int,
        flags: List<HealthFlag> = emptyList(),
        symptomSummaries: List<PhaseSymptomSummary> = emptyList(),
        /**
         * Whether any symptom was logged at all, regardless of whether it could be placed in a
         * phase. Only the caller knows this, and without it an empty [symptomSummaries] has two
         * possible meanings that a reader cannot distinguish. See the symptoms section below.
         */
        anySymptomsLogged: Boolean = false,
        config: CycleConfig = CycleConfig.Default,
        /**
         * Symptom summaries for every phase that has enough logged. Supersedes [symptomSummaries],
         * which covered only today's phase under a heading that read as if it covered them all.
         */
        symptomsByPhase: Map<Phase, List<PhaseSymptomSummary>> = emptyMap(),
        /** Logged flow per day, for the heaviest flow of each period. */
        flowByDate: Map<LocalDate, FlowLevel> = emptyMap(),
        /** Logged pain per day (0 None to 4 Extreme), for pain during periods. */
        painByDate: Map<LocalDate, Int> = emptyMap(),
        /** Where [expectedCycleLength] came from, so the working estimate is not read as measured. */
        lengthSource: LengthSource? = null,
    ): SummaryDocument {
        val sections = mutableListOf<SummarySection>()

        // -- overview -------------------------------------------------------
        val observed = projection.cycles.filter { it.source == Source.OBSERVED && it.length != null }
        val assumed = projection.cycles.filter { it.source == Source.ASSUMED && it.length != null }
        sections += SummarySection(
            "OVERVIEW",
            buildList {
                add(SummaryItem.Figure("Completed cycles recorded", "${observed.size + assumed.size}"))
                add(SummaryItem.Figure("of which observed", "${observed.size}", indent = 4))
                add(SummaryItem.Figure("of which estimated", "${assumed.size}", indent = 4))

                val lengths = observed.mapNotNull { it.length }.filter { it in config.plausibleCycleRange }
                if (lengths.size >= 2) {
                    add(SummaryItem.Figure("Observed cycle length", "${lengths.min()}–${lengths.max()} days"))
                }
                if (lengths.size >= 3) {
                    add(
                        SummaryItem.Figure(
                            "Median observed length",
                            "${CycleStats.roundHalfUp(CycleStats.median(lengths))} days",
                        ),
                    )
                    CycleStats.cycleLengthVariability(projection.cycles, config)?.let {
                        add(SummaryItem.Figure("Standard deviation", "%.1f days".format(it)))
                    }
                } else {
                    add(SummaryItem.Figure("Median observed length", "not enough observed cycles"))
                }
                add(SummaryItem.Figure("App's working estimate", "$expectedCycleLength days", note = sourceNote(lengthSource)))

                projection.periods.lastOrNull()?.let {
                    add(
                        SummaryItem.Figure(
                            "Most recent period began",
                            it.start.format(short),
                            tags = if (it.source == Source.ASSUMED) listOf(ESTIMATED) else emptyList(),
                        ),
                    )
                    // As a cycle day, the way the cycle list below and Today count it. "Days since 43"
                    // beside "44 days so far" were both right and read as a contradiction.
                    add(SummaryItem.Figure("Cycle day today", "${daysBetween(it.start, today) + 1}"))
                }
            },
        )

        // -- cycles ---------------------------------------------------------
        sections += SummarySection(
            "CYCLES (most recent last)",
            buildList {
                val completed = projection.cycles.filter { it.length != null }
                val recentCycles = completed.takeLast(DETAIL_ROWS)
                if (recentCycles.isEmpty()) {
                    add(none("No completed cycles recorded."))
                } else {
                    recentCycles.forEach { cycle ->
                        add(
                            SummaryItem.Row(
                                "${cycle.start.format(short)} to ${cycle.end?.format(short)}",
                                "${cycle.length} days",
                                tags = if (cycle.source == Source.ASSUMED) listOf(ESTIMATED) else emptyList(),
                            ),
                        )
                    }
                    truncationNote(recentCycles.size, completed.size)
                }
                projection.currentCycle?.takeIf { it.isOpen }?.let {
                    add(
                        SummaryItem.Row(
                            "${it.start.format(short)} to present",
                            "${daysBetween(it.start, today) + 1} days so far",
                            tags = listOf(IN_PROGRESS) + if (it.source == Source.ASSUMED) listOf(ESTIMATED) else emptyList(),
                        ),
                    )
                }
            },
        )

        // -- periods --------------------------------------------------------
        sections += SummarySection(
            "PERIODS (bleeding days per episode)",
            buildList {
                val recentPeriods = projection.periods.takeLast(DETAIL_ROWS)
                if (recentPeriods.isEmpty()) {
                    add(none("No periods recorded."))
                } else {
                    recentPeriods.forEach { period ->
                        val heaviest = period.days().mapNotNull { flowByDate[it] }.maxByOrNull { it.ordinal }
                        add(
                            SummaryItem.Row(
                                period.start.format(short),
                                periodLine(period) + ", " +
                                    (heaviest?.let { "heaviest flow ${it.name.lowercase()}" } ?: "flow not logged"),
                                // Any estimated day earns the tag, and the line says how many. Tagging
                                // only wholly estimated periods presented a logged first day with four
                                // backfilled ones as a five-day observed period (device review B1).
                                tags = if (period.estimatedDayCount > 0) listOf(ESTIMATED) else emptyList(),
                            ),
                        )
                    }
                    truncationNote(recentPeriods.size, projection.periods.size)
                }
            },
        )

        // -- pain during periods --------------------------------------------
        // Painful periods are among the commonest reasons to see someone, and pain was logged daily
        // and never reported. Counts only, over observed periods; no interpretation.
        sections += SummarySection(
            "PAIN DURING PERIODS",
            buildList {
                val observedPeriods = projection.periods.filter { it.source == Source.OBSERVED }.takeLast(DETAIL_ROWS)
                val painDays = observedPeriods.flatMap { p -> p.days().mapNotNull { painByDate[it] } }
                if (painDays.isEmpty()) {
                    add(none("No pain logged during a period."))
                } else {
                    val severe = painDays.count { it >= SEVERE_PAIN }
                    add(
                        SummaryItem.Note(
                            "Pain logged on ${painDays.size} period day${if (painDays.size == 1) "" else "s"}; " +
                                "severe or worse on $severe of ${painDays.size}.",
                        ),
                    )
                    observedPeriods.forEach { p ->
                        val logged = p.days().mapNotNull { painByDate[it] }
                        if (logged.isNotEmpty()) {
                            val worst = Symptom.PAIN.levelLabel(logged.max()) ?: logged.max().toString()
                            add(SummaryItem.Row(p.start.format(short), "worst: $worst, on ${plural(logged.size, "logged day")}"))
                        }
                    }
                }
            },
        )

        // -- spotting -------------------------------------------------------
        sections += SummarySection(
            "BLEEDING BETWEEN PERIODS",
            buildList {
                if (projection.spotting.isEmpty()) {
                    // Stated, not omitted: "none" here is a clinical finding, and a missing heading
                    // would read as the app not looking for it.
                    add(none("None recorded. The app derives these from bleeding logged outside a period."))
                } else {
                    val recentSpotting = projection.spotting.takeLast(DETAIL_ROWS)
                    recentSpotting.forEach { add(SummaryItem.Row(it.start.format(short), plural(it.spanDays, "day"))) }
                    truncationNote(recentSpotting.size, projection.spotting.size)
                }
            },
        )

        // -- flags ----------------------------------------------------------
        sections += SummarySection(
            "PATTERNS THE APP FLAGGED",
            if (flags.isEmpty()) {
                // The most important of the five. An empty flag list is good news, and omitting the
                // section turns good news into an unanswered question about whether anything was
                // checked.
                listOf(none("Nothing flagged. The app's checks ran and matched no pattern."))
            } else {
                flags.map { SummaryItem.Flag(it.headline, it.detail) }
            },
        )

        // -- symptoms -------------------------------------------------------
        sections += SummarySection(
            "SYMPTOMS BY PHASE",
            buildList {
                val phased = symptomsByPhase.filterValues { it.isNotEmpty() }
                if (phased.isNotEmpty()) {
                    add(SummaryItem.Note("Averages of what was logged, by phases worked out by the app. Scales run 0–4."))
                    Phase.entries.filter { it in phased }.forEach { phase ->
                        add(SummaryItem.Group(phase.name.lowercase().replaceFirstChar { it.uppercase() }))
                        phased.getValue(phase).forEach { add(SummaryItem.Note(symptomLine(it), indent = 4)) }
                    }
                } else if (symptomSummaries.isEmpty()) {
                    // Two different absences, and the caller is the only one who can tell them apart —
                    // hence [anySymptomsLogged]. "No symptoms logged" and "logged but unattributable"
                    // would mean the same blank otherwise, and they are not the same fact.
                    if (anySymptomsLogged) {
                        add(none("Symptoms were logged, but there is not enough cycle history to place them in a phase."))
                    } else {
                        add(none("No symptoms logged."))
                    }
                } else {
                    add(SummaryItem.Note("Averages of what was logged. Scales run 0–4."))
                    symptomSummaries.forEach { add(SummaryItem.Note(symptomLine(it))) }
                }
            },
        )

        return SummaryDocument(
            title = "Cycle summary",
            generatedOn = today,
            preamble = listOf(
                "Self-reported data recorded by the patient on their own phone. Not a medical record.",
                "Days marked ESTIMATED were extrapolated by the app, not observed or recalled.",
            ),
            sections = sections,
        )
    }

    /**
     * The document as plain text, laid out for a monospaced reader with dot leaders.
     *
     * The leader rule (at least three dots, values from column 31) reproduces the layout this text had
     * before the summary became structured, character for character, so nothing that already read it
     * sees a change.
     */
    fun text(document: SummaryDocument): String = buildString {
        appendLine(document.title.uppercase())
        appendLine("Generated ${document.generatedOn.format(short)} from the Luna app")
        appendLine()
        document.preamble.forEach { appendLine(it) }
        appendLine()

        document.sections.forEach { section ->
            appendLine(section.title)
            section.items.forEach { item ->
                when (item) {
                    is SummaryItem.Figure -> {
                        val pad = " ".repeat(item.indent)
                        val dots = ".".repeat(maxOf(3, 29 - item.indent - item.label.length))
                        appendLine(
                            "$pad${item.label} $dots ${item.value}" +
                                (item.note?.let { " ($it)" } ?: "") + tagText(item.tags),
                        )
                    }
                    is SummaryItem.Row -> appendLine(
                        " ".repeat(item.indent) + item.primary +
                            (item.secondary?.let { "  $it" } ?: "") + tagText(item.tags),
                    )
                    is SummaryItem.Note -> appendLine(" ".repeat(item.indent) + item.text)
                    is SummaryItem.Flag -> {
                        appendLine("  - ${item.headline}")
                        appendLine("      ${item.detail}")
                    }
                    is SummaryItem.Group -> appendLine("  ${item.title}:")
                }
            }
            appendLine()
        }
        appendLine("END OF SUMMARY")
    }

    private fun tagText(tags: List<String>) = tags.joinToString("") { "   $it" }

    private fun symptomLine(s: PhaseSymptomSummary): String =
        "${s.symptom.label}: ${s.label()} (%.1f) across ${s.daysObserved} days".format(s.phaseMean) +
            (s.elsewhereMean?.let { " vs %.1f in other phases".format(it) } ?: "")

    /** Where the working estimate came from, in a doctor's terms. */
    private fun sourceNote(source: LengthSource?): String? = when (source) {
        LengthSource.MEDIAN_OF_OBSERVED -> "median of observed cycles"
        LengthSource.USER_STATED -> "as stated by the patient"
        LengthSource.MEDIAN_WITH_ESTIMATES -> "mostly from estimated cycles"
        LengthSource.APP_DEFAULT -> "app default, not measured"
        null -> null
    }

    /**
     * "5 days, all bleeding", or the logged and estimated split when backfill is involved. A
     * doctor reading the row has to be able to tell which days the patient actually recorded.
     */
    private fun periodLine(period: Period): String {
        val days = plural(period.spanDays, "day")
        val bleeding = when {
            period.estimatedDayCount == 0 && period.bleedingDayCount == period.spanDays -> "all bleeding"
            period.estimatedDayCount == 0 -> "${period.bleedingDayCount} bleeding"
            period.loggedDayCount == 0 && period.bleedingDayCount == period.spanDays -> "all estimated"
            period.loggedDayCount == 0 -> "${period.bleedingDayCount} bleeding, all estimated"
            else -> "${period.loggedDayCount} logged, ${period.estimatedDayCount} estimated"
        }
        return "$days, $bleeding"
    }

    private fun plural(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

    private fun Period.days(): List<LocalDate> = (0 until spanDays).map { start.plusDays(it.toLong()) }
}
