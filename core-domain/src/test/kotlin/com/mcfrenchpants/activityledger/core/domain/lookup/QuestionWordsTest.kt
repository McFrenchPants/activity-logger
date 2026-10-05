package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Every rule of [QuestionWords], its "unless exact" guard, the rule order and the no-op case. */
class QuestionWordsTest {

    private fun subject(id: String, name: String, vararg aliases: String) = KnownTag(id, TagKind.SUBJECT, name, aliases.toList())
    private fun action(id: String, name: String, vararg aliases: String) = KnownTag(id, TagKind.ACTION, name, aliases.toList())

    private val catalog = TagCatalog(
        subjects = listOf(
            subject("s-furnace", "Furnace"),
            subject("s-hot-tub", "Hot tub", "spa"),
            subject("s-car", "Car", "truck"),
            subject("s-grill", "Grill"),
        ),
        actions = listOf(
            action("a-change-filter", "Change filter"),
            action("a-change-oil", "Change oil", "oil change"),
            action("a-add-chlorine", "Add chlorine"),
            action("a-clean", "Clean"),
        ),
        pairs = emptyList(),
    )

    private fun clean(question: String, subject: String?, action: String?, dateWindow: String? = null, cat: TagCatalog = catalog) =
        QuestionWords.clean(question, QuestionCandidate(subject, action, dateWindow, QuestionKind.LAST_TIME), cat)

    // ---- Rule 1 ---------------------------------------------------------------------------

    @Test
    fun `rule 1 drops last-time-only date words`() {
        for (w in listOf("last", "Last", "last time", "the last time", "ever", "at all", "before", " Ever? ")) {
            assertEquals(null, clean("When was the last time I cleaned the grill ever before at all?", "grill", "clean", w).dateWindow, w)
        }
    }

    @Test
    fun `rule 1 keeps real date words`() {
        for (w in listOf("last week", "the last month", "last year", "ever since June", "in August")) {
            assertEquals(w, clean("q?", "grill", "clean", w).dateWindow, w)
        }
        val c = QuestionCandidate("grill", "clean", "last week")
        assertSame(c, QuestionWords.withoutLastTimeDateWords(c))
        assertEquals(null, QuestionWords.withoutLastTimeDateWords(c.copy(dateWindow = "last")).dateWindow)
    }

    // ---- Rule 2 ---------------------------------------------------------------------------

    @Test
    fun `rule 2 drops placeholder subjects`() {
        for (s in QuestionWords.PLACEHOLDER_SUBJECTS + listOf("I", "Something", "it.")) {
            val c = clean("When did I last clean something?", s, "clean")
            assertEquals(null, c.subject, s)
            assertEquals("clean", c.action, s)
        }
    }

    @Test
    fun `rule 2 keeps a placeholder that is exactly a tag`() {
        val withStuff = catalog.copy(subjects = catalog.subjects + subject("s-stuff", "Stuff"))
        assertEquals("stuff", clean("When did I last clean stuff?", "stuff", "clean", cat = withStuff).subject)
    }

    // ---- Rule 3 ---------------------------------------------------------------------------

    @Test
    fun `rule 3 drops a subject made only of the date words`() {
        val c = clean("What did I do around the holidays?", "holidays", null, "around the holidays")
        assertEquals(null, c.subject)
        assertEquals("around the holidays", c.dateWindow)
        assertEquals(null, clean("What did I do around the holidays?", "the Holidays", null, "around the holidays").subject)
    }

    @Test
    fun `rule 3 needs date words that occur in the question`() {
        assertEquals("holidays", clean("What did I do around the holidays?", "holidays", null, "over the holidays").subject)
        assertEquals("holidays", clean("What did I do around the holidays?", "holidays", null, null).subject)
        // "last" is gone after rule 1, so it cannot empty a subject.
        assertEquals("last", clean("When did I last clean?", "last", null, "last").subject)
    }

    @Test
    fun `rule 3 keeps a subject with any word outside the date words`() {
        assertEquals("holiday lights", clean("When did I hang holiday lights around the holidays?", "holiday lights", null, "around the holidays").subject)
    }

    @Test
    fun `rule 3 keeps a subject that is exactly a tag`() {
        assertEquals("grill", clean("Did I clean the grill?", "grill", "clean", "clean the grill").subject)
    }

    // ---- Rule 4 ---------------------------------------------------------------------------

    @Test
    fun `rule 4 drops generic actions`() {
        for (a in QuestionWords.GENERIC_ACTIONS + listOf("Do", "get done.")) {
            val c = clean("What did I do to the grill?", "grill", a)
            assertEquals(null, c.action, a)
            assertEquals("grill", c.subject, a)
        }
    }

    @Test
    fun `rule 4 keeps a generic action that is exactly a tag`() {
        val withDo = catalog.copy(actions = catalog.actions + action("a-work-on", "Work on"))
        assertEquals("work on", clean("When did I last work on the car?", "car", "work on", cat = withDo).action)
    }

