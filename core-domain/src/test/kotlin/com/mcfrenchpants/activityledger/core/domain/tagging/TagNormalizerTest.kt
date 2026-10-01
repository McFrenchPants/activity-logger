package com.mcfrenchpants.activityledger.core.domain.tagging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagNormalizerTest {

    private fun key(s: String?) = TagNormalizer.key(s)

    @Test
    fun `cleanName collapses whitespace, trims punctuation and keeps casing`() {
        assertEquals("Hot tub", TagNormalizer.cleanName("  Hot \t  tub. "))
        assertEquals("Wi-Fi", TagNormalizer.cleanName("\"Wi-Fi\""))
        assertEquals("Durango", TagNormalizer.cleanName("Durango"))
    }

    @Test
    fun `cleanName strips leading determiners and possessives`() {
        assertEquals("Wi-Fi", TagNormalizer.cleanName("the Wi-Fi"))
        assertEquals("dishes", TagNormalizer.cleanName("all the dishes"))
        assertEquals("lawn", TagNormalizer.cleanName("My lawn"))
        assertEquals("car", TagNormalizer.cleanName("our car"))
        assertEquals("filter", TagNormalizer.cleanName("a filter"))
        assertEquals("dogs", TagNormalizer.cleanName("some dogs"))
        assertEquals("truck", TagNormalizer.cleanName("their, truck"))
        listOf("your", "his", "her", "its", "an").forEach {
            assertEquals("thing", TagNormalizer.cleanName("$it thing"), it)
        }
        // Only leading words are stripped.
        assertEquals("take a walk", TagNormalizer.cleanName("take a walk"))
    }

    @Test
    fun `cleanName is null when nothing is left`() {
        assertNull(TagNormalizer.cleanName(null))
        assertNull(TagNormalizer.cleanName(""))
        assertNull(TagNormalizer.cleanName("   "))
        assertNull(TagNormalizer.cleanName("?!"))
        assertNull(TagNormalizer.cleanName("the"))
        assertNull(TagNormalizer.cleanName("all the"))
    }

    @Test
    fun `key merges Wi-Fi spellings and joined words`() {
        assertEquals("wifi", key("Wi-Fi"))
        assertEquals(key("Wi-Fi"), key("WiFi"))
        assertEquals(key("Wi-Fi"), key("wifi"))
        assertEquals(key("the Wi‑Fi"), key("wifi"))
        assertEquals(key("lawn mower"), key("lawnmower"))
        assertEquals(key("Lawn-Mower"), key("lawnmower"))
    }

    @Test
    fun `key strips determiners, possessives and punctuation`() {
        assertEquals("dish", key("all the dishes"))
        assertEquals("lawn", key("my lawn!"))
        assertEquals("dog", key("the dog's"))
        assertEquals("dog", key("the dog’s"))
        assertEquals(key("dryer vent"), key("dryer/vent"))
        assertEquals("", key("hers"))
        assertEquals("", key(null))
        assertEquals("", key("  ...  "))
    }

    @Test
    fun `singularize rule 1 -ies becomes -y when longer than four letters`() {
        assertEquals("battery", TagNormalizer.singularize("batteries"))
        assertEquals(key("batteries"), key("battery"))
        assertEquals("tie", TagNormalizer.singularize("ties"))
    }

    @Test
    fun `singularize rule 2 drops -es after sses, xes, ches, shes`() {
        assertEquals("glass", TagNormalizer.singularize("glasses"))
        assertEquals("box", TagNormalizer.singularize("boxes"))
        assertEquals("bench", TagNormalizer.singularize("benches"))
        assertEquals("dish", TagNormalizer.singularize("dishes"))
        assertEquals(key("dishes"), key("dish"))
    }

    @Test
    fun `singularize rule 3 drops a plain trailing s`() {
        assertEquals("dog", TagNormalizer.singularize("dogs"))
        assertEquals(key("dogs"), key("dog"))
        assertEquals("house", TagNormalizer.singularize("houses"))
        assertEquals("gutter", TagNormalizer.singularize("gutters"))
        assertEquals("hedge", TagNormalizer.singularize("hedges"))
    }

    @Test
    fun `singularize leaves singular s endings alone`() {
        assertEquals("glass", TagNormalizer.singularize("glass"))
        assertEquals("grass", TagNormalizer.singularize("grass"))
        assertEquals("bus", TagNormalizer.singularize("bus"))
        assertEquals("tennis", TagNormalizer.singularize("tennis"))
        assertEquals("gas", TagNormalizer.singularize("gas"))
        assertEquals("lawn", TagNormalizer.singularize("lawn"))
        assertNotEquals(key("glass"), key("grass"))
    }

    @Test
    fun `tokens are the singular words before joining`() {
        assertEquals(listOf("lawn", "mower"), TagNormalizer.tokens("the Lawn Mowers"))
        assertEquals(listOf("replace", "battery"), TagNormalizer.tokens("replace batteries"))
        assertEquals(listOf("wifi"), TagNormalizer.tokens("Wi-Fi"))
        assertEquals(emptyList(), TagNormalizer.tokens("the"))
    }

    @Test
    fun `everything is idempotent`() {
        val samples = listOf(
            "the Wi-Fi", "all the dishes", "Lawn Mowers", "get gas", "replace batteries", "glasses",
            "hot tub", "dryer lint trap", "my dog's bowls", "boxes", "ties", "Some STUFF!", "bus stops",
            "change oil", "yes", "a", "  ", "the hers",
        )
        samples.forEach { s ->
            val clean = TagNormalizer.cleanName(s)
            assertEquals(clean, TagNormalizer.cleanName(clean), "cleanName")
            assertEquals(key(s), key(key(s)), "key")
            val tokens = TagNormalizer.tokens(s)
            assertEquals(tokens, TagNormalizer.tokens(tokens.joinToString(" ")), "tokens")
            tokens.forEach { assertEquals(it, TagNormalizer.singularize(it), "singularize") }
        }
    }

    private fun match(a: String, b: String) = TagNormalizer.verbsMatch(a, b)

    @Test
    fun `inflected verbs match their base (TG1_4b rule 1)`() {
        listOf(
            "hosed" to "hose", "taped" to "tape", "mowed" to "mow", "mowing" to "mow", "mows" to "mow",
            "edged" to "edge", "edging" to "edge", "edges" to "edge", "cleaned" to "clean", "cleaning" to "clean",
            "changed" to "change", "changing" to "change", "changes" to "change", "emptied" to "empty",
            "emptying" to "empty", "mopped" to "mop", "mopping" to "mop", "added" to "add", "adding" to "add",
            "washes" to "wash", "waxed" to "wax", "weeded" to "weed", "swapped" to "swap", "cutting" to "cut",
            "filled" to "fill", "passed" to "pass", "goes" to "go", "tied" to "tie",
        ).forEach { (inflected, b) ->
            assertTrue(match(inflected, b), "$inflected ~ $b")
            assertTrue(match(b, inflected), "symmetric $b ~ $inflected")
        }
        // Two inflections of one verb match each other.
        assertTrue(match("edged", "edging"))
        assertTrue(match("mowed", "mowing"))
    }

    @Test
    fun `different uninflected verbs never match`() {
        listOf(
            "tap" to "tape", "plan" to "plane", "strip" to "stripe", "scrap" to "scrape", "rid" to "ride",
            "can" to "cane", "hose" to "hoe", "cleanse" to "clean", "tease" to "tea", "dose" to "do",
            "wash" to "walk", "wash" to "wax", "walk" to "wax", "weed" to "wee", "need" to "nee",
        ).forEach { (a, b) -> assertFalse(match(a, b), "$a !~ $b") }
        assertEquals(setOf("tape"), TagNormalizer.verbForms("tape"))
        assertEquals(setOf("hose"), TagNormalizer.verbForms("hose"))
        assertEquals(setOf("bring"), TagNormalizer.verbForms("bring"))
        assertEquals(setOf("weed"), TagNormalizer.verbForms("weed"))
    }

    @Test
    fun `inflected forms are never re-reduced`() {
        // hosed -> hos / hose only; never re-singularized to "ho", so not "hoe".
        assertFalse(match("hosed", "hoe"))
        assertFalse(match("hosing", "hoe"))
        assertFalse(match("cleansed", "clean"))
        assertFalse(match("teased", "tea"))
        assertFalse(match("dosed", "do"))
        assertFalse(match("washing", "walk"))
        assertFalse(match("waxed", "wash"))
        assertEquals(setOf("hosed", "hos", "hose"), TagNormalizer.verbForms("hosed"))
        assertEquals(setOf("mopped", "mopp", "moppe", "mop"), TagNormalizer.verbForms("mopped"))
        assertEquals(setOf("emptied", "empty"), TagNormalizer.verbForms("emptied"))
    }

    @Test
    fun `an inflected verb may match two different bases - the caller decides`() {
        assertTrue(match("taped", "tap"))
        assertTrue(match("taped", "tape"))
        assertTrue(match("planed", "plan"))
        assertTrue(match("planed", "plane"))
    }

    @Test
    fun `action verb and objects`() {
        assertEquals("changed", TagNormalizer.actionVerb("the changed filters"))
        assertEquals(listOf("filter"), TagNormalizer.actionObjects("changed filters"))
        assertEquals(listOf("oil"), TagNormalizer.actionObjects("change oil"))
        assertEquals(null, TagNormalizer.actionVerb("the"))
        assertEquals(emptyList(), TagNormalizer.actionObjects("mow"))
    }
}
