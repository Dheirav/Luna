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
    ): List<HealthFlag> {
        val flags = mutableListOf<HealthFlag>()

        val observedCycles = projection.cycles
            .filter { it.source == Source.OBSERVED }
            .mapNotNull { cycle -> cycle.length?.let { cycle to it } }
            .filter { (_, length) -> length in cycleConfig.plausibleCycleRange }
            .takeLast(config.recentCycles)

        val lastPeriod = projection.periods.lastOrNull()

        // -- nothing has happened for a long time ---------------------------
        if (lastPeriod != null) {
            val since = daysBetween(lastPeriod.start, today)
            if (since >= config.absentPeriodDays) {
                flags += HealthFlag(
                    kind = HealthFlagKind.PERIOD_ABSENT,
                    headline = "No period logged for $since days",
                    detail = "The last one you logged started on ${lastPeriod.start}. Three " +
                        "months without one is worth raising with a doctor, and worth checking " +
                        "you have not simply missed logging it.",
                    on = lastPeriod.start,
                )
            } else {
                // Lateness only makes sense while a cycle is open and not yet absent.
                val open = projection.currentCycle?.takeIf { it.isOpen }
                if (open != null) {
                    val dayOfCycle = daysBetween(open.start, today) + 1
                    val late = dayOfCycle - expectedCycleLength
                    if (late >= config.lateByDays) {
                        flags += HealthFlag(
                            kind = HealthFlagKind.PERIOD_LATE,
                            headline = "Period is $late days later than expected",
                            detail = "You are on day $dayOfCycle of a cycle that usually runs " +
                                "$expectedCycleLength days. Stress, illness, travel and sleep " +
                                "all shift this, and one late cycle on its own is common.",
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
            .filter { it.source == Source.OBSERVED && it.spanDays > config.prolongedBleedDays }
            .maxByOrNull { it.start }
            ?.let { period ->
                flags += HealthFlag(
                    kind = HealthFlagKind.BLEEDING_PROLONGED,
                    headline = "A period lasted ${period.spanDays} days",
                    detail = "Starting ${period.start}. Bleeding beyond " +
                        "${config.prolongedBleedDays} days is outside the usual range and is " +
                        "worth raising, particularly if it is heavy.",
                    on = period.start,
                )
            }

        // -- bleeding between periods ---------------------------------------
        projection.spotting.maxByOrNull { it.start }?.let { event ->
            flags += HealthFlag(
                kind = HealthFlagKind.SPOTTING_BETWEEN_PERIODS,
                headline = "Bleeding logged between periods",
                detail = "On ${event.start}" +
                    (if (event.spanDays > 1) " for ${event.spanDays} days" else "") +
                    ". Occasional spotting is common, including around ovulation. Worth " +
                    "mentioning if it happens repeatedly.",
                on = event.start,
            )
        }

        return flags.sortedByDescending { it.on }
    }
}
