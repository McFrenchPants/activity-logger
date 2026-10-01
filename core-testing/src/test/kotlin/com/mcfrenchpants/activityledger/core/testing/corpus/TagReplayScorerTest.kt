package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests of [TagReplay] with small hand-built tag recordings over real tag corpus case ids.
 * Every case gets a default "not a log" answer (held for review); the cases under test get the
 * answers below.
 */
class TagReplayScorerTest {

    private val corpus = TagCorpus.load()
    private val replay = TagReplay(corpus)

    private fun log(
        subject: String?,
        action: String?,
        time: String? = null,
        duration: String? = null,
    ) = RecordedExtraction(InterpretationOperation.LOG_ACTIVITY, subject, action, null, time, duration)

    private fun scored(caseId: String, answer: RecordedExtraction): TagReplayEntry =
        replay.replay(TagRecordings.withAnswers(corpus, mapOf(caseId to answer))).entries.single { it.caseId == caseId }

    @Test
    fun `exact existing tags with the right time are CORRECT`() {
        val e = scored("real-changed-hot-tub-filter", log("hot tub", "change filter", time = "just"))
        assertEquals(TagReplayClass.CORRECT, e.replayClass)
        assertEquals(TagOutcome.AUTO_SAVE, e.outcome)
        assertEquals("subj-hot-tub", e.subjectTag)
        assertEquals("act-change-filter", e.actionTag)
        assertEquals(true, e.timeOk)
        assertEquals(true, e.durationOk)
        assertTrue(e.unsafeChecks.isEmpty() && e.decisionReasons.isEmpty())
    }

    @Test
    fun `held for review when a silent save was expected is SAFE_MISS with the reason codes`() {
        val e = scored("real-changed-furnace-filter", RecordedExtraction(InterpretationOperation.UNSUPPORTED, null, null, null, null, null))
        assertEquals(TagReplayClass.SAFE_MISS, e.replayClass)
        assertEquals(TagOutcome.NEEDS_REVIEW, e.outcome)
        assertEquals(listOf("NOT_A_LOG"), e.reasonCodes)
    }

    @Test
    fun `review on a case that expects review is CORRECT`() {
        val e = scored("p-ambiguous-worked-on-the-yard", log("yard", "work on"))
        assertEquals(TagReplayClass.CORRECT, e.replayClass)
        assertEquals(TagOutcome.NEEDS_REVIEW, e.outcome)
    }

    @Test
    fun `a failure entry is FAILED`() {
        val recording = TagRecordings.withAnswers(corpus, emptyMap())
        val failed = recording.copy(
            entries = recording.entries.map {
                if (it.caseId == "real-reboot-wifi") TagRecordingEntry(it.caseId, null, InterpreterFailureKind.MALFORMED, 10) else it
            },
        )
        val e = replay.replay(failed).entries.single { it.caseId == "real-reboot-wifi" }
        assertEquals(TagReplayClass.FAILED, e.replayClass)
        assertNull(e.outcome)
        assertNull(e.timeOk)
        assertEquals(listOf("MALFORMED"), e.reasonCodes)
    }

    @Test
    fun `a silently saved odd new name is NAME_MISMATCH`() {
        val e = scored("p-new-flushed-the-water-heater", log("boiler", "flush"))
        assertEquals(TagReplayClass.NAME_MISMATCH, e.replayClass)
        assertEquals(TagOutcome.AUTO_SAVE, e.outcome)
        assertEquals(listOf(TagNameMismatch.SUBJECT_NEW_NAME), e.nameMismatches)
        assertEquals(TagReplay.NEW, e.subjectTag)
    }

    @Test
    fun `acceptable new names are CORRECT`() {
        val e = scored("p-new-flushed-the-water-heater", log("water heater", "drain"))
        assertEquals(TagReplayClass.CORRECT, e.replayClass)
    }

