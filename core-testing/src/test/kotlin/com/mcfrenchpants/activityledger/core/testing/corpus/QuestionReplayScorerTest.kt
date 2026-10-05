package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.lookup.DateWords
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.DATES_DROPPED
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.DATES_NOT_UNDERSTOOD
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.DATE_WORDS_DIFFER
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.MUST_NOT_MATCH
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.NOTHING_FOUND
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.UNEXPECTED_ANSWER
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.WRONG_ACTION
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.WRONG_KIND
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.WRONG_OUTCOME
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.WRONG_RANGE
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCheck.WRONG_SUBJECT
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRecordings.q
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionReplayClass.CORRECT
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionReplayClass.FAILED
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionReplayClass.SAFE_MISS
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionReplayClass.WRONG
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every class and check of [QuestionReplay], with small synthetic recordings. */
class QuestionReplayScorerTest {

    private val corpus = QuestionCorpus.load()
    private val replay = QuestionReplay(corpus)

    private fun score(caseId: String, answer: RecordedQuestion): QuestionReplayEntry =
        replay.replay(QuestionRecordings.withAnswers(corpus, mapOf(caseId to answer))).entries.single { it.caseId == caseId }

    private fun assertScore(entry: QuestionReplayEntry, replayClass: QuestionReplayClass, vararg checks: QuestionCheck) {
        assertEquals(replayClass, entry.replayClass, "${entry.caseId}: ${entry.reasonCodes}")
        assertEquals(checks.toList(), entry.checks, entry.caseId)
    }

    @Test
    fun `correct answer records the resolved ids, kind, range and date words`() {
        val e = score("c-mow-lawn-august", q("lawn", "mow", "in August"))
        assertScore(e, CORRECT)
        assertEquals(QuestionOutcome.ANSWER, e.outcome)
        assertEquals("subj-lawn", e.subjectId)
        assertEquals("act-mow", e.actionId)
        assertEquals(true, e.subjectExact)
        assertEquals(QuestionKind.COUNT, e.kind)
        assertEquals(QuestionRangeDates(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), e.range)
        assertEquals(DateWords.USED, e.dateWords)
    }

    @Test
    fun `correct browse, correct nothing-to-answer and a near match are CORRECT`() {
        assertScore(score("do-last-week", q(null, null, "last week")), CORRECT)
        assertScore(score("nl-cleaned-gutters", q("gutters", "clean")), CORRECT)
        val near = score("tr-replaced-furnace-filter", q("furnace", "replace filter"))
        assertScore(near, CORRECT)
        assertEquals(false, near.actionExact)
    }

