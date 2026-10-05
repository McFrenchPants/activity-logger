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
        assertEquals("q2", QUESTION_PROMPT_VERSION, bumpVersion)
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
        /** SHA-256 of `buildQuestionPrompt(fixedQuestion)` at question prompt version q2. */
        const val PINNED_PROMPT_SHA256 = 
            "0eada412947fc255ed4edae72ff08ff0fec9c0302ce559166e27e49abf7a0172"

        /** SHA-256 of [QUESTION_SYSTEM_INSTRUCTION] at question prompt version q2. */
        const val PINNED_SYSTEM_INSTRUCTION_SHA256 = 
            "6d95ca65a2d0d5b5dae1f469d9c69c7e4bab76884684bed3f75896fe18435bcf"
    }
}