    @Test
    fun `a must-not-match subject is UNSAFE`() {
        val e = scored("real-changed-hot-tub-filter", log("furnace", "change filter", time = "just"))
        assertEquals(TagReplayClass.UNSAFE, e.replayClass)
        assertEquals(
            listOf(TagUnsafeCheck.SUBJECT_MUST_NOT_MATCH, TagUnsafeCheck.SUBJECT_WRONG_EXISTING_TAG),
            e.unsafeChecks,
        )
    }

    @Test
    fun `a must-not-match action is UNSAFE`() {
        val e = scored("real-weeded-garden-half-hour", log("garden", "mow", duration = "for half an hour"))
        assertEquals(listOf(TagUnsafeCheck.ACTION_MUST_NOT_MATCH, TagUnsafeCheck.ACTION_WRONG_EXISTING_TAG), e.unsafeChecks)
    }

    @Test
    fun `another existing subject is WRONG_EXISTING_TAG`() {
        val e = scored("real-changed-hot-tub-filter", log("dogs", "change filter", time = "just"))
        assertEquals(TagReplayClass.UNSAFE, e.replayClass)
        assertEquals(listOf(TagUnsafeCheck.SUBJECT_WRONG_EXISTING_TAG), e.unsafeChecks)
    }

    @Test
    fun `a new tag where an existing one is expected is DUPLICATE_TAG`() {
        val e = scored("real-changed-furnace-filter", log("boiler", "change filter", time = "just"))
        assertEquals(listOf(TagUnsafeCheck.SUBJECT_DUPLICATE_TAG), e.unsafeChecks)
        val a = scored("real-changed-furnace-filter", log("furnace", "vacuum", time = "just"))
        assertEquals(listOf(TagUnsafeCheck.ACTION_DUPLICATE_TAG), a.unsafeChecks)
    }

    @Test
    fun `a silent save on a review case is SILENT_WHEN_NOT_ACCEPTABLE`() {
        // "yard" is now a synonym of the existing "lawn" (a confirmation card), so use a new subject.
        val e = scored("p-ambiguous-worked-on-the-yard", log("shed", "rake"))
        assertEquals(TagReplayClass.UNSAFE, e.replayClass)
        assertEquals(listOf(TagUnsafeCheck.SILENT_WHEN_NOT_ACCEPTABLE), e.unsafeChecks)
    }

    @Test
    fun `an inferred subject that is the expected one is CORRECT`() {
        val e = scored("p-mow-finished-mowing", log(null, "mow"))
        assertEquals(TagReplayClass.CORRECT, e.replayClass)
        assertTrue(e.subjectInferred)
        assertEquals("subj-lawn", e.subjectTag)
    }

    @Test
    fun `an inferred subject that is not acceptable is still WRONG_EXISTING_TAG`() {
        // household: "water" pairs only with garden; the case expects lawn and allows the subject to be omitted.
        val e = scored("p-mow-finished-mowing", log(null, "water"))
        assertTrue(e.subjectInferred)
        assertEquals(TagReplayClass.UNSAFE, e.replayClass)
        assertEquals(
            listOf(TagUnsafeCheck.SUBJECT_WRONG_EXISTING_TAG, TagUnsafeCheck.ACTION_WRONG_EXISTING_TAG),
            e.unsafeChecks,
        )
    }

    @Test
    fun `a time on another day than expected is WRONG_TIME`() {
        assertEquals(TagReplayClass.CORRECT, scored("p-time-mowed-yesterday", log("lawn", "mow", time = "yesterday")).replayClass)
        val missing = scored("p-time-mowed-yesterday", log("lawn", "mow", time = null))
        assertEquals(listOf(TagUnsafeCheck.WRONG_TIME), missing.unsafeChecks)
        assertEquals(false, missing.timeOk)
        assertFalse(missing.timeDropped)
        // Grounded words (in the sentence) that resolve to another day than expected.
        val otherDay = scored("p-time-mowed-saturday-morning", log("lawn", "mow", time = "morning"))
        assertEquals(listOf(TagUnsafeCheck.WRONG_TIME), otherDay.unsafeChecks)
        assertFalse(otherDay.timeDropped)
    }

