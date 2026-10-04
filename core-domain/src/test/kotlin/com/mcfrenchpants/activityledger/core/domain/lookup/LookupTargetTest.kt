package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagMatchVia
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LookupTargetTest {

    private val furnace = KnownTag("s-furnace", TagKind.SUBJECT, "furnace", emptyList())
    private val lawn = KnownTag("s-lawn", TagKind.SUBJECT, "lawn", emptyList())
    private val change = KnownTag("a-change", TagKind.ACTION, "change filter", emptyList())

    private val exactSubject = TagResolution.Exact(furnace, TagMatchVia.NAME)
    private val exactAction = TagResolution.Exact(change, TagMatchVia.NAME)
    private val nearSubject = TagResolution.Near("furnce", listOf(lawn, furnace))

    @Test
    fun `a target with neither side is invalid`() {
        assertFailsWith<IllegalArgumentException> { LookupTarget(null, null) }
    }

    @Test
    fun `exact on both sides`() {
        assertEquals(
            LookupTarget(LookupTag("s-furnace", true), LookupTag("a-change", true)),
            LookupTarget.fromResolutions(exactSubject, exactAction),
        )
    }

    @Test
    fun `near uses the first candidate and is not exact`() {
        assertEquals(
            LookupTarget(LookupTag("s-lawn", false), LookupTag("a-change", true)),
            LookupTarget.fromResolutions(nearSubject, exactAction),
        )
    }

    @Test
    fun `empty and new sides are absent`() {
        assertEquals(
            LookupTarget(LookupTag("s-furnace", true), null),
            LookupTarget.fromResolutions(exactSubject, TagResolution.Empty),
        )
        assertEquals(
            LookupTarget(null, LookupTag("a-change", true)),
            LookupTarget.fromResolutions(TagResolution.New("hot tub"), exactAction),
        )
    }

    @Test
    fun `null when both sides are absent`() {
        assertNull(LookupTarget.fromResolutions(TagResolution.Empty, TagResolution.New("x")))
        assertNull(LookupTarget.fromResolutions(TagResolution.New("a"), TagResolution.Empty))
    }
}
