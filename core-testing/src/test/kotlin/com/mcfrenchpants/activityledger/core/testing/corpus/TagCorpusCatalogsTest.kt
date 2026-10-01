package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TagCorpusCatalogsTest {

    @Test
    fun `every tag corpus fixture converts with ids, names, aliases, order and pairs kept`() {
        TagCorpus.load().catalogs.forEach { (name, fixture) ->
            val catalog = TagCorpusCatalogs.toTagCatalog(fixture)
            assertEquals(fixture.subjects.map { it.id }, catalog.subjects.map { it.id }, "$name subjects")
            assertEquals(fixture.actions.map { it.id }, catalog.actions.map { it.id }, "$name actions")
            assertEquals(fixture.pairs.map { KnownPair(it.subjectId, it.actionId) }, catalog.pairs, "$name pairs")
            assertEquals(fixture.subjects.map { it.displayName to it.aliases }, catalog.subjects.map { it.displayName to it.aliases }, "$name subject names")
            assertEquals(fixture.actions.map { it.displayName to it.aliases }, catalog.actions.map { it.displayName to it.aliases }, "$name action names")
        }
    }

    @Test
    fun `kinds are assigned by list`() {
        val fixture = TagCatalogFixture(
            subjects = listOf(FixtureTag("subj-a", "a", listOf("aa"))),
            actions = listOf(FixtureTag("act-b", "b", emptyList())),
            pairs = listOf(FixturePair("subj-a", "act-b", "A b")),
        )
        val catalog = TagCorpusCatalogs.toTagCatalog(fixture)
        assertEquals(listOf(KnownTag("subj-a", TagKind.SUBJECT, "a", listOf("aa"))), catalog.subjects)
        assertEquals(listOf(KnownTag("act-b", TagKind.ACTION, "b", emptyList())), catalog.actions)
    }

    @Test
    fun `a broken fixture is rejected`() {
        val fixture = TagCatalogFixture(
            subjects = emptyList(),
            actions = listOf(FixtureTag("act-b", "b", emptyList())),
            pairs = listOf(FixturePair("subj-missing", "act-b", "x")),
        )
        assertFailsWith<IllegalArgumentException> { TagCorpusCatalogs.toTagCatalog(fixture) }
    }
}
