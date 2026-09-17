package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Behaviour of the pure model-response -> domain-candidate decode. */
class InterpretationResponseDecoderTest {

    @Test
    fun `fully populated response decodes field by field`() {
        val candidate = decoded(
            response(
                operation = "LOG_ACTIVITY",
                activityResolution = "EXISTING_ACTIVITY",
                matchedActivityId = "activity-42",
                proposedCanonicalName = "mow lawn",
                activityState = "COMPLETED",
                temporalExpression = "yesterday morning",
                confidenceBand = "HIGH",
            ),
        )

        assertEquals(InterpretationOperation.LOG_ACTIVITY, candidate.operation)
        assertEquals(ActivityResolution.EXISTING_ACTIVITY, candidate.activityResolution)
        assertEquals("activity-42", candidate.matchedActivityId)
        assertEquals("mow lawn", candidate.proposedCanonicalName)
        assertEquals(ActivityState.COMPLETED, candidate.activityState)
        assertEquals("yesterday morning", candidate.temporalExpression)
        assertEquals(ConfidenceBand.HIGH, candidate.confidenceBand)
    }

    @Test
    fun `every domain enum constant round-trips from its schema spelling`() {
        InterpretationOperation.entries.forEach {
            assertEquals(it, decoded(response(operation = it.name)).operation)
        }
        ActivityResolution.entries.forEach {
            assertEquals(it, decoded(response(activityResolution = it.name)).activityResolution)
        }
        ActivityState.entries.forEach {
            assertEquals(it, decoded(response(activityState = it.name)).activityState)
        }
        ConfidenceBand.entries.forEach {
            assertEquals(it, decoded(response(confidenceBand = it.name)).confidenceBand)
        }
    }

    // --- required fields -----------------------------------------------------------

    @Test
    fun `absent operation fails to decode`() {
        assertEquals(
            InterpretationDecodeFailure.OPERATION_MISSING,
            failure(response(operation = null)),
        )
    }

    @Test
    fun `blank operation fails to decode`() {
        assertEquals(
            InterpretationDecodeFailure.OPERATION_MISSING,
            failure(response(operation = "   ")),
        )
    }

    @Test
    fun `unknown operation string fails to decode`() {
        assertEquals(
            InterpretationDecodeFailure.OPERATION_UNRECOGNISED,
            failure(response(operation = "LOG_SOMETHING_ELSE")),
        )
    }

    @Test
    fun `absent activityResolution fails to decode`() {
        assertEquals(
            InterpretationDecodeFailure.ACTIVITY_RESOLUTION_MISSING,
            failure(response(activityResolution = null)),
        )
    }

    @Test
    fun `blank activityResolution fails to decode`() {
        assertEquals(
            InterpretationDecodeFailure.ACTIVITY_RESOLUTION_MISSING,
            failure(response(activityResolution = "\t \n")),
        )
    }

    @Test
    fun `unknown activityResolution string fails to decode`() {
        assertEquals(
            InterpretationDecodeFailure.ACTIVITY_RESOLUTION_UNRECOGNISED,
            failure(response(activityResolution = "MAYBE_EXISTING")),
        )
    }

    // --- optional enums ------------------------------------------------------------

    @Test
    fun `unknown activityState string fails to decode`() {
        // An unreadable state is a broken schema contract, not an absent value: it must not
        // be quietly turned into null.
        assertEquals(
            InterpretationDecodeFailure.ACTIVITY_STATE_UNRECOGNISED,
            failure(response(activityState = "HALF_DONE")),
        )
    }

    @Test
    fun `unknown confidenceBand string fails to decode`() {
        assertEquals(
            InterpretationDecodeFailure.CONFIDENCE_BAND_UNRECOGNISED,
            failure(response(confidenceBand = "VERY_HIGH")),
        )
    }

    // --- optional fields -----------------------------------------------------------

    @Test
    fun `optional fields are null when absent, empty or whitespace-only`() {
        listOf(null, "", "   ").forEach { blank ->
            val candidate = decoded(
                response(
                    matchedActivityId = blank,
                    proposedCanonicalName = blank,
                    activityState = blank,
                    temporalExpression = blank,
                    confidenceBand = blank,
                ),
            )
            assertNull(candidate.matchedActivityId)
            assertNull(candidate.proposedCanonicalName)
            assertNull(candidate.activityState)
            assertNull(candidate.temporalExpression)
            assertNull(candidate.confidenceBand)
        }
    }

