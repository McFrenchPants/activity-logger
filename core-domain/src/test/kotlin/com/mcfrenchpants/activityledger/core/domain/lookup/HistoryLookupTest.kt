package com.mcfrenchpants.activityledger.core.domain.lookup

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryLookupTest {

    private val base = Instant.parse("2026-01-01T00:00:00Z")

    private fun entry(id: String, s: String, a: String, days: Long) =
        LookupEntry(id, s, "name-$s", a, "name-$a", base.plusSeconds(days * 86_400), null)

    private fun ids(r: LookupResult) = r.matches.map { it.entry.occurrenceId }

    private fun both(exactS: Boolean = true, exactA: Boolean = true) =
        LookupTarget(LookupTag("furnace", exactS), LookupTag("change", exactA))

    @Test
    fun `both before subject-only before action-only`() {
        val entries = listOf(
            entry("act", "other", "change", 30),
            entry("sub", "furnace", "other", 20),
            entry("both", "furnace", "change", 1),
        )
        val r = HistoryLookup.rank(entries, both())
        assertEquals(listOf("both", "sub", "act"), ids(r))
        assertEquals(listOf(LookupTier.BOTH, LookupTier.SUBJECT_ONLY, LookupTier.ACTION_ONLY), r.matches.map { it.tier })
    }

    @Test
    fun `newest first within a tier`() {
        val entries = listOf(
            entry("old", "furnace", "change", 1),
            entry("new", "furnace", "change", 9),
            entry("mid", "furnace", "change", 5),
        )
        assertEquals(listOf("new", "mid", "old"), ids(HistoryLookup.rank(entries, both())))
    }

    @Test
    fun `exactness is reported per match from the target tags used`() {
        val t = LookupTarget(LookupTag("furnace", true), LookupTag("change", false))
        val entries = listOf(
            entry("bothNear", "furnace", "change", 9),
            entry("subjectOnlyExact", "furnace", "other", 1),
            entry("actionOnlyNear", "other", "change", 20),
        )
        val r = HistoryLookup.rank(entries, t)
        assertEquals(listOf("bothNear", "subjectOnlyExact", "actionOnlyNear"), ids(r))
        assertEquals(listOf(false, true, false), r.matches.map { it.exact })
    }

    @Test
    fun `tier wins over exactness and recency`() {
        val t = LookupTarget(LookupTag("furnace", false), LookupTag("change", true))
        val entries = listOf(
            entry("actionExactNewer", "other", "change", 50),
            entry("subjectNearOlder", "furnace", "other", 1),
        )
        assertEquals(listOf("subjectNearOlder", "actionExactNewer"), ids(HistoryLookup.rank(entries, t)))
    }

    @Test
    fun `occurrence id descending breaks timestamp ties deterministically`() {
        val entries = listOf(
            entry("a", "furnace", "change", 3),
            entry("c", "furnace", "change", 3),
            entry("b", "furnace", "change", 3),
        )
        val expected = listOf("c", "b", "a")
        assertEquals(expected, ids(HistoryLookup.rank(entries, both())))
        assertEquals(expected, ids(HistoryLookup.rank(entries.reversed(), both())))
    }

    @Test
    fun `entries matching nothing are dropped`() {
        val entries = listOf(entry("x", "o1", "o2", 1), entry("y", "furnace", "change", 2))
        assertEquals(listOf("y"), ids(HistoryLookup.rank(entries, both())))
    }

    @Test
    fun `subject-only target produces only subject tier`() {
        val target = LookupTarget(LookupTag("furnace", true), null)
        val entries = listOf(
            entry("1", "furnace", "change", 1),
            entry("2", "furnace", "clean", 2),
            entry("3", "other", "change", 3),
        )
        val r = HistoryLookup.rank(entries, target)
        assertEquals(listOf("2", "1"), ids(r))
        assertTrue(r.matches.all { it.tier == LookupTier.SUBJECT_ONLY })
    }

    @Test
    fun `action-only target produces only action tier`() {
        val target = LookupTarget(null, LookupTag("change", true))
        val entries = listOf(
            entry("1", "furnace", "change", 1),
            entry("2", "furnace", "clean", 2),
            entry("3", "other", "change", 3),
        )
        val r = HistoryLookup.rank(entries, target)
        assertEquals(listOf("3", "1"), ids(r))
        assertTrue(r.matches.all { it.tier == LookupTier.ACTION_ONLY })
    }

    @Test
    fun `empty entries give an empty result`() {
        val r = HistoryLookup.rank(emptyList(), both())
        assertTrue(r.isEmpty)
        assertNull(r.top)
        assertNull(r.previous)
        assertNull(r.intervalFromPrevious)
    }

    @Test
    fun `top is the first match`() {
        val r = HistoryLookup.rank(listOf(entry("a", "furnace", "change", 1), entry("b", "furnace", "change", 4)), both())
        assertEquals("b", r.top?.entry?.occurrenceId)
        assertFalse(r.isEmpty)
    }

    @Test
    fun `previous and interval come from the same tier and exactness`() {
        val entries = listOf(
            entry("top", "furnace", "change", 100),
            entry("subOnly", "furnace", "other", 99),
            entry("prev", "furnace", "change", 40),
            entry("older", "furnace", "change", 10),
        )
        val r = HistoryLookup.rank(entries, both())
        assertEquals("top", r.top?.entry?.occurrenceId)
        assertEquals("prev", r.previous?.entry?.occurrenceId)
        assertEquals(Duration.ofDays(60), r.intervalFromPrevious)
    }

    @Test
    fun `no previous when the only other match is in another tier`() {
        val entries = listOf(entry("top", "furnace", "change", 100), entry("subOnly", "furnace", "other", 99))
        val r = HistoryLookup.rank(entries, both())
        assertNull(r.previous)
        assertNull(r.intervalFromPrevious)
    }

    @Test
    fun `equal timestamps give a zero interval, never negative`() {
        val entries = listOf(entry("a", "furnace", "change", 7), entry("b", "furnace", "change", 7))
        val r = HistoryLookup.rank(entries, both())
        assertEquals(Duration.ZERO, r.intervalFromPrevious)
    }

    @Test
    fun `interval is never negative for any input order`() {
        val entries = (1..8).map { entry("e$it", "furnace", "change", ((it * 7) % 5).toLong()) }
        listOf(entries, entries.reversed(), entries.shuffled(java.util.Random(1))).forEach {
            val d = HistoryLookup.rank(it, both()).intervalFromPrevious
            assertFalse(d!!.isNegative)
        }
    }
}
