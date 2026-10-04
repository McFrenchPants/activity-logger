package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.testing.corpus.SemanticCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpus
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shape, content and purity of the extraction prompt (version 4) and its system instruction. */
class ExtractionPromptTest {

    private val detroit = ZoneId.of("America/Detroit")

    /** 2026-09-15T20:00-04:00, i.e. 8pm local in America/Detroit. */
    private val capturedAt: Instant = Instant.parse("2026-09-16T00:00:00Z")

    private fun input(rawText: String = "Oiled the gate hinges") =
        ExtractionInput(rawText = rawText, capturedAt = capturedAt, zoneId = detroit)

    private val headings = listOf("## Task", "## Rules", "## Current context", "## User utterance")

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    // region determinism

    @Test
    fun `same input builds byte-identical prompts`() {
        val single = input()
        assertEquals(buildExtractionPrompt(single), buildExtractionPrompt(single))
    }

    @Test
    fun `separately constructed but equal inputs build identical prompts`() {
        assertEquals(buildExtractionPrompt(input()), buildExtractionPrompt(input()))
    }

    // endregion

    // region structure

    @Test
    fun `all four sections are present once and in order`() {
        val prompt = buildExtractionPrompt(input())
        val indices = headings.map { heading ->
            assertEquals(1, prompt.occurrencesOf(heading), "heading appears once: $heading")
            prompt.indexOf(heading)
        }
        assertEquals(indices.sorted(), indices, "headings appear in the required order")
    }

    @Test
    fun `there is no candidate, activity or tag list`() {
        val texts = listOf(buildExtractionPrompt(input()), EXTRACTION_SYSTEM_INSTRUCTION)
        texts.forEach { text ->
            assertFalse(text.contains("## Candidate"), "no candidate section")
            assertFalse(text.contains("matchedActivityId"), "no matched id field")
            assertFalse(text.contains("id:"), "no rendered id rows")
            assertFalse(text.contains("aliases"), "no alias rows")
        }
        assertTrue(
            buildExtractionPrompt(input()).contains("not shown any list of activities or tags"),
            "the prompt says no list is shown",
        )
    }

    @Test
    fun `raw utterance is fenced and appears exactly once, character-identical`() {
        val raw = "  Odd  spacing, \"quotes\" and ### hashes\twith a tab  "
        val prompt = buildExtractionPrompt(input(rawText = raw))
        assertEquals(1, prompt.occurrencesOf(raw))
        assertTrue(prompt.endsWith("<<<UTTERANCE\n$raw\nUTTERANCE>>>\n"), "utterance is fenced last")
    }

    @Test
    fun `current context renders local date-time and zone deterministically`() {
        val prompt = buildExtractionPrompt(input())
        assertTrue(prompt.contains("Capture local date and time: 2026-09-15T20:00:00\n"))
        assertTrue(prompt.contains("Time zone: America/Detroit\n"))
    }

    @Test
    fun `current context forbids the model computing a date itself`() {
        assertTrue(buildExtractionPrompt(input()).contains("Do not compute a date yourself"))
    }

    // endregion

    // region content rules

    @Test
    fun `duration is separated from time wording`() {
        val prompt = buildExtractionPrompt(input())
        assertTrue(prompt.contains("A duration is NEVER time wording"))
        assertTrue(prompt.contains("Duration is not time:"))
        assertTrue(prompt.contains("duration wording \"for 45 minutes\""))
        assertTrue(EXTRACTION_SYSTEM_INSTRUCTION.contains("never time wording"))
    }

    @Test
    fun `subject rules are stated`() {
        val prompt = buildExtractionPrompt(input())
        assertTrue(prompt.contains("Subject is the thing acted on"))
        assertTrue(prompt.contains("no article, no possessive and no filler"))
        assertTrue(prompt.contains("Keep the full name"))
        assertTrue(prompt.contains("Leave the subject empty when the sentence names no thing"))
        assertTrue(prompt.contains("-> no subject, action \"vacuum\""))
    }

    @Test
    fun `action rules are stated`() {
        val prompt = buildExtractionPrompt(input())
        assertTrue(prompt.contains("Action is what was done"))
        assertTrue(prompt.contains("verb in its plain form"))
        assertTrue(prompt.contains("\"replace bulb\" and \"replace fuse\" are different actions"))
    }

    @Test
    fun `time is copied verbatim and never computed`() {
        val prompt = buildExtractionPrompt(input())
        assertTrue(prompt.contains("Copy them verbatim; never write a date, a time or a timestamp"))
        assertTrue(EXTRACTION_SYSTEM_INSTRUCTION.contains("never compute or write a date"))
    }

    @Test
    fun `state, operation and speech-slip rules are stated`() {
        val prompt = buildExtractionPrompt(input())
        assertTrue(prompt.contains("COMPLETED when the sentence says it is done"))
        assertTrue(prompt.contains("IN_PROGRESS when it says it is happening now"))
        assertTrue(prompt.contains("QUERY_HISTORY when they ask about past activity"))
        assertTrue(prompt.contains("-> QUERY_HISTORY"))
        assertTrue(prompt.contains("never add anything the sentence does not say"))
    }

    @Test
    fun `system instruction is numbered one to eight`() {
        (1..8).forEach { n ->
            assertTrue(EXTRACTION_SYSTEM_INSTRUCTION.contains("\n$n. "), "principle $n present")
        }
        assertFalse(EXTRACTION_SYSTEM_INSTRUCTION.contains("\n9. "))
    }

    // endregion

    // region not trained on the test

    @Test
    fun `no tag corpus or corpus json sentence appears in the prompt or system instruction`() {
        val sentences = TagCorpus.load().cases.map { it.id to it.rawText } +
            SemanticCorpus.load().cases.map { it.id to it.rawText }
        assertTrue(sentences.size > 100, "both corpora were loaded")

        // Compared case-insensitively and without trailing punctuation, so "Just mowed" in an
        // example would be caught as well as "Just mowed.".
        val texts = mapOf(
            "prompt" to buildExtractionPrompt(input()).lowercase(),
            "system instruction" to EXTRACTION_SYSTEM_INSTRUCTION.lowercase(),
        )
        sentences.forEach { (caseId, rawText) ->
            val needle = rawText.trim().trimEnd('.', '!', '?').lowercase()
            texts.forEach { (where, text) ->
                // Case id only in the message: never the sentence itself (AGENTS.md #11 style).
                assertFalse(text.contains(needle), "the $where contains the sentence of case $caseId")
            }
        }
    }

    // endregion
}
