package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.mcfrenchpants.activityledger.core.domain.interpretation.CandidateActivity
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of the on-device interpreter, driven entirely through a fake client so it runs on a
 * host JVM with no device. `runBlocking` is enough: nothing here is time- or scheduler-dependent.
 */
class GeminiNanoActivityInterpreterTest {

    // ---- composition -----------------------------------------------------------------------

    @Test
    fun `many interpret calls share one client`() {
        val factory = CountingSessionFactory(FakeInterpretationSession())
        val capability = OnDeviceModelCapability(factory)
        val interpreter = GeminiNanoActivityInterpreter(capability)

        runBlocking {
            repeat(5) { interpreter.interpret(INPUT) }
        }

        assertEquals(1, factory.creations)
    }

    // ---- readiness gate --------------------------------------------------------------------

    @Test
    fun `not installed model is unavailable and never calls or downloads`() {
        assertGatedOut(FakeInterpretationSession(status = FeatureStatus.DOWNLOADABLE))
    }

    @Test
    fun `downloading model is unavailable and never calls or downloads`() {
        assertGatedOut(FakeInterpretationSession(status = FeatureStatus.DOWNLOADING))
    }

    @Test
    fun `unsupported device is unavailable and never calls or downloads`() {
        assertGatedOut(FakeInterpretationSession(status = FeatureStatus.UNAVAILABLE))
    }

    @Test
    fun `missing structured output is unavailable and never calls or downloads`() {
        assertGatedOut(
            FakeInterpretationSession(
                status = FeatureStatus.AVAILABLE,
                structuredOutputAvailable = false,
            ),
        )
    }

    @Test
    fun `failed capability check is unavailable and never calls or downloads`() {
        assertGatedOut(FakeInterpretationSession(status = UNKNOWN_FEATURE_STATUS))
    }

    /** Asserts the readiness gate held: no generation, no download, and an UNAVAILABLE failure. */
    private fun assertGatedOut(fake: FakeInterpretationSession) {
        val result = runBlocking { interpreterFor(fake).interpret(INPUT) }

        assertEquals(failure(InterpreterFailureKind.UNAVAILABLE), result)
        assertEquals(0, fake.generateContentCalls)
        assertEquals(0, fake.downloadCalls)
    }

    // ---- one shot --------------------------------------------------------------------------

    @Test
    fun `a ready device runs generation exactly once per interpret call`() {
        val fake = FakeInterpretationSession()
        val interpreter = interpreterFor(fake)

        runBlocking { interpreter.interpret(INPUT) }
        assertEquals(1, fake.generateContentCalls)

        runBlocking { interpreter.interpret(INPUT) }
        assertEquals(2, fake.generateContentCalls)
    }

    @Test
    fun `a failed generation is not retried`() {
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Throws(RuntimeException()),
        )

        runBlocking { interpreterFor(fake).interpret(INPUT) }

