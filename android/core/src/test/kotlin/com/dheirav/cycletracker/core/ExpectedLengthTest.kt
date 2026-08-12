package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The precedence rule of §3, and the one property that had nothing holding it: that the basis a user
 * reads describes the branch the number actually came from.
 *
 * [Forecast.basis] used to reimplement the rule so it could name it, and its copy of the sample
 * selection applied the source filter and the last-N window in the opposite order to
 * [CycleStats.expectedLength]. `observed >= 3` and `observed.size >= 3` therefore meant different
 * things, and the two agreed only while assumed cycles stayed older than observed ones — true of the
 * data the app had, and recorded nowhere as a requirement.
 *
 * `the label cannot contradict the number` below is that case, built to fail against the old code.
 */
class ExpectedLengthTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    /** Cycles of the given lengths, back to back, earliest first. */
    private fun cycles(vararg lengths: Int, source: Source = Source.OBSERVED): List<Cycle> {
        var start = date("2025-01-01")
        return lengths.map { length ->
            val end = start.plusDays(length - 1L)
            Cycle(start, end, length, source).also { start = end.plusDays(1) }
        }
    }

    /** Appends `later` after `earlier` in time, keeping each run's own source. */
    private fun then(earlier: List<Cycle>, lengths: IntArray, source: Source): List<Cycle> {
        var start = earlier.last().end!!.plusDays(1)
        return earlier + lengths.map { length ->
            val end = start.plusDays(length - 1L)
            Cycle(start, end, length, source).also { start = end.plusDays(1) }
        }
    }

    // -- the four branches, in precedence order ----------------------------

    @Test
    fun `three observed cycles beat everything`() {
        val result = CycleStats.expectedLength(cycles(30, 30, 30), userTypicalCycleLength = 25)
        assertEquals(LengthSource.MEDIAN_OF_OBSERVED, result.source)
        assertEquals(30, result.days)
    }

    @Test
    fun `below three observed the user outranks the app's own estimates`() {
        val history = then(cycles(30, 30), intArrayOf(28, 28, 28, 28), Source.ASSUMED)
        val result = CycleStats.expectedLength(history, userTypicalCycleLength = 31)
        assertEquals(LengthSource.USER_STATED, result.source)
        assertEquals(31, result.days)
    }

    @Test
    fun `with nothing stated the estimates are used and labelled as such`() {
        val result = CycleStats.expectedLength(cycles(28, 28, 28, source = Source.ASSUMED))
        assertEquals(LengthSource.MEDIAN_WITH_ESTIMATES, result.source)
        assertEquals(28, result.days)
    }

    @Test
    fun `with no cycles and nothing stated it is the app default, and says so`() {
        val result = CycleStats.expectedLength(emptyList())
        assertEquals(LengthSource.APP_DEFAULT, result.source)
        assertEquals(CycleConfig.Default.defaultCycleLength, result.days)
    }

    // -- the label and the number cannot disagree --------------------------

    /**
     * Three observed cycles, then five assumed ones *after* them — the ordering nothing guaranteed.
     *
     * The sample window is six, so the last six cycles of any source hold only one observed cycle.
     * Counting observed cycles inside that window gives 1 and sends the label to USER_STATED;
     * counting the last six *observed* cycles gives 3 and sends the number to the observed median.
     * The old code did one of each, and reported "31 days, as you stated" over a number that was
     * the median of cycles the user never stated anything about — or 30 labelled as 31's source.
     */
    @Test
    fun `the label cannot contradict the number when assumed cycles are the recent ones`() {
        val history = then(cycles(30, 30, 30), intArrayOf(28, 28, 28, 28, 28), Source.ASSUMED)
        val basis = Forecast.basis(history, userTypicalCycleLength = 31)

        assertEquals(LengthSource.MEDIAN_OF_OBSERVED, basis.source)
        assertEquals(30, basis.expectedCycleLength)
        // The number is not the stated one, so the label must not claim the user stated it.
        assertNotEquals(31, basis.expectedCycleLength)
        assertEquals(3, basis.observedCycles)
    }

    /**
     * The general invariant, over every shape the branches can take.
     *
     * `basis` and `expectedCycleLength` must return the same number, and the source must be the one
     * that produced it. Checked as a pair rather than separately — either alone can be right while
     * the two disagree, which is exactly the bug this file exists for.
     */
    @Test
    fun `basis always agrees with the length and with its own source`() {
        val histories = listOf(
            emptyList(),
            cycles(28),
            cycles(30, 30, 30),
            cycles(28, 28, 28, source = Source.ASSUMED),
            then(cycles(30, 30), intArrayOf(28, 28, 28, 28), Source.ASSUMED),
            then(cycles(30, 30, 30), intArrayOf(28, 28, 28, 28, 28), Source.ASSUMED),
            then(cycles(28, 28, 28, 28, 28, source = Source.ASSUMED), intArrayOf(31, 31, 31), Source.OBSERVED),
            // A cycle outside the plausible range must not count toward any threshold.
            cycles(30, 30, 200),
        )
        listOf(null, 31).forEach { stated ->
            histories.forEach { history ->
                val basis = Forecast.basis(history, userTypicalCycleLength = stated)
                val expected = CycleStats.expectedLength(history, userTypicalCycleLength = stated)

                assertEquals(
                    "number disagrees for stated=$stated, ${history.size} cycles",
                    CycleStats.expectedCycleLength(history, userTypicalCycleLength = stated),
                    basis.expectedCycleLength,
                )
                assertEquals(
                    "source disagrees for stated=$stated, ${history.size} cycles",
                    expected.source,
                    basis.source,
                )
                // MEDIAN_OF_OBSERVED is claimed only when three observed cycles really are there.
                if (basis.source == LengthSource.MEDIAN_OF_OBSERVED) {
                    assertEquals(
                        "claimed observed median with too few observed cycles",
                        true,
                        basis.observedCycles >= 3,
                    )
                }
                // USER_STATED is claimed only when the number really is what the user said.
                if (basis.source == LengthSource.USER_STATED) {
                    assertEquals(stated, basis.expectedCycleLength)
                }
            }
        }
    }
}
