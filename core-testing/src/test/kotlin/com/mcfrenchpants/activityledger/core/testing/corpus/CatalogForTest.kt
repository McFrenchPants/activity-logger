package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogForTest {

    private val corpus = SemanticCorpus.load()

    @Test
    fun `catalogFor keeps fixed ids and normalizes names and aliases`() {
        corpus.cases.forEach { case ->
            val fixture = corpus.catalogs.getValue(case.catalog)
            val catalog = corpus.catalogFor(case)
            assertEquals(fixture.activities.map { it.id }, catalog.map { it.id }, case.id)
            fixture.activities.zip(catalog).forEach { (source, entry) ->
                assertEquals(source.displayName, entry.displayName)
                assertEquals(NameNormalizer.normalize(source.displayName), entry.normalizedName)
                assertEquals(source.aliases.map(NameNormalizer::normalize), entry.normalizedAliases)
                assertNull(entry.lastOccurredAt)
            }
        }
    }

    @Test
    fun `household fixture has the expected fixed entries`() {
        val case = corpus.cases.first { it.catalog == "household" }
        val byId = corpus.catalogFor(case).associateBy { it.id }
        assertEquals("mow lawn", byId.getValue("act-mow-lawn").normalizedName)
        assertEquals(listOf("leaf blowing"), byId.getValue("act-blow-leaves").normalizedAliases)
        assertTrue("act-edge-lawn" in byId)
    }

    @Test
    fun `catalogFor rejects an unknown fixture`() {
        val case = corpus.cases.first().copy(catalog = "no-such-catalog")
        assertFailsWith<IllegalArgumentException> { corpus.catalogFor(case) }
    }

    @Test
    fun `parse hashes exactly the given bytes`() {
        val bytesA = """{"schemaVersion":1,"catalogs":{},"cases":[]}""".toByteArray()
        val a = SemanticCorpus.parse(bytesA)
        val b = SemanticCorpus.parse("""{"schemaVersion":1,"catalogs":{},"cases":[] }""".toByteArray())
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytesA)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(expected, a.sha256)
        assertTrue(a.sha256 != b.sha256)
        assertEquals(0, a.cases.size)
    }
}
