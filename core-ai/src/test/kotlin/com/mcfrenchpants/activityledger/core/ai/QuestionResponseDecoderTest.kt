package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Behaviour of the pure question-response -> domain-candidate decode (schema version 2). */
class QuestionResponseDecoderTest {

    private fun decodedFull(
        subject: String?,
        action: String?,
        dateWindow: String?,
        kind: String?,
    ): QuestionCandidate {
        val result = QuestionResponseDecoder.decode(QuestionResponse(subject, action, dateWindow, kind))
        return assertIs<QuestionDecodeResult.Decoded>(result).candidate
    }

    private fun decoded(subject: String?, action: String?): QuestionCandidate =
        decodedFull(subject, action, null, null)

    private fun kindOf(raw: String?): QuestionKind = decodedFull(null, null, null, raw).kind

    @Test
    fun `populated response decodes field by field`() {
        assertEquals(QuestionCandidate("furnace filter", "change"), decoded("furnace filter", "change"))
        assertEquals(
            QuestionCandidate("tomatoes", "water", "last month", QuestionKind.COUNT),
            decodedFull("tomatoes", "water", "last month", "COUNT"),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed and inner spacing kept`() {
        assertEquals(QuestionCandidate("coffee  maker", "change oil"), decoded("  coffee  maker\n", "\tchange oil "))
        assertEquals("since  June", decodedFull(null, null, " \tsince  June\n", null).dateWindow)
    }

    @Test
    fun `blank or empty becomes null`() {
        assertEquals(QuestionCandidate(null, null), decoded("", "   \t\n"))
        assertEquals(QuestionCandidate(null, "clean"), decoded(" ", "clean"))
        assertEquals(null, decodedFull(null, null, "", null).dateWindow)
        assertEquals(null, decodedFull(null, null, "  \t\n ", null).dateWindow)
    }

    @Test
    fun `every field null is a valid decode`() {
        assertEquals(QuestionCandidate(null, null), decoded(null, null))
        assertEquals(QuestionCandidate(null, null, null, QuestionKind.UNKNOWN), decodedFull(null, null, null, null))
    }

    @Test
    fun `each free text field is capped at the maximum length`() {
        val long = "a".repeat(QUESTION_FIELD_MAX_LENGTH + 40)
        val candidate = decodedFull(long, long, long, null)
        assertEquals(QUESTION_FIELD_MAX_LENGTH, candidate.subject?.length)
        assertEquals(QUESTION_FIELD_MAX_LENGTH, candidate.action?.length)
        assertEquals(QUESTION_FIELD_MAX_LENGTH, candidate.dateWindow?.length)
        assertEquals("a".repeat(QUESTION_FIELD_MAX_LENGTH), decoded("a".repeat(QUESTION_FIELD_MAX_LENGTH), null).subject)
        assertEquals(
            "a".repeat(QUESTION_FIELD_MAX_LENGTH),
            decodedFull(null, null, "a".repeat(QUESTION_FIELD_MAX_LENGTH), null).dateWindow,
        )
    }

    @Test
    fun `each kind spelling maps to its kind`() {
        assertEquals(QuestionKind.LAST_TIME, kindOf("LAST_TIME"))
        assertEquals(QuestionKind.COUNT, kindOf("COUNT"))
        assertEquals(QuestionKind.HOW_OFTEN, kindOf("HOW_OFTEN"))
        assertEquals(QuestionKind.LIST, kindOf("LIST"))
    }

    @Test
    fun `kind is matched case-insensitively and trimmed`() {
        assertEquals(QuestionKind.LAST_TIME, kindOf("last_time"))
        assertEquals(QuestionKind.HOW_OFTEN, kindOf("How_Often"))
        assertEquals(QuestionKind.COUNT, kindOf("  count\n"))
        assertEquals(QuestionKind.LIST, kindOf("\tList "))
    }

    @Test
    fun `unknown, blank or missing kind is UNKNOWN and never a failure`() {
        listOf("UNKNOWN", "HOW OFTEN", "LASTTIME", "COUNTS", "when", "", "   ", null).forEach { raw ->
            val result = QuestionResponseDecoder.decode(QuestionResponse("gutters", "clean", null, raw))
            val candidate = assertIs<QuestionDecodeResult.Decoded>(result, "kind '$raw'").candidate
            assertEquals(QuestionKind.UNKNOWN, candidate.kind, "kind '$raw'")
            assertEquals("gutters", candidate.subject)
        }
    }
}
