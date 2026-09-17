package com.mcfrenchpants.activityledger.core.domain.candidates

import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CandidateSelectorTest {
    private fun entry(
        id: String,
        name: String,
        aliases: List<String> = emptyList(),
        last: String? = null,
    ) = CatalogActivity(
        id = id,
        displayName = name.replaceFirstChar { it.uppercaseChar() },
        normalizedName = name,
        normalizedAliases = aliases,
        lastOccurredAt = last?.let { Instant.parse(it) },
    )

    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `bound must be at least one`() {
        assertFailsWith<IllegalArgumentException> { CandidateSelector(0) }
        CandidateSelector(1)
        assertEquals(40, CandidateSelector().bound)
    }

    @Test
    fun `catalog within bound returns every entry ordered by name then id`() {
        val catalog = listOf(
            entry("z", "mow lawn", listOf("cut grass", "mow the lawn")),
            entry("b", "clean gutters"),
            entry("a", "mow lawn"),
            entry("c", "air filter", last = "2026-01-01T00:00:00Z"),
        )
        val result = CandidateSelector(bound = 4).select(catalog, "anything")
        assertEquals(listOf("c", "b", "a", "z"), result.candidates.map { it.id })
        assertEquals(listOf("cut grass", "mow the lawn"), result.candidates.last().aliases)
        assertEquals("Mow lawn", result.candidates.last().displayName)
    }

    @Test
    fun `empty catalog gives empty list and hash of empty string`() {
        val result = CandidateSelector().select(emptyList(), "I cut the grass")
        assertTrue(result.candidates.isEmpty())
        assertEquals(emptyHash, result.contextHash)
    }

    private val bigCatalog = listOf(
        entry("1", "mow lawn", listOf("cut the grass"), last = null),
        entry("2", "clean gutters", last = "2026-09-10T00:00:00Z"),
        entry("3", "replace air filter", last = "2026-09-12T00:00:00Z"),
        entry("4", "edge lawn", listOf("mow"), last = "2026-09-01T00:00:00Z"),
        entry("5", "flush water heater", last = "2026-09-14T00:00:00Z"),
        entry("6", "wash car", last = null),
    )

    @Test
    fun `text hit is included even when least recent and rest go by recency`() {
        val result = CandidateSelector(bound = 3).select(bigCatalog.reversed(), "I cut the grass yesterday.")
        // hit: 1; then most recent: 5, 3. Final list name-ordered.
        assertEquals(listOf("5", "1", "3"), result.candidates.map { it.id })
    }

    @Test
    fun `alias inside another word is not a hit`() {
        val result = CandidateSelector(bound = 3).select(bigCatalog, "Checked the mowers.")
        // no hits: most recent 5, 3, 2
        assertEquals(listOf("2", "5", "3"), result.candidates.map { it.id })
    }

    @Test
    fun `whole token alias at text edges is a hit`() {
        val result = CandidateSelector(bound = 3).select(bigCatalog, "Mow")
        assertTrue(result.candidates.any { it.id == "4" })
    }

    @Test
    fun `null recency sorts last with ties by name then id`() {
        val catalog = listOf(
            entry("n2", "zeta"),
            entry("n1", "zeta"),
            entry("x", "alpha"),
            entry("r", "recent", last = "2026-01-01T00:00:00Z"),
        )
        val result = CandidateSelector(bound = 3).select(catalog, "nothing matches")
        assertEquals(listOf("x", "r", "n1"), result.candidates.map { it.id })
    }

    @Test
    fun `hits exceeding bound are truncated by recency then name ordered`() {
        val catalog = listOf(
            entry("a", "wash car", last = "2026-01-01T00:00:00Z"),
            entry("b", "mow lawn", last = "2026-03-01T00:00:00Z"),
            entry("c", "clean gutters", last = null),
            entry("d", "edge lawn", last = "2026-02-01T00:00:00Z"),
            entry("e", "unrelated", last = "2026-09-01T00:00:00Z"),
        )
        val text = "wash car, mow lawn, clean gutters and edge lawn"
        val result = CandidateSelector(bound = 2).select(catalog, text)
        assertEquals(listOf("d", "b"), result.candidates.map { it.id })
    }

    @Test
    fun `hash is independent of input order and is 64 lowercase hex`() {
        val selector = CandidateSelector()
        val h1 = selector.select(bigCatalog, "x").contextHash
        val h2 = selector.select(bigCatalog.shuffled(java.util.Random(7)), "x").contextHash
        assertEquals(h1, h2)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(h1))
    }

    @Test
    fun `hash changes when an id or display name changes`() {
        val selector = CandidateSelector()
        val base = selector.select(bigCatalog, "x").contextHash
        val idChanged = bigCatalog.map { if (it.id == "3") it.copy(id = "33") else it }
        val nameChanged = bigCatalog.map { if (it.id == "3") it.copy(displayName = "Replace Air Filter") else it }
        assertNotEquals(base, selector.select(idChanged, "x").contextHash)
        assertNotEquals(base, selector.select(nameChanged, "x").contextHash)
    }

    @Test
    fun `different raw texts selecting same candidates give same hash`() {
        val selector = CandidateSelector(bound = 3)
        val a = selector.select(bigCatalog, "I cut the grass yesterday.")
        val b = selector.select(bigCatalog, "cut the grass")
        assertEquals(a.candidates, b.candidates)
        assertEquals(a.contextHash, b.contextHash)
    }

    @Test
    fun `hash matches documented construction`() {
        val catalog = listOf(entry("id1", "mow lawn"))
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("id1Mow lawn".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        assertEquals(expected, CandidateSelector().select(catalog, "").contextHash)
    }
}
