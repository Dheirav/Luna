package com.dheirav.cycletracker.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

    private val long = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val short = DateTimeFormatter.ofPattern("d MMM yyyy")

    /** How many recent cycles and periods to list individually. */
    private const val DETAIL_ROWS = 12

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
    private fun StringBuilder.none(reason: String) = appendLine("  $reason")

    /**
     * Notes when a list was cut short.
     *
     * `takeLast(DETAIL_ROWS)` is a display limit, and an unannounced one reads as "this is
     * everything". A clinician counting twelve cycles in a summary built from twenty would be
     * counting the wrong number.
     */
    private fun StringBuilder.truncationNote(shown: Int, total: Int) {
        if (total > shown) appendLine("  (most recent $shown of $total shown)")
    }

    fun build(
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
    ): String = buildString {
        appendLine("CYCLE SUMMARY")
        appendLine("Generated ${today.format(long)} from the Luna app")
        appendLine()
        appendLine(
            "Self-reported data recorded by the patient on their own phone. Not a medical record.",
        )
        appendLine("Days marked ESTIMATED were extrapolated by the app, not observed or recalled.")
        appendLine()

        val observed = projection.cycles.filter { it.source == Source.OBSERVED && it.length != null }
        val assumed = projection.cycles.filter { it.source == Source.ASSUMED && it.length != null }

        appendLine("OVERVIEW")
        appendLine("  Completed cycles recorded ... ${observed.size + assumed.size}")
        appendLine("    of which observed ........ ${observed.size}")
        appendLine("    of which estimated ....... ${assumed.size}")

        val lengths = observed.mapNotNull { it.length }.filter { it in config.plausibleCycleRange }
        if (lengths.size >= 2) {
            appendLine("  Observed cycle length ...... ${lengths.min()}–${lengths.max()} days")
        }
        if (lengths.size >= 3) {
            appendLine(
                "  Median observed length ..... ${CycleStats.roundHalfUp(CycleStats.median(lengths))} days",
            )
            CycleStats.cycleLengthVariability(projection.cycles, config)?.let {
                appendLine("  Standard deviation ......... %.1f days".format(it))
            }
        } else {
            appendLine("  Median observed length ..... not enough observed cycles")
        }
        appendLine("  App's working estimate ..... $expectedCycleLength days")

        projection.periods.lastOrNull()?.let {
            appendLine("  Most recent period began ... ${it.start.format(short)}")
            appendLine("  Days since ................. ${daysBetween(it.start, today)}")
        }
        appendLine()

        // -- cycles ---------------------------------------------------------
        val completed = projection.cycles.filter { it.length != null }
        val recentCycles = completed.takeLast(DETAIL_ROWS)
        appendLine("CYCLES (most recent last)")
        if (recentCycles.isEmpty()) {
            none("No completed cycles recorded.")
        } else {
            recentCycles.forEach { cycle ->
                appendLine(
                    "  ${cycle.start.format(short)} to ${cycle.end?.format(short)}" +
                        "  ${cycle.length} days" +
                        if (cycle.source == Source.ASSUMED) "   ESTIMATED" else "",
                )
            }
            truncationNote(recentCycles.size, completed.size)
        }
        projection.currentCycle?.takeIf { it.isOpen }?.let {
            appendLine(
                "  ${it.start.format(short)} to present" +
                    "  ${daysBetween(it.start, today) + 1} days so far   IN PROGRESS",
            )
        }
        appendLine()

        // -- periods --------------------------------------------------------
        val recentPeriods = projection.periods.takeLast(DETAIL_ROWS)
        appendLine("PERIODS (bleeding days per episode)")
        if (recentPeriods.isEmpty()) {
            none("No periods recorded.")
        } else {
            recentPeriods.forEach { period ->
                appendLine(
                    "  ${period.start.format(short)}" +
                        "  ${period.spanDays} days span, ${period.bleedingDayCount} bleeding" +
                        if (period.source == Source.ASSUMED) "   ESTIMATED" else "",
                )
            }
            truncationNote(recentPeriods.size, projection.periods.size)
        }
        appendLine()

        // -- spotting -------------------------------------------------------
        appendLine("BLEEDING BETWEEN PERIODS")
        if (projection.spotting.isEmpty()) {
            // Stated, not omitted: "none" here is a clinical finding, and a missing heading would
            // read as the app not looking for it.
            none("None recorded. The app derives these from bleeding logged outside a period.")
        } else {
            val recentSpotting = projection.spotting.takeLast(DETAIL_ROWS)
            recentSpotting.forEach {
                appendLine("  ${it.start.format(short)}  ${it.spanDays} day(s)")
            }
            truncationNote(recentSpotting.size, projection.spotting.size)
        }
        appendLine()

        // -- flags ----------------------------------------------------------
        appendLine("PATTERNS THE APP FLAGGED")
        if (flags.isEmpty()) {
            // The most important of the five. An empty flag list is good news, and omitting the
            // section turns good news into an unanswered question about whether anything was checked.
            none("Nothing flagged. The app's checks ran and matched no pattern.")
        } else {
            flags.forEach { flag ->
                appendLine("  - ${flag.headline}")
                appendLine("      ${flag.detail}")
            }
        }
        appendLine()

        // -- symptoms -------------------------------------------------------
        appendLine("SYMPTOMS BY PHASE")
        if (symptomSummaries.isEmpty()) {
            // Two different absences, and the caller is the only one who can tell them apart —
            // hence [anySymptomsLogged]. "No symptoms logged" and "logged but unattributable" would
            // mean the same blank otherwise, and they are not the same fact.
            if (anySymptomsLogged) {
                none("Symptoms were logged, but there is not enough cycle history to place them in a phase.")
            } else {
                none("No symptoms logged.")
            }
        } else {
            appendLine("  Averages of what was logged. Scales run 0–4.")
            symptomSummaries.forEach { s ->
                appendLine(
                    "  ${s.symptom.label}: ${s.label()} (%.1f) across ${s.daysObserved} days"
                        .format(s.phaseMean) +
                        (s.elsewhereMean?.let { " vs %.1f in other phases".format(it) } ?: ""),
                )
            }
        }
        appendLine()

        appendLine("END OF SUMMARY")
    }
}
