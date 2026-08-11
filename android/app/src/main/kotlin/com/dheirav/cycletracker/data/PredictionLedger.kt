package com.dheirav.cycletracker.data

import com.dheirav.cycletracker.core.CycleState
import com.dheirav.cycletracker.core.PredictionAccuracy
import com.dheirav.cycletracker.core.PredictionRecord
import com.dheirav.cycletracker.core.PredictionScorer
import com.dheirav.cycletracker.core.Projection
import com.dheirav.cycletracker.core.ScoredPrediction
import java.time.LocalDate

/**
 * Writes down what the app predicted, so it can be graded later.
 *
 * Rule 3 says confidence must be earned by measuring prediction error. Measuring requires a record
 * of the prediction, and a record can only be made *at the time* — the inputs are gone as soon as
 * the logs change. Every day this does not run is a day of scoring data lost for good, which is
 * why it exists before any screen that displays the result.
 *
 * Writing is deliberately silent and cheap: one upsert keyed by date, no user-visible effect.
 */
class PredictionLedger(private val dao: LogDao) {

    /**
     * Records today's call, if there is one to record.
     *
     * Skipped when the engine has no cycle to work from — §6 forbids inventing a cycle day, and a
     * prediction of "nothing" is not a prediction. Skipped for past dates too: back-dating a
     * record would fabricate a prediction that was never actually made, which is precisely the
     * dishonesty this whole mechanism exists to prevent.
     */
    suspend fun record(state: CycleState, today: LocalDate = LocalDate.now()) {
        if (state.date != today) return
        val cycleStart = state.cycleStart ?: return
        val predicted = state.nextPeriodExpected ?: return

        dao.upsertPrediction(
            PredictionEntity(
                madeOn = today,
                cycleStart = cycleStart,
                predictedNextPeriod = predicted,
                expectedCycleLength = state.expectedCycleLength,
                variability = state.cycleLengthVariability,
            ),
        )
    }

    /** Every recorded prediction graded against the periods that followed. */
    suspend fun scored(projection: Projection): List<ScoredPrediction> =
        PredictionScorer.latestPerCycle(
            PredictionScorer.score(
                records = dao.allPredictionsOnce().map(PredictionEntity::toRecord),
                periods = projection.periods,
            ),
        )

    /**
     * Measured accuracy, or null while the track record is too thin to report one.
     *
     * Null is the answer rule 3 requires — not a low number, not a number with a disclaimer. The
     * UI must render nothing at all in that case.
     */
    suspend fun accuracy(projection: Projection): PredictionAccuracy? =
        PredictionScorer.accuracy(scored(projection))
}

private fun PredictionEntity.toRecord() = PredictionRecord(
    madeOn = madeOn,
    cycleStart = cycleStart,
    predictedNextPeriod = predictedNextPeriod,
    expectedCycleLength = expectedCycleLength,
    variability = variability,
)
