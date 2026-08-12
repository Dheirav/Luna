package com.dheirav.cycletracker.core

import java.time.LocalDate

/**
 * A morning temperature reading.
 *
 * **Centidegrees, not a Double**, because these are stored in `symptom_values`, whose `value` column
 * is an `Int` — 3650 is 36.50 °C. That keeps the whole feature free of a database migration, since
 * that table is deliberately a generic key/value store ("what gets tracked can change without a
 * migration"), and the backup codec serialises it as `Map<String, Int>` so an unfamiliar key
 * round-trips untouched.
 *
 * Integer hundredths are also honest about precision: a basal thermometer reads to 0.01 °C and a
 * Double would invite arithmetic that implies more.
 */
data class TemperatureReading(val date: LocalDate, val centidegrees: Int)

/** Where a luteal length came from. Same shape as [LengthSource], and for the same reason. */
enum class LutealSource {
    /** Median of luteal phases measured from a temperature shift in this user's own cycles. */
    MEASURED,

    /** [CycleConfig.defaultLutealLength] — a population figure, and the current behaviour. */
    APP_DEFAULT,
}

/**
 * One cycle's ovulation, as read from its temperature series.
 *
 * @param ovulation the last low day before the sustained rise — the conventional estimate, and an
 *   estimate rather than an event: a temperature shift follows ovulation by a day or so and cannot
 *   locate it exactly.
 * @param lutealDays ovulation to the start of the next period.
 */
data class OvulationDetection(
    val cycleStart: LocalDate,
    val ovulation: LocalDate,
    val lutealDays: Int,
)

/**
 * A luteal length, and what it rests on.
 *
 * Carries [cyclesUsed] for the same reason [PredictionBasis] carries its counts: "14 days" from a
 * population table and "14 days" measured across five of this user's cycles are different claims,
 * and a UI that cannot tell them apart will present the first as the second.
 */
data class LutealEstimate(
    val days: Int,
    val source: LutealSource,
    val cyclesUsed: Int,
    val detections: List<OvulationDetection> = emptyList(),
)

/**
 * Phase 4 — learning luteal length from basal body temperature.
 *
 * **DORMANT. Nothing calls this, and nothing is expected to.** It is complete and tested, and it has
 * no data source, because the app records no temperature and the decision was taken (2026-08-12) not
 * to add one:
 *
 *  - **Manual basal temperature was rejected as unreasonable to ask for.** It means a thermometer at
 *    the bedside and a reading before sitting up, most mornings, for at least three cycles. And per
 *    `a gap inside a run delays the shift rather than reading through it`, patchy measurement does not
 *    fail loudly — it silently returns a luteal phase that is too short. Half-hearted logging here is
 *    worse than none, which makes it a bad thing to ask of someone who is unsure.
 *  - **A wearable source is possible but not available.** Health Connect can read skin temperature
 *    entirely on-device, so it would not breach the no-internet rule. It needs hardware that records
 *    and exports it — a Galaxy Watch or Oura will, the Xiaomi band on this project will not.
 *
 * So this file exists for one reason: if a temperature source ever appears, the analysis is already
 * written and does not have to be re-derived from a spec clause. **Do not wire it to anything else.**
 * In particular do not feed it estimated cycle lengths to "get it working" — that is precisely the
 * circular route §7 forbids, and the code refuses assumed cycles specifically to make that harder.
 *
 * Until then the app keeps `CycleConfig.defaultLutealLength` and calls it an assumption, which is the
 * honest end state rather than a gap.
 *
 * `CYCLE_RULES.md` §7 is specific about what is permitted here:
 *
 * > Learned luteal length. Fixed at 14 for now. Refine only with real evidence — basal body
 * > temperature or wearable skin-temperature shift (Phase 4). **Do not infer it from cycle-length
 * > variance; that is circular.**
 *
 * The circularity is worth spelling out, because inferring from cycle length is the obvious
 * shortcut and it looks reasonable. Ovulation day is currently derived *from* the expected cycle
 * length, so deriving luteal length from observed cycle lengths would produce a number that agrees
 * with the prediction by construction, whatever the body actually did. It would then feed the
 * fertility window, which would inherit the appearance of measurement and none of the substance.
 * Temperature is an independent signal; that is the whole reason the spec insists on it.
 *
 * **What this method cannot do.** A biphasic shift says ovulation has *already happened* — it is
 * retrospective by two or three days and useless for predicting the current cycle's ovulation. That
 * is fine for the purpose: luteal length is learned from completed cycles and applied to future
 * ones. It also fails silently on cycles with too few readings, which is most cycles for most
 * people, so [estimate] must be able to return [LutealSource.APP_DEFAULT] indefinitely without that
 * reading as an error.
 *
 * **Known confounders, deliberately not corrected for yet.** Alcohol, illness, a short night and a
 * late reading all move basal temperature by more than the threshold here. The app already logs
 * `DayTag.ALCOHOL`, `ILLNESS` and `POOR_ROUTINE`, which is the obvious hook for excluding days — but
 * excluding days changes what the baseline means, and doing it without evidence that it helps would
 * be exactly the kind of unearned sophistication §7 warns against. Left as a documented next step.
 */
object LutealLength {

    /**
     * How much warmer a day must be than the preceding baseline to count as elevated.
     *
     * 0.2 °C is the conventional threshold for the three-over-six rule. Lower and ordinary noise
     * triggers it; higher and genuine shifts are missed, since the post-ovulatory rise is often only
     * 0.2–0.3 °C.
     */
    const val SHIFT_THRESHOLD_CENTI = 20

    /** Baseline readings required before a candidate day. The "six" of three-over-six. */
    const val BASELINE_DAYS = 6

