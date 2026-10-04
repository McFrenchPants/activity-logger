package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Behaviour of the on-device question extractor, driven through the shared scripted fake. */
class GeminiNanoQuestionExtractorTest {

    @Test
    fun `ready path returns the decoded candidate`() {
        val result = resultOf(session())

        assertEquals(QuestionExtractionResult.Success(QuestionCandidate("furnace filter", "change")), result)
    }

    @Test
    fun `every not-ready state is unavailable and never calls or downloads`() {
        listOf(
            session(status = FeatureStatus.DOWNLOADABLE),
            session(status = FeatureStatus.DOWNLOADING),
            session(status = FeatureStatus.UNAVAILABLE),
            session(structuredOutputAvailable = false),
            session(status = 9_999),
        ).forEachIndexed { index, fake ->
            assertEquals(
                QuestionExtractionResult.Failure(InterpreterFailureKind.UNAVAILABLE),
                resultOf(fake),
                "state #$index",
            )
            assertEquals(0, fake.generateContentCalls, "state #$index generated")
            assertEquals(0, fake.downloadCalls, "state #$index downloaded")
        }
    }

    @Test
    fun `exactly one generation per call and a failure is not retried`() {
        val fake = session()
        val extractor = extractorFor(fake)
        runBlocking { extractor.extract(QUESTION) }
        assertEquals(1, fake.generateContentCalls)
        runBlocking { extractor.extract(QUESTION) }
        assertEquals(2, fake.generateContentCalls)

        val failing = session(outcome = GenerationOutcome.Throws(RuntimeException()))
        resultOf(failing)
        assertEquals(1, failing.generateContentCalls)
    }

    @Test
    fun `failure mapping matches the activity extractor`() {
        val cases = listOf(
            GenAiException("", null, ERROR_CODE, Duration.ofSeconds(30)) to InterpreterFailureKind.RETRYABLE,
            GenAiException("", null, ERROR_CODE, Duration.ZERO) to InterpreterFailureKind.OTHER,
            GenAiException("", null, GenAiException.ErrorCode.BUSY, Duration.ZERO) to InterpreterFailureKind.RETRYABLE,
            RuntimeException() to InterpreterFailureKind.OTHER,
        )
        cases.forEachIndexed { index, (thrown, kind) ->
            val fake = session(outcome = GenerationOutcome.Throws(thrown))
            assertEquals(QuestionExtractionResult.Failure(kind), resultOf(fake), "case #$index")
            assertEquals(1, fake.generateContentCalls)
        }
    }

    @Test
    fun `no candidate is malformed`() {
        val fake = session(outcome = GenerationOutcome.Responses(emptyList()))

        assertEquals(QuestionExtractionResult.Failure(InterpreterFailureKind.MALFORMED), resultOf(fake))
    }

    @Test
    fun `an error from the runtime is not swallowed`() {
        val fake = session(outcome = GenerationOutcome.Throws(StackOverflowError()))

        assertFailsWith<StackOverflowError> { runBlocking { extractorFor(fake).extract(QUESTION) } }
    }

    @Test
    fun `cancellation propagates instead of becoming a failure`() {
        val fake = session(outcome = GenerationOutcome.Throws(CancellationException()))

        assertFailsWith<CancellationException> { runBlocking { extractorFor(fake).extract(QUESTION) } }
    }

    @Test
    fun `no failure carries question text or an exception message`() {
        val secret = "bled the radiators at 3am"
        val fake = session(
            outcome = GenerationOutcome.Throws(
                GenAiException("failed while handling: $secret", null, ERROR_CODE, Duration.ZERO),
            ),
        )

        val result = runBlocking { extractorFor(fake).extract(secret) }

        val text = (result as QuestionExtractionResult.Failure).toString()
        assertFalse(text.contains(secret))
        assertFalse(text.contains("failed while handling"))
    }

    @Test
    fun `warm up does nothing when not ready and warms once when ready`() {
        val notReady = session(status = FeatureStatus.DOWNLOADABLE)
        runBlocking { extractorFor(notReady).warmUp() }
        assertEquals(0, notReady.warmupCalls)
        assertEquals(0, notReady.downloadCalls)

        val ready = session()
        runBlocking { extractorFor(ready).extract(QUESTION) }
        assertEquals(0, ready.warmupCalls)
        runBlocking { extractorFor(ready).warmUp() }
        assertEquals(1, ready.warmupCalls)
    }

    @Test
    fun `provenance reports extractor, prompt and schema versions`() {
        val provenance = extractorFor(session()).provenance

        assertEquals("gemini-nano-question-1", provenance.interpreterVersion)
        assertEquals("q1", provenance.promptVersion)
        assertEquals(1, provenance.schemaVersion)
    }

    @Test
    fun `request uses the shared deterministic settings, question schema and prompt`() {
        val fake = session()

        runBlocking { extractorFor(fake).extract(QUESTION) }

        val request = requireNotNull(fake.lastRequest)
        val content = request.generateContentRequest
        assertEquals(GENERATION_TEMPERATURE, content.temperature)
        assertEquals(GENERATION_TOP_K, content.topK)
        assertEquals(GENERATION_CANDIDATE_COUNT, content.candidateCount)
        assertEquals(GENERATION_SEED, content.seed)
        assertEquals(GENERATION_MAX_OUTPUT_TOKENS, content.maxOutputTokens)
        assertEquals(GENERATION_ENABLE_THINKING, content.enableThinking)
        assertEquals(INCLUDE_SCHEMA_IN_PROMPT, request.includeSchemaInPrompt)
        assertEquals(QuestionResponse::class, request.outputClass)
        assertEquals(QUESTION_SYSTEM_INSTRUCTION, content.systemInstruction?.textString)
        assertEquals(buildQuestionPrompt(QUESTION), content.text.textString)
    }

    private fun session(
        status: Int = FeatureStatus.AVAILABLE,
        structuredOutputAvailable: Boolean = true,
        outcome: GenerationOutcome = GenerationOutcome.Responses(listOf(WELL_FORMED_QUESTION)),
    ) = FakeInterpretationSession(status, structuredOutputAvailable, outcome)

    private fun extractorFor(fake: FakeInterpretationSession) =
        GeminiNanoQuestionExtractor(OnDeviceModelCapability(CountingSessionFactory(fake)))

    private fun resultOf(fake: FakeInterpretationSession): QuestionExtractionResult =
        runBlocking { extractorFor(fake).extract(QUESTION) }

    private companion object {
        const val ERROR_CODE = 13
        const val QUESTION = "When did I last change the furnace filter?"
        val WELL_FORMED_QUESTION = QuestionResponse(subject = "furnace filter", action = "change")
    }
}
