package com.dheirav.cycletracker.core

/**
 * What tends to happen in each phase, and what tends to help.
 *
 * **All of this is population-level.** It is what is typical across many people, ported from the
 * legacy `config/rules.yaml`, and it describes nobody in particular — least of all this user. That
 * matters because the rest of the app is careful never to assert what it has not measured: the
 * window says "not yours yet", the cycle length says "median of N you logged". Generic advice
 * dressed as personal insight would be the first place that discipline broke, and it is the exact
 * failure mode of every cycle app that tells you how you feel today.
 *
 * So this content is always labelled as typical, and [SymptomPatterns] provides the separate,
 * measured layer that describes the user's own logs. The two must never be blended in the UI.
 *
 * The secondary benefit is not accidental: a visible "yours" section that is empty until symptoms
 * are logged gives symptom logging a payoff, and symptom data is what Phase 4 needs.
 */
data class PhaseGuidance(
    val phase: Phase,
    /** One line on what the phase is, physically. */
    val summary: String,
    /** What mood and energy commonly do here. Hedged on purpose — these vary enormously. */
    val mood: String,
    val movement: List<String>,
    val nourishment: List<String>,
    val selfCare: List<String>,
)

object Guidance {

    /**
     * Shown wherever guidance appears.
     *
     * Short and once, rather than hedging every line into uselessness. The app is not a clinician
     * and should not sound like one, but it also should not let a list of tips imply it knows
     * what is wrong with someone.
     */
    const val DISCLAIMER: String =
        "General information about what is common, not medical advice and not a description of " +
            "you. Anything severe, new, or worrying is worth taking to a doctor."

    fun forPhase(phase: Phase): PhaseGuidance = content.getValue(phase)

    private val content: Map<Phase, PhaseGuidance> = mapOf(
        Phase.MENSTRUATION to PhaseGuidance(
            phase = Phase.MENSTRUATION,
            summary = "The lining sheds and hormones sit at their lowest point of the cycle.",
            mood = "Energy is often at its lowest, and many people feel flat, tender or inward " +
                "for the first couple of days. Some feel relief once bleeding starts, " +
                "particularly if the days before were rough.",
            movement = listOf(
                "Gentle movement — stretching, restorative yoga, an easy walk",
                "Rest days are a legitimate choice here, not a failure of discipline",
                "Warmth helps: a bath, a hot water bottle, staying out of the cold",
            ),
            nourishment = listOf(
                "Iron-rich foods — red meat, spinach, lentils — to offset what is lost",
                "Warm, substantial meals rather than cold light ones",
                "Drink more than usual; bleeding is dehydrating",
                "Magnesium sources such as dark chocolate, nuts and seeds",
            ),
            selfCare = listOf(
                "Protect sleep before anything else",
                "Move the hard conversations and big decisions if you can",
                "Low-effort comfort: tea, a bath, something undemanding to watch",
                "Journalling, if the flatness needs somewhere to go",
            ),
        ),

        Phase.FOLLICULAR to PhaseGuidance(
            phase = Phase.FOLLICULAR,
            summary = "Oestrogen climbs as a follicle matures. Energy usually climbs with it.",
            mood = "Often the most open stretch of the cycle — mood lifts, patience returns, " +
                "and new things feel possible again. A good window for anything that needs " +
                "optimism to start.",
            movement = listOf(
                "Build intensity gradually as energy returns",
                "Good time to start something new or add load",
                "Classes and training with other people, if that appeals",
                "Endurance work tends to feel easier here",
            ),
            nourishment = listOf(
                "Lighter, fresher food suits the rising metabolism",
                "Plenty of vegetables and fruit",
                "Steady protein to support the added training",
            ),
            selfCare = listOf(
                "Spend the energy on things that matter — this window does not last",
                "Good stretch for planning and problem-solving",
                "Reach outward; social energy is usually higher",
            ),
        ),

        Phase.OVULATION to PhaseGuidance(
            phase = Phase.OVULATION,
            summary = "An egg is released. Oestrogen peaks and then drops sharply.",
            mood = "Frequently the most outward and confident few days — verbal, social, " +
                "physically capable. Some people get a short dip or a twinge of pain right at " +
                "release, which is common and usually brief.",
            movement = listOf(
                "Strength and power tend to peak here — a good time to test yourself",
                "High intensity work, sprints, competitive sport",
                "Warm up properly; ligaments are laxer around ovulation and injury risk rises",
            ),
            nourishment = listOf(
                "Lighter meals tend to sit better",
                "Keep fluids up, especially alongside harder training",
                "Antioxidant-rich food — berries, leafy greens",
                "Watch caffeine if you are sensitive to it; anxiety can spike here",
            ),
            selfCare = listOf(
                "Front-load the demanding or social things onto these days",
                "Guard against overcommitting — the energy is real but it is borrowed",
                "Set the boundaries now that luteal you will need",
            ),
        ),

        Phase.LUTEAL to PhaseGuidance(
            phase = Phase.LUTEAL,
            summary = "Progesterone rises after ovulation, then falls away if there is no " +
                "pregnancy. The drop is what brings the period.",
            mood = "The longest and most variable stretch. It often starts steady and turns " +
                "harder in the last few days as hormones fall — irritability, low mood, anxiety " +
                "and poorer sleep are all common then. Appetite frequently rises, which is " +
                "physiological rather than a lapse.",
            movement = listOf(
                "Consistency over intensity — keep moving, skip the new maximums",
                "Steady cardio, moderate strength work",
                "Expect the same effort to feel harder; that is normal, not lost fitness",
            ),
            nourishment = listOf(
                "Genuinely higher calorie needs — eat accordingly",
                "Complex carbohydrates support serotonin",
                "Fibre from whole grains and legumes",
                "Healthy fats — avocado, nuts, oily fish",
                "Calcium and magnesium are associated with steadier mood",
            ),
            selfCare = listOf(
                "Lower the bar deliberately before you have to",
                "Breathing exercises or meditation for the sharper days",
                "Write it down — late-luteal feelings are real but not always accurate",
                "Book the rest in advance rather than earning it afterwards",
            ),
        ),
    )
}