    /** Consecutive elevated days required to call a shift sustained. The "three". */
    const val SUSTAINED_DAYS = 3

    /**
     * How far back the baseline may reach.
     *
     * Six readings spread over three weeks are not a baseline for anything; they are six unrelated
     * mornings. Requiring them inside a short window is what stops a sparse logger getting a
     * confident-looking answer from data that cannot support one.
     */
    const val BASELINE_WINDOW_DAYS = 10L

    /** Luteal phases outside this are rejected as measurement error rather than reported. */
    val plausibleLutealRange: IntRange = 8..18

    /** Cycles needed before a measured median replaces the default. Mirrors §3's three-cycle rule. */
    const val MIN_CYCLES = 3

    /**
     * The luteal length to use, measured if there is enough evidence and the default otherwise.
     *
     * @param cycles completed cycles, earliest first. Only [Source.OBSERVED] ones are considered —
     *   an extrapolated cycle has no temperature series behind it, and pairing real readings with an
     *   invented cycle boundary would produce a luteal length for a cycle nobody lived.
     */
    fun estimate(
        cycles: List<Cycle>,
        temperatures: List<TemperatureReading>,
        config: CycleConfig = CycleConfig.Default,
    ): LutealEstimate {
        val detections = detectAll(cycles, temperatures)
        val lengths = detections.map { it.lutealDays }.filter { it in plausibleLutealRange }

        if (lengths.size < MIN_CYCLES) {
            return LutealEstimate(
                days = config.defaultLutealLength,
                source = LutealSource.APP_DEFAULT,
                cyclesUsed = lengths.size,
                detections = detections,
            )
        }

        return LutealEstimate(
            // Median, for the reason §3 gives for cycle length: one anomalous cycle — a fever, a bad
            // week of sleep — moves a mean and barely touches a median.
            days = CycleStats.roundHalfUp(CycleStats.median(lengths)),
            source = LutealSource.MEASURED,
            cyclesUsed = lengths.size,
            detections = detections,
        )
    }

    /** Every cycle in which a shift could be found. Cycles without one are simply absent. */
    fun detectAll(
        cycles: List<Cycle>,
        temperatures: List<TemperatureReading>,
    ): List<OvulationDetection> {
        val byDate = temperatures.associate { it.date to it.centidegrees }
        return cycles.mapNotNull { cycle ->
            val end = cycle.end ?: return@mapNotNull null
            if (cycle.source != Source.OBSERVED) return@mapNotNull null
            val nextStart = end.plusDays(1)
            detectShift(cycle.start, nextStart, byDate)?.let { ovulation ->
                OvulationDetection(
                    cycleStart = cycle.start,
                    ovulation = ovulation,
                    // Ovulation to the cycle's **last** day, which is `cycleLength - ovulationDay` —
                    // the convention `defaultLutealLength = 14` is stated in, for a day-14 ovulation
                    // in a 28-day cycle. Measuring to `nextStart` instead gives 15 and would have
                    // reported every luteal phase a day long.
                    lutealDays = daysBetween(ovulation, end),
                )
            }
        }
    }

    /**
     * The last low day before a sustained rise, or null when there is no such day.
     *
     * Scans forward and takes the **first** qualifying shift rather than the largest. A cycle can
     * contain more than one three-day run above its own early baseline, and the first is the one that
     * corresponds to ovulation; picking the biggest would drift later in the cycle, shortening the
     * measured luteal phase and doing it most on the noisiest data.
     */
    fun detectShift(
        cycleStart: LocalDate,
        nextCycleStart: LocalDate,
        byDate: Map<LocalDate, Int>,
    ): LocalDate? {
        var day = cycleStart
        while (day.isBefore(nextCycleStart)) {
            val candidate = byDate[day]
            if (candidate != null) {
                val baseline = baselineBefore(day, byDate)
                if (baseline != null && sustainedFrom(day, nextCycleStart, byDate, baseline)) {
                    // Ovulation is placed the day before the rise: the shift is a consequence of
                    // ovulation and lags it. Returning the rise itself would systematically report a
                    // luteal phase one day short.
                    val ovulation = day.minusDays(1)
                    return if (ovulation.isBefore(cycleStart)) null else ovulation
                }
            }
            day = day.plusDays(1)
        }
        return null
    }

    /** Mean of the last [BASELINE_DAYS] readings before `day`, or null when there are too few. */
    private fun baselineBefore(day: LocalDate, byDate: Map<LocalDate, Int>): Double? {
        val earliest = day.minusDays(BASELINE_WINDOW_DAYS)
        val readings = byDate.entries
            .filter { it.key.isBefore(day) && !it.key.isBefore(earliest) }
            .sortedBy { it.key }
            .map { it.value }
            .takeLast(BASELINE_DAYS)
        return if (readings.size < BASELINE_DAYS) null else readings.average()
    }

    /**
     * Whether `start` and the following days are all elevated above `baseline`.
     *
     * A missing reading fails the run rather than being skipped over. Three elevated days spread
     * across a week is not a sustained shift, and treating an absent morning as "presumably also
     * elevated" would be reading data that was never recorded — the same absent-is-not-zero rule the
     * rest of the app follows.
     */
    private fun sustainedFrom(
        start: LocalDate,
        nextCycleStart: LocalDate,
        byDate: Map<LocalDate, Int>,
        baseline: Double,
    ): Boolean {
        for (offset in 0 until SUSTAINED_DAYS) {
            val day = start.plusDays(offset.toLong())
            if (!day.isBefore(nextCycleStart)) return false
            val value = byDate[day] ?: return false
            if (value < baseline + SHIFT_THRESHOLD_CENTI) return false
        }
        return true
    }
}