    @Test
    fun `a preset range compares by its dates as of the case's today`() {
        val e = score("c-dogs-past-30-days", q("dogs", "walk", "in the past 30 days"))
        assertScore(e, CORRECT)
        assertEquals(QuestionRangeDates(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 10, 5)), e.range)
    }

    @Test
    fun `an extractor failure is FAILED`() {
        val recording = QuestionRecordings.withAnswers(corpus, emptyMap()).let { r ->
            r.copy(entries = r.entries.map { if (it.caseId == "lt-walked-dogs") QuestionRecordingEntry(it.caseId, null, InterpreterFailureKind.MALFORMED, 9) else it })
        }
        val e = replay.replay(recording).entries.single { it.caseId == "lt-walked-dogs" }
        assertScore(e, FAILED)
        assertNull(e.outcome)
        assertEquals(listOf("MALFORMED"), e.reasonCodes)
    }

    @Test
    fun `an answer where nothing may be answered is WRONG, with must-not-match`() {
        assertScore(score("nl-cleaned-gutters", q("grill", "clean")), WRONG, UNEXPECTED_ANSWER, MUST_NOT_MATCH)
        assertScore(score("nl-cleaned-gutters", q(null, "clean")), WRONG, UNEXPECTED_ANSWER, MUST_NOT_MATCH)
        // Dropping the future date words answers a question about next week.
        assertScore(score("tr-future-next-week", q("dogs", "walk")), WRONG, UNEXPECTED_ANSWER)
    }

    @Test
    fun `an answer for a browse and a browse for an answer are WRONG_OUTCOME`() {
        assertScore(score("do-last-week", q("dogs", "walk", "last week")), WRONG, WRONG_OUTCOME)
        assertScore(score("li-show-me-car", q(null, null)), WRONG, WRONG_OUTCOME)
    }

    @Test
    fun `wrong, missing or unwanted target sides are WRONG`() {
        assertScore(score("lt-furnace-filter", q("hot tub", "change filter")), WRONG, WRONG_SUBJECT)
        assertScore(score("lt-action-only-chlorine", q("hot tub", "add chlorine")), WRONG, WRONG_SUBJECT)
        // Unset subject, and the action is also logged for another subject (clean: grill and windows).
        assertScore(score("tr-did-ever-clean-grill", q(null, "clean")), WRONG, WRONG_SUBJECT)
        assertScore(score("lt-subject-only-grill", q("grill", "clean")), WRONG, WRONG_ACTION)
        assertScore(score("ho-change-oil", q(null, "change filter")), WRONG, WRONG_ACTION)
        assertScore(score("tr-replaced-furnace-filter", q("furnace", "change oil")), WRONG, MUST_NOT_MATCH, WRONG_ACTION)
    }

    @Test
    fun `an unset subject is CORRECT only when the action alone selects the same entries`() {
        // "change filter" is only ever logged for the furnace; "add chlorine" only for the hot tub.
        val e = score("lt-furnace-filter", q(null, "change filter"))
        assertScore(e, CORRECT)
        assertNull(e.subjectId)
        assertEquals("act-change-filter", e.actionId)
        assertScore(score("c-how-much-chlorine-last-week", q(null, "add chlorine", "last week")), CORRECT)
        // Same through the question-word clean-up: "add" + "chlorine" re-joins to the action.
        assertScore(score("c-how-much-chlorine-last-week", q("chlorine", "add", "last week")), CORRECT)
        // "clean" is logged for the grill AND the windows: other entries would be selected.
        assertScore(score("tr-have-ever-cleaned-windows", q(null, "clean")), WRONG, WRONG_SUBJECT)
        // The action must itself be acceptable.
        assertScore(score("lt-furnace-filter", q(null, "change oil")), WRONG, WRONG_SUBJECT, WRONG_ACTION)
        // A case that expects no action never takes the exception: the subject is required.
        assertScore(score("li-what-to-furnace", q(null, "change filter")), WRONG, WRONG_SUBJECT, WRONG_ACTION)
    }

    @Test
    fun `used date words with other dates are WRONG_RANGE`() {
        val e = score("c-mowed-since-june", q("lawn", "mow", "June"))
        assertScore(e, WRONG, WRONG_RANGE)
        assertEquals(QuestionRangeDates(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), e.range)
    }

    @Test
    fun `shorter copies of the date words are fine when they resolve to the same dates, else a safe miss`() {
        assertScore(score("do-show-september", q(null, null, "September")), CORRECT)
        assertScore(score("c-dogs-past-30-days", q("dogs", "walk", "the past 30 days")), CORRECT)
        assertScore(score("ho-mow-lawn-july", q("lawn", "mow", "July")), CORRECT)
        assertScore(score("do-this-month", q(null, null, "month")), SAFE_MISS, DATES_NOT_UNDERSTOOD)
        assertScore(score("do-last-week", q(null, null, "week")), SAFE_MISS, DATES_NOT_UNDERSTOOD)
        assertScore(score("li-grill-this-year", q("grill", null, "year")), SAFE_MISS, DATES_NOT_UNDERSTOOD)
    }

    @Test
    fun `a kind outside the acceptable kinds is WRONG_KIND`() {
        // No text rule decides this question's kind, so the model's kind counts.
        assertScore(score("tr-did-mow-yesterday", q("lawn", "mow", "yesterday", QuestionKind.HOW_OFTEN)), WRONG, WRONG_KIND)
        assertScore(score("tr-did-mow-yesterday", q("lawn", "mow", "yesterday", QuestionKind.COUNT)), CORRECT)
        assertScore(score("tr-did-mow-yesterday", q("lawn", "mow", "yesterday", QuestionKind.UNKNOWN)), CORRECT)
    }

    @Test
    fun `nothing found where an answer was expected is SAFE_MISS`() {
        val e = score("lt-furnace-filter", q("boiler", "change filter"))
        assertScore(e, SAFE_MISS, NOTHING_FOUND)
        assertEquals(QuestionOutcome.NOT_ENOUGH_HISTORY, e.outcome)
        assertNull(e.range)
    }

    @Test
    fun `date words not understood or dropped are SAFE_MISS`() {
        assertScore(score("c-mow-lawn-august", q("lawn", "mow", "lawn in August")), SAFE_MISS, DATES_NOT_UNDERSTOOD)
        assertScore(score("c-dogs-last-month", q("dogs", "walk", null)), SAFE_MISS, DATES_DROPPED)
        // Words that are not in the question are ignored: dropped.
        assertScore(score("c-dogs-last-month", q("dogs", "walk", "in March")), SAFE_MISS, DATES_DROPPED)
        // An expected not-understood range that the model dropped.
        assertScore(score("tr-windows-around-the-holidays", q("windows", "clean")), SAFE_MISS, DATES_DROPPED)
        // Words where none were expected that no rule understands.
        assertScore(score("tr-did-ever-clean-grill", q("grill", "clean", "clean the grill")), SAFE_MISS, DATES_NOT_UNDERSTOOD)
    }

    @Test
    fun `the right all-time dates from used words where none were expected is SAFE_MISS`() {
        // "ever" alone is last-time phrasing and is dropped by the lookup (DH4.5 rule 1): CORRECT.
        assertScore(score("tr-did-ever-clean-grill", q("grill", "clean", "ever")), CORRECT)
        // "ever before" is still resolved to all time and counted as used. The corpus has no such
        // question, so the case's text is changed in an in-memory copy of the corpus.
        val text = QuestionCorpus.resourceBytes().toString(Charsets.UTF_8)
        val original = "\"Did I ever clean the grill?\""
        assertTrue(original in text)
        val changed = QuestionCorpus.parse(text.replace(original, "\"Did I ever before clean the grill?\"").toByteArray(Charsets.UTF_8))
        val e = QuestionReplay(changed)
            .replay(QuestionRecordings.withAnswers(changed, mapOf("tr-did-ever-clean-grill" to q("grill", "clean", "ever before"))))
            .entries.single { it.caseId == "tr-did-ever-clean-grill" }
        assertScore(e, SAFE_MISS, DATE_WORDS_DIFFER)
        assertEquals(QuestionRangeDates.ALL_TIME, e.range)
    }

    @Test
    fun `a wrong check outranks a safe one`() {
        assertScore(score("c-mow-lawn-august", q("hot tub", "mow", null)), WRONG, WRONG_SUBJECT, DATES_DROPPED)
    }

    @Test
    fun `stale recordings are rejected`() {
        val good = QuestionRecordings.withAnswers(corpus, emptyMap())
        assertFailsWith<StaleRecordingException> { replay.replay(good.copy(questionCorpusSha256 = "0".repeat(64))) }
        assertFailsWith<StaleRecordingException> { replay.replay(good.copy(entries = good.entries.drop(1))) }
        assertFailsWith<StaleRecordingException> {
            replay.replay(good.copy(entries = good.entries + QuestionRecordingEntry("no-such-case", QuestionRecordings.NOTHING, null, 1)))
        }
    }

    @Test
    fun `result is in corpus order with one entry per case and counts every class`() {
        val result = replay.replay(QuestionRecordings.withAnswers(corpus, emptyMap()))
        assertEquals(corpus.cases.map { it.id }, result.entries.map { it.caseId })
        assertEquals(corpus.cases.size, result.classCounts.values.sum())
        assertTrue(result.groupCounts.keys.containsAll(QuestionCaseGroup.entries))
    }
}
