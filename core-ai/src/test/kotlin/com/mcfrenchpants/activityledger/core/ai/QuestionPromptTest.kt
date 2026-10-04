package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.testing.corpus.SemanticCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shape, content and purity of the question prompt (version q1) and its system instruction. */
class QuestionPromptTest {

    private val question = "When did I last bleed the radiators?"

    @Test
    fun `version constant is q1`() {
        assertEquals("q1", QUESTION_PROMPT_VERSION)
    }

    @Test
    fun `same question builds byte-identical prompts`() {
        assertEquals(buildQuestionPrompt(question), buildQuestionPrompt(question))
    }

    @Test
    fun `question text is fenced last and appears exactly once, character-identical`() {
        val raw = "  Odd  spacing, \"quotes\" and ### hashes\twith a tab?  "
        val prompt = buildQuestionPrompt(raw)
        assertTrue(prompt.endsWith("<<<QUESTION\n$raw\nQUESTION>>>\n"))
        assertEquals(1, prompt.split(raw).size - 1)
    }

    @Test
    fun `prompt contains the question`() {
        assertTrue(buildQuestionPrompt(question).contains(question))
    }

    @Test
    fun `no activity, tag or catalog list is shown or mentioned as present`() {
        listOf(buildQuestionPrompt(question), QUESTION_SYSTEM_INSTRUCTION).forEach { text ->
            assertFalse(text.contains("## Candidate"))
            assertFalse(text.contains("matchedActivityId"))
            assertFalse(text.contains("id:"))
            assertFalse(text.contains("aliases"))
            assertFalse(text.contains("catalog", ignoreCase = true))
            assertFalse(text.contains("Known activities", ignoreCase = true))
        }
        assertTrue(buildQuestionPrompt(question).contains("not shown any list of activities or tags"))
    }

    @Test
    fun `asks for words only, empty when not named, and never an answer or a date`() {
        val prompt = buildQuestionPrompt(question)
        assertTrue(prompt.contains("Do not answer it"))
        assertTrue(prompt.contains("Leave a field empty when the question does not name it"))
        assertTrue(prompt.contains("-> no subject, no action"))
        assertTrue(QUESTION_SYSTEM_INSTRUCTION.contains("Never answer the question, never compute a date"))
    }

    @Test
    fun `system instruction is numbered one to five`() {
        (1..5).forEach { n -> assertTrue(QUESTION_SYSTEM_INSTRUCTION.contains("\n$n. ")) }
        assertFalse(QUESTION_SYSTEM_INSTRUCTION.contains("\n6. "))
    }

    @Test
    fun `no corpus sentence appears in the prompt or system instruction`() {
        val sentences = TagCorpus.load().cases.map { it.id to it.rawText } +
            SemanticCorpus.load().cases.map { it.id to it.rawText }
        assertTrue(sentences.size > 100)
        val texts = mapOf(
            "prompt" to buildQuestionPrompt(question).lowercase(),
            "system instruction" to QUESTION_SYSTEM_INSTRUCTION.lowercase(),
        )
        sentences.forEach { (caseId, rawText) ->
            val needle = rawText.trim().trimEnd('.', '!', '?').lowercase()
            texts.forEach { (where, text) ->
                assertFalse(text.contains(needle), "the $where contains the sentence of case $caseId")
            }
        }
    }
}