        assertEquals(1, fake.generateContentCalls)
    }

    @Test
    fun `interpret never warms the model up by itself`() {
        val fake = FakeInterpretationSession()

        runBlocking { interpreterFor(fake).interpret(INPUT) }

        assertEquals(0, fake.warmupCalls)
    }

    @Test
    fun `warm up is an explicit call`() {
        val fake = FakeInterpretationSession()

        runBlocking { interpreterFor(fake).warmUp() }

        assertEquals(1, fake.warmupCalls)
        assertEquals(0, fake.generateContentCalls)
    }

    // ---- failure mapping -------------------------------------------------------------------

    @Test
    fun `a retry delay means retryable`() {
        val outcome = GenerationOutcome.Throws(
            GenAiException("", null, ERROR_CODE, Duration.ofSeconds(30)),
        )

        assertEquals(
            failure(InterpreterFailureKind.RETRYABLE),
            resultOf(FakeInterpretationSession(outcome = outcome)),
        )
    }

    @Test
    fun `no retry delay is not retryable`() {
        val outcome = GenerationOutcome.Throws(GenAiException("", null, ERROR_CODE, Duration.ZERO))

        assertEquals(
            failure(InterpreterFailureKind.OTHER),
            resultOf(FakeInterpretationSession(outcome = outcome)),
        )
    }

    @Test
    fun `an arbitrary exception is other`() {
        val outcome = GenerationOutcome.Throws(RuntimeException())

        assertEquals(
            failure(InterpreterFailureKind.OTHER),
            resultOf(FakeInterpretationSession(outcome = outcome)),
        )
    }

    @Test
    fun `an empty candidate list is malformed`() {
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Responses(emptyList()),
        )

        assertEquals(failure(InterpreterFailureKind.MALFORMED), resultOf(fake))
    }

    @Test
    fun `a response that does not decode is malformed`() {
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Responses(
                listOf(WELL_FORMED_RESPONSE.copy(operation = "DO_SOMETHING_ELSE")),
            ),
        )

        assertEquals(failure(InterpreterFailureKind.MALFORMED), resultOf(fake))
    }

    @Test
    fun `an error from the runtime is not swallowed`() {
        // Errors are deliberately outside the catch: a broken JVM is not an unusable answer.
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Throws(StackOverflowError()),
        )

        assertFailsWith<StackOverflowError> {
            runBlocking { interpreterFor(fake).interpret(INPUT) }
        }
    }

    @Test
    fun `cancellation propagates instead of becoming a failure`() {
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Throws(CancellationException()),
        )

        assertFailsWith<CancellationException> {
            runBlocking { interpreterFor(fake).interpret(INPUT) }
        }
    }

    // ---- nothing leaks ---------------------------------------------------------------------

    @Test
    fun `no failure carries capture text or an exception message`() {
        val secret = "bled the radiators at 3am"
        val input = INPUT.copy(
            rawText = secret,
            candidates = listOf(CandidateActivity("act-1", secret, listOf(secret))),
        )
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Throws(
                GenAiException("failed while handling: $secret", null, ERROR_CODE, Duration.ZERO),
            ),
        )

        val result = runBlocking { interpreterFor(fake).interpret(input) }

        val failure = result as InterpretationResult.Failure
        assertNull(failure.structuredResultJson)
        assertFalse(failure.toString().contains(secret))
        assertFalse(failure.toString().contains("failed while handling"))
    }

    @Test
    fun `every failure kind is reported without structured json`() {
        val outcomes = listOf(
            GenerationOutcome.Throws(GenAiException("", null, ERROR_CODE, Duration.ofSeconds(1))),
            GenerationOutcome.Throws(RuntimeException("message that must not travel")),
            GenerationOutcome.Responses(emptyList()),
            GenerationOutcome.Responses(listOf(WELL_FORMED_RESPONSE.copy(operation = null))),
        )

        outcomes.forEach { outcome ->
            // Never throws, whatever the fake does.
            val result = resultOf(FakeInterpretationSession(outcome = outcome))
            val failure = result as InterpretationResult.Failure
            assertNull(failure.structuredResultJson)
            assertFalse(failure.toString().contains("message that must not travel"))
        }
    }

    // ---- success ---------------------------------------------------------------------------

    @Test
    fun `a decodable response is returned exactly as the decoder produced it`() {
        val response = WELL_FORMED_RESPONSE
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Responses(listOf(response)),
        )

        val result = resultOf(fake) as InterpretationResult.Success

        val decoded = InterpretationResponseDecoder.decode(response)
            as InterpretationDecodeResult.Decoded
        assertEquals(decoded.candidate, result.candidate)
    }

    @Test
    fun `a semantically questionable response is still a success`() {
        // EXISTING_ACTIVITY with no matched id is nonsense, and judging that is the domain
        // validator's job: this module must hand it back untouched (ADR-010, AGENTS.md #5).
        val response = WELL_FORMED_RESPONSE.copy(
            activityResolution = "EXISTING_ACTIVITY",
            matchedActivityId = null,
            proposedCanonicalName = null,
        )
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Responses(listOf(response)),
        )

        val result = resultOf(fake) as InterpretationResult.Success

        val decoded = InterpretationResponseDecoder.decode(response)
            as InterpretationDecodeResult.Decoded
        assertEquals(decoded.candidate, result.candidate)
        assertNull(result.candidate.matchedActivityId)
    }

    @Test
    fun `only the first candidate is used`() {
        val first = WELL_FORMED_RESPONSE.copy(proposedCanonicalName = "first")
        val second = WELL_FORMED_RESPONSE.copy(proposedCanonicalName = "second")
        val fake = FakeInterpretationSession(
            outcome = GenerationOutcome.Responses(listOf(first, second)),
        )

        val result = resultOf(fake) as InterpretationResult.Success

        assertEquals("first", result.candidate.proposedCanonicalName)
    }

    @Test
    fun `success carries no fabricated structured json`() {
        // The typed API returns a decoded object, never the model's raw text, so there is
        // nothing genuine to record in the audit field.
        val result = resultOf(FakeInterpretationSession()) as InterpretationResult.Success

        assertNull(result.structuredResultJson)
    }

    // ---- provenance ------------------------------------------------------------------------

    @Test
    fun `provenance reports interpreter, prompt and schema versions`() {
        val provenance = GeminiNanoActivityInterpreter(
            OnDeviceModelCapability(CountingSessionFactory(FakeInterpretationSession())),
        ).provenance

        assertTrue(provenance.interpreterVersion.isNotBlank())
        assertEquals(PROMPT_VERSION, provenance.promptVersion)
        assertEquals(INTERPRETATION_SCHEMA_VERSION, provenance.schemaVersion)
    }

    // ---- request settings ------------------------------------------------------------------

    @Test
    fun `generation is configured for deterministic classification`() {
        val fake = FakeInterpretationSession()

        runBlocking { interpreterFor(fake).interpret(INPUT) }

        val request = assertNotNullRequest(fake)
        val content = request.generateContentRequest
        assertEquals(GENERATION_TEMPERATURE, content.temperature)
        assertEquals(GENERATION_TOP_K, content.topK)
        assertEquals(GENERATION_CANDIDATE_COUNT, content.candidateCount)
        assertEquals(GENERATION_SEED, content.seed)
        assertEquals(GENERATION_MAX_OUTPUT_TOKENS, content.maxOutputTokens)
        assertEquals(GENERATION_ENABLE_THINKING, content.enableThinking)
        assertEquals(INCLUDE_SCHEMA_IN_PROMPT, request.includeSchemaInPrompt)
        assertEquals(InterpretationResponse::class, request.outputClass)
    }

    @Test
    fun `the request carries this module's system instruction and built prompt`() {
        val fake = FakeInterpretationSession()

        runBlocking { interpreterFor(fake).interpret(INPUT) }

        val content = assertNotNullRequest(fake).generateContentRequest
        assertEquals(
            INTERPRETATION_SYSTEM_INSTRUCTION,
            content.systemInstruction?.textString,
        )
        assertEquals(buildInterpretationPrompt(INPUT), content.text.textString)
    }

    private fun assertNotNullRequest(fake: FakeInterpretationSession) =
        requireNotNull(fake.lastRequest) { "no request was captured" }

    // ---- helpers ---------------------------------------------------------------------------

    private fun interpreterFor(fake: FakeInterpretationSession) =
        GeminiNanoActivityInterpreter(OnDeviceModelCapability(CountingSessionFactory(fake)))

    private fun resultOf(fake: FakeInterpretationSession): InterpretationResult =
        runBlocking { interpreterFor(fake).interpret(INPUT) }

    private fun failure(kind: InterpreterFailureKind) =
        InterpretationResult.Failure(kind, structuredResultJson = null)

    private companion object {
        /** A status value this app does not recognise, which yields a failed capability check. */
        const val UNKNOWN_FEATURE_STATUS = 9_999

        /** Any ML Kit error code; its meaning is never read. */
        const val ERROR_CODE = 13

        val INPUT = InterpretationInput(
            rawText = "mowed the lawn yesterday",
            capturedAt = Instant.parse("2026-03-04T09:15:00Z"),
            zoneId = ZoneId.of("Europe/London"),
            candidates = listOf(CandidateActivity("act-1", "mow lawn", listOf("cut grass"))),
        )
    }
}
