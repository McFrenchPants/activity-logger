package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateTypedContentRequest
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import kotlinx.coroutines.CancellationException

/**
 * Identifies this implementation of the extraction step, recorded as provenance with every
 * extraction.
 *
 * Bump it whenever the behaviour of [GeminiNanoActivityExtractor.extract] changes in a way that
 * could change the candidate produced for an unchanged capture -- different generation settings,
 * a different decode, a different model family. It is NOT a version of the prompt (that is
 * [EXTRACTION_PROMPT_VERSION]) nor of the response shape (that is [EXTRACTION_SCHEMA_VERSION]).
 * Separate from [INTERPRETER_VERSION] because the v3 interpreter stays live beside it (ADR-038).
 */
internal const val EXTRACTOR_VERSION: String = "gemini-nano-extract-1"

/**
 * Version of the structured response shape the model is constrained to, i.e. of
 * [ExtractionResponse].
 *
 * Bump it when that class's *shape or meaning* changes: a field added, removed or renamed, a
 * field's type changed, or the permitted spellings in a `@Guide(enumValues = ...)` changed. A
 * purely editorial reword of a `@Guide` description is a prompt change and belongs to
 * [EXTRACTION_PROMPT_VERSION] instead. Independent of [INTERPRETATION_SCHEMA_VERSION].
 */
internal const val EXTRACTION_SCHEMA_VERSION: Int = 1

/**
 * Pulls subject, action, time, duration and state words out of a capture using Gemini Nano on
 * the device, through ML Kit's schema-constrained generation (prompt version 4, ADR-038).
 *
 * It is the extraction twin of [GeminiNanoActivityInterpreter] and deliberately behaves the
 * same way in everything but the question asked:
 *
 * - **Shared client.** It is handed the process-wide [OnDeviceModelCapability] and never creates
 *   a model client of its own.
 * - **Same generation settings.** It uses the interpreter's constants by reference
 *   ([GENERATION_TEMPERATURE], [GENERATION_TOP_K], [GENERATION_CANDIDATE_COUNT],
 *   [GENERATION_SEED], [GENERATION_MAX_OUTPUT_TOKENS], [GENERATION_ENABLE_THINKING],
 *   [INCLUDE_SCHEMA_IN_PROMPT]), so the two cannot quietly diverge.
 * - **Readiness gate, never a download.** Not [ModelReadiness.READY] means UNAVAILABLE without
 *   calling the model; nothing on this path starts a download.
 * - **One shot, no repair.** Exactly one generation per [extract] call, no retry, no fallback.
 * - **Untrusted output.** [ExtractionResult.Success] means only that the answer had the promised
 *   shape. Normalising and resolving the words is deterministic domain logic.
 * - **Nothing leaves, nothing is logged** (AGENTS.md #11). A failure carries a kind and nothing
 *   else, and every ML Kit type stays inside this class's implementation (ADR-023).
 */
class GeminiNanoActivityExtractor(
    private val capability: OnDeviceModelCapability,
) : ActivityExtractor {

    override val provenance: InterpreterProvenance = InterpreterProvenance(
        interpreterVersion = EXTRACTOR_VERSION,
        promptVersion = EXTRACTION_PROMPT_VERSION,
        schemaVersion = EXTRACTION_SCHEMA_VERSION,
    )

    /**
     * Extracts from one capture with a single on-device generation.
     *
     * Reports every underlying failure as [ExtractionResult.Failure] instead of throwing, mapped
     * exactly as [GeminiNanoActivityInterpreter.interpret] maps them:
     *
     * - the device is not ready, or no client can be obtained -> [InterpreterFailureKind.UNAVAILABLE]
     * - ML Kit refused the request as busy, or signalled a positive retry delay -> [InterpreterFailureKind.RETRYABLE]
     * - no candidate came back, or the one that did would not decode -> [InterpreterFailureKind.MALFORMED]
     * - anything else -> [InterpreterFailureKind.OTHER]
     *
     * Cancellation of the calling coroutine propagates rather than being reported as a failure.
     */
    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        if (capability.readiness() != ModelReadiness.READY) {
            return extractionFailure(InterpreterFailureKind.UNAVAILABLE)
        }
        val session = capability.session()
            ?: return extractionFailure(InterpreterFailureKind.UNAVAILABLE)

        val candidateResponse = try {
            val response = session.generateContent(buildRequest(input))
            // candidateCount is 1: only the first candidate is ever considered.
            response.candidates.firstOrNull()
                ?: return extractionFailure(InterpreterFailureKind.MALFORMED)
        } catch (cancellation: CancellationException) {
            // Not a model failure: the caller went away.
            throw cancellation
        } catch (genAi: GenAiException) {
            // Same mapping as the interpreter; never carries the exception or its message.
            return if (genAi.isWorthRetrying()) {
                extractionFailure(InterpreterFailureKind.RETRYABLE)
            } else {
                extractionFailure(InterpreterFailureKind.OTHER)
            }
        } catch (expected: Exception) {
            // Deliberately broad (the interface promises a Failure rather than a throw), and
            // deliberately not catching Errors: a broken process is not an unusable answer.
            return extractionFailure(InterpreterFailureKind.OTHER)
        }

        val response = candidateResponse.response
            ?: return extractionFailure(InterpreterFailureKind.MALFORMED)

        return when (val decoded = ExtractionResponseDecoder.decode(response)) {
            is ExtractionDecodeResult.Decoded -> ExtractionResult.Success(decoded.candidate)
            // The reason code stays inside this module; the domain only needs "unusable".
            is ExtractionDecodeResult.Failed -> extractionFailure(InterpreterFailureKind.MALFORMED)
        }
    }

    /**
     * Pre-loads the model so the first real extraction does not also pay for loading it.
     *
     * Does nothing if the device is not ready or no client is available, and never throws for an
     * underlying failure. Cancellation propagates. See [GeminiNanoActivityInterpreter.warmUp].
     */
    suspend fun warmUp() {
        if (capability.readiness() != ModelReadiness.READY) return
        val session = capability.session() ?: return
        try {
            session.warmup()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            // Nothing to report and nothing to log.
        }
    }

    /** The one request shape this extractor ever sends. */
    private fun buildRequest(input: ExtractionInput): GenerateTypedContentRequest<ExtractionResponse> {
        val contentRequest = GenerateContentRequest.Builder(
            SystemInstruction(EXTRACTION_SYSTEM_INSTRUCTION),
            TextPart(buildExtractionPrompt(input)),
        ).apply {
            temperature = GENERATION_TEMPERATURE
            topK = GENERATION_TOP_K
            candidateCount = GENERATION_CANDIDATE_COUNT
            seed = GENERATION_SEED
            maxOutputTokens = GENERATION_MAX_OUTPUT_TOKENS
            enableThinking = GENERATION_ENABLE_THINKING
        }.build()

        return GenerateTypedContentRequest.Builder(
            contentRequest,
            ExtractionResponse::class,
        ).apply {
            includeSchemaInPrompt = INCLUDE_SCHEMA_IN_PROMPT
        }.build()
    }
}

/** Every failure this extractor returns: a kind and nothing else. */
private fun extractionFailure(kind: InterpreterFailureKind): ExtractionResult.Failure =
    ExtractionResult.Failure(kind)
