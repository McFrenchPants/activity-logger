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

    // ---- TG1.4b ----

    private val clean = action("a-clean", "clean")
    private val edge = action("a-edge", "edge")
    private val blow = action("a-blow", "blow")
    private val household = TagCatalog(
        subjects = listOf(lawn, furnace, subject("s-gutters", "gutters")),
        actions = listOf(mow, clean, edge, blow, replaceFilter, wax, action("a-take-out", "take out")),
        pairs = emptyList(),
    )

    @Test
    fun `rule 1 - inflected verbs are the existing action, exactly`() {
        assertEquals(TagResolution.Exact(clean, TagMatchVia.NAME), resolve("cleaning", TagKind.ACTION, household))
        assertEquals(TagResolution.Exact(clean, TagMatchVia.NAME), resolve("cleaned", TagKind.ACTION, household))
        assertEquals(TagResolution.Exact(edge, TagMatchVia.NAME), resolve("edging", TagKind.ACTION, household))
        assertEquals(TagResolution.Exact(replaceFilter, TagMatchVia.NAME), resolve("Replaced filters", TagKind.ACTION, household))
        assertEquals(TagResolution.Exact(mow, TagMatchVia.ALIAS), resolve("cutting grass", TagKind.ACTION))
        // The catalog side is stemmed too.
        val stemmedCatalog = TagCatalog(emptyList(), listOf(action("a-mopped", "mopped floor")), emptyList())
        assertIs<TagResolution.Exact>(resolve("mop floor", TagKind.ACTION, stemmedCatalog))
    }

    @Test
    fun `rule 1 - stemming keeps the TG1_3 negatives`() {
        val c = TagCatalog(emptyList(), listOf(walk, wax), emptyList())
        assertIs<TagResolution.New>(resolve("washing", TagKind.ACTION, c))
        assertEquals(TagResolution.Exact(wax, TagMatchVia.NAME), resolve("waxed", TagKind.ACTION, c))
        assertEquals(TagResolution.Exact(walk, TagMatchVia.NAME), resolve("walking", TagKind.ACTION, c))
        assertIs<TagResolution.New>(resolve("changing oil", TagKind.ACTION, TagCatalog(emptyList(), listOf(changeFilter), emptyList())))
        assertIs<TagResolution.New>(resolve("replaced batteries", TagKind.ACTION, TagCatalog(emptyList(), listOf(replaceFilter), emptyList())))
        // Subjects are never verb-stemmed: "edging" is not the subject "edge".
        val subjects = TagCatalog(listOf(subject("s-edge", "edge")), emptyList(), emptyList())
        assertIs<TagResolution.New>(resolve("edging", TagKind.SUBJECT, subjects))
    }

    @Test
    fun `rule 1 - different base verbs never resolve to each other`() {
        listOf(
            "tap" to "tape", "plan" to "plane", "strip" to "stripe", "scrap" to "scrape", "rid" to "ride",
            "can" to "cane", "hose" to "hoe", "cleanse" to "clean", "tease" to "tea", "dose" to "do",
        ).forEach { (user, existing) ->
            // Never Exact (a long pair may still be spelling-close, which only asks).
            val c = TagCatalog(emptyList(), listOf(action("a-x", existing)), emptyList())
            assertFalse(resolve(user, TagKind.ACTION, c) is TagResolution.Exact, "$user vs $existing")
            val reverse = TagCatalog(emptyList(), listOf(action("a-y", user)), emptyList())
            assertFalse(resolve(existing, TagKind.ACTION, reverse) is TagResolution.Exact, "$existing vs $user")
        }
        // Short pairs are not even close.
        assertIs<TagResolution.New>(resolve("tap", TagKind.ACTION, TagCatalog(emptyList(), listOf(action("a-tape", "tape")), emptyList())))
        // Not re-reduced: "hosed" is not "hoe".
        assertIs<TagResolution.New>(resolve("hosed", TagKind.ACTION, TagCatalog(emptyList(), listOf(action("a-hoe", "hoe")), emptyList())))
    }

    @Test
    fun `rule 1 - an inflected verb is exact only when exactly one existing verb matches`() {
        val tape = action("a-tape", "tape")
        val tap = action("a-tap", "tap")
        assertEquals(TagResolution.Exact(tape, TagMatchVia.NAME), resolve("taped", TagKind.ACTION, TagCatalog(emptyList(), listOf(tape), emptyList())))
        assertEquals(
            TagResolution.Exact(action("a-hose", "hose"), TagMatchVia.NAME),
            resolve("hosed", TagKind.ACTION, TagCatalog(emptyList(), listOf(action("a-hose", "hose"), action("a-hoe", "hoe")), emptyList())),
        )
        // Both "tap" and "tape" exist: ambiguous, so Near over exactly those two.
        val both = TagCatalog(emptyList(), listOf(tape, tap, wax), emptyList())
        val near = assertIs<TagResolution.Near>(resolve("taped", TagKind.ACTION, both))
        assertEquals(listOf(tap, tape), near.candidates)
        // The uninflected word is itself exact.
        assertEquals(TagResolution.Exact(tap, TagMatchVia.NAME), resolve("tap", TagKind.ACTION, both))
        // Object words must be identical for an exact verb match; extra words are only head-verb Near.
        assertEquals(listOf(tape), assertIs<TagResolution.Near>(resolve("taped box", TagKind.ACTION, TagCatalog(emptyList(), listOf(tape), emptyList()))).candidates)
    }

    @Test
    fun `rule 5 - verb synonyms are Near only, with equal object words`() {
        // cut ~ mow
        val cut = assertIs<TagResolution.Near>(resolve("cut", TagKind.ACTION, household))
        assertEquals(listOf(mow), cut.candidates)
        // swap ~ replace, same object
        assertEquals(listOf(replaceFilter), assertIs<TagResolution.Near>(resolve("swapped filter", TagKind.ACTION, TagCatalog(emptyList(), listOf(replaceFilter), emptyList()))).candidates)
        // A synonym verb with a different object is not close.
        assertIs<TagResolution.New>(resolve("change oil", TagKind.ACTION, TagCatalog(emptyList(), listOf(replaceFilter), emptyList())))
        // Not in a group: no match.
        assertIs<TagResolution.New>(resolve("trim", TagKind.ACTION, household))
        assertTrue(TagResolver.synonymVerbs("cut", "mow"))
        assertTrue(TagResolver.synonymVerbs("cutting", "mowed"))
        assertFalse(TagResolver.synonymVerbs("mow", "mow"))
        assertFalse(TagResolver.synonymVerbs("wash", "wax"))
        assertFalse(TagResolver.synonymVerbs("mowing", "mow"))
    }

    @Test
    fun `rule 5 - subject synonyms are Near only`() {
        assertEquals(listOf(lawn), assertIs<TagResolution.Near>(resolve("grass", TagKind.SUBJECT, household)).candidates)
        assertEquals(listOf(lawn), assertIs<TagResolution.Near>(resolve("the yard", TagKind.SUBJECT, household)).candidates)
        assertEquals(listOf(furnace), assertIs<TagResolution.Near>(resolve("HVAC", TagKind.SUBJECT, household)).candidates)
        // Not in a group, or only part of a longer name: no synonym match.
        assertIs<TagResolution.New>(resolve("garden", TagKind.SUBJECT, household))
        assertIs<TagResolution.New>(resolve("air filter", TagKind.SUBJECT, household))
        assertTrue(TagResolver.synonymSubjects("grass", "lawn"))
        assertFalse(TagResolver.synonymSubjects("lawn", "lawn"))
    }

    @Test
    fun `rule 6 - extra words after an existing single-word verb are Near`() {
        assertEquals(listOf(blow), assertIs<TagResolution.Near>(resolve("blow off driveway", TagKind.ACTION, household)).candidates)
        assertEquals(listOf(edge), assertIs<TagResolution.Near>(resolve("edging the walk", TagKind.ACTION, household)).candidates)
        // Through a synonym verb: clear ~ clean.
        assertEquals(listOf(clean), assertIs<TagResolution.Near>(resolve("clear leaves", TagKind.ACTION, household)).candidates)
        // Only against SINGLE-word actions: "take in" is not near "take out".
        assertIs<TagResolution.New>(resolve("take in", TagKind.ACTION, household))
        // A different verb with extra words is not near.
        assertIs<TagResolution.New>(resolve("rake leaves", TagKind.ACTION, household))
    }

    @Test
    fun `real close matches rank before synonym matches`() {
        val change = action("a-change", "change")
        val shared = action("a-swap-filter-x", "fit filter")
        val c = TagCatalog(emptyList(), listOf(change, shared), emptyList())
        // "swap filter": "fit filter" shares the object word (real), "change" is a synonym head verb.
        assertEquals(listOf(shared, change), assertIs<TagResolution.Near>(resolve("swap filter", TagKind.ACTION, c)).candidates)
    }

    @Test
    fun `equivalent actions are exact or synonym-with-same-object only`() {
        assertEquals(listOf(replaceFilter), TagResolver.equivalentActions("replace filter", household))
        assertEquals(listOf(replaceFilter), TagResolver.equivalentActions("swap filters", household))
        assertEquals(emptyList(), TagResolver.equivalentActions("swap oil", household))
        assertEquals(emptyList(), TagResolver.equivalentActions("put filter", household))
        assertEquals(emptyList(), TagResolver.equivalentActions(null, household))
    }
}
