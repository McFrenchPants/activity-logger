package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
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
import kotlin.test.assertTrue

/**
 * Behaviour of the on-device extractor, driven entirely through the shared fake client (scripted
 * with [ExtractionResponse]s) so it runs on a host JVM with no device.
 */
class GeminiNanoActivityExtractorTest {

    // ---- composition -----------------------------------------------------------------------

    @Test
    fun `many extract calls share one client`() {
        val factory = CountingSessionFactory(extractionSession())
        val extractor = GeminiNanoActivityExtractor(OnDeviceModelCapability(factory))

        runBlocking { repeat(5) { extractor.extract(INPUT) } }

        assertEquals(1, factory.creations)
    }

    // ---- readiness gate --------------------------------------------------------------------

    @Test
    fun `every not-ready state is unavailable and never calls or downloads`() {
        listOf(
            extractionSession(status = FeatureStatus.DOWNLOADABLE),
            extractionSession(status = FeatureStatus.DOWNLOADING),
            extractionSession(status = FeatureStatus.UNAVAILABLE),
            extractionSession(status = FeatureStatus.AVAILABLE, structuredOutputAvailable = false),
            extractionSession(status = UNKNOWN_FEATURE_STATUS),
        ).forEachIndexed { index, fake ->
            val result = runBlocking { extractorFor(fake).extract(INPUT) }
            assertEquals(ExtractionResult.Failure(InterpreterFailureKind.UNAVAILABLE), result, "state #$index")
            assertEquals(0, fake.generateContentCalls, "state #$index generated")
            assertEquals(0, fake.downloadCalls, "state #$index downloaded")
        }
    }

    @Test
    fun `warm up on a device that is not ready does nothing and downloads nothing`() {
        val fake = extractionSession(status = FeatureStatus.DOWNLOADABLE)

        runBlocking { extractorFor(fake).warmUp() }

        assertEquals(0, fake.warmupCalls)
        assertEquals(0, fake.downloadCalls)
    }

    // ---- one shot --------------------------------------------------------------------------

    @Test
    fun `a ready device runs generation exactly once per extract call`() {
        val fake = extractionSession()
        val extractor = extractorFor(fake)

        runBlocking { extractor.extract(INPUT) }
        assertEquals(1, fake.generateContentCalls)

        runBlocking { extractor.extract(INPUT) }
        assertEquals(2, fake.generateContentCalls)
        assertEquals(0, fake.downloadCalls)
    }

    @Test
    fun `a failed generation is not retried`() {
        val fake = extractionSession(outcome = GenerationOutcome.Throws(RuntimeException()))

        runBlocking { extractorFor(fake).extract(INPUT) }

        assertEquals(1, fake.generateContentCalls)
    }

    @Test
    fun `extract never warms the model up by itself, and warm up is explicit`() {
        val fake = extractionSession()

        runBlocking { extractorFor(fake).extract(INPUT) }
        assertEquals(0, fake.warmupCalls)

        runBlocking { extractorFor(fake).warmUp() }
        assertEquals(1, fake.warmupCalls)
        assertEquals(1, fake.generateContentCalls)
    }

    // ---- failure mapping -------------------------------------------------------------------

    @Test
    fun `failure mapping matches the interpreter`() {
        val cases = listOf(
            GenAiException("", null, ERROR_CODE, Duration.ofSeconds(30)) to InterpreterFailureKind.RETRYABLE,
            GenAiException("", null, ERROR_CODE, Duration.ZERO) to InterpreterFailureKind.OTHER,
            GenAiException("", null, GenAiException.ErrorCode.BUSY, Duration.ZERO) to InterpreterFailureKind.RETRYABLE,
            GenAiException("", null, GenAiException.ErrorCode.BUSY, Duration.ofSeconds(5)) to InterpreterFailureKind.RETRYABLE,
            RuntimeException() to InterpreterFailureKind.OTHER,
        )
        assertTrue(ERROR_CODE != GenAiException.ErrorCode.BUSY)
        cases.forEachIndexed { index, (thrown, kind) ->
            val fake = extractionSession(outcome = GenerationOutcome.Throws(thrown))
            assertEquals(ExtractionResult.Failure(kind), resultOf(fake), "case #$index")
            assertEquals(1, fake.generateContentCalls, "case #$index is a single call")
        }
    }

    @Test
    fun `an empty candidate list is malformed`() {
        val fake = extractionSession(outcome = GenerationOutcome.Responses(emptyList()))

        assertEquals(ExtractionResult.Failure(InterpreterFailureKind.MALFORMED), resultOf(fake))
    }

    @Test
    fun `a response that does not decode is malformed`() {
        listOf(
            WELL_FORMED_EXTRACTION.copy(operation = "DO_SOMETHING_ELSE"),
            WELL_FORMED_EXTRACTION.copy(operation = null),
            WELL_FORMED_EXTRACTION.copy(activityState = "HALF_DONE"),
        ).forEach { response ->
            val fake = extractionSession(outcome = GenerationOutcome.Responses(listOf(response)))
            assertEquals(ExtractionResult.Failure(InterpreterFailureKind.MALFORMED), resultOf(fake))
        }
    }