    // ---- Rule 5 ---------------------------------------------------------------------------

    @Test
    fun `rule 5 re-joins an untagged subject with its action`() {
        assertEquals(QuestionCandidate(null, "add chlorine", null, QuestionKind.LAST_TIME), clean("When did I last add chlorine?", "chlorine", "add"))
        assertEquals("change oil", clean("How often do I change the oil?", "oil", "change").action)
        assertEquals("change oil", clean("How often do I change the oil?", "the oil", "Change").action)
        assertEquals(null, clean("How often do I change the oil?", "the oil", "Change").subject)
    }

    @Test
    fun `rule 5 does not apply when the subject is a tag or near one`() {
        // "spa" is an alias: Exact.
        assertEquals(QuestionCandidate("spa", "add", null, QuestionKind.LAST_TIME), clean("q?", "spa", "add"))
        // "furnaces" is the existing "Furnace" once made singular; "furnace room" is near it.
        assertEquals("furnaces", clean("q?", "furnaces", "change").subject)
        assertEquals("furnace room", clean("q?", "furnace room", "add").subject)
    }

    @Test
    fun `rule 5 does not apply when the joined phrase is no existing action`() {
        assertEquals("boat", clean("When did I last wash the boat?", "boat", "wash").subject)
        // "clean gutters" is only near "Clean" by its verb: the named subject must stay.
        val c = clean("When did I last clean the gutters?", "gutters", "clean")
        assertEquals("gutters", c.subject)
        assertEquals("clean", c.action)
    }

    @Test
    fun `rule 5 needs an action`() {
        assertEquals("chlorine", clean("When did I last chlorine?", "chlorine", null).subject)
        assertEquals("chlorine", clean("When did I last chlorine?", "chlorine", "  ").subject)
    }

    // ---- Rule 6 ---------------------------------------------------------------------------

    @Test
    fun `rule 6 re-splits a subject whose last word belongs to the action`() {
        assertEquals(
            QuestionCandidate("furnace", "change filter", null, QuestionKind.LAST_TIME),
            clean("When did I last change the furnace filter?", "furnace filter", "change", "last"),
        )
        assertEquals("the furnace", clean("q?", "the furnace filter", "change").subject)
    }

    @Test
    fun `rule 6 accepts a near joined action with the same object words`() {
        // "replace filter" is near "Change filter" (same object word "filter").
        val c = clean("Have I ever replaced the furnace filter?", "furnace filter", "replace")
        assertEquals("furnace", c.subject)
        assertEquals("replace filter", c.action)
    }

    @Test
    fun `rule 6 does not apply without an exact shorter subject, an existing joined action, or when the subject is exact`() {
        assertEquals("boiler filter", clean("q?", "boiler filter", "change").subject)
        assertEquals("furnace vent", clean("q?", "furnace vent", "change").subject)
        assertEquals("furnace vent", clean("q?", "furnace vent", "clean").subject)
        val withFilter = catalog.copy(subjects = catalog.subjects + subject("s-ff", "Furnace filter"))
        assertEquals(QuestionCandidate("furnace filter", "change", null, QuestionKind.LAST_TIME), clean("q?", "furnace filter", "change", cat = withFilter))
        assertEquals("furnace filter", clean("q?", "furnace filter", null).subject)
    }

    // ---- Order and no-op ------------------------------------------------------------------

    @Test
    fun `rules apply in order`() {
        // Rule 4 empties the action before rule 5 could join "do" + "chlorine".
        assertEquals(QuestionCandidate("chlorine", null, null, QuestionKind.LAST_TIME), clean("What did I do with chlorine?", "chlorine", "do"))
        // Rule 1 removes "last" before rule 3 could match a subject "last" against it.
        assertEquals("last", clean("When did I last clean?", "last", "clean", "last").subject)
        // Rule 2 empties the subject, so rules 5 and 6 never see it.
        assertEquals(QuestionCandidate(null, "clean", null, QuestionKind.LAST_TIME), clean("When did I last clean something?", "something", "clean"))
        // Rule 5 empties the subject, so rule 6 does not also re-split.
        assertEquals(QuestionCandidate(null, "change oil", null, QuestionKind.LAST_TIME), clean("q?", "oil", "change"))
    }

    @Test
    fun `nothing applies to well-formed words`() {
        val c = QuestionCandidate("hot tub", "add chlorine", "this year", QuestionKind.COUNT)
        assertEquals(c, QuestionWords.clean("How many times did I add chlorine to the hot tub this year?", c, catalog))
        val none = QuestionCandidate(null, null, null, QuestionKind.UNKNOWN)
        assertEquals(none, QuestionWords.clean("What have I logged?", none, catalog))
    }
}
