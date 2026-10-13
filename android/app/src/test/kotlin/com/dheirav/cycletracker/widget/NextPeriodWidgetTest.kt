package com.dheirav.cycletracker.widget

import com.dheirav.cycletracker.core.PeriodWindow
import com.dheirav.cycletracker.core.WindowBasis
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The countdown is a range of days, like the window it counts down to, and never one number. */
class NextPeriodWidgetTest {

    private fun d(iso: String) = LocalDate.parse(iso)
    private val window = PeriodWindow(d("2026-03-10"), d("2026-03-08"), d("2026-03-12"), 2, WindowBasis.MEASURED, 3)

    @Test
    fun `before the window it is a range of days`() {
        assertEquals("in 5 to 9 days", countdown(window, d("2026-03-03")))
    }

    @Test
    fun `inside the window it says it could start any day`() {
        assertEquals("Could start any day now", countdown(window, d("2026-03-08")))
        assertEquals("Could start any day now", countdown(window, d("2026-03-11")))
    }
}
