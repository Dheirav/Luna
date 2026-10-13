package com.dheirav.cycletracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One page before the tour, whatever the data. The setup page that used to follow it held the tour
 * back after a fresh install; its job is now a button on the empty Today screen.
 */
class OnboardingPagesTest {

    @Test
    fun `only the privacy page comes before the tour`() {
        assertEquals(listOf(OnboardingPage.PRIVATE), onboardingPages(hasPeriods = false))
        assertEquals(listOf(OnboardingPage.PRIVATE), onboardingPages(hasPeriods = true))
    }
}
