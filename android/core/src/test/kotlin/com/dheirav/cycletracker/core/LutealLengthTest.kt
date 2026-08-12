package com.dheirav.cycletracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 4's measured luteal length.
 *
 * The tests that matter are the ones about *refusing* to measure. §7 permits refining luteal length
 * only from a temperature shift, and the failure this guards is not a wrong number — it is a
 * confident number produced from a sparse or noisy series, which would then feed the fertility
 * window and look exactly like a measurement.
 */
class LutealLengthTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    /** Cycles of `length` days each, back to back from `first`, all observed. */
    private fun cycles(first: String, vararg lengths: Int): List<Cycle> {
        var start = date(first)
        return lengths.map { length ->
            val end = start.plusDays(length - 1L)
            Cycle(start, end, length, Source.OBSERVED).also { start = end.plusDays(1) }
        }
    }

    /**
     * A biphasic series for one cycle: flat at `low`, then `low + rise` from `shiftOnDay` onward.
     *
     * `shiftOnDay` is 1-based cycle day, so the temperature rise appears on that day and ovulation
     * should be read as the day before it.
     */
    private fun biphasic(
        cycleStart: String,
        cycleLength: Int,
        shiftOnDay: Int,
        low: Int = 3640,
        rise: Int = 30,
    ): List<TemperatureReading> {
        val start = date(cycleStart)
        return (1..cycleLength).map { day ->
            TemperatureReading(
                date = start.plusDays(day - 1L),
                centidegrees = if (day >= shiftOnDay) low + rise else low,
            )
        }
    }

    // -- reading a shift ---------------------------------------------------

    /** Ovulation is the last low day, not the first high one — the rise lags the event. */
    @Test
    fun `ovulation is placed the day before the sustained rise`() {
        val temps = biphasic("2025-01-01", cycleLength = 28, shiftOnDay = 15)

        val ovulation = LutealLength.detectShift(
            cycleStart = date("2025-01-01"),
            nextCycleStart = date("2025-01-29"),
            byDate = temps.associate { it.date to it.centidegrees },
        )

        assertEquals(date("2025-01-14"), ovulation)
    }

    /** 28-day cycle, ovulation on day 14, luteal 14 — the figure the default happens to assume. */
    @Test
    fun `luteal length runs from ovulation to the next period`() {
        val detections = LutealLength.detectAll(
            cycles = cycles("2025-01-01", 28),
            temperatures = biphasic("2025-01-01", 28, shiftOnDay = 15),
        )

        assertEquals(1, detections.size)
        assertEquals(14, detections.single().lutealDays)
    }

    @Test
    fun `a rise below the threshold is not a shift`() {
        // 0.15 °C, under the 0.2 °C the three-over-six rule requires.
        val temps = biphasic("2025-01-01", 28, shiftOnDay = 15, rise = 15)

        assertNull(
            LutealLength.detectShift(
                date("2025-01-01"), date("2025-01-29"),
                temps.associate { it.date to it.centidegrees },
            ),
        )
    }

    /** One warm morning is not a shift. Alcohol and a short night both do this. */
    @Test
    fun `a single elevated day is not a shift`() {
        val start = date("2025-01-01")
        val temps = (1..28).map { day ->
            TemperatureReading(start.plusDays(day - 1L), if (day == 15) 3690 else 3640)
        }

        assertNull(
            LutealLength.detectShift(
                start, date("2025-01-29"),
                temps.associate { it.date to it.centidegrees },
            ),
        )
    }

    /**
     * A gap breaks the run it falls in, and the scan then finds a later one.
     *
     * The missing morning is not read through — absent is not "presumably also elevated" — but the
     * consequence is not "no detection" either, which is what this test originally asserted and got
     * wrong. The run starting at the true rise fails, a run starting two days later succeeds, and the
     * measured ovulation lands late.
     *
     * **So a sparse logger gets a short luteal phase, not a missing one**, and nothing on the surface
     * distinguishes the two. That is the most important limitation of this method and the reason it is
     * asserted rather than left as a comment: a systematic bias in one direction is far more dangerous
     * than noise, because a median does not cancel it.
     */
    @Test
    fun `a gap inside a run delays the shift rather than reading through it`() {
        val temps = biphasic("2025-01-01", 28, shiftOnDay = 15)
            .filterNot { it.date == date("2025-01-16") }

        val ovulation = LutealLength.detectShift(
            date("2025-01-01"), date("2025-01-29"),
            temps.associate { it.date to it.centidegrees },
        )

        assertEquals(date("2025-01-16"), ovulation)
        // Which is to say: two days late, and the luteal phase two days short.
        assertEquals(14 - 2, daysBetween(date("2025-01-16"), date("2025-01-28")))
    }

    /** Fewer than six prior readings means no baseline, so nothing can be called elevated. */
    @Test
    fun `too few readings before the rise gives no baseline`() {
        val start = date("2025-01-01")
        // Readings begin on day 12, so the rise on day 15 has only three days behind it.
        val temps = (12..28).map { day ->
            TemperatureReading(start.plusDays(day - 1L), if (day >= 15) 3670 else 3640)
        }

        assertNull(
            LutealLength.detectShift(
                start, date("2025-01-29"),
                temps.associate { it.date to it.centidegrees },
            ),
        )
    }

    /**
     * Six readings scattered over a month are not a baseline.
     *
     * This is the sparse-logger case, and the one most likely to produce a confident answer from
     * data that cannot support one.
     */
    @Test
    fun `a baseline spread too thin is rejected`() {
        val start = date("2025-01-01")
        val sparse = listOf(1, 3, 5, 7, 9, 11).map {
            TemperatureReading(start.plusDays(it - 1L), 3640)
        }
        // Then a genuine three-day run much later, with the baseline now stale.
        val run = (25..27).map { TemperatureReading(start.plusDays(it - 1L), 3670) }

        assertNull(
            LutealLength.detectShift(
                start, date("2025-01-29"),
                (sparse + run).associate { it.date to it.centidegrees },
            ),
        )
    }

    // -- the estimate ------------------------------------------------------

    @Test
    fun `below three measured cycles it stays the app default and says so`() {
        val two = cycles("2025-01-01", 28, 28)
        val temps = biphasic("2025-01-01", 28, shiftOnDay = 15) +
            biphasic("2025-01-29", 28, shiftOnDay = 15)

        val estimate = LutealLength.estimate(two, temps)

        assertEquals(LutealSource.APP_DEFAULT, estimate.source)
        assertEquals(CycleConfig.Default.defaultLutealLength, estimate.days)
        assertEquals(2, estimate.cyclesUsed)
    }

    @Test
    fun `three measured cycles produce a measured median`() {
        val three = cycles("2025-01-01", 28, 28, 28)
        // Shifts on day 17 give a 12-day luteal phase, distinct from the 14-day default so the test
        // cannot pass by accident.
        val temps = biphasic("2025-01-01", 28, shiftOnDay = 17) +
            biphasic("2025-01-29", 28, shiftOnDay = 17) +
            biphasic("2025-02-26", 28, shiftOnDay = 17)

        val estimate = LutealLength.estimate(three, temps)

        assertEquals(LutealSource.MEASURED, estimate.source)
        assertEquals(12, estimate.days)
        assertEquals(3, estimate.cyclesUsed)
    }

    /** An implausible luteal phase is dropped rather than dragged into the median. */
    @Test
    fun `an implausible measurement is excluded`() {
        val cycles = cycles("2025-01-01", 28, 28, 28)
        // The middle cycle's rise lands on day 26, giving a 3-day luteal phase — outside 8..18.
        val temps = biphasic("2025-01-01", 28, shiftOnDay = 15) +
            biphasic("2025-01-29", 28, shiftOnDay = 26) +
            biphasic("2025-02-26", 28, shiftOnDay = 15)

        val estimate = LutealLength.estimate(cycles, temps)

        assertEquals(2, estimate.cyclesUsed)
        assertEquals(LutealSource.APP_DEFAULT, estimate.source)
    }

    /**
     * Estimated cycles are never measured, however much temperature data surrounds them.
     *
     * An extrapolated cycle boundary is invented, so pairing it with real readings would produce a
     * luteal length for a cycle nobody lived.
     */
    @Test
    fun `assumed cycles are ignored`() {
        var start = date("2025-01-01")
        val assumed = (1..3).map {
            val end = start.plusDays(27)
            Cycle(start, end, 28, Source.ASSUMED).also { _ -> start = end.plusDays(1) }
        }
        val temps = biphasic("2025-01-01", 28, shiftOnDay = 17) +
            biphasic("2025-01-29", 28, shiftOnDay = 17) +
            biphasic("2025-02-26", 28, shiftOnDay = 17)

        val estimate = LutealLength.estimate(assumed, temps)

        assertEquals(0, estimate.cyclesUsed)
        assertEquals(LutealSource.APP_DEFAULT, estimate.source)
    }

    @Test
    fun `no temperature data at all is a default, not a failure`() {
        val estimate = LutealLength.estimate(cycles("2025-01-01", 28, 28, 28), emptyList())

        assertEquals(LutealSource.APP_DEFAULT, estimate.source)
        assertEquals(0, estimate.cyclesUsed)
        assertEquals(emptyList<OvulationDetection>(), estimate.detections)
    }

    /**
     * The first qualifying run wins, not the warmest.
     *
     * A later, larger run would drift the measured ovulation toward the end of the cycle and shorten
     * every luteal phase it touched — worst on the noisiest data.
     */
    @Test
    fun `the earliest qualifying shift is taken`() {
        val start = date("2025-01-01")
        val temps = (1..28).map { day ->
            val v = when {
                day >= 22 -> 3720
                day >= 15 -> 3670
                else -> 3640
            }
            TemperatureReading(start.plusDays(day - 1L), v)
        }

        val ovulation = LutealLength.detectShift(
            start, date("2025-01-29"),
            temps.associate { it.date to it.centidegrees },
        )

        assertEquals(date("2025-01-14"), ovulation)
    }
}
