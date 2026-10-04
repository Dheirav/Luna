package com.dheirav.cycletracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which walkthrough pages appear.
 *
 * The setup page writes data, so it must only ever appear on a first run with nothing logged. A
 * replay from Settings, or a first run on a phone that already has periods (an update, or a
 * restore), would otherwise invite someone to enter a period twice.
 */
class OnboardingPagesTest {

    @Test
    fun `a first run with nothing logged ends on setup`() {
        val pages = onboardingPages(replay = false, hasPeriods = false)
        assertEquals(OnboardingPage.SETUP, pages.last())
        assertEquals(OnboardingPage.PRIVATE, pages.first())
    }

    @Test
    fun `a first run with periods already logged skips setup`() {
        assertFalse(OnboardingPage.SETUP in onboardingPages(replay = false, hasPeriods = true))
    }

    @Test
    fun `a replay from settings never offers setup`() {
        assertFalse(OnboardingPage.SETUP in onboardingPages(replay = true, hasPeriods = false))
    }

    @Test
    fun `every explanation page appears in every case`() {
        val explained = OnboardingPage.entries - OnboardingPage.SETUP
        listOf(true, false).forEach { replay ->
            listOf(true, false).forEach { hasPeriods ->
                assertTrue(onboardingPages(replay, hasPeriods).containsAll(explained))
            }
        }
    }
}
