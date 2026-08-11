package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Scoring is what turns "confidence" from a proxy into a measurement, so the arithmetic has to be
 * right and — more importantly — it has to refuse to answer when it does not know. Most of these
 * tests are about the refusals.
 */
class PredictionScorerTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    private fun period(start: String, source: Source = Source.OBSERVED) = Period(
        start = date(start),
        end = date(start).plusDays(4),
        spanDays = 5,
        bleedingDayCount = 5,
        source = source,
    )

    private fun record(
        madeOn: String,
        cycleStart: String,
        predicted: String,
        expectedLength: Int = 28,
    ) = PredictionRecord(
        madeOn = date(madeOn),
        cycleStart = date(cycleStart),
        predictedNextPeriod = date(predicted),
        expectedCycleLength = expectedLength,
        variability = null,
    )

    // -- error sign -------------------------------------------------------

    @Test
    fun `a period arriving after the predicted date is positive error`() {
        val scored = PredictionScorer.score(
            records = listOf(record("2024-03-03", "2024-02-12", "2024-03-11")),
            periods = listOf(period("2024-02-12"), period("2024-03-14")),
        )

        assertEquals(1, scored.size)
        assertEquals(3, scored.single().errorDays)
        assertEquals(3, scored.single().absoluteErrorDays)
    }

    @Test
    fun `a period arriving early is negative error but positive absolute error`() {
        val scored = PredictionScorer.score(
            records = listOf(record("2024-03-03", "2024-02-12", "2024-03-11")),
            periods = listOf(period("2024-02-12"), period("2024-03-09")),
        )

        assertEquals(-2, scored.single().errorDays)
        assertEquals(2, scored.single().absoluteErrorDays)
    }

    @Test
    fun `lead time is the gap between making the call and the date predicted`() {
        assertEquals(8, record("2024-03-03", "2024-02-12", "2024-03-11").leadTimeDays)
    }

    // -- what refuses to be scored ----------------------------------------

    /** The whole point of rule 3: the open cycle has not happened yet. Counting "hasn't arrived"
     *  as "on time" would make accuracy improve simply by asking earlier. */
    @Test
    fun `the open cycle is not scored`() {
        val scored = PredictionScorer.score(
            records = listOf(record("2024-03-25", "2024-03-11", "2024-04-08")),
            periods = listOf(period("2024-02-12"), period("2024-03-11")),
        )

        assertTrue(scored.isEmpty())
    }

    /** §3.2 — grading against a backfilled guess measures two estimates agreeing, not accuracy. */
    @Test
    fun `predictions against an assumed period are discarded`() {
        val scored = PredictionScorer.score(
            records = listOf(record("2024-02-02", "2024-01-15", "2024-02-12")),
            periods = listOf(period("2024-01-15"), period("2024-02-12", Source.ASSUMED)),
        )

        assertTrue(scored.isEmpty())
    }

    @Test
    fun `accuracy is null below the minimum sample rather than a small number`() {
        val scored = listOf(
            ScoredPrediction(record("2024-02-02", "2024-01-15", "2024-02-12"), date("2024-02-12")),
            ScoredPrediction(record("2024-03-03", "2024-02-12", "2024-03-11"), date("2024-03-11")),
        )

        assertNull(PredictionScorer.accuracy(scored))
    }

    // -- aggregation ------------------------------------------------------

    @Test
    fun `bias and absolute error separate irregular cycles from a systematic offset`() {
        // Errors +4, -4, +4, -4: the length estimate is unbiased, the cycles are just erratic.
        val erratic = listOf(4, -4, 4, -4).mapIndexed { i, error -> scoredWithError(i, error) }
        val erraticAccuracy = PredictionScorer.accuracy(erratic)!!
        assertEquals(4.0, erraticAccuracy.meanAbsoluteError, 1e-9)
        assertEquals(0.0, erraticAccuracy.bias, 1e-9)

        // Errors +4, +4, +4, +4: same absolute error, but the estimate is consistently short.
        val biased = listOf(4, 4, 4, 4).mapIndexed { i, error -> scoredWithError(i, error) }
        val biasedAccuracy = PredictionScorer.accuracy(biased)!!
        assertEquals(4.0, biasedAccuracy.meanAbsoluteError, 1e-9)
        assertEquals(4.0, biasedAccuracy.bias, 1e-9)
    }

    @Test
    fun `hit rate counts predictions inside the tolerance`() {
        val scored = listOf(0, 2, -2, 5).mapIndexed { i, error -> scoredWithError(i, error) }
        val accuracy = PredictionScorer.accuracy(scored)!!

        assertEquals(0.75, accuracy.hitRate, 1e-9)
        assertEquals(5, accuracy.worstError)
    }

    @Test
    fun `worst error keeps its sign so a large early miss is not reported as late`() {
        val scored = listOf(1, -9, 2).mapIndexed { i, error -> scoredWithError(i, error) }

        assertEquals(-9, PredictionScorer.accuracy(scored)!!.worstError)
    }

    @Test
    fun `only the most recent cycles are aggregated`() {
        // Twenty cycles: the first eight badly wrong, the last twelve perfect. With a sample size
        // of twelve, stale failures must not keep dragging the current figure down.
        val scored = (0 until 20).map { i -> scoredWithError(i, if (i < 8) 10 else 0) }
        val accuracy = PredictionScorer.accuracy(scored)!!

        assertEquals(12, accuracy.sampleSize)
        assertEquals(0.0, accuracy.meanAbsoluteError, 1e-9)
    }

    // -- one prediction per cycle -----------------------------------------

    @Test
    fun `latestPerCycle keeps the final call and drops the rest`() {
        val cycleStart = "2024-02-12"
        val scored = listOf(
            ScoredPrediction(record("2024-02-13", cycleStart, "2024-03-11"), date("2024-03-14")),
            ScoredPrediction(record("2024-02-27", cycleStart, "2024-03-11"), date("2024-03-14")),
            ScoredPrediction(record("2024-03-10", cycleStart, "2024-03-13"), date("2024-03-14")),
        )

        val latest = PredictionScorer.latestPerCycle(scored)

        assertEquals(1, latest.size)
        assertEquals(date("2024-03-10"), latest.single().record.madeOn)
        assertEquals(1, latest.single().errorDays)
    }

    /** A "prediction" recorded once bleeding has started is a reading of the present. Counting it
     *  would let the app grade itself on days it could already see the answer. */
    @Test
    fun `records made after the period arrived are excluded`() {
        val cycleStart = "2024-02-12"
        val scored = listOf(
            ScoredPrediction(record("2024-02-27", cycleStart, "2024-03-11"), date("2024-03-14")),
            ScoredPrediction(record("2024-03-16", cycleStart, "2024-03-14"), date("2024-03-14")),
        )

        val latest = PredictionScorer.latestPerCycle(scored)

        assertEquals(date("2024-02-27"), latest.single().record.madeOn)
    }

    @Test
    fun `lead time filter compares like with like`() {
        val scored = listOf(
            ScoredPrediction(record("2024-02-13", "2024-02-12", "2024-03-11"), date("2024-03-11")),
            ScoredPrediction(record("2024-03-09", "2024-02-12", "2024-03-11"), date("2024-03-11")),
        )

        val farOut = PredictionScorer.atLeadTimeAtLeast(scored, days = 7)

        assertEquals(1, farOut.size)
        assertEquals(date("2024-02-13"), farOut.single().record.madeOn)
    }

    /** Each entry gets its own cycle, a month apart, so aggregation helpers see distinct cycles. */
    private fun scoredWithError(index: Int, errorDays: Int): ScoredPrediction {
        val cycleStart = date("2025-01-01").plusDays(index * 28L)
        val predicted = cycleStart.plusDays(28)
        return ScoredPrediction(
            record = PredictionRecord(
                madeOn = predicted.minusDays(7),
                cycleStart = cycleStart,
                predictedNextPeriod = predicted,
                expectedCycleLength = 28,
                variability = null,
            ),
            actualNextPeriod = predicted.plusDays(errorDays.toLong()),
        )
    }
}
