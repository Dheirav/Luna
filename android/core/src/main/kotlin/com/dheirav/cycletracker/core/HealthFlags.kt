package com.dheirav.cycletracker.core

import java.time.LocalDate

/**
 * Patterns worth noticing, stated as facts rather than diagnoses.
 *
 * This is the closest the app comes to saying something about health, so the line it must not
 * cross is worth naming precisely: **it reports what was logged and how that compares to common
 * reference ranges. It never names a condition.** "Your last three cycles were 44, 47 and 41 days"
 * is an observation the user can take to a doctor. "This may indicate PCOS" is a diagnosis from an
 * app that has seen a few dozen rows of self-reported data, and it would be both wrong and
 * frightening.
 *
 * Three rules follow from that, and each is enforced below:
 *
 *  1. **Only observed data fires a flag.** Backfill invented eleven cycles at a uniform 28 days;
 *     alarming someone about extrapolation the app itself produced would be indefensible (§3.2).
 *  2. **Every flag carries its evidence.** The numbers behind it are in the flag, so the UI can
 *     show its working rather than asserting a conclusion.
 *  3. **Silence is the default.** No flag fires without enough data to mean anything.
 *
 * Thresholds come from the widely used FIGO/ACOG descriptive ranges for adult cycles. They
 * describe what is *common*, not what is healthy — plenty of people sit outside them and are
 * entirely fine, which is why the wording asks rather than concludes.
 */
data class HealthFlagConfig(
    /** Cycles shorter than this are "frequent" in the FIGO descriptors. */
    val shortCycleDays: Int = 24,
    /** Cycles longer than this are "infrequent". */
    val longCycleDays: Int = 38,
    /** Bleeding beyond this many days is "prolonged". */
    val prolongedBleedDays: Int = 8,
    /** No period at all for this long is worth raising regardless of history. */
    val absentPeriodDays: Int = 90,
    /** Days past the expected date before lateness is worth mentioning. Deliberately generous —
     *  a cycle running a few days over is ordinary and flagging it would be noise. */
    val lateByDays: Int = 7,
    /** Observed cycles needed before any cycle-length flag fires. */
    val minObservedCycles: Int = 3,
    /** How many recent cycles a length flag considers. */
    val recentCycles: Int = 6,
) {
    companion object {
        val Default = HealthFlagConfig()
    }
}

enum class HealthFlagKind {
    /** Current cycle has run well past its expected length. */
    PERIOD_LATE,

    /** No period logged for a long stretch. */
    PERIOD_ABSENT,

    /** Several recent observed cycles longer than the common range. */
    CYCLES_LONG,

    /** Several recent observed cycles shorter than the common range. */
    CYCLES_SHORT,

    /** A period that ran longer than the common range. */
    BLEEDING_PROLONGED,

    /**
     * Bleeding between periods.
     *
     * The one flag whose data was already being computed and silently discarded: `CycleProjector`
     * has always produced `SpottingEvent`s, CYCLE_RULES §2.3 calls them a health-flag input, and
     * nothing ever read them.
     */
    SPOTTING_BETWEEN_PERIODS,

    /**
     * Severe or worse pain logged during most recent periods.
     *
     * Added 5 Oct 2026, by decision. Pain was logged daily and reported nowhere. Like the others it
     * reports what was logged and names no condition.
     */
    PAIN_SEVERE_DURING_PERIODS,
}

/**
 * One noticed pattern.
 *
 * [detail] carries the actual numbers. The UI must show them: a flag without its evidence is an
 * app telling someone their body is wrong and declining to say why.
 */
data class HealthFlag(
    val kind: HealthFlagKind,
    val headline: String,
    val detail: String,
    /** Most recent date this concerns, for ordering. */
    val on: LocalDate?,
)

/** Dates in flag text, which reaches a doctor: "1 Jul 2026", not "2026-07-01". */
private val FLAG_DATE = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
private val DAY_MONTH = java.time.format.DateTimeFormatter.ofPattern("d MMM")

/**
 * How the late flag names the expected length. "Usually runs 28 days" is only true when 28 was
 * measured; a population default printed that way in the doctor summary described nobody.
 */
