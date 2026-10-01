package com.mcfrenchpants.activityledger.core.domain.tagging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TagResolverTest {

    private fun subject(id: String, name: String, vararg aliases: String) =
        KnownTag(id, TagKind.SUBJECT, name, aliases.toList())

    private fun action(id: String, name: String, vararg aliases: String) =
        KnownTag(id, TagKind.ACTION, name, aliases.toList())

    private val lawn = subject("s-lawn", "lawn")
    private val dryerVent = subject("s-dryer-vent", "dryer vent")
    private val wifi = subject("s-wifi", "WiFi", "internet")
    private val furnace = subject("s-furnace", "furnace")
    private val walk = action("a-walk", "walk")
    private val wax = action("a-wax", "wax")
    private val changeFilter = action("a-change-filter", "change filter")
    private val replaceFilter = action("a-replace-filter", "replace filter")
    private val mow = action("a-mow", "mow", "cut grass")

    private val catalog = TagCatalog(
        subjects = listOf(lawn, dryerVent, wifi, furnace),
        actions = listOf(walk, wax, changeFilter, mow),
        pairs = emptyList(),
    )

    private fun resolve(words: String?, kind: TagKind, c: TagCatalog = catalog) = TagResolver.resolve(words, kind, c)

    @Test
    fun `blank, null and determiner-only words are Empty`() {
        listOf(null, "", "   ", "...", "the", "all the").forEach {
            assertEquals(TagResolution.Empty, resolve(it, TagKind.SUBJECT), "input #${it?.length}")
        }
    }

    @Test
    fun `empty catalog gives New with the clean name`() {
        assertEquals(TagResolution.New("Wi-Fi"), resolve("the Wi-Fi", TagKind.SUBJECT, TagCatalog.EMPTY))
        assertEquals(TagResolution.New("change filter"), resolve("change filter", TagKind.ACTION, TagCatalog.EMPTY))
    }

    @Test
    fun `exact by name and by alias`() {
        assertEquals(TagResolution.Exact(wifi, TagMatchVia.NAME), resolve("the Wi-Fi", TagKind.SUBJECT))
        assertEquals(TagResolution.Exact(wifi, TagMatchVia.ALIAS), resolve("Internet", TagKind.SUBJECT))
        assertEquals(TagResolution.Exact(mow, TagMatchVia.ALIAS), resolve("Cut-Grass", TagKind.ACTION))
        assertEquals(TagResolution.Exact(lawn, TagMatchVia.NAME), resolve("my Lawns", TagKind.SUBJECT))
        // An inner "the" is kept, so this is only close to the alias, not exact.
        assertEquals(listOf(mow), assertIs<TagResolution.Near>(resolve("cut the grass", TagKind.ACTION)).candidates)
    }

    @Test
    fun `name match wins over an alias match`() {
        val a = subject("s-a", "pool", "spa")
        val b = subject("s-b", "spa")
        val c = TagCatalog(listOf(a, b), emptyList(), emptyList())
        assertEquals(TagResolution.Exact(b, TagMatchVia.NAME), resolve("spa", TagKind.SUBJECT, c))
    }

    @Test
    fun `kinds are isolated`() {
        // "walk" is an action; as a subject there is nothing to match.
        assertEquals(TagResolution.New("walk"), resolve("walk", TagKind.SUBJECT))
        assertEquals(TagResolution.New("lawn"), resolve("lawn", TagKind.ACTION))
    }

    @Test
    fun `rule a - spelling close for keys of five or more`() {
        val near = assertIs<TagResolution.Near>(resolve("furnice", TagKind.SUBJECT))
        assertEquals(listOf(furnace), near.candidates)
        assertEquals("furnice", near.newName)
        // Swap of adjacent letters counts as one edit.
        assertEquals(listOf(furnace), assertIs<TagResolution.Near>(resolve("furnaec", TagKind.SUBJECT)).candidates)
        // Two edits on a short key are not close.
        assertIs<TagResolution.New>(resolve("furnxcx", TagKind.SUBJECT))
    }

    @Test
    fun `rule a - two edits allowed from nine characters`() {
        val c = TagCatalog(emptyList(), listOf(changeFilter), emptyList())
        assertIs<TagResolution.Near>(resolve("change filtre", TagKind.ACTION, c)) // swap
        assertIs<TagResolution.Near>(resolve("chang filtr", TagKind.ACTION, c)) // two deletions
        assertIs<TagResolution.New>(resolve("chng fltr", TagKind.ACTION, c)) // shorter key 7 chars
    }

    @Test
    fun `rule a - short keys are never spelling close`() {
        assertIs<TagResolution.New>(resolve("wash", TagKind.ACTION))
        assertFalse(TagResolver.spellingClose("wash", "walk"))
        assertFalse(TagResolver.spellingClose("wash", "wax"))
        assertTrue(TagResolver.spellingClose("garden", "gardne"))
        assertEquals(1, TagResolver.editDistance("ab", "ba"))
        assertEquals(3, TagResolver.editDistance("kitten", "sitting"))
    }

    @Test
    fun `rule b - subjects sharing a content word are close`() {
        assertEquals(listOf(lawn), assertIs<TagResolution.Near>(resolve("lawn mower", TagKind.SUBJECT)).candidates)
        assertEquals(listOf(dryerVent), assertIs<TagResolution.Near>(resolve("dryer lint trap", TagKind.SUBJECT)).candidates)
    }

    @Test
    fun `rule c - actions are close only on a shared word after the verb`() {
        val c = TagCatalog(emptyList(), listOf(changeFilter, replaceFilter), emptyList())
        assertIs<TagResolution.New>(resolve("change oil", TagKind.ACTION, TagCatalog(emptyList(), listOf(changeFilter), emptyList())))
        assertIs<TagResolution.New>(resolve("replace batteries", TagKind.ACTION, TagCatalog(emptyList(), listOf(replaceFilter), emptyList())))
        val near = assertIs<TagResolution.Near>(resolve("swap filter", TagKind.ACTION, c))
        assertEquals(setOf(changeFilter, replaceFilter), near.candidates.toSet())
        val oneFilter = TagCatalog(emptyList(), listOf(changeFilter), emptyList())
        assertEquals(listOf(changeFilter), assertIs<TagResolution.Near>(resolve("replace filter", TagKind.ACTION, oneFilter)).candidates)
        assertEquals(setOf("filter"), TagResolver.sharedTokens(TagKind.ACTION, listOf("replace", "filter"), listOf("change", "filter")))
        assertEquals(emptySet(), TagResolver.sharedTokens(TagKind.ACTION, listOf("change", "oil"), listOf("change", "filter")))
    }

    @Test
    fun `rule c - actions have no word-length minimum or stop-word list`() {
        assertEquals(setOf("out"), TagResolver.sharedTokens(TagKind.ACTION, listOf("put", "out"), listOf("take", "out")))
        assertEquals(setOf("up"), TagResolver.sharedTokens(TagKind.ACTION, listOf("pick", "up"), listOf("clean", "up")))
        val takeOut = action("a-take-out", "take out")
        val c = TagCatalog(emptyList(), listOf(takeOut), emptyList())
        assertEquals(listOf(takeOut), assertIs<TagResolution.Near>(resolve("put out", TagKind.ACTION, c)).candidates)
        // The verb alone is still never shared.
        assertEquals(emptySet(), TagResolver.sharedTokens(TagKind.ACTION, listOf("take", "out"), listOf("take", "in")))
    }

    @Test
    fun `rule b - subjects need a shared word of at least three letters`() {
        assertEquals(emptySet(), TagResolver.sharedTokens(TagKind.SUBJECT, listOf("ab", "x"), listOf("ab", "y")))
        assertEquals(setOf("car"), TagResolver.sharedTokens(TagKind.SUBJECT, listOf("car", "seat"), listOf("car")))
    }

    @Test
    fun `candidates are ranked closest first then by name then id`() {
        val cover = subject("s-1", "hot tub cover")
        val pump = subject("s-0", "Hot tub pump")
        val jets = subject("s-2", "hot tub jets")
        val c = TagCatalog(listOf(cover, pump, jets), emptyList(), emptyList())
        val near = assertIs<TagResolution.Near>(resolve("hot tub", TagKind.SUBJECT, c))
        // cover is 5 edits away, pump and jets 4 each; the tie sorts by name, case-insensitively.
        assertEquals(listOf(jets, pump, cover), near.candidates)

        val x = subject("s-b", "dryer")
        val y = subject("s-a", "dryer")
        val tie = TagCatalog(listOf(x, y), emptyList(), emptyList())
        assertEquals(listOf(y, x), assertIs<TagResolution.Near>(resolve("dryer vent", TagKind.SUBJECT, tie)).candidates)
        // Same input, same answer.
        assertEquals(resolve("dryer vent", TagKind.SUBJECT, tie), resolve("dryer vent", TagKind.SUBJECT, tie))
    }

    @Test
    fun `unrelated speech slips stay New`() {
        assertEquals(TagResolution.New("James Durango"), resolve("James Durango", TagKind.SUBJECT))
    }
}
