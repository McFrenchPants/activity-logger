package com.mcfrenchpants.activityledger.core.domain.tagging

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionOutcome.AUTO_SAVE
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionOutcome.CONFIRM
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionOutcome.NEEDS_REVIEW
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.ACTION_MISSING
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.ACTION_NEAR_EXISTING
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.FILLER_WORDS
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.NEW_NAME_REJECTED
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.NOT_A_LOG
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.SUBJECT_MISSING
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.SUBJECT_NEAR_EXISTING
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.SUBJECT_ONLY_OBJECT
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason.VAGUE_ACTION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TagDecisionPolicyTest {

    private fun s(id: String, name: String) = KnownTag(id, TagKind.SUBJECT, name, emptyList())
    private fun a(id: String, name: String) = KnownTag(id, TagKind.ACTION, name, emptyList())

    private val lawn = s("s-lawn", "lawn")
    private val hotTub = s("s-hot-tub", "hot tub")
    private val furnace = s("s-furnace", "furnace")
    private val mow = a("a-mow", "mow")
    private val clean = a("a-clean", "clean")
    private val changeFilter = a("a-change-filter", "change filter")
    private val edge = a("a-edge", "edge")

    private val catalog = TagCatalog(
        subjects = listOf(lawn, hotTub, furnace),
        actions = listOf(mow, clean, changeFilter, edge),
        pairs = listOf(
            KnownPair("s-lawn", "a-mow"),
            KnownPair("s-lawn", "a-edge"),
            KnownPair("s-hot-tub", "a-clean"),
            KnownPair("s-furnace", "a-clean"),
            KnownPair("s-furnace", "a-change-filter"),
        ),
    )

    private fun extraction(
        subject: String?,
        action: String?,
        operation: InterpretationOperation = InterpretationOperation.LOG_ACTIVITY,
    ) = ExtractionCandidate(
        operation = operation,
        subject = subject,
        action = action,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        durationExpression = null,
    )

    private fun decide(subject: String?, action: String?, c: TagCatalog = catalog) =
        TagDecisionPolicy.decide(extraction(subject, action), c)

    @Test
    fun `exact subject and action auto-save with no reasons`() {
        val d = decide("the hot tub", "Clean")
        assertEquals(AUTO_SAVE, d.outcome)
        assertEquals(emptySet(), d.reasons)
        assertEquals(TagResolution.Exact(hotTub, TagMatchVia.NAME), d.subject)
        assertEquals(TagResolution.Exact(clean, TagMatchVia.NAME), d.action)
        assertFalse(d.subjectInferred)
    }

    @Test
    fun `empty catalog auto-saves new tags`() {
        val d = decide("Wi-Fi", "reboot", TagCatalog.EMPTY)
        assertEquals(AUTO_SAVE, d.outcome)
        assertEquals(TagResolution.New("Wi-Fi"), d.subject)
        assertEquals(TagResolution.New("reboot"), d.action)
    }

    @Test
    fun `new tags with nothing close auto-save`() {
        val d = decide("tractor", "change oil")
        assertEquals(AUTO_SAVE, d.outcome)
        assertEquals(TagResolution.New("tractor"), d.subject)
        assertEquals(TagResolution.New("change oil"), d.action)
    }

    @Test
    fun `rule 1 - anything but a log needs review first`() {
        listOf(InterpretationOperation.QUERY_HISTORY, InterpretationOperation.UNSUPPORTED).forEach { op ->
            val d = TagDecisionPolicy.decide(extraction(null, null, op), catalog)
            assertEquals(NEEDS_REVIEW, d.outcome)
            assertEquals(setOf(NOT_A_LOG), d.reasons)
        }
        // Even with perfect words.
        assertEquals(setOf(NOT_A_LOG), TagDecisionPolicy.decide(extraction("lawn", "mow", InterpretationOperation.QUERY_HISTORY), catalog).reasons)
    }

    @Test
    fun `rule 2 - missing action`() {
        listOf(null, "", "  ", "the").forEach {
            assertEquals(setOf(ACTION_MISSING), decide("lawn", it).reasons)
        }
        // Action missing is checked before filler in the subject.
        assertEquals(setOf(ACTION_MISSING), decide("thing", null).reasons)
    }

    @Test
    fun `rule 3 - vague actions`() {
        listOf(
            "do", "did", "done", "doing", "handle", "handled", "work", "worked", "fix", "fixed", "deal", "dealt",
            "take care", "sort", "sorted", "work on", "worked on", "deal with", "take care of", "sort out", "Did",
        ).forEach { assertEquals(setOf(VAGUE_ACTION), decide("furnace", it).reasons, "vague action #${it.length}") }
        // A vague verb followed only by pronouns is still vague.
        listOf(
            "fix it", "do it", "did that", "handled this", "take care of it", "work on it", "deal with them",
            "sorted that out", "sort it out", "fixed those", "worked on these", "dealt with it", "Did That",
        ).forEach { assertEquals(setOf(VAGUE_ACTION), decide("furnace", it).reasons, "pronoun action #${it.length}") }
        assertFalse(TagDecisionPolicy.isVagueAction("fix it fence"))
        assertFalse(TagDecisionPolicy.isVagueAction("sort out mail"))
        assertFalse(TagDecisionPolicy.isVagueAction("mow it"))
        assertFalse(TagDecisionPolicy.isVagueAction("fix out"))
        // A verb with an object word is not vague.
        assertEquals(AUTO_SAVE, decide("fence", "fix fence").outcome)
        assertFalse(TagDecisionPolicy.isVagueAction("work out"))
        assertFalse(TagDecisionPolicy.isVagueAction("mow"))
        // Vague is checked before filler.
        assertEquals(setOf(VAGUE_ACTION), decide("furnace thing", "do").reasons)
    }

    @Test
    fun `rule 4 - filler words in subject or action`() {
        assertEquals(setOf(FILLER_WORDS), decide("furnace thing", "clean").reasons)
        assertEquals(setOf(FILLER_WORDS), decide("garage", "move stuff").reasons)
        assertEquals(setOf(FILLER_WORDS), decide("Things", "clean").reasons)
        // Whole words only.
        assertFalse(TagDecisionPolicy.hasFiller("something"))
        // Filler is checked before new-name rules (which would also reject it).
        assertEquals(setOf(FILLER_WORDS), decide("yesterday stuff", "clean").reasons)
    }

    @Test
    fun `rule 5 - new names failing the new-name rules`() {
        assertEquals(setOf(NEW_NAME_REJECTED), decide("garage yesterday", "clean").reasons)
        assertEquals(setOf(NEW_NAME_REJECTED), decide("garage", "finished painting").reasons)
        assertEquals(setOf(NEW_NAME_REJECTED), decide("12345", "clean").reasons)
        assertEquals(setOf(NEW_NAME_REJECTED), decide("garage", "x".repeat(61)).reasons)
        // Checked before subject-missing.
        assertEquals(setOf(NEW_NAME_REJECTED), decide(null, "paint today").reasons)
    }

    @Test
    fun `rule 6 - an omitted subject is inferred from the only pair with the action`() {
        val d = decide(null, "change filter")
        assertEquals(AUTO_SAVE, d.outcome)
        assertTrue(d.subjectInferred)
        assertEquals(TagResolution.Exact(furnace, TagMatchVia.NAME), d.subject)

        assertEquals(TagResolution.Exact(lawn, TagMatchVia.NAME), decide("", "mow").subject)
    }

    @Test
    fun `rule 6 - no inference from zero or several pairs, or from a non-exact action`() {
        // Two subjects are paired with clean.
        val two = decide(null, "clean")
        assertEquals(NEEDS_REVIEW, two.outcome)
        assertEquals(setOf(SUBJECT_MISSING), two.reasons)
        assertFalse(two.subjectInferred)
        assertEquals(TagResolution.Empty, two.subject)
        // No pair for this action.
        val zero = TagCatalog(listOf(lawn), listOf(mow), emptyList())
        assertEquals(setOf(SUBJECT_MISSING), decide(null, "mow", zero).reasons)
        // New and near actions are not inferred from.
        assertEquals(setOf(SUBJECT_MISSING), decide(null, "paint").reasons)
        assertEquals(setOf(SUBJECT_MISSING), decide(null, "swap filter").reasons)
        // Duplicate pairs still count as one subject.
        val dup = TagCatalog(listOf(lawn), listOf(mow), listOf(KnownPair("s-lawn", "a-mow"), KnownPair("s-lawn", "a-mow")))
        assertTrue(decide(null, "mow", dup).subjectInferred)
    }

    @Test
    fun `rule 7 - anything close asks to confirm`() {
        val subjectNear = decide("lawn mower", "mow")
        assertEquals(CONFIRM, subjectNear.outcome)
        assertEquals(setOf(SUBJECT_NEAR_EXISTING), subjectNear.reasons)
        assertEquals(listOf(lawn), assertIs<TagResolution.Near>(subjectNear.subject).candidates)

        val actionNear = decide("hot tub", "replace filter")
        assertEquals(CONFIRM, actionNear.outcome)
        assertEquals(setOf(ACTION_NEAR_EXISTING), actionNear.reasons)

        assertEquals(setOf(SUBJECT_NEAR_EXISTING, ACTION_NEAR_EXISTING), decide("hot tub cover", "swap filter").reasons)
    }

    @Test
    fun `speech slips become new tags, never an unrelated existing tag`() {
        val d = decide("James Durango", "replace headlight")
        assertEquals(AUTO_SAVE, d.outcome)
        assertEquals(TagResolution.New("James Durango"), d.subject)
        assertEquals(TagResolution.New("replace headlight"), d.action)
    }

    @Test
    fun `reasons are empty exactly when auto-saving`() {
        val inputs = listOf(
            "lawn" to "mow", null to "mow", null to null, "thing" to "do", "lawn mower" to "mow",
            "tractor" to "change oil", null to "clean", "garage today" to "clean", "hot tub" to "replace filter",
        )
        inputs.forEach { (subject, action) ->
            val d = decide(subject, action)
            assertEquals(d.outcome == AUTO_SAVE, d.reasons.isEmpty())
        }
        assertFailsWith<IllegalArgumentException> {
            TagDecision(AUTO_SAVE, TagResolution.Empty, TagResolution.Empty, false, setOf(SUBJECT_MISSING))
        }
        assertFailsWith<IllegalArgumentException> {
            TagDecision(CONFIRM, TagResolution.Empty, TagResolution.Empty, false, emptySet())
        }
    }

    @Test
    fun `state, time and duration do not affect the decision`() {
        val base = extraction("lawn", "mow")
        val variants = listOf(
            base.copy(activityState = null),
            base.copy(activityState = ActivityState.IN_PROGRESS),
            base.copy(temporalExpression = "gibberish time"),
            base.copy(durationExpression = "for half an hour"),
        )
        variants.forEach { assertEquals(TagDecisionPolicy.decide(base, catalog), TagDecisionPolicy.decide(it, catalog)) }
    }

    // ---- TG1.4b ----

    @Test
    fun `rule 1 - inflected verbs auto-save to the existing action`() {
        val d = decide("hot tub", "cleaning")
        assertEquals(AUTO_SAVE, d.outcome)
        assertEquals(TagResolution.Exact(clean, TagMatchVia.NAME), d.action)
        assertEquals(TagResolution.Exact(edge, TagMatchVia.NAME), decide("lawn", "edged").action)
        // Inference works through a stemmed exact action.
        val inferred = decide(null, "mowing")
        assertEquals(AUTO_SAVE, inferred.outcome)
        assertTrue(inferred.subjectInferred)
    }

    @Test
    fun `rule 2 - re-split moves the subject's trailing word into a one-word action`() {
        val furnaceFilter = decide("furnace filter", "change")
        assertEquals(AUTO_SAVE, furnaceFilter.outcome)
        assertTrue(furnaceFilter.resplit)
        assertEquals(TagResolution.Exact(furnace, TagMatchVia.NAME), furnaceFilter.subject)
        assertEquals(TagResolution.Exact(changeFilter, TagMatchVia.NAME), furnaceFilter.action)

        val hotTubFilter = decide("the hot tub filter", "changed")
        assertEquals(AUTO_SAVE, hotTubFilter.outcome)
        assertTrue(hotTubFilter.resplit)
        assertEquals(TagResolution.Exact(hotTub, TagMatchVia.NAME), hotTubFilter.subject)
    }

    @Test
    fun `rule 2 - no re-split unless both sides become exact`() {
        // "replace filter" is not an existing action here: keep the original words.
        val replace = decide("furnace filter", "replace")
        assertFalse(replace.resplit)
        assertEquals(CONFIRM, replace.outcome)
        assertEquals(setOf(SUBJECT_NEAR_EXISTING), replace.reasons)
        // Already both exact: nothing to re-split.
        assertFalse(decide("hot tub", "clean").resplit)
        // The action has two words: no re-split.
        val twoWords = decide("lawn mower", "mow lawn")
        assertFalse(twoWords.resplit)
        // "lawn" / "mow mower" is not exact.
        val mower = decide("lawn mower", "mow")
        assertFalse(mower.resplit)
        assertEquals(CONFIRM, mower.outcome)
        // Empty catalog: never.
        assertFalse(decide("furnace filter", "change", TagCatalog.EMPTY).resplit)
        assertEquals(AUTO_SAVE, decide("furnace filter", "change", TagCatalog.EMPTY).outcome)
    }

    @Test
    fun `rule 3 - a subject that is only the verb is treated as absent`() {
        val edging = decide("edging", "edging")
        assertEquals(AUTO_SAVE, edging.outcome)
        assertTrue(edging.subjectInferred)
        assertEquals(TagResolution.Exact(lawn, TagMatchVia.NAME), edging.subject)
        assertEquals(TagResolution.Exact(edge, TagMatchVia.NAME), edging.action)
        assertTrue(decide("mowing", "mow").subjectInferred)
        assertTrue(TagDecisionPolicy.isJunkSubject("Edging", "edge"))
        // Without a single known pair the absent subject is still missing (never invented).
        val noPair = TagCatalog(listOf(lawn), listOf(edge), emptyList())
        assertEquals(setOf(SUBJECT_MISSING), decide("edging", "edging", noPair).reasons)
        assertEquals(setOf(SUBJECT_MISSING), decide("edging", "edging", TagCatalog.EMPTY).reasons)
    }

    @Test
    fun `rule 3 - a subject made only of time words is treated as absent`() {
        listOf("this morning", "Saturday", "Saturday morning", "last night", "today").forEach {
            val d = decide(it, "mow")
            assertEquals(AUTO_SAVE, d.outcome, "time subject #${it.length}")
            assertTrue(d.subjectInferred)
        }
        // Several subjects pair with clean: missing, not guessed.
        assertEquals(setOf(SUBJECT_MISSING), decide("this morning", "clean").reasons)
    }

    @Test
    fun `rule 3 - a subject that names an existing tag is never discarded`() {
        // A dog called June: "June" is a month name, but it is an existing subject.
        val june = s("s-june", "June")
        val dogs = s("s-dogs", "dogs")
        val walk = a("a-walk", "walk")
        val feed = a("a-feed", "feed")
        val pets = TagCatalog(
            subjects = listOf(dogs, june),
            actions = listOf(walk, feed),
            pairs = listOf(KnownPair("s-dogs", "a-walk"), KnownPair("s-june", "a-feed")),
        )
        val d = decide("June", "walked", pets)
        assertEquals(AUTO_SAVE, d.outcome)
        assertFalse(d.subjectInferred)
        assertEquals(TagResolution.Exact(june, TagMatchVia.NAME), d.subject)
        assertEquals(TagResolution.Exact(walk, TagMatchVia.NAME), d.action)

        // An existing subject that is the action verb's gerund is kept too.
        val edgingSubject = s("s-edging", "edging")
        val c = TagCatalog(
            subjects = listOf(lawn, edgingSubject),
            actions = listOf(edge),
            pairs = listOf(KnownPair("s-lawn", "a-edge")),
        )
        val e = decide("edging", "edging", c)
        assertEquals(AUTO_SAVE, e.outcome)
        assertFalse(e.subjectInferred)
        assertEquals(TagResolution.Exact(edgingSubject, TagMatchVia.NAME), e.subject)

        // Time-only words close to an existing subject are kept as Near (asked), not discarded.
        val market = s("s-market", "Saturday market")
        val withMarket = TagCatalog(listOf(dogs, market), listOf(walk), listOf(KnownPair("s-dogs", "a-walk")))
        val near = decide("Saturday", "walk", withMarket)
        assertEquals(CONFIRM, near.outcome)
        assertEquals(listOf(market), assertIs<TagResolution.Near>(near.subject).candidates)
        assertFalse(near.subjectInferred)
    }

    @Test
    fun `rule 3 - real subjects are not junk`() {
        assertFalse(TagDecisionPolicy.isJunkSubject("lawn", "edging"))
        assertFalse(TagDecisionPolicy.isJunkSubject("edging", "mow"))
        assertFalse(TagDecisionPolicy.isJunkSubject("garage yesterday", "clean"))
        assertFalse(TagDecisionPolicy.isJunkSubject("morning glory", "water"))
        assertFalse(TagDecisionPolicy.isJunkSubject(null, "mow"))
        assertFalse(TagDecisionPolicy.isJunkSubject("edge trimmer", "edge"))
        val d = decide("lawn", "edging")
        assertEquals(AUTO_SAVE, d.outcome)
        assertFalse(d.subjectInferred)
    }

    @Test
    fun `rule 4 - a subject that is only the action's object is never inferred silently`() {
        val inAction = decide("filter", "change filter")
        assertEquals(CONFIRM, inAction.outcome)
        assertEquals(setOf(SUBJECT_ONLY_OBJECT, SUBJECT_NEAR_EXISTING), inAction.reasons)
        assertEquals(listOf(furnace), assertIs<TagResolution.Near>(inAction.subject).candidates)
        assertEquals(TagResolution.Exact(changeFilter, TagMatchVia.NAME), inAction.action)
        assertFalse(inAction.subjectInferred)

        // "filter" + "change" = the existing "change filter".
        val combined = decide("filter", "change")
        assertEquals(CONFIRM, combined.outcome)
        assertEquals(setOf(SUBJECT_ONLY_OBJECT, SUBJECT_NEAR_EXISTING), combined.reasons)
        assertEquals(TagResolution.Exact(changeFilter, TagMatchVia.NAME), combined.action)

        // Through a verb synonym: "swap" + "filter" means "change filter"; the action is only Near.
        val synonym = decide("the filters", "swapped")
        assertEquals(CONFIRM, synonym.outcome)
        assertEquals(setOf(SUBJECT_ONLY_OBJECT, SUBJECT_NEAR_EXISTING, ACTION_NEAR_EXISTING), synonym.reasons)
        assertEquals(listOf(furnace), assertIs<TagResolution.Near>(synonym.subject).candidates)
    }

    @Test
    fun `rule 4 - with zero or several paired subjects the capture needs review`() {
        val twoPairs = TagCatalog(
            subjects = listOf(hotTub, furnace),
            actions = listOf(changeFilter),
            pairs = listOf(KnownPair("s-hot-tub", "a-change-filter"), KnownPair("s-furnace", "a-change-filter")),
        )
        val d = decide("filter", "change filter", twoPairs)
        assertEquals(NEEDS_REVIEW, d.outcome)
        assertEquals(setOf(SUBJECT_ONLY_OBJECT, SUBJECT_MISSING), d.reasons)
        val noPairs = TagCatalog(listOf(furnace), listOf(changeFilter), emptyList())
        assertEquals(setOf(SUBJECT_ONLY_OBJECT, SUBJECT_MISSING), decide("filter", "change", noPairs).reasons)
    }

    @Test
    fun `rule 4 - does not apply to real subjects or unknown actions`() {
        // Exact subject: the normal rules apply.
        assertEquals(AUTO_SAVE, decide("furnace", "change filter").outcome)
        // The object repeated in a NEW action is not rule 4.
        assertEquals(AUTO_SAVE, decide("fence", "fix fence").outcome)
        assertEquals(AUTO_SAVE, decide("fence", "paint", TagCatalog.EMPTY).outcome)
        // An exact one-word action with a new subject is a new subject, not rule 4.
        val newSubject = decide("patio", "clean")
        assertEquals(AUTO_SAVE, newSubject.outcome)
        assertEquals(TagResolution.New("patio"), newSubject.subject)
        // A synonym verb with a different object means nothing existing.
        assertEquals(AUTO_SAVE, decide("tractor", "swap oil").outcome)
        // Empty catalog: nothing to be the object of.
        assertEquals(AUTO_SAVE, decide("filter", "change filter", TagCatalog.EMPTY).outcome)
    }

    @Test
    fun `rules 5 and 6 - synonym and head-verb matches only ever confirm`() {
        val grassCut = decide("grass", "cut")
        assertEquals(CONFIRM, grassCut.outcome)
        assertEquals(setOf(SUBJECT_NEAR_EXISTING, ACTION_NEAR_EXISTING), grassCut.reasons)
        assertEquals(listOf(lawn), assertIs<TagResolution.Near>(grassCut.subject).candidates)
        assertEquals(listOf(mow), assertIs<TagResolution.Near>(grassCut.action).candidates)

        assertEquals(setOf(ACTION_NEAR_EXISTING), decide("lawn", "cut").reasons)
        assertEquals(setOf(ACTION_NEAR_EXISTING), decide("hot tub", "clear leaves").reasons)
        assertEquals(setOf(ACTION_NEAR_EXISTING), decide("lawn", "edge along the walk").reasons)
        assertEquals(setOf(SUBJECT_NEAR_EXISTING), decide("HVAC", "clean").reasons)
        // A Near action is never used to infer a subject.
        assertEquals(setOf(SUBJECT_MISSING), decide(null, "cut").reasons)
    }

    @Test
    fun `reasons stay empty exactly when auto-saving with the new rules`() {
        val inputs = listOf(
            "furnace filter" to "change", "edging" to "edging", "this morning" to "mow", "filter" to "change filter",
            "filter" to "swap", "grass" to "cut", "lawn" to "edge along the walk", "fence" to "fix fence",
            "lawn" to "edged", "Saturday" to "clean",
        )
        inputs.forEach { (subject, action) ->
            val d = decide(subject, action)
            assertEquals(d.outcome == AUTO_SAVE, d.reasons.isEmpty())
            if (d.outcome == AUTO_SAVE) {
                assertFalse(d.subject is TagResolution.Near || d.action is TagResolution.Near, "Near never auto-saves")
            }
        }
    }
}