internal fun lengthClause(length: Int, source: LengthSource?): String = when (source) {
    LengthSource.USER_STATED -> "you set at $length days"
    LengthSource.MEDIAN_WITH_ESTIMATES, LengthSource.APP_DEFAULT -> "the app assumes is $length days"
    LengthSource.MEDIAN_OF_OBSERVED, null -> "that usually runs $length days"
}

object HealthFlags {

    /**
     * Everything worth raising, most recent first.
     *
     * Empty is the expected result and the common one.
     */
    fun evaluate(
        projection: Projection,
        today: LocalDate,
        expectedCycleLength: Int,
        config: HealthFlagConfig = HealthFlagConfig.Default,
        cycleConfig: CycleConfig = CycleConfig.Default,
        /**
         * Where [expectedCycleLength] came from. Null is treated as measured, for callers that
         * predate it. The late flag words a stated or assumed length as exactly that, because it
         * reaches the doctor summary verbatim.
         */
        lengthSource: LengthSource? = null,
        /**
         * Starts of spotting events that sit just after an unanswered gap (see [UnloggedGaps]). Their
         * flag waits for the answer, since the "spotting" may be day 4 of a period whose middle days
         * were simply not logged.
         */
        heldSpotting: Set<LocalDate> = emptySet(),
        /** Logged pain per day, 0 None to 4 Extreme. */
        painByDate: Map<LocalDate, Int> = emptyMap(),
        /**
         * The forecast window, so the late flag quotes the range Today showed rather than one
         * date. The summary said "Expected around 14 Mar" beside a Today card reading 13 to 15 Mar.
         */
        window: PeriodWindow? = null,
        /**
         * Set when nothing has been logged since before the passed window opened. Lateness and
         * absence are then unknown rather than measured, so neither flag is raised: they reach the
         * doctor summary, and "no period for 120 days" about a phone nobody opened is not a finding.
         */
        silentSince: LocalDate? = null,
    ): List<HealthFlag> {
        val flags = mutableListOf<HealthFlag>()

        val observedCycles = projection.cycles
            .filter { it.source == Source.OBSERVED }
            .mapNotNull { cycle -> cycle.length?.let { cycle to it } }
            .filter { (_, length) -> length in cycleConfig.plausibleCycleRange }
            .takeLast(config.recentCycles)

        val lastPeriod = projection.periods.lastOrNull()

        // -- nothing has happened for a long time ---------------------------
        // Only from a period that was observed. These two flags used to skip the check every other
        // flag makes, so an estimated period could anchor "the last one you logged", which it was
        // not, and assumed cycles must never raise a flag (§3.2).
        if (silentSince == null && lastPeriod != null && lastPeriod.source == Source.OBSERVED) {
            val since = daysBetween(lastPeriod.start, today)
            if (since >= config.absentPeriodDays) {
                flags += HealthFlag(
                    kind = HealthFlagKind.PERIOD_ABSENT,
                    headline = "No period logged for $since days",
                    detail = "The last one you logged started on ${lastPeriod.start.format(FLAG_DATE)}. Three " +
                        "months without one is worth raising with a doctor, and worth checking " +
                        "you have not simply missed logging it.",
                    on = lastPeriod.start,
                )
            } else {
                // Lateness only makes sense while a cycle is open and not yet absent.
                val open = projection.currentCycle?.takeIf { it.isOpen && silentSince == null }
                if (open != null) {
                    val dayOfCycle = daysBetween(open.start, today) + 1
                    // Counted from the expected start date, as clinicians count it (see LateGuidance).
                    // It was cycle day minus cycle length, one higher, and disagreed with Today.
                    val expected = open.start.plusDays(expectedCycleLength.toLong())
                    val late = LateGuidance.daysPastExpected(open.start, expectedCycleLength, today)
                    if (late >= config.lateByDays) {
                        flags += HealthFlag(
                            kind = HealthFlagKind.PERIOD_LATE,
                            headline = "Period is $late days past the expected date",
                            detail = (
                                window?.let {
                                    "Expected between ${it.earliest.format(DAY_MONTH)} and " +
                                        "${it.latest.format(FLAG_DATE)}, from a cycle " +
                                        lengthClause(expectedCycleLength, lengthSource) +
                                        ". Lateness is counted from the middle of that window, " +
                                        "${expected.format(DAY_MONTH)}"
                                } ?: (
                                    "Expected around ${expected.format(FLAG_DATE)}, from a cycle " +
                                        lengthClause(expectedCycleLength, lengthSource)
                                    )
                                ) +
                                "; today is day $dayOfCycle. Stress, illness, travel and sleep all " +
                                "shift this, and one late cycle on its own is common.",
                            on = today,
                        )
                    }
                }
            }
        }

        // -- cycle length ---------------------------------------------------
        if (observedCycles.size >= config.minObservedCycles) {
            val lengths = observedCycles.map { it.second }
            val long = lengths.filter { it > config.longCycleDays }
            val short = lengths.filter { it < config.shortCycleDays }

            if (long.size >= 2) {
                flags += HealthFlag(
                    kind = HealthFlagKind.CYCLES_LONG,
                    headline = "${long.size} of your last ${lengths.size} cycles ran long",
                    detail = "They were ${long.sorted().joinToString(", ")} days. Cycles longer " +
                        "than ${config.longCycleDays} days are outside the usual range — not " +
                        "necessarily a problem, but worth mentioning if it keeps up.",
                    on = observedCycles.last().first.end,
                )
            }
            if (short.size >= 2) {
                flags += HealthFlag(
                    kind = HealthFlagKind.CYCLES_SHORT,
                    headline = "${short.size} of your last ${lengths.size} cycles ran short",
                    detail = "They were ${short.sorted().joinToString(", ")} days. Cycles under " +
                        "${config.shortCycleDays} days are outside the usual range — worth " +
                        "mentioning if it keeps up.",
                    on = observedCycles.last().first.end,
                )
            }
        }

        // -- how long the bleeding lasted -----------------------------------
        projection.periods
            // Wholly observed: a long period made long by backfilled days is not something to raise.
            .filter { it.whollyObserved && it.spanDays > config.prolongedBleedDays }
            .maxByOrNull { it.start }
            ?.let { period ->
                flags += HealthFlag(
                    kind = HealthFlagKind.BLEEDING_PROLONGED,
                    headline = "A period lasted ${period.spanDays} days",
                    detail = "Starting ${period.start.format(FLAG_DATE)}. Bleeding beyond " +
                        "${config.prolongedBleedDays} days is outside the usual range and is " +
                        "worth raising, particularly if it is heavy.",
                    on = period.start,
                )
            }

        // -- severe pain during periods --------------------------------------
        // Observed periods only (§3.2), the last three, and a pattern means two or more: one bad
        // period is ordinary.
        val recentPeriods = projection.periods.filter { it.source == Source.OBSERVED }.takeLast(3)
        val painful = recentPeriods.count { period ->
            (0 until period.spanDays).any { (painByDate[period.start.plusDays(it.toLong())] ?: -1) >= 3 }
        }
        if (recentPeriods.size == 3 && painful >= 2) {
            flags += HealthFlag(
                kind = HealthFlagKind.PAIN_SEVERE_DURING_PERIODS,
                headline = "Severe pain logged during $painful of your last 3 periods",
                detail = "Logged as Severe or Extreme on at least one day of each. Period pain that " +
                    "regularly gets in the way of ordinary days is worth raising with a doctor.",
                on = recentPeriods.last().start,
            )
        }

        // -- bleeding between periods ---------------------------------------
        projection.spotting.filter { it.start !in heldSpotting }.maxByOrNull { it.start }?.let { event ->
            flags += HealthFlag(
                kind = HealthFlagKind.SPOTTING_BETWEEN_PERIODS,
                headline = "Bleeding logged between periods",
                detail = "On ${event.start.format(FLAG_DATE)}" +
                    (if (event.spanDays > 1) " for ${event.spanDays} days" else "") +
                    ". Occasional spotting is common, including around ovulation. Worth " +
                    "mentioning if it happens repeatedly.",
                on = event.start,
            )
        }

        return flags.sortedByDescending { it.on }
    }
}
