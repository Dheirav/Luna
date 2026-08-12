package com.dheirav.cycletracker.core

/**
 * The symptom catalog.
 *
 * Three Phase 0 decisions are encoded here:
 *
 * 1. **Anchored levels, not bare numbers.** Every level has a word. An unanchored 1–5 drifts over
 *    months — your "3" in January stops meaning your "3" in June, and six-month trends become
 *    noise. Anchors also make the eventual language-model extraction tractable, since a small
 *    model picks reliably between five labelled words but is poorly calibrated emitting integers.
 *
 * 2. **Mood is split.** Irritability, anxiety and low mood have different cycle profiles;
 *    collapsing them into one "mood" scalar destroys the signal before the maths ever sees it.
 *
 * 3. **Core versus extended.** Only [core] symptoms appear by default. Adherence is the binding
 *    constraint — a form with nine required fields gets abandoned inside three weeks, and
 *    analytics over an empty database is worth nothing.
 *
 * Values are stored 0–4. A missing row means "not logged", which is never the same as a logged 0.
 */
private val BURDEN = listOf("None", "Slight", "Moderate", "Strong", "Overwhelming")

enum class Symptom(
    val key: String,
    val label: String,
    val levels: List<String>,
    val isCore: Boolean,
) {
    ENERGY("energy", "Energy", listOf("Depleted", "Low", "OK", "Good", "High"), isCore = true),
    PAIN("pain", "Pain", listOf("None", "Mild", "Moderate", "Severe", "Extreme"), isCore = true),
    SLEEP("sleep", "Sleep", listOf("Awful", "Poor", "OK", "Good", "Great"), isCore = true),

    IRRITABILITY("irritability", "Irritability", BURDEN, isCore = false),
    ANXIETY("anxiety", "Anxiety", BURDEN, isCore = false),
    /**
     * **Core, unlike the other three burdens.** Promoted 2026-08-12.
     *
     * Everything mood-related was behind the log form's "More" button, and on the one phone this app
     * runs on it had never once been logged — which left `SymptomPatterns` empty, the clinical summary
     * with no symptoms section, the mood widget able to say only what is *typical* rather than
     * anything about this person, and Phase 4's correlation half with nothing to correlate. Four built
     * features inert behind one collapsed section.
     *
     * One of the four, not all four: seven rows would break the form's own constraint of one screen
     * and about ten seconds, which rule 4 makes the thing everything else depends on. Low mood is the
     * one promoted because it is the most directly *mood* of the four — irritability, anxiety and
     * stress all have obvious non-cycle causes, and the day tags already exist to record those.
     */
    LOW_MOOD("low_mood", "Low mood", BURDEN, isCore = true),
    STRESS("stress", "Stress", BURDEN, isCore = false);

    /** Human-readable anchor for a stored value, or null when out of range. */
    fun levelLabel(value: Int): String? = levels.getOrNull(value)

    companion object {
        val core: List<Symptom> get() = entries.filter { it.isCore }
        val extended: List<Symptom> get() = entries.filter { !it.isCore }

        fun byKey(key: String): Symptom? = entries.firstOrNull { it.key == key }

        /** Valid stored range for every symptom. */
        val range: IntRange = 0..4
    }
}

/**
 * External factors that confound the cycle signal.
 *
 * Without these you will eventually blame hormones for a bad cycle that was actually a deadline
 * and a head cold. They cost one tap and make the difference between interpretable analysis and
 * noise once the correlation work in Phase 4 starts.
 */
enum class DayTag(val key: String, val label: String) {
    TRAVEL("travel", "Travel"),
    ILLNESS("illness", "Illness"),
    ALCOHOL("alcohol", "Alcohol"),
    HIGH_STRESS_EVENT("deadline", "Deadline"),
    MEDICATION_CHANGE("med_change", "Med change"),
    POOR_ROUTINE("routine", "Off routine");

    companion object {
        fun byKey(key: String): DayTag? = entries.firstOrNull { it.key == key }
    }
}
