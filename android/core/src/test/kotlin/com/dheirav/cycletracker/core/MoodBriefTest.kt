package com.dheirav.cycletracker.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The short mood lines exist for surfaces with one row and no space to hedge at length.
 *
 * That makes them the easiest place for population content to start sounding personal, which
 * `Guidance` names as the failure mode of every cycle app that tells you how you feel today. The
 * framing has to come from the surface — the widget prefixes "Typically:" — so the phrase itself must
 * never contain a second person. These tests hold that, because a later edit adding "you" would read
 * perfectly well and quietly turn generic information into a claim about the reader.
 */
class MoodBriefTest {

    private val briefs = Phase.entries.associateWith { Guidance.forPhase(it).moodBrief }

    @Test
    fun `every phase has one`() {
        briefs.forEach { (phase, brief) ->
            assertTrue("$phase has a blank moodBrief", brief.isNotBlank())
        }
    }

    /** One line beside a 32dp face leaves very little room; long ones ellipsize into nonsense. */
    @Test
    fun `each stays short enough for a widget row`() {
        briefs.forEach { (phase, brief) ->
            assertTrue(
                "$phase moodBrief is ${brief.length} chars, too long for one row: $brief",
                brief.length <= 40,
            )
        }
    }

    /**
     * No second person, and no future tense that amounts to one.
     *
     * "energy at its lowest" describes a phase. "you will feel flat" describes the reader, and the app
     * has measured nothing about them. The difference is one word.
     */
    @Test
    fun `none of them addresses the reader`() {
        val secondPerson = Regex("\\b(you|your|yours|you'll|you're)\\b", RegexOption.IGNORE_CASE)
        briefs.forEach { (phase, brief) ->
            assertFalse(
                "$phase moodBrief speaks to the reader: $brief",
                secondPerson.containsMatchIn(brief),
            )
        }
    }

    /** Lower case and no trailing stop, since the surface supplies "Typically: " in front. */
    @Test
    fun `each reads as a continuation rather than a sentence`() {
        briefs.forEach { (phase, brief) ->
            assertFalse("$phase moodBrief ends with a full stop: $brief", brief.endsWith("."))
            assertTrue(
                "$phase moodBrief starts upper case, so \"Typically: $brief\" reads wrong",
                brief.first().isLowerCase(),
            )
        }
    }
}
