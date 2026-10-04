package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Behaviour of the pure question-response -> domain-candidate decode. */
class QuestionResponseDecoderTest {

    private fun decoded(subject: String?, action: String?): QuestionCandidate {
        val result = QuestionResponseDecoder.decode(QuestionResponse(subject, action))
        return assertIs<QuestionDecodeResult.Decoded>(result).candidate
    }

    @Test
    fun `populated response decodes field by field`() {
        assertEquals(QuestionCandidate("furnace filter", "change"), decoded("furnace filter", "change"))
    }

    @Test
    fun `surrounding whitespace is trimmed and inner spacing kept`() {
        assertEquals(QuestionCandidate("coffee  maker", "change oil"), decoded("  coffee  maker\n", "\tchange oil "))
    }

    @Test
    fun `blank or empty becomes null`() {
        assertEquals(QuestionCandidate(null, null), decoded("", "   \t\n"))
        assertEquals(QuestionCandidate(null, "clean"), decoded(" ", "clean"))
    }

    @Test
    fun `both null is a valid decode`() {
        assertEquals(QuestionCandidate(null, null), decoded(null, null))
    }

    @Test
    fun `each field is capped at the maximum length`() {
        val long = "a".repeat(QUESTION_FIELD_MAX_LENGTH + 40)
        val candidate = decoded(long, long)
        assertEquals(QUESTION_FIELD_MAX_LENGTH, candidate.subject?.length)
        assertEquals(QUESTION_FIELD_MAX_LENGTH, candidate.action?.length)
        assertEquals("a".repeat(QUESTION_FIELD_MAX_LENGTH), decoded("a".repeat(QUESTION_FIELD_MAX_LENGTH), null).subject)
    }
}
