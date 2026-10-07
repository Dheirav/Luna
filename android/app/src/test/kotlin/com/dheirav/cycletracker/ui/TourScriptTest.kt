package com.dheirav.cycletracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guided tour's script.
 *
 * Asked for on 5 Oct 2026: show where to tap in the app itself, not a stack of text. So each step
 * points at a real element, says one short thing, and the steps that move between screens are done by
 * tapping the real button. These tests keep the script honest about that: short captions, every
 * tap-step leading somewhere, and each step on the screen the previous one left the person on.
 */
class TourScriptTest {

    private val steps = tourSteps()

    @Test
    fun `it starts on Today and visits every main screen`() {
        assertEquals(TourScreen.TODAY, steps.first().screen)
        assertEquals(TourScreen.entries.toSet(), steps.map { it.screen }.toSet())
    }

    /** Show, not tell: a caption is a line next to the thing, not a paragraph. */
    @Test
    fun `every caption is short`() {
        steps.flatMap { listOfNotNull(it.caption, it.captionWhenWindowPassed) }
            .forEach { assertTrue("too long: $it", it.length <= 90) }
    }

    @Test
    fun `every tap-the-real-button step says where it leads`() {
        steps.filter { it.tapToContinue }.forEach { assertNotNull(it.caption, it.leadsTo) }
        steps.filterNot { it.tapToContinue }.forEach { assertNull(it.caption, it.leadsTo) }
    }

    /** A step must be on the screen the step before it left the person on, or its spotlight is empty. */
    @Test
    fun `each step is on the screen the previous one leads to`() {
        steps.zipWithNext().forEach { (before, after) ->
            val expected = before.leadsTo ?: before.screen
            assertEquals("${before.caption} -> ${after.caption}", expected, after.screen)
        }
    }

    @Test
    fun `nothing that writes data is ever a tap-through step`() {
        val writes = setOf(TourTarget.LOG_SAVE, TourTarget.LOG_BLEEDING)
        assertTrue(steps.none { it.target in writes && it.tapToContinue })
    }

    @Test
    fun `the tour advances when the person reaches the screen a tap-step leads to`() {
        val logIndex = steps.indexOfFirst { it.target == TourTarget.LOG_BUTTON }
        assertEquals(logIndex + 1, advanceOnScreen(steps, logIndex, TourScreen.LOG))
        assertEquals("staying put changes nothing", logIndex, advanceOnScreen(steps, logIndex, TourScreen.TODAY))
    }

    @Test
    fun `a next-step does not advance on a screen change`() {
        val hero = steps.indexOfFirst { it.target == TourTarget.HERO }
        assertEquals(hero, advanceOnScreen(steps, hero, TourScreen.LOG))
    }
}
