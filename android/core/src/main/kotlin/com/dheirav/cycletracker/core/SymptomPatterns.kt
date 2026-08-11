package com.dheirav.cycletracker.core

import kotlin.math.abs

/**
 * What the user's **own** logs say about a phase, as opposed to what is typical.
 *
 * The counterpart to [Guidance], and deliberately a separate type so the two cannot be rendered
 * as one thing. Guidance describes a population; this describes a person, and only once there is
 * enough of them to describe.
 *
 * Everything here is descriptive. It reports averages of what was logged — it does not claim the
 * phase *caused* anything, and it is not a prediction. Establishing that a phase drives a symptom
 * is Phase 4's correlation work, which needs far more data and a real hypothesis test. Saying
 * "your energy averaged Low across 8 luteal days you logged" is a fact about the logs; saying
 * "luteal makes you tired" is a claim this cannot support.
 */
data class PhaseSymptomSummary(
    val symptom: Symptom,
    /** Mean logged value in this phase, on the symptom's own 0–4 scale. */
    val phaseMean: Double,
    /** Mean across every other phase, or null when too little was logged elsewhere to compare. */
    val elsewhereMean: Double?,
    /** Days in this phase with this symptom logged. Absent days are excluded, never read as 0. */
    val daysObserved: Int,
) {
    /** Nearest anchor word, so the UI can say "Low" rather than "1.4". */
    fun label(): String = symptom.levelLabel(phaseMean.roundHalfUp().coerceIn(0, 4)) ?: "—"

    /**
     * Whether this phase looks different from the rest.
     *
     * The threshold is deliberately coarse. On a five-point scale with a handful of observations,
     * a gap under three quarters of a point is well inside the noise of how someone happens to
     * feel that week, and reporting it would manufacture a pattern out of nothing.
     */
    val standsOut: Boolean
        get() = elsewhereMean != null && abs(phaseMean - elsewhereMean) >= 0.75

    /** True when this phase is *worse*, given the symptom's direction. Null when it does not stand out. */
    val worseHere: Boolean?
        get() = if (!standsOut || elsewhereMean == null) {
            null
        } else {
            val higher = phaseMean > elsewhereMean
            if (symptom.higherIsBetter) !higher else higher
        }
}

/**
 * Which way a symptom's scale runs.
 *
 * Energy and sleep are graded low-to-good; pain and the mood burdens run the other way. Without
 * this the app would cheerfully report high pain as an improvement.
 */
val Symptom.higherIsBetter: Boolean
    get() = this == Symptom.ENERGY || this == Symptom.SLEEP

/** One logged day, reduced to what this analysis needs. */
data class PhaseObservation(val phase: Phase?, val symptoms: Map<Symptom, Int>)

object SymptomPatterns {

    /**
     * Days needed in a phase before anything is reported for it.
     *
     * Five is a floor, not a sufficiency. It is enough to summarise what was logged without the
     * average swinging wildly on one bad day, and not nearly enough to establish a cycle pattern —
     * hence the careful wording required of the UI. Raising it further would mean showing nothing
     * for months, which defeats the purpose of giving symptom logging a visible payoff.
     */
    const val MIN_DAYS_IN_PHASE = 5

    /** Days needed outside the phase before a comparison is drawn at all. */
    const val MIN_DAYS_ELSEWHERE = 5

    /**
     * Summarises each symptom for one phase, most distinctive first.
     *
     * Returns an empty list rather than null when nothing clears the threshold — "we have nothing
     * to say yet" is a normal state here, not an error, and it is what the UI shows for months.
     */
    fun summarise(
        observations: List<PhaseObservation>,
        phase: Phase,
        minDaysInPhase: Int = MIN_DAYS_IN_PHASE,
    ): List<PhaseSymptomSummary> {
        val inPhase = observations.filter { it.phase == phase }
        val elsewhere = observations.filter { it.phase != null && it.phase != phase }

        return Symptom.entries.mapNotNull { symptom ->
            // Absent is not zero (rule 2) — a day without this symptom logged is simply not a
            // data point for it, and must never be averaged in as a floor value.
            val here = inPhase.mapNotNull { it.symptoms[symptom] }
            if (here.size < minDaysInPhase) return@mapNotNull null

            val other = elsewhere.mapNotNull { it.symptoms[symptom] }
            PhaseSymptomSummary(
                symptom = symptom,
                phaseMean = here.average(),
                elsewhereMean = if (other.size >= MIN_DAYS_ELSEWHERE) other.average() else null,
                daysObserved = here.size,
            )
        }.sortedByDescending { summary ->
            summary.elsewhereMean?.let { abs(summary.phaseMean - it) } ?: 0.0
        }
    }
}

private fun Double.roundHalfUp(): Int = kotlin.math.floor(this + 0.5).toInt()
