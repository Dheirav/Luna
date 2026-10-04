package com.dheirav.cycletracker.ui

import com.dheirav.cycletracker.core.BackupDay
import com.dheirav.cycletracker.core.BackupSettings
import com.dheirav.cycletracker.core.BackupSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * What the person is told before a restore, and when they are nudged to back up.
 *
 * Restore replaces everything, and it used to happen straight after the file picker, so choosing last
 * year's backup threw away months of logs with nothing shown first. The preview is the safeguard, so
 * it has to say the thing that matters: whether the backup is missing days the phone has.
 */
class BackupWordingTest {

    private val zone = ZoneOffset.UTC
    private fun day(iso: String) = BackupDay(date = iso, isBleeding = false)

    private val backup = BackupSnapshot(
        exportedAt = "2026-08-01T10:00:00Z",
        days = listOf(day("2026-07-01"), day("2026-07-30")),
        settings = BackupSettings(typicalCycleLength = 30),
    )

    @Test
    fun `the preview names the backup's date and size against the phone's`() {
        Locale.setDefault(Locale.UK)
        val text = restorePreview(backup, phoneDays = 412, phoneNewest = LocalDate.parse("2026-10-05"), zone = zone)

        assertTrue(text, text.contains("1 Aug 2026"))
        assertTrue(text, text.contains("2 days"))
        assertTrue(text, text.contains("412 days"))
    }

    /** The case the preview exists for: an older backup would silently drop recent days. */
    @Test
    fun `the preview warns when the backup is missing recent days`() {
        Locale.setDefault(Locale.UK)
        val text = restorePreview(backup, phoneDays = 412, phoneNewest = LocalDate.parse("2026-10-05"), zone = zone)
        assertTrue(text, text.contains("anything logged after 30 Jul 2026"))
    }

    @Test
    fun `no missing-days warning when the backup is as recent as the phone`() {
        Locale.setDefault(Locale.UK)
        val text = restorePreview(backup, phoneDays = 2, phoneNewest = LocalDate.parse("2026-07-30"), zone = zone)
        assertFalse(text, text.contains("anything logged after"))
    }

    @Test
    fun `the preview says whether settings come too`() {
        Locale.setDefault(Locale.UK)
        val withSettings = restorePreview(backup, 0, null, zone)
        val without = restorePreview(backup.copy(settings = null), 0, null, zone)
        assertTrue(withSettings, withSettings.contains("including settings"))
        assertTrue(without, without.contains("keeps this phone's settings"))
    }

    // -- the nudge ---------------------------------------------------------------

    private val now = Instant.parse("2026-10-05T12:00:00Z")

    @Test
    fun `nothing logged means nothing to nudge about`() {
        assertFalse(backupDue(lastBackup = null, earliestLog = null, now = now, zone = zone))
    }

    @Test
    fun `a backup within 30 days is not due`() {
        assertFalse(backupDue(now.minusSeconds(29L * 86_400), LocalDate.parse("2025-01-01"), now, zone))
    }

    @Test
    fun `a backup older than 30 days is due`() {
        assertTrue(backupDue(now.minusSeconds(31L * 86_400), LocalDate.parse("2025-01-01"), now, zone))
    }

    /** Never nag a brand-new user on day one: only once there is a month of data to lose. */
    @Test
    fun `never backed up is due only once there is a month of history`() {
        assertFalse(backupDue(null, LocalDate.parse("2026-09-20"), now, zone))
        assertTrue(backupDue(null, LocalDate.parse("2026-09-01"), now, zone))
    }
}
