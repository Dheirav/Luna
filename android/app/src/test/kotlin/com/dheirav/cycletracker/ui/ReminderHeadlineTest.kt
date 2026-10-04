package com.dheirav.cycletracker.ui

import com.dheirav.cycletracker.reminder.ReminderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * The one line the reminder card shows while its diagnostics are folded away.
 *
 * Folding them only works if this line is honest about what is behind it. The failure to avoid is a
 * reassuring headline over a broken reminder, since then nobody opens the details, and the details
 * are the only place the fault was ever visible. So a fault always wins the headline and always
 * reports itself as a problem, which is what opens the card on its own.
 */
class ReminderHeadlineTest {

    private val fired = Instant.parse("2026-10-04T15:30:00Z")
    private val stamp: (Instant) -> String = { "4 Oct, 21:00" }

    private fun status(
        enabled: Boolean = true,
        lastFired: Instant? = fired,
        notificationsAllowed: Boolean = true,
        batteryUnrestricted: Boolean = true,
        looksBroken: Boolean = false,
    ) = ReminderStatus(
        enabled = enabled,
        nextFireAt = LocalDateTime.parse("2026-10-05T21:00"),
        lastFired = lastFired,
        workState = "ENQUEUED",
        notificationsAllowed = notificationsAllowed,
        batteryUnrestricted = batteryUnrestricted,
        looksBroken = looksBroken,
    )

    @Test
    fun `a working reminder says when it last fired, and is not a problem`() {
        val headline = reminderHeadline(status(), stamp)
        assertEquals("Working · last fired 4 Oct, 21:00", headline.text)
        assertFalse(headline.problem)
    }

    @Test
    fun `blocked notifications are a problem whatever else looks fine`() {
        val headline = reminderHeadline(status(notificationsAllowed = false), stamp)
        assertTrue(headline.problem)
        assertEquals("Blocked: notifications are off for this app", headline.text)
    }

    @Test
    fun `a reminder that stopped firing is a problem`() {
        val headline = reminderHeadline(status(looksBroken = true), stamp)
        assertTrue(headline.problem)
        assertEquals("Stopped: a reminder was due and did not fire", headline.text)
    }

    /** A restriction is a risk, not a fault: it is mentioned without forcing the card open. */
    @Test
    fun `restricted battery is mentioned but does not count as a problem`() {
        val headline = reminderHeadline(status(batteryUnrestricted = false), stamp)
        assertFalse(headline.problem)
        assertEquals("Working · last fired 4 Oct, 21:00 · battery restricted", headline.text)
    }

    @Test
    fun `a reminder that has never fired says so rather than claiming to work`() {
        val headline = reminderHeadline(status(lastFired = null), stamp)
        assertEquals("Set, has not fired yet", headline.text)
        assertFalse(headline.problem)
    }

    @Test
    fun `switched off is simply off`() {
        val headline = reminderHeadline(status(enabled = false, notificationsAllowed = false), stamp)
        assertEquals("Off", headline.text)
        assertFalse(headline.problem)
    }
}
