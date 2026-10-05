package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRecordings.q
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuestionGateTest {

    private val corpus = QuestionCorpus.load()

    /** Two CORRECT cases, one WRONG (a never-logged subject answered with the grill), the rest NOTHING. */
    private val result = QuestionReplay(corpus).replay(
        QuestionRecordings.withAnswers(
            corpus,
            mapOf(
                "lt-furnace-filter" to q("furnace", "change filter"),
                "ho-walk-dogs" to q("dogs", "walk"),
                "nl-cleaned-gutters" to q("grill", "clean"),
            ),
        ),
    )

    @Test
    fun `the fixture result has the expected classes`() {
        assertEquals(QuestionReplayClass.CORRECT, result.entries.single { it.caseId == "lt-furnace-filter" }.replayClass)
        assertEquals(QuestionReplayClass.WRONG, result.entries.single { it.caseId == "nl-cleaned-gutters" }.replayClass)
    }

    @Test
    fun `passes when every must-stay-correct case is CORRECT and WRONG is within the limit`() {
        val wrong = result.of(QuestionReplayClass.WRONG).size
        val baseline = QuestionBaseline(listOf("lt-furnace-filter", "ho-walk-dogs"), maxWrong = wrong)
        assertEquals(emptyList(), QuestionGate.failures(result, baseline, corpus))
    }

    @Test
    fun `fails on a must-stay-correct case that is not CORRECT`() {
        val wrong = result.of(QuestionReplayClass.WRONG).size
        val failures = QuestionGate.failures(result, QuestionBaseline(listOf("nl-cleaned-gutters"), wrong), corpus)
        assertEquals(1, failures.size)
        assertTrue(failures[0].startsWith("baseline case nl-cleaned-gutters is WRONG: UNEXPECTED_ANSWER,MUST_NOT_MATCH"))
    }

    @Test
    fun `fails when WRONG exceeds maxWrong`() {
        val wrong = result.of(QuestionReplayClass.WRONG).size
        val failures = QuestionGate.failures(result, QuestionBaseline(emptyList(), wrong - 1), corpus)
        assertEquals(1, failures.size)
        assertTrue(failures[0].startsWith("WRONG cases $wrong exceed maxWrong ${wrong - 1}"))
    }

    @Test
    fun `fails on a baseline id that is not in the corpus`() {
        val failures = QuestionGate.failures(result, QuestionBaseline(listOf("no-such-case"), 100), corpus)
        assertEquals(listOf("question baseline lists case ids not in the question corpus: no-such-case"), failures)
    }

    @Test
    fun `baseline parsing is strict`() {
        assertEquals(QuestionBaseline(listOf("a"), 2), QuestionGate.parseBaseline("""{ "mustStayCorrect": ["a"], "maxWrong": 2 }"""))
        assertFailsWith<Exception> { QuestionGate.parseBaseline("""{ "mustStayCorrect": ["a"] }""") }
        assertFailsWith<Exception> { QuestionGate.parseBaseline("""{ "maxWrong": 2 }""") }
        assertFailsWith<Exception> { QuestionGate.parseBaseline("""{ "mustStayCorrect": [], "maxWrong": 2, "extra": 1 }""") }
        assertFailsWith<Exception> { QuestionGate.parseBaseline("""{ "mustStayCorrect": ["a", "a"], "maxWrong": 2 }""") }
        assertFailsWith<Exception> { QuestionGate.parseBaseline("""{ "mustStayCorrect": [], "maxWrong": -1 }""") }
    }
}
