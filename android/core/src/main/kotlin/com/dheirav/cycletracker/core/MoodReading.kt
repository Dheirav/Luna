package com.dheirav.cycletracker.core

/**
 * What a face may say about how someone is doing, and on what evidence.
 *
 * **The distinction this type exists to hold: the app never decides how you feel.** It either shows
 * back what you logged today, or says what you have *usually logged* around this point — with the
 * number of days that rests on — or admits it does not know. There is no fourth case, and in
 * particular there is no case where a phase alone produces a face. A calendar cannot observe a mood,
 * and "day 24, so you must be irritable" is a stereotype with an app's authority behind it.
 *
 * That rules out what the hero card currently does, where [MascotMood] is derived from the phase and
 * nothing else. This is the honest version of the same idea.
 *
 * Only the four burden-scaled symptoms count as mood here — see [isMood]. Energy, pain and sleep are
 * logged far more often, which would make the face light up sooner, but they are not moods, and a
 * "mood" that is really a pain reading would be the same category error in a friendlier shape.
 */
enum class MoodFace {
    /** Nothing logged, or too little to say anything. Must never be drawn as content or neutral. */
    UNKNOWN,

    /** Mood burdens logged low. */
    SETTLED,

    /** Middling, or logged enough with nothing standing out from the usual. */
    STEADY,

    /** Mood burdens logged high. */
    HEAVY,
}

/** Where a [MoodReading] came from. The UI must word these differently; they are not the same claim. */
enum class MoodSource {
    /** From what was logged today. An observation about today, and the strongest thing available. */
    TODAY,

    /** From what was usually logged in this phase. A description of past logs, not a prediction. */
    TENDENCY,

    /** Nothing to draw on. */
    NOTHING,
}

/**
 * A face, and the evidence behind it.
 *
 * Carries facts, not sentences. The wording belongs to whichever surface renders it — the widget has
 * about thirty characters and the Today screen has a paragraph, and the same reading has to be
 * honest at both lengths.
 */
data class MoodReading(
    val face: MoodFace,
    /**
     * The symptom driving the face. Null when [face] is [MoodFace.UNKNOWN], and also null for a
     * [MoodFace.STEADY] tendency, where the finding is precisely that no single symptom stood out.
     */
    val symptom: Symptom?,
    val source: MoodSource,
    /** Logged days behind a [MoodSource.TENDENCY]. Zero otherwise — never a stand-in for "unknown". */
    val daysObserved: Int = 0,
    /** The 0–4 level logged today, when [source] is [MoodSource.TODAY]. */
    val level: Int? = null,
)

/**
 * The mood symptoms: the four sharing the burden scale.
 *
 * Higher is worse for all four, which is why [higherIsBetter] excludes them, and why the face can
 * read a level directly without asking the symptom which way it runs.
 */
val Symptom.isMood: Boolean
    get() = this == Symptom.IRRITABILITY ||
        this == Symptom.ANXIETY ||
        this == Symptom.LOW_MOOD ||
        this == Symptom.STRESS

object MoodReadings {

    /**
     * Reads today's logs first, then the phase tendency, then gives up.
     *
     * The order is the point, and it mirrors §5.2 — an observed bleed beats any computed phase. What
     * you logged today outranks any pattern drawn from previous cycles, because one is an
     * observation of today and the other is an average of other days. An app that showed you a
     * tendency while ignoring the entry you made an hour ago would be telling you about yourself
     * over your own head.
     *
     * @param todaysSymptoms everything logged for today, mood or not. Absent keys mean not logged.
     * @param observations every logged day with its phase, for the tendency.
     * @param phase today's phase, or null when there is not enough history to place it.
     */
    fun read(
        todaysSymptoms: Map<Symptom, Int>,
        observations: List<PhaseObservation>,
        phase: Phase?,
    ): MoodReading {
        // Today, if there is anything. The worst of the four rather than an average of them: these
        // are four separate burdens, and averaging "overwhelming anxiety" with "no irritability"
        // into "slight" would report a calm day to someone having a terrible one.
        val loggedToday = todaysSymptoms.filterKeys { it.isMood }
            .filterValues { it in Symptom.range }
        loggedToday.maxByOrNull { it.value }?.let { (symptom, level) ->
            return MoodReading(
                face = faceForBurden(level),
                symptom = symptom,
                source = MoodSource.TODAY,
                level = level,
            )
        }

        if (phase == null) return unknown()

        val moodSummaries = SymptomPatterns.summarise(observations, phase)
            .filter { it.symptom.isMood }
        if (moodSummaries.isEmpty()) return unknown()

        // `summarise` already orders most distinctive first and already refuses to speak below
        // SymptomPatterns.MIN_DAYS_IN_PHASE, so reaching here means there is enough logged to
        // describe. `standsOut` is what decides whether it is worth describing.
        val notable = moodSummaries.firstOrNull { it.standsOut }
        if (notable != null) {
            return MoodReading(
                // worseHere is direction-aware and non-null whenever standsOut holds.
                face = if (notable.worseHere == true) MoodFace.HEAVY else MoodFace.SETTLED,
                symptom = notable.symptom,
                source = MoodSource.TENDENCY,
                daysObserved = notable.daysObserved,
            )
        }

        // Enough logged, nothing unusual. A real finding and a reassuring one, so it gets a face of
        // its own rather than falling back to UNKNOWN — "your mood around now looks like your mood
        // the rest of the time" is worth saying, and is not the same as "no idea".
        return MoodReading(
            face = MoodFace.STEADY,
            symptom = null,
            source = MoodSource.TENDENCY,
            daysObserved = moodSummaries.maxOf { it.daysObserved },
        )
    }

    private fun unknown() = MoodReading(MoodFace.UNKNOWN, null, MoodSource.NOTHING)

    /**
     * A burden level to a face.
     *
     * "None" and "Slight" are settled; "Moderate" sits in the middle; "Strong" and "Overwhelming"
     * are heavy. Deliberately coarse — the face is a glance, and a five-way facial gradient would
     * imply a precision that a self-reported 0–4 does not have.
     */
    private fun faceForBurden(level: Int): MoodFace = when (level) {
        0, 1 -> MoodFace.SETTLED
        2 -> MoodFace.STEADY
        else -> MoodFace.HEAVY
    }
}