    @Test
    fun `an invented time the sentence does not contain is dropped before scoring`() {
        val otherDay = scored("real-weeded-garden-half-hour", log("garden", "weed", time = "two days ago", duration = "for half an hour"))
        assertEquals(TagReplayClass.CORRECT, otherDay.replayClass)
        assertTrue(otherDay.timeDropped)
        assertFalse(otherDay.durationDropped)
        assertEquals(true, otherDay.timeOk)
        val today = scored("real-weeded-garden-half-hour", log("garden", "weed", time = "this evening", duration = "for half an hour"))
        assertEquals(TagReplayClass.CORRECT, today.replayClass)
        assertTrue(today.timeDropped)
        val placeholder = scored("real-changed-hot-tub-filter", log("hot tub", "change filter", time = "n/a"))
        assertEquals(TagReplayClass.CORRECT, placeholder.replayClass)
        assertTrue(placeholder.timeDropped)
    }

    @Test
    fun `unreadable time words count as a wrong time on a silent save`() {
        // Grounded (the sentence contains it) but unreadable on its own: "last" points at some other day.
        val e = scored("p-time-took-out-the-trash-last-night", log("trash", "take out", time = "last"))
        assertEquals(listOf(TagUnsafeCheck.WRONG_TIME), e.unsafeChecks)
        assertFalse(e.timeDropped)
    }

    @Test
    fun `duration is checked through DurationResolver`() {
        val ok = scored("real-weeded-garden-half-hour", log("garden", "weed", duration = "half an hour"))
        assertEquals(TagReplayClass.CORRECT, ok.replayClass)
        assertEquals(true, ok.durationOk)
        // "30 minutes" is not in the sentence: dropped, so the expected 30 minutes are lost.
        val ungrounded = scored("real-weeded-garden-half-hour", log("garden", "weed", duration = "30 minutes"))
        assertTrue(ungrounded.durationDropped)
        assertEquals(listOf(TagUnsafeCheck.WRONG_DURATION), ungrounded.unsafeChecks)
        val wrong = scored("real-weeded-garden-half-hour", log("garden", "weed", duration = "an hour"))
        assertEquals(listOf(TagUnsafeCheck.WRONG_DURATION), wrong.unsafeChecks)
        // Duration words misplaced into the time field: today's date, but the duration is lost.
        val misplaced = scored("real-weeded-garden-half-hour", log("garden", "weed", time = "for half an hour"))
        assertEquals(listOf(TagUnsafeCheck.WRONG_DURATION), misplaced.unsafeChecks)
        // An invented duration is dropped by the grounding guard before scoring.
        val invented = scored("real-changed-hot-tub-filter", log("hot tub", "change filter", time = "just", duration = "an hour"))
        assertEquals(TagReplayClass.CORRECT, invented.replayClass)
        assertTrue(invented.durationDropped)
        assertFalse(invented.timeDropped)
        // "an hour" followed by "ago" in the sentence is a time, not a duration.
        val ago = scored("p-time-mowed-about-an-hour-ago", log("lawn", "mow", time = "about an hour ago", duration = "for an hour"))
        assertEquals(TagReplayClass.CORRECT, ago.replayClass)
        assertTrue(ago.durationDropped)
        assertFalse(ago.timeDropped)
    }

    @Test
    fun `drop counts are reported on the result`() {
        val result = replay.replay(
            TagRecordings.withAnswers(
                corpus,
                mapOf(
                    "real-mowed-lawn-40-minutes" to log("lawn", "mow", time = "yesterday", duration = "for 40 minutes"),
                    "p-time-mowed-about-an-hour-ago" to log("lawn", "mow", time = "about an hour ago", duration = "for an hour"),
                ),
            ),
        )
        assertEquals(1, result.timeDroppedCount)
        assertEquals(1, result.durationDroppedCount)
        val forty = result.entries.single { it.caseId == "real-mowed-lawn-40-minutes" }
        assertEquals(TagReplayClass.CORRECT, forty.replayClass)
        assertTrue(forty.timeDropped)
        assertFalse(forty.durationDropped)
    }

