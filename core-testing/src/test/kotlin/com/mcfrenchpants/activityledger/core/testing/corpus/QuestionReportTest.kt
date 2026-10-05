package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRecordings.q
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionReportTest {

    private val corpus = QuestionCorpus.load()

    /**
     * Answers carrying the sentinel in every free-text field, chosen to land in every class:
     * WRONG (a near subject built from the sentinel), SAFE_MISS (a new subject named from it),
     * CORRECT (all words the sentinel), plus a FAILED entry.
     */
    private val recording: QuestionRecording = run {
        val base = QuestionRecordings.withAnswers(
            corpus,
            mapOf(
                "nl-cleaned-gutters" to q("grill $SENTINEL", "clean $SENTINEL", SENTINEL, QuestionKind.LAST_TIME),
                "lt-furnace-filter" to q("$SENTINEL boiler", "change filter", "$SENTINEL time"),
                "nl-wash-boat" to q(SENTINEL, SENTINEL, SENTINEL),
                "tr-replaced-furnace-filter" to q("furnace", "replace filter $SENTINEL", null),
                "c-mow-lawn-august" to q("lawn", "mow", "lawn in August"),
            ),
            source = RecordingSource.DEVICE,
        )
        base.copy(
            entries = base.entries.map {
                if (it.caseId == "ho-walk-dogs") QuestionRecordingEntry(it.caseId, null, InterpreterFailureKind.MALFORMED, 7) else it
            },
        )
    }

    private val result = QuestionReplay(corpus).replay(recording)

    @Test
    fun `the sentinel recording reaches every class`() {
        assertEquals(QuestionReplayClass.entries.toSet(), result.entries.map { it.replayClass }.toSet())
        assertEquals(QuestionReplayClass.WRONG, result.entries.single { it.caseId == "nl-cleaned-gutters" }.replayClass)
        assertEquals(QuestionReplayClass.SAFE_MISS, result.entries.single { it.caseId == "lt-furnace-filter" }.replayClass)
        assertEquals(QuestionReplayClass.CORRECT, result.entries.single { it.caseId == "nl-wash-boat" }.replayClass)
    }

    @Test
    fun `replay result, markdown and console never contain model output`() {
        val markdown = QuestionReport.markdown(result, corpus)
        val console = QuestionReport.consoleSummary(result)
        listOf(SENTINEL, "SENTINEL").forEach { needle ->
            assertFalse(result.entries.toString().contains(needle, ignoreCase = true), "replay result kept model text")
            assertFalse(markdown.contains(needle, ignoreCase = true), "markdown leaked model text")
            assertFalse(console.contains(needle, ignoreCase = true), "console leaked model text")
        }
    }

    @Test
    fun `markdown has provenance, totals, groups, checks and one row per non-correct case`() {
        val markdown = QuestionReport.markdown(result, corpus)
        assertTrue(markdown.startsWith("# Question corpus replay: DEVICE"))
        assertTrue(markdown.contains("## Provenance"))
        assertTrue(markdown.contains("| questionCorpusSha256 | `${corpus.sha256}` |"))
        assertTrue(markdown.contains("## Totals"))
        assertTrue(markdown.contains("## By group"))
        assertTrue(markdown.contains("| NOT_LOGGED |"))
        assertTrue(markdown.contains("| UNEXPECTED_ANSWER | WRONG |"))
        assertTrue(markdown.contains("| DATES_NOT_UNDERSTOOD | SAFE_MISS | "))
        assertTrue(
            markdown.contains(
                "| `nl-cleaned-gutters` | NOT_LOGGED | WRONG | UNEXPECTED_ANSWER, MUST_NOT_MATCH | " +
                    "ANSWER; `subj-grill` (closest) / `act-clean` (closest); LAST_TIME; ALL_TIME; NONE | " +
                    "NOT_ENOUGH_HISTORY; unset / unset; LAST_TIME; ALL_TIME; NONE; never `subj-grill`, `subj-windows`, `act-clean` | " +
                    "When was the last time I cleaned the gutters? |",
            ),
            markdown,
        )
        assertTrue(markdown.contains("| `lt-furnace-filter` | LAST_TIME | SAFE_MISS | NOTHING_FOUND | NOT_ENOUGH_HISTORY; unset / unset; -; -; - |"))
        assertTrue(markdown.contains("| `ho-walk-dogs` | HOW_OFTEN | FAILED | MALFORMED | - |"))
        assertFalse(markdown.contains("| `nl-wash-boat` |"), "CORRECT cases get no row")
    }

    @Test
    fun `console carries ids, counts and codes only`() {
        val console = QuestionReport.consoleSummary(result)
        assertTrue(console.startsWith("Question replay [DEVICE]"))
        assertTrue(console.contains("WRONG nl-cleaned-gutters: ANSWER UNEXPECTED_ANSWER,MUST_NOT_MATCH"))
        assertTrue(console.contains("SAFE_MISS lt-furnace-filter: NOT_ENOUGH_HISTORY NOTHING_FOUND"))
        assertTrue(console.contains("FAILED ho-walk-dogs: - MALFORMED"))
        corpus.cases.forEach { assertFalse(console.contains(it.question), "console contains a corpus question (${it.id})") }
    }

    @Test
    fun `only device recordings are reported`() {
        val synthetic = QuestionReplay(corpus).replay(QuestionRecordings.withAnswers(corpus, emptyMap()))
        assertFailsWith<IllegalArgumentException> { QuestionReport.markdown(synthetic, corpus) }
        assertFailsWith<IllegalArgumentException> { QuestionReport.consoleSummary(synthetic) }
    }

    private companion object {
        const val SENTINEL = "ZQXSENTINELQZX"
    }
}