    @Test
    fun `free text is trimmed at the ends but otherwise left character-identical`() {
        val candidate = decoded(
            response(
                matchedActivityId = "  activity-7  ",
                proposedCanonicalName = "  mow  the lawn!  ",
                temporalExpression = "  after   lunch, ish  ",
            ),
        )
        assertEquals("activity-7", candidate.matchedActivityId)
        assertEquals("mow  the lawn!", candidate.proposedCanonicalName)
        assertEquals("after   lunch, ish", candidate.temporalExpression)
    }

    // --- case insensitivity --------------------------------------------------------

    @Test
    fun `enum strings are matched case-insensitively on purpose`() {
        // Deliberate: the schema pins the spellings, but small-model output drifts in case.
        // Rejecting "log_activity" would discard a correct answer over a cosmetic
        // difference. Nothing beyond case and surrounding whitespace is normalised.
        val candidate = decoded(
            response(
                operation = "log_activity",
                activityResolution = "Existing_Activity",
                activityState = " completed ",
                confidenceBand = "mEdIuM",
            ),
        )
        assertEquals(InterpretationOperation.LOG_ACTIVITY, candidate.operation)
        assertEquals(ActivityResolution.EXISTING_ACTIVITY, candidate.activityResolution)
        assertEquals(ActivityState.COMPLETED, candidate.activityState)
        assertEquals(ConfidenceBand.MEDIUM, candidate.confidenceBand)
    }

    // --- no semantic validation ----------------------------------------------------

    @Test
    fun `semantically questionable responses still decode successfully`() {
        // Judging these is InterpretationValidator's job in core-domain (ADR-010). A second
        // copy of the rules here would be a defect, not defence in depth.
        val existingWithoutId = decoded(
            response(activityResolution = "EXISTING_ACTIVITY", matchedActivityId = null),
        )
        assertNull(existingWithoutId.matchedActivityId)
        assertEquals(ActivityResolution.EXISTING_ACTIVITY, existingWithoutId.activityResolution)

        val newWithoutName = decoded(
            response(activityResolution = "NEW_ACTIVITY", proposedCanonicalName = null),
        )
        assertNull(newWithoutName.proposedCanonicalName)
        assertEquals(ActivityResolution.NEW_ACTIVITY, newWithoutName.activityResolution)

        val nonsenseId = decoded(response(matchedActivityId = "???-never-offered-???"))
        assertEquals("???-never-offered-???", nonsenseId.matchedActivityId)

        val query = decoded(
            response(operation = "QUERY_HISTORY", activityResolution = "UNRESOLVED"),
        )
        assertEquals(InterpretationOperation.QUERY_HISTORY, query.operation)
    }

    // --- totality ------------------------------------------------------------------

    @Test
    fun `decode never throws, whatever the response contains`() {
        val pathological = listOf(
            InterpretationResponse(null, null, null, null, null, null, null),
            InterpretationResponse("", "", "", "", "", "", ""),
            InterpretationResponse(" ", " ", " ", " ", " ", " ", " "),
            InterpretationResponse("?", "?", "?", "?", "?", "?", "?"),
            InterpretationResponse(
                "LOG_ACTIVITY",
                "NEW_ACTIVITY",
                " ",
                "😀",
                null,
                "a".repeat(10_000),
                null,
            ),
            response(operation = "LOG_ACTIVITY\n"),
        )
        pathological.forEach { response ->
            // No try/catch: a throw would fail the test, which is the assertion.
            val result = InterpretationResponseDecoder.decode(response)
            assertIs<InterpretationDecodeResult>(result)
        }
    }

    private companion object {
        /** A valid baseline response; each test overrides only what it is about. */
        fun response(
            operation: String? = "LOG_ACTIVITY",
            activityResolution: String? = "EXISTING_ACTIVITY",
            matchedActivityId: String? = null,
            proposedCanonicalName: String? = null,
            activityState: String? = null,
            temporalExpression: String? = null,
            confidenceBand: String? = null,
        ) = InterpretationResponse(
            operation = operation,
            activityResolution = activityResolution,
            matchedActivityId = matchedActivityId,
            proposedCanonicalName = proposedCanonicalName,
            activityState = activityState,
            temporalExpression = temporalExpression,
            confidenceBand = confidenceBand,
        )

        fun decoded(response: InterpretationResponse) =
            assertIs<InterpretationDecodeResult.Decoded>(
                InterpretationResponseDecoder.decode(response),
            ).candidate

        fun failure(response: InterpretationResponse) =
            assertIs<InterpretationDecodeResult.Failed>(
                InterpretationResponseDecoder.decode(response),
            ).reason
    }
}
