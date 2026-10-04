package com.mcfrenchpants.activityledger.core.ai

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Version-drift guard for the question prompt. Pins the exact prompt for one fixed question, and
 * the system instruction, against [QUESTION_PROMPT_VERSION].
 */
class QuestionPromptDriftTest {

    private val fixedQuestion = "When did I last oil the gate hinges?"

    private val bumpVersion =
        "Prompt text is product logic: it is versioned, and changing it is never a silent " +
            "edit. If this change is intended, bump QUESTION_PROMPT_VERSION in " +
            "QuestionPrompt.kt and update the pinned checksum(s) in this test."

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    @Test
    fun `question prompt version is the one these fixtures were pinned against`() {
        assertEquals("q1", QUESTION_PROMPT_VERSION, bumpVersion)
    }

    @Test
    fun `per-question prompt text has not drifted`() {
        assertEquals(
            PINNED_PROMPT_SHA256,
            buildQuestionPrompt(fixedQuestion).sha256(),
            "Question prompt text changed for prompt version $QUESTION_PROMPT_VERSION. $bumpVersion",
        )
    }

    @Test
    fun `question system instruction text has not drifted`() {
        assertEquals(
            PINNED_SYSTEM_INSTRUCTION_SHA256,
            QUESTION_SYSTEM_INSTRUCTION.sha256(),
            "Question system instruction changed for prompt version $QUESTION_PROMPT_VERSION. $bumpVersion",
        )
    }

    private companion object {
        /** SHA-256 of `buildQuestionPrompt(fixedQuestion)` at question prompt version q1. */
        const val PINNED_PROMPT_SHA256 = 
            "ada0ab47f752d565ddac780544454ecc8aee06f25ac59ac6d0c71523fa5b12ad"

        /** SHA-256 of [QUESTION_SYSTEM_INSTRUCTION] at question prompt version q1. */
        const val PINNED_SYSTEM_INSTRUCTION_SHA256 = 
            "0f258bb1939147acfbb9d93b1d0af6b9589379ebc99113adc52bf7397c5e0c1b"
    }
}
