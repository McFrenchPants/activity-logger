package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagReportTest {

    private val corpus = TagCorpus.load()

    private fun answer(subject: String?, action: String?, time: String? = null, duration: String? = null) =
        RecordedExtraction(InterpretationOperation.LOG_ACTIVITY, subject, action, ActivityState.COMPLETED, time, duration)

    /**
     * Answers carrying the sentinel in every free-text field, chosen to land in every class that
     * gets a report row: NAME_MISMATCH (a new tag named from the sentinel), UNSAFE (duplicate tag,
     * wrong duration, silent save on a review case), SAFE_MISS (a near name built from the
     * sentinel) -- plus a FAILED entry. The sentinel time and duration words are not in their
     * sentences, so the grounding guard drops them (reported as counts and codes only).
     */
    private val recording: TagRecording = run {
        val base = TagRecordings.withAnswers(
            corpus,
            mapOf(
                "p-new-flushed-the-water-heater" to answer("$SENTINEL boiler", "flush"),
                "real-changed-furnace-filter" to answer(SENTINEL, "change filter", time = "just"),
                "real-changed-hot-tub-filter" to answer("hot tub", "change filter", time = "$SENTINEL two days ago"),
                "real-weeded-garden-half-hour" to answer("garden", "weed", duration = "$SENTINEL hours"),
                "p-ambiguous-worked-on-the-yard" to answer("yard $SENTINEL", "rake $SENTINEL"),
                "real-mowed-lawn-40-minutes" to answer("lawn", "mow $SENTINEL"),
                "empty-reboot-wifi" to answer("Wi-Fi$SENTINEL", "reboot"),
            ),
            source = RecordingSource.STAND_IN,
        )
        base.copy(
            entries = base.entries.map {
                if (it.caseId == "real-reboot-wifi") TagRecordingEntry(it.caseId, null, InterpreterFailureKind.MALFORMED, 7) else it
            },
        )
    }

    private val result = TagReplay(corpus).replay(recording)

    @Test
    fun `the sentinel recording reaches every reported class`() {
        val classes = result.entries.map { it.replayClass }.toSet()
        assertEquals(TagReplayClass.entries.toSet(), classes)
    }

    @Test
    fun `markdown and console never contain model output`() {
        val markdown = TagReport.markdown(result, corpus)
        val console = TagReport.consoleSummary(result)
        listOf(SENTINEL, "SENTINEL").forEach { needle ->
            assertFalse(markdown.contains(needle, ignoreCase = true), "markdown leaked model text")
            assertFalse(console.contains(needle, ignoreCase = true), "console leaked model text")
        }
    }

    @Test
    fun `markdown has totals, groups, checks, case rows, and the stand-in banner`() {
        val markdown = TagReport.markdown(result, corpus)
        assertTrue(markdown.contains("STAND-IN MODEL -- NOT THE OFFICIAL RESULT"))
        assertTrue(markdown.contains("## Totals"))
        assertTrue(markdown.contains("## By group"))
        assertTrue(markdown.contains("## Unsafe checks"))
        assertTrue(markdown.contains("| REAL_ENTRY |"))
        assertTrue(markdown.contains("| SUBJECT_DUPLICATE_TAG | 1 |"))
        assertTrue(markdown.contains("`real-changed-furnace-filter` | REAL_ENTRY | UNSAFE | AUTO_SAVE | SUBJECT_DUPLICATE_TAG"))
        assertTrue(markdown.contains("`p-new-flushed-the-water-heater` | PORTED | NAME_MISMATCH"))
        // The committed corpus sentence is allowed in the Markdown.
        assertTrue(markdown.contains(corpus.case("real-changed-furnace-filter")!!.rawText.replace("|", "\\|")))
        assertTrue(markdown.contains("`subj-furnace`"))
        assertTrue(markdown.contains("- Grounding guard: time words dropped in 1 cases, duration words dropped in 1 cases"))
    }

    @Test
    fun `console carries ids, counts and codes only`() {
        val console = TagReport.consoleSummary(result)
        assertTrue(console.startsWith("Tag replay [STAND_IN] STAND-IN MODEL -- NOT THE OFFICIAL RESULT"))
        assertTrue(console.contains("UNSAFE real-changed-furnace-filter: AUTO_SAVE SUBJECT_DUPLICATE_TAG"))
        assertTrue(console.contains("FAILED real-reboot-wifi: - MALFORMED"))
        assertTrue(console.contains("grounding: TIME_DROPPED=1, DURATION_DROPPED=1"))
        assertTrue(console.contains("GROUNDING real-changed-hot-tub-filter: TIME_DROPPED"))
        assertTrue(console.contains("GROUNDING real-weeded-garden-half-hour: DURATION_DROPPED"))
        corpus.cases.forEach { assertFalse(console.contains(it.rawText), "console contains a corpus sentence (${it.id})") }
    }

    private companion object {
        const val SENTINEL = "ZQXSENTINELQZX"
    }
}
