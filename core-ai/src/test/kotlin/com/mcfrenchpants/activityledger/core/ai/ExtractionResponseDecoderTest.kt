package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Behaviour of the pure extraction-response -> domain-candidate decode. */
class ExtractionResponseDecoderTest {

    @Test
    fun `fully populated response decodes field by field`() {
        val candidate = decoded(
            response(
                operation = "LOG_ACTIVITY",
                subject = "porch light",
                action = "replace bulb",
                activityState = "COMPLETED",
                temporalExpression = "yesterday morning",
                durationExpression = "for 10 minutes",
            ),
        )

        assertEquals(InterpretationOperation.LOG_ACTIVITY, candidate.operation)
        assertEquals("porch light", candidate.subject)
        assertEquals("replace bulb", candidate.action)
        assertEquals(ActivityState.COMPLETED, candidate.activityState)
        assertEquals("yesterday morning", candidate.temporalExpression)
        assertEquals("for 10 minutes", candidate.durationExpression)
    }

    @Test
    fun `every domain enum constant round-trips from its schema spelling`() {
        InterpretationOperation.entries.forEach {
            assertEquals(it, decoded(response(operation = it.name)).operation)
        }
        ActivityState.entries.forEach {
            assertEquals(it, decoded(response(activityState = it.name)).activityState)
        }
    }

    // --- required field ------------------------------------------------------------

    @Test
    fun `absent or blank operation fails to decode`() {
        listOf(null, "", "   ", "\t \n").forEach { blank ->
            assertEquals(ExtractionDecodeFailure.OPERATION_MISSING, failure(response(operation = blank)))
        }
    }

    @Test
    fun `unknown operation string fails to decode`() {
        assertEquals(
            ExtractionDecodeFailure.OPERATION_UNRECOGNISED,
            failure(response(operation = "LOG_SOMETHING_ELSE")),
        )
    }

    // --- optional enum -------------------------------------------------------------

    @Test
    fun `unknown activityState string fails to decode`() {
        // An unreadable state is a broken schema contract, not an absent value.
        assertEquals(
            ExtractionDecodeFailure.ACTIVITY_STATE_UNRECOGNISED,
            failure(response(activityState = "HALF_DONE")),
        )
    }

    // --- optional fields -----------------------------------------------------------

    @Test
    fun `optional fields are null when absent, empty or whitespace-only`() {
        listOf(null, "", "   ").forEach { blank ->
            val candidate = decoded(
                response(
                    subject = blank,
                    action = blank,
                    activityState = blank,
                    temporalExpression = blank,
                    durationExpression = blank,
                ),
            )
            assertNull(candidate.subject)
            assertNull(candidate.action)
            assertNull(candidate.activityState)
            assertNull(candidate.temporalExpression)
            assertNull(candidate.durationExpression)
        }
    }

    @Test
    fun `free text is trimmed at the ends but otherwise left character-identical`() {
        val candidate = decoded(
            response(
                subject = "  The  Porch light!  ",
                action = " Replace   bulb ",
                temporalExpression = "  after   lunch, ish  ",
                durationExpression = "\tfor  about 10 min\n",
            ),
        )
        assertEquals("The  Porch light!", candidate.subject)
        assertEquals("Replace   bulb", candidate.action)
        assertEquals("after   lunch, ish", candidate.temporalExpression)
        assertEquals("for  about 10 min", candidate.durationExpression)
    }

    @Test
    fun `enum strings are matched case-insensitively on purpose`() {
        val candidate = decoded(response(operation = "query_history", activityState = " in_progress "))
        assertEquals(InterpretationOperation.QUERY_HISTORY, candidate.operation)
        assertEquals(ActivityState.IN_PROGRESS, candidate.activityState)
    }

    // --- no semantic validation ----------------------------------------------------

    @Test
    fun `semantically questionable responses still decode successfully`() {
        // Normalising and resolving the words is the domain's job (ADR-038), never the decoder's.
        val empty = decoded(response(operation = "LOG_ACTIVITY", subject = null, action = null))
        assertEquals(InterpretationOperation.LOG_ACTIVITY, empty.operation)
        assertNull(empty.subject)
        assertNull(empty.action)

        val sameWords = decoded(response(subject = "yesterday", temporalExpression = "for an hour"))
        assertEquals("yesterday", sameWords.subject)
        assertEquals("for an hour", sameWords.temporalExpression)

        val unsupported = decoded(response(operation = "UNSUPPORTED", subject = "x", action = "y"))
        assertEquals(InterpretationOperation.UNSUPPORTED, unsupported.operation)
    }

    // --- totality ------------------------------------------------------------------

    @Test
    fun `decode never throws, whatever the response contains`() {
        val pathological = listOf(
            ExtractionResponse(null, null, null, null, null, null),
            ExtractionResponse("", "", "", "", "", ""),
            ExtractionResponse(" ", " ", " ", " ", " ", " "),
            ExtractionResponse("?", "?", "?", "?", "?", "?"),
            ExtractionResponse("LOG_ACTIVITY", "😀", "a".repeat(10_000), null, " ", null),
            response(operation = "LOG_ACTIVITY\n"),
        )
        pathological.forEach { response ->
            // No try/catch: a throw would fail the test, which is the assertion.
            assertIs<ExtractionDecodeResult>(ExtractionResponseDecoder.decode(response))
        }
    }

    private companion object {
        /** A valid baseline response; each test overrides only what it is about. */
        fun response(
            operation: String? = "LOG_ACTIVITY",
            subject: String? = null,
            action: String? = null,
            activityState: String? = null,
            temporalExpression: String? = null,
            durationExpression: String? = null,
        ) = ExtractionResponse(
            operation = operation,
            subject = subject,
            action = action,
            activityState = activityState,
            temporalExpression = temporalExpression,
            durationExpression = durationExpression,
        )

        fun decoded(response: ExtractionResponse) =
            assertIs<ExtractionDecodeResult.Decoded>(ExtractionResponseDecoder.decode(response)).candidate

        fun failure(response: ExtractionResponse) =
            assertIs<ExtractionDecodeResult.Failed>(ExtractionResponseDecoder.decode(response)).reason
    }
}
