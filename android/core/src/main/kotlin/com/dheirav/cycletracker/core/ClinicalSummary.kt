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
 */
object ClinicalSummary {

    private val long = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val short = DateTimeFormatter.ofPattern("d MMM yyyy")

    /** How many recent cycles and periods to list individually. */
    private const val DETAIL_ROWS = 12

    fun build(
        projection: Projection,
        today: LocalDate,
        expectedCycleLength: Int,
        flags: List<HealthFlag> = emptyList(),
        symptomSummaries: List<PhaseSymptomSummary> = emptyList(),
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
        val recentCycles = projection.cycles.filter { it.length != null }.takeLast(DETAIL_ROWS)
        if (recentCycles.isNotEmpty()) {
            appendLine("CYCLES (most recent last)")
            recentCycles.forEach { cycle ->
                appendLine(
                    "  ${cycle.start.format(short)} to ${cycle.end?.format(short)}" +
                        "  ${cycle.length} days" +
                        if (cycle.source == Source.ASSUMED) "   ESTIMATED" else "",
                )
            }
            projection.currentCycle?.takeIf { it.isOpen }?.let {
                appendLine(
                    "  ${it.start.format(short)} to present" +
                        "  ${daysBetween(it.start, today) + 1} days so far   IN PROGRESS",
                )
            }
            appendLine()
        }

        // -- periods --------------------------------------------------------
        val recentPeriods = projection.periods.takeLast(DETAIL_ROWS)
        if (recentPeriods.isNotEmpty()) {
            appendLine("PERIODS (bleeding days per episode)")
            recentPeriods.forEach { period ->
                appendLine(
                    "  ${period.start.format(short)}" +
                        "  ${period.spanDays} days span, ${period.bleedingDayCount} bleeding" +
                        if (period.source == Source.ASSUMED) "   ESTIMATED" else "",
                )
            }
            appendLine()
        }

        // -- spotting -------------------------------------------------------
        if (projection.spotting.isNotEmpty()) {
            appendLine("BLEEDING BETWEEN PERIODS")
            projection.spotting.takeLast(DETAIL_ROWS).forEach {
                appendLine("  ${it.start.format(short)}  ${it.spanDays} day(s)")
            }
            appendLine()
        }

        // -- flags ----------------------------------------------------------
        if (flags.isNotEmpty()) {
            appendLine("PATTERNS THE APP FLAGGED")
            flags.forEach { flag ->
                appendLine("  - ${flag.headline}")
                appendLine("      ${flag.detail}")
            }
            appendLine()
        }

        // -- symptoms -------------------------------------------------------
        if (symptomSummaries.isNotEmpty()) {
            appendLine("SYMPTOMS BY PHASE")
            appendLine("  Averages of what was logged. Scales run 0–4.")
            symptomSummaries.forEach { s ->
                appendLine(
                    "  ${s.symptom.label}: ${s.label()} (%.1f) across ${s.daysObserved} days"
                        .format(s.phaseMean) +
                        (s.elsewhereMean?.let { " vs %.1f in other phases".format(it) } ?: ""),
                )
            }
            appendLine()
        }

        appendLine("END OF SUMMARY")
    }
}