    @Test
    fun `an error from the runtime is not swallowed`() {
        val fake = extractionSession(outcome = GenerationOutcome.Throws(StackOverflowError()))

        assertFailsWith<StackOverflowError> { runBlocking { extractorFor(fake).extract(INPUT) } }
    }

    @Test
    fun `cancellation propagates instead of becoming a failure`() {
        val fake = extractionSession(outcome = GenerationOutcome.Throws(CancellationException()))

        assertFailsWith<CancellationException> { runBlocking { extractorFor(fake).extract(INPUT) } }
    }

    @Test
    fun `no failure carries capture text or an exception message`() {
        val secret = "bled the radiators at 3am"
        val fake = extractionSession(
            outcome = GenerationOutcome.Throws(
                GenAiException("failed while handling: $secret", null, ERROR_CODE, Duration.ZERO),
            ),
        )

        val result = runBlocking { extractorFor(fake).extract(INPUT.copy(rawText = secret)) }

        val failure = result as ExtractionResult.Failure
        assertFalse(failure.toString().contains(secret))
        assertFalse(failure.toString().contains("failed while handling"))
    }

    // ---- success ---------------------------------------------------------------------------

    @Test
    fun `a decodable response is returned exactly as the decoder produced it`() {
        val fake = extractionSession()

        val result = resultOf(fake) as ExtractionResult.Success

        val decoded = ExtractionResponseDecoder.decode(WELL_FORMED_EXTRACTION) as ExtractionDecodeResult.Decoded
        assertEquals(decoded.candidate, result.candidate)
    }

    @Test
    fun `only the first candidate is used`() {
        val fake = extractionSession(
            outcome = GenerationOutcome.Responses(
                listOf(WELL_FORMED_EXTRACTION.copy(subject = "first"), WELL_FORMED_EXTRACTION.copy(subject = "second")),
            ),
        )

        assertEquals("first", (resultOf(fake) as ExtractionResult.Success).candidate.subject)
    }

    // ---- provenance ------------------------------------------------------------------------

    @Test
    fun `provenance reports extractor, prompt and schema versions`() {
        val provenance = extractorFor(extractionSession()).provenance

        assertEquals("gemini-nano-extract-1", provenance.interpreterVersion)
        assertEquals(EXTRACTOR_VERSION, provenance.interpreterVersion)
        assertEquals("4", provenance.promptVersion)
        assertEquals(EXTRACTION_PROMPT_VERSION, provenance.promptVersion)
        assertEquals(1, provenance.schemaVersion)
        assertEquals(EXTRACTION_SCHEMA_VERSION, provenance.schemaVersion)
    }

    // ---- request settings ------------------------------------------------------------------

    @Test
    fun `generation uses the interpreter's deterministic settings and the extraction schema`() {
        val fake = extractionSession()

        runBlocking { extractorFor(fake).extract(INPUT) }

        val request = requireNotNull(fake.lastRequest) { "no request was captured" }
        val content = request.generateContentRequest
        assertEquals(GENERATION_TEMPERATURE, content.temperature)
        assertEquals(GENERATION_TOP_K, content.topK)
        assertEquals(GENERATION_CANDIDATE_COUNT, content.candidateCount)
        assertEquals(GENERATION_SEED, content.seed)
        assertEquals(GENERATION_MAX_OUTPUT_TOKENS, content.maxOutputTokens)
        assertEquals(GENERATION_ENABLE_THINKING, content.enableThinking)
        assertEquals(INCLUDE_SCHEMA_IN_PROMPT, request.includeSchemaInPrompt)
        assertEquals(ExtractionResponse::class, request.outputClass)
    }

    @Test
    fun `the request carries the extraction system instruction and built prompt`() {
        val fake = extractionSession()

        runBlocking { extractorFor(fake).extract(INPUT) }

        val content = requireNotNull(fake.lastRequest) { "no request was captured" }.generateContentRequest
        assertEquals(EXTRACTION_SYSTEM_INSTRUCTION, content.systemInstruction?.textString)
        assertEquals(buildExtractionPrompt(INPUT), content.text.textString)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun extractionSession(
        status: Int = FeatureStatus.AVAILABLE,
        structuredOutputAvailable: Boolean = true,
        outcome: GenerationOutcome = GenerationOutcome.Responses(listOf(WELL_FORMED_EXTRACTION)),
    ) = FakeInterpretationSession(status, structuredOutputAvailable, outcome)

    private fun extractorFor(fake: FakeInterpretationSession) =
        GeminiNanoActivityExtractor(OnDeviceModelCapability(CountingSessionFactory(fake)))

    private fun resultOf(fake: FakeInterpretationSession): ExtractionResult =
        runBlocking { extractorFor(fake).extract(INPUT) }

    private companion object {
        /** A status value this app does not recognise, which yields a failed capability check. */
        const val UNKNOWN_FEATURE_STATUS = 9_999

        /** Any ML Kit error code other than BUSY. */
        const val ERROR_CODE = 13

        val INPUT = ExtractionInput(
            rawText = "descaled the coffee maker yesterday",
            capturedAt = Instant.parse("2026-03-04T09:15:00Z"),
            zoneId = ZoneId.of("Europe/London"),
        )
    }
}
