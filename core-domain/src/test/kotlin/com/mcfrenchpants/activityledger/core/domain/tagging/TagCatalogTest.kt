package com.mcfrenchpants.activityledger.core.domain.tagging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagCatalogTest {

    private val lawn = KnownTag("s-lawn", TagKind.SUBJECT, "lawn", emptyList())
    private val mow = KnownTag("a-mow", TagKind.ACTION, "mow", emptyList())

    @Test
    fun `a valid catalog builds and looks tags up by kind`() {
        val c = TagCatalog(listOf(lawn), listOf(mow), listOf(KnownPair("s-lawn", "a-mow")))
        assertEquals(listOf(lawn), c.tagsOf(TagKind.SUBJECT))
        assertEquals(listOf(mow), c.tagsOf(TagKind.ACTION))
        assertEquals(lawn, c.tag(TagKind.SUBJECT, "s-lawn"))
        assertNull(c.tag(TagKind.ACTION, "s-lawn"))
    }

    @Test
    fun `EMPTY has nothing`() {
        assertTrue(TagCatalog.EMPTY.subjects.isEmpty())
        assertTrue(TagCatalog.EMPTY.actions.isEmpty())
        assertTrue(TagCatalog.EMPTY.pairs.isEmpty())
    }

    @Test
    fun `kinds must match their list`() {
        assertFailsWith<IllegalArgumentException> { TagCatalog(listOf(mow), emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { TagCatalog(emptyList(), listOf(lawn), emptyList()) }
    }

    @Test
    fun `ids are unique per kind and non-blank`() {
        assertFailsWith<IllegalArgumentException> {
            TagCatalog(listOf(lawn, lawn.copy(displayName = "grass")), emptyList(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            TagCatalog(emptyList(), listOf(mow, mow.copy(displayName = "cut")), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            TagCatalog(listOf(lawn.copy(id = " ")), emptyList(), emptyList())
        }
        // The same id in different kinds is fine.
        TagCatalog(listOf(lawn.copy(id = "x")), listOf(mow.copy(id = "x")), emptyList())
    }

    @Test
    fun `pairs must refer to existing ids of the right kind`() {
        assertFailsWith<IllegalArgumentException> {
            TagCatalog(listOf(lawn), listOf(mow), listOf(KnownPair("s-missing", "a-mow")))
        }
        assertFailsWith<IllegalArgumentException> {
            TagCatalog(listOf(lawn), listOf(mow), listOf(KnownPair("s-lawn", "a-missing")))
        }
        assertFailsWith<IllegalArgumentException> {
            TagCatalog(listOf(lawn), listOf(mow), listOf(KnownPair("a-mow", "s-lawn")))
        }
    }
}
