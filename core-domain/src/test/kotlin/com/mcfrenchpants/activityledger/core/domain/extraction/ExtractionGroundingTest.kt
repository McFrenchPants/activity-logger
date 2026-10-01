package com.mcfrenchpants.activityledger.core.domain.extraction

import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractionGroundingTest {

    private fun candidate(time: String?, duration: String?) = ExtractionCandidate(
        operation = InterpretationOperation.LOG_ACTIVITY,
        subject = "lawn",
        action = "mow",
        activityState = ActivityState.COMPLETED,
        temporalExpression = time,
        durationExpression = duration,
    )

    private fun ground(time: String?, duration: String?, raw: String) =
        ExtractionGrounding.ground(candidate(time, duration), raw)

    @Test
    fun `an invented time is dropped and a said duration kept`() {
        val g = ground("yesterday", "for 40 minutes", "Spent 40 minutes mowing the lawn")
        assertNull(g.candidate.temporalExpression)
        assertTrue(g.timeDropped)
        assertEquals("for 40 minutes", g.candidate.durationExpression)
        assertFalse(g.durationDropped)
        assertFalse(ground(null, "40 minutes", "Spent 40 minutes mowing the lawn").durationDropped)
        assertFalse(ground(null, "Spent 40 minutes", "Spent 40 minutes mowing the lawn").durationDropped)
    }

    @Test
    fun `an amount followed by ago is a time, not a duration`() {
        val g = ground("about an hour ago", "for an hour", "Mowed about an hour ago.")
        assertEquals("about an hour ago", g.candidate.temporalExpression)
        assertFalse(g.timeDropped)
        assertNull(g.candidate.durationExpression)
        assertTrue(g.durationDropped)
        assertTrue(ground(null, "an hour", "Mowed about an hour ago.").durationDropped)
        assertTrue(ground(null, "about an hour", "Mowed about an hour ago.").durationDropped)
    }

    @Test
    fun `time and duration both said are both kept`() {
        val g = ground("just", "for about 30 minutes", "I just walked the dogs for about 30 minutes")
        assertEquals(candidate("just", "for about 30 minutes"), g.candidate)
        assertFalse(g.timeDropped)
        assertFalse(g.durationDropped)
        assertFalse(ground(null, "about 30 minutes", "I just walked the dogs for about 30 minutes").durationDropped)
        assertFalse(ground(null, "30 minutes", "I just walked the dogs for about 30 minutes").durationDropped)
    }

    @Test
    fun `just is kept when the sentence says just, dropped otherwise`() {
        assertFalse(ground("just", null, "I just changed the furnace filter").timeDropped)
        assertTrue(ground("just", null, "Changed the furnace filter").timeDropped)
    }

    @Test
    fun `matching ignores case and punctuation but needs whole contiguous words`() {
        assertFalse(ground("Yesterday", null, "yesterday, I changed the oil").timeDropped)
        assertFalse(ground("at 7pm", null, "Watered the garden at 7pm.").timeDropped)
        assertFalse(ground("Saturday morning", null, "Mowed Saturday morning.").timeDropped)
        // Not contiguous.
        assertTrue(ground("Saturday evening", null, "Mowed Saturday, then the evening").timeDropped)
        // Part of a word does not count.
        assertTrue(ground("now", null, "I know the lawn").timeDropped)
        // "this" alone is not in the sentence.
        assertTrue(ground("this", null, "I mowed the lawn.").timeDropped)
    }

    @Test
    fun `leading duration words are optional but other words are not`() {
        assertFalse(ground(null, "for half an hour", "I weeded the garden for half an hour").durationDropped)
        assertFalse(ground(null, "roughly half an hour", "I weeded the garden for half an hour").durationDropped)
        assertTrue(ground(null, "30 minutes", "I weeded the garden for half an hour").durationDropped)
        assertTrue(ground(null, "for", "I weeded the garden for half an hour").durationDropped)
        // A duration the time words merely resemble is not grounded.
        assertTrue(ground(null, "for an hour", "Mowed yesterday.").durationDropped)
    }

    @Test
    fun `absent words stay absent and blank words are dropped`() {
        val none = ground(null, null, "Mowed.")
        assertEquals(candidate(null, null), none.candidate)
        assertFalse(none.timeDropped || none.durationDropped)
        val blank = ground("  ", "...", "Mowed.")
        assertNull(blank.candidate.temporalExpression)
        assertNull(blank.candidate.durationExpression)
        assertTrue(blank.timeDropped && blank.durationDropped)
    }

    @Test
    fun `only time and duration are touched`() {
        val c = candidate("yesterday", "for an hour").copy(subject = "something not in the text", action = "invented")
        val g = ExtractionGrounding.ground(c, "Mowed.")
        assertEquals(c.copy(temporalExpression = null, durationExpression = null), g.candidate)
    }
}
