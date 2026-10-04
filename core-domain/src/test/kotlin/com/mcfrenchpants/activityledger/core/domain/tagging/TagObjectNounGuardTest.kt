package com.mcfrenchpants.activityledger.core.domain.tagging

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionOutcome.AUTO_SAVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** TG3.8: the object-noun guard (rule 5) in [TagDecisionPolicy]. */
class TagObjectNounGuardTest {

    private fun decide(subject: String?, action: String?, c: TagCatalog = TagCatalog.EMPTY) =
        TagDecisionPolicy.decide(
            ExtractionCandidate(
                operation = InterpretationOperation.LOG_ACTIVITY,
                subject = subject,
                action = action,
                activityState = ActivityState.COMPLETED,
                temporalExpression = null,
                durationExpression = null,
            ),
            c,
        )

    private fun tag(kind: TagKind, id: String, name: String) = KnownTag(id, kind, name, emptyList())

    private fun assertNew(d: TagDecision, subject: String, action: String) {
        assertEquals(AUTO_SAVE, d.outcome)
        assertEquals(TagResolution.New(subject), d.subject)
        assertEquals(TagResolution.New(action), d.action)
    }

    @Test
    fun `real sentences from an empty catalog split object noun into the action`() {
        val a = decide("hot tub filter", "change")
        assertNew(a, "hot tub", "change filter")
        assertTrue(a.resplit)
        val b = decide("furnace filter", "change")
        assertNew(b, "furnace", "change filter")
        assertTrue(b.resplit)
        val c = decide("hot tub filter", "adjust")
        assertNew(c, "hot tub", "adjust filter")
        assertTrue(c.resplit)
    }

    @Test
    fun `one-word subject is untouched`() {
        val d = decide("filter", "clean")
        assertNew(d, "filter", "clean")
        assertFalse(d.resplit)
    }

    @Test
    fun `existing hot tub and change filter become exact`() {
        val hotTub = tag(TagKind.SUBJECT, "s1", "hot tub")
        val cf = tag(TagKind.ACTION, "a1", "change filter")
        val cat = TagCatalog(listOf(hotTub), listOf(cf), emptyList())
        val d = decide("hot tub filter", "change", cat)
        assertEquals(AUTO_SAVE, d.outcome)
        assertEquals(TagResolution.Exact(hotTub, TagMatchVia.NAME), d.subject)
        assertEquals(TagResolution.Exact(cf, TagMatchVia.NAME), d.action)
        assertTrue(d.resplit)
    }

    @Test
    fun `existing air filter subject is kept`() {
        val air = tag(TagKind.SUBJECT, "s1", "air filter")
        val cat = TagCatalog(listOf(air), emptyList(), emptyList())
        val d = decide("air filter", "change", cat)
        assertEquals(TagResolution.Exact(air, TagMatchVia.NAME), d.subject)
        assertEquals(TagResolution.New("change"), d.action)
        assertFalse(d.resplit)
    }

    @Test
    fun `near full subject is not split`() {
        val air = tag(TagKind.SUBJECT, "s1", "air filter")
        val cat = TagCatalog(listOf(air), emptyList(), emptyList())
        val d = decide("pool filter", "change", cat)
        assertFalse(d.resplit)
        assertIs<TagResolution.Near>(d.subject)
    }

    @Test
    fun `nouns outside the set do not split`() {
        val a = decide("sump pump", "replace")
        assertNew(a, "sump pump", "replace")
        assertFalse(a.resplit)
        val b = decide("lawn mower", "sharpen")
        assertNew(b, "lawn mower", "sharpen")
        assertFalse(b.resplit)
    }

    @Test
    fun `multi-word action is not touched`() {
        val d = decide("lawn mower oil", "change oil")
        assertFalse(d.resplit)
        assertEquals(TagResolution.New("lawn mower oil"), d.subject)
    }

    @Test
    fun `repaired subject that is junk is left to the normal checks`() {
        val d = decide("this morning filter", "change")
        assertTrue(d.resplit)
        assertEquals(TagDecisionOutcome.NEEDS_REVIEW, d.outcome)
        assertTrue(TagDecisionReason.NEW_NAME_REJECTED in d.reasons)
    }

    @Test
    fun `already correct words are unchanged`() {
        val d = decide("hot tub", "change filter")
        assertNew(d, "hot tub", "change filter")
        assertFalse(d.resplit)
    }

    @Test
    fun `plural object noun is recognised`() {
        val d = decide("garage belts", "replace")
        assertNew(d, "garage", "replace belts")
        assertTrue(d.resplit)
    }
}
