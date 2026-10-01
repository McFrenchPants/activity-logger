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
}
