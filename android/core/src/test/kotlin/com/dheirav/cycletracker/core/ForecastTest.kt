package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The window exists to stop the app claiming more precision than it has, so the tests that matter
 * are the ones about *width* — that ignorance produces a wide band, that a measured band is
 * narrower than an assumed one, and that neither can ever collapse to a single day.
 */
class ForecastTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    /** Cycles of the given lengths, back to back, ending most recently. */
    private fun cycles(vararg lengths: Int, source: Source = Source.OBSERVED): List<Cycle> {
        var start = date("2025-01-01")
        return lengths.map { length ->
            val end = start.plusDays(length - 1L)
            Cycle(start, end, length, source).also { start = end.plusDays(1) }
        }
    }

    // -- the window --------------------------------------------------------

    /** §6 — no cycle means no prediction, not a prediction from nothing. */
    @Test
    fun `no cycle start yields no window`() {
        assertNull(Forecast.periodWindow(null, 28, emptyList()))
    }

    @Test
    fun `with no measured variability the window is assumed and wide`() {
        val window = Forecast.periodWindow(
            cycleStart = date("2024-03-11"),
            expectedCycleLength = 28,
            cycles = cycles(28, source = Source.ASSUMED),
        )!!

        assertEquals(WindowBasis.ASSUMED, window.basis)
        assertEquals(0, window.observedCycles)
        assertEquals(date("2024-04-08"), window.center)
        // 4.0 assumed days x 1.0 multiplier.
        assertEquals(4, window.halfWidthDays)
        assertEquals(date("2024-04-04"), window.earliest)
        assertEquals(date("2024-04-12"), window.latest)
        assertEquals(9, window.spanDays)
    }

    /**
     * The case the seeded database is actually in: twelve cycles, eleven extrapolated. The
     * synthetic ones have a standard deviation of exactly zero, and if they reached the window it
     * would collapse to a single day off the back of data nobody observed (§3.2).
     */
    @Test
    fun `a uniform synthetic backfill cannot produce a narrow window`() {
        val backfill = cycles(28, 28, 28, 28, 28, 28, source = Source.ASSUMED)

        val window = Forecast.periodWindow(date("2024-03-11"), 28, backfill)!!

        assertEquals(WindowBasis.ASSUMED, window.basis)
        assertTrue("synthetic data must not narrow the window", window.halfWidthDays >= 4)
    }

    @Test
    fun `measured variability narrows the window and marks it measured`() {
        // Six observed cycles, standard deviation well under the assumed 4 days.
        val window = Forecast.periodWindow(
            cycleStart = date("2024-03-11"),
            expectedCycleLength = 28,
            cycles = cycles(27, 28, 28, 29, 28, 28),
        )!!

        assertEquals(WindowBasis.MEASURED, window.basis)
        assertEquals(6, window.observedCycles)
        assertTrue(
            "measured window should be narrower than the assumed default",
            window.halfWidthDays < 4,
        )
    }

    @Test
    fun `erratic cycles produce a wider window than regular ones`() {
        val regular = Forecast.periodWindow(date("2024-03-11"), 28, cycles(28, 28, 27, 28, 29, 28))!!
        val erratic = Forecast.periodWindow(date("2024-03-11"), 28, cycles(22, 34, 26, 31, 24, 35))!!

        assertTrue(
            "erratic ${erratic.halfWidthDays} should exceed regular ${regular.halfWidthDays}",
            erratic.halfWidthDays > regular.halfWidthDays,
        )
    }

    /** However regular the history looks, naming one exact day is not a supportable claim. */
    @Test
    fun `a perfectly regular history still gets a window at least one day either side`() {
        val window = Forecast.periodWindow(date("2024-03-11"), 28, cycles(28, 28, 28, 28, 28, 28))!!

        // Six observed identical cycles: standard deviation is genuinely zero, so the floor is
        // the only thing keeping this off a single date.
        assertEquals(WindowBasis.MEASURED, window.basis)
        assertEquals(1, window.halfWidthDays)
        assertEquals(3, window.spanDays)
        assertFalse(window.center == window.earliest)
    }

    /** Fewer observed cycles means less is known, and the band must reflect that. */
    @Test
    fun `the same spread widens the window when fewer cycles support it`() {
        val few = Forecast.periodWindow(date("2024-03-11"), 28, cycles(25, 28, 31))!!
        val many = Forecast.periodWindow(
            date("2024-03-11"), 28, cycles(25, 28, 31, 25, 28, 31),
        )!!

        assertTrue(
            "3 cycles (${few.halfWidthDays}) should not be narrower than 6 (${many.halfWidthDays})",
            few.halfWidthDays >= many.halfWidthDays,
        )
    }

    @Test
    fun `contains covers the whole inclusive range`() {
        val window = Forecast.periodWindow(date("2024-03-11"), 28, emptyList())!!

        assertTrue(window.earliest in window)
        assertTrue(window.center in window)
        assertTrue(window.latest in window)
        assertFalse(window.earliest.minusDays(1) in window)
        assertFalse(window.latest.plusDays(1) in window)
    }

    // -- the receipt -------------------------------------------------------

    /**
     * The headline case for showing receipts at all: the seeded database reports twelve cycles
     * behind a 28-day estimate, and exactly one of them was ever observed.
     */
    @Test
    fun `basis separates observed cycles from extrapolated ones`() {
        val backfill = cycles(28, 28, 28, 28, 28, source = Source.ASSUMED)
        val real = cycles(29, source = Source.OBSERVED)

        val basis = Forecast.basis(backfill + real)

        assertEquals(6, basis.cyclesUsed)
        assertEquals(1, basis.observedCycles)
        assertEquals(5, basis.assumedCycles)
        assertTrue(basis.mostlyAssumed)
        assertNull("one observed cycle cannot yield a variability", basis.variability)
    }

    @Test
    fun `basis reports the median as the source once there are enough observed cycles`() {
        val basis = Forecast.basis(cycles(27, 28, 29))

        assertEquals(LengthSource.MEDIAN_OF_OBSERVED, basis.source)
        assertEquals(28, basis.expectedCycleLength)
        assertEquals(28.0, basis.medianCycleLength!!, 1e-9)
        assertFalse(basis.mostlyAssumed)
    }

    @Test
    fun `basis falls back to the user's stated length and says so`() {
        val basis = Forecast.basis(cycles(30), userTypicalCycleLength = 30)

        assertEquals(LengthSource.USER_STATED, basis.source)
        assertEquals(30, basis.expectedCycleLength)
        assertEquals(1, basis.cyclesUsed)
    }

    /**
     * The bug this ordering exists to prevent.
     *
     * The seeded database carries eleven extrapolated 28-day cycles. Under the old rule those
     * three-or-more cycles won outright, so a user who said "mine are 31" was overruled by data
     * the app had invented and then quoted back at them as measurement.
     */
    @Test
    fun `a stated cycle length beats extrapolated backfill`() {
        val backfill = cycles(28, 28, 28, 28, 28, 28, source = Source.ASSUMED)

        val basis = Forecast.basis(backfill, userTypicalCycleLength = 31)

        assertEquals(LengthSource.USER_STATED, basis.source)
        assertEquals(31, basis.expectedCycleLength)
    }

    /** ...but a real measurement still beats what the user believes. Three observed cycles are
     *  evidence about this body; a remembered typical length is a recollection. */
    @Test
    fun `observed cycles beat a stated cycle length`() {
        val basis = Forecast.basis(cycles(26, 27, 26), userTypicalCycleLength = 31)

        assertEquals(LengthSource.MEDIAN_OF_OBSERVED, basis.source)
        assertEquals(26, basis.expectedCycleLength)
    }

    /** With nothing stated, behaviour is exactly as before — backfill still seeds the estimate. */
    @Test
    fun `backfill still seeds the estimate when the user has stated nothing`() {
        val basis = Forecast.basis(cycles(30, 30, 30, source = Source.ASSUMED))

        assertEquals(LengthSource.MEDIAN_WITH_ESTIMATES, basis.source)
        assertEquals(30, basis.expectedCycleLength)
    }

    @Test
    fun `basis falls back to the app default when there is nothing else`() {
        val basis = Forecast.basis(emptyList())

        assertEquals(LengthSource.APP_DEFAULT, basis.source)
        assertEquals(28, basis.expectedCycleLength)
        assertEquals(0, basis.cyclesUsed)
        assertNull(basis.medianCycleLength)
    }

    /** Implausible cycles are logging errors and must not appear in the receipt's counts either,
     *  or the explanation would describe a calculation that did not happen. */
    @Test
    fun `basis excludes implausible cycles just as the estimate does`() {
        val basis = Forecast.basis(cycles(28, 3, 29, 90, 28))

        assertEquals(3, basis.cyclesUsed)
        assertEquals(28, basis.expectedCycleLength)
    }
}