    @Test
    fun `time and duration problems on a non-silent decision are not unsafe`() {
        // "gardne" is one swap from "garden": a confirmation card, nothing saved silently. The
        // duration words are in the sentence (grounded, not dropped) but wrong.
        val e = scored("real-weeded-garden-half-hour", log("gardne", "weed", duration = "an hour"))
        assertEquals(TagOutcome.CONFIRM, e.outcome)
        assertEquals(TagReplayClass.SAFE_MISS, e.replayClass)
        assertEquals(TagReplay.NEAR, e.subjectTag)
        assertEquals(listOf("SUBJECT_NEAR_EXISTING"), e.reasonCodes)
        assertTrue(e.unsafeChecks.isEmpty())
        assertFalse(e.durationOk!!)
        assertFalse(e.durationDropped)
        // Same for grounded but wrong time words ("trassh" is one letter from "trash").
        val t = scored("p-time-took-out-the-trash-last-night", log("trassh", "take out", time = "last"))
        assertEquals(TagOutcome.CONFIRM, t.outcome)
        assertTrue(t.unsafeChecks.isEmpty())
        assertFalse(t.timeOk!!)
        assertFalse(t.timeDropped)
    }

    @Test
    fun `counts per class, group and check add up`() {
        val result = replay.replay(
            TagRecordings.withAnswers(
                corpus,
                mapOf(
                    "real-changed-hot-tub-filter" to log("furnace", "change filter", time = "just"),
                    "p-mow-finished-mowing" to log(null, "mow"),
                ),
            ),
        )
        assertEquals(corpus.cases.map { it.id }, result.entries.map { it.caseId })
        assertEquals(corpus.cases.size, result.classCounts.values.sum())
        assertEquals(1, result.classCounts[TagReplayClass.UNSAFE])
        assertEquals(1, result.groupCounts.getValue(TagCaseGroup.REAL_ENTRY)[TagReplayClass.UNSAFE])
        assertEquals(1, result.unsafeCheckCounts[TagUnsafeCheck.SUBJECT_MUST_NOT_MATCH])
        assertEquals(0, result.unsafeCheckCounts[TagUnsafeCheck.WRONG_TIME])
        assertEquals(corpus.cases.size, result.groupCounts.values.sumOf { it.values.sum() })
        assertEquals(RecordingSource.SYNTHETIC, result.provenance.source)
    }

    @Test
    fun `stale recordings are rejected`() {
        val good = TagRecordings.withAnswers(corpus, emptyMap())
        assertFailsWith<StaleRecordingException> { replay.replay(good.copy(tagCorpusSha256 = "0".repeat(64))) }
        assertFailsWith<StaleRecordingException> { replay.replay(good.copy(entries = good.entries.drop(1))) }
        assertFailsWith<StaleRecordingException> {
            replay.replay(good.copy(entries = good.entries + TagRecordingEntry("no-such-case", null, InterpreterFailureKind.OTHER, null)))
        }
    }
}

/** Hand-built tag recordings over the real tag corpus, for tests. */
internal object TagRecordings {

    /** The default answer: not a log, so every case without an override is held for review. */
    val NOT_A_LOG = RecordedExtraction(InterpretationOperation.UNSUPPORTED, null, null, null, null, null)

    /** A SYNTHETIC recording with one entry per corpus case: [answers] where given, else [NOT_A_LOG]. */
    fun withAnswers(
        corpus: TagCorpus,
        answers: Map<String, RecordedExtraction>,
        source: RecordingSource = RecordingSource.SYNTHETIC,
    ): TagRecording {
        require(answers.keys.all { corpus.case(it) != null }) { "unknown case ids in test answers" }
        return TagRecording(
            source = source,
            modelLabel = "test",
            deviceModel = null,
            interpreterVersion = "test-extract",
            promptVersion = "4",
            schemaVersion = 1,
            tagCorpusSha256 = corpus.sha256,
            recordedAt = "2026-10-01T12:00:00Z",
            notes = null,
            entries = corpus.cases.map { TagRecordingEntry(it.id, answers[it.id] ?: NOT_A_LOG, null, 5) },
        )
    }
}
