package com.dheirav.cycletracker.core

import java.time.LocalDate

/**
 * How late a period is, and when it is worth raising with a doctor, by clinical convention.
 *
 * Decided on 5 Oct 2026 after checking the published guidance, which is why both rules live here
 * rather than being worked out separately by each screen:
 *
 *  - **Late is counted from the expected start date**, the last period's start plus the usual cycle
 *    length. Clinic guidance varies on how many days make a period "late" (3, 5 or 7) but always
 *    counts from that date. The engine's `CycleState.daysLate` (CYCLE_RULES §4) is cycle day minus
 *    cycle length, which counts the expected day itself and so reads one higher. It stays, because the
 *    confidence arithmetic uses it, but nothing shown to a person does.
 *  - **The doctor point is the secondary amenorrhea threshold**: no period for three months after
 *    regular cycles, or six after irregular ones (AAFP 2019; ASRM 2024). "Irregular" uses FIGO's
 *    definition, the spread between shortest and longest cycle. FIGO allows up to seven or nine days
 *    depending on age; the app knows no age, so it takes nine, and calls a history irregular only past
 *    it. With too few observed cycles to say, the earlier three-month point applies.
 */
object LateGuidance {

    /** Wider than this between shortest and longest observed cycle is irregular (FIGO, widest band). */
    const val IRREGULAR_SPREAD_DAYS = 9

    fun daysPastExpected(cycleStart: LocalDate, expectedCycleLength: Int, today: LocalDate): Int =
        maxOf(0, daysBetween(cycleStart.plusDays(expectedCycleLength.toLong()), today))

    /** When to suggest a doctor, and whether the history counted as irregular in choosing it. */
    data class DoctorPoint(val date: LocalDate, val irregular: Boolean)

    fun doctorPoint(projection: Projection, cycleStart: LocalDate, config: CycleConfig = CycleConfig.Default): DoctorPoint {
        // Observed, completed, plausible cycles only (§3.2): estimates must not move a medical threshold.
        val lengths = projection.cycles
            .filter { it.source == Source.OBSERVED }
            .mapNotNull { it.length }
            .filter { it in config.plausibleCycleRange }
            .takeLast(config.cycleLengthSampleSize)
        val irregular = lengths.size >= 3 && lengths.max() - lengths.min() > IRREGULAR_SPREAD_DAYS
        return DoctorPoint(
            date = cycleStart.plusMonths(if (irregular) 6 else 3),
            irregular = irregular,
        )
    }
}
