package com.dheirav.cycletracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which pages come before the tour.
 *
 * Everything that can be pointed at is shown by the tour on the real screens. What is left is the
 * welcome, and the setup page, which writes data and so must only appear with nothing logged: on a
 * phone that already has history it would invite entering a period twice.
 */
class OnboardingPagesTest {

    @Test
    fun `with nothing logged, the welcome is followed by setup`() {
        assertEquals(listOf(OnboardingPage.PRIVATE, OnboardingPage.SETUP), onboardingPages(hasPeriods = false))
    }

    @Test
    fun `with periods already logged, only the welcome`() {
        assertEquals(listOf(OnboardingPage.PRIVATE), onboardingPages(hasPeriods = true))
    }
}
