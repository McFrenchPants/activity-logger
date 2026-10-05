package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateTypedContentRequest
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractor
import kotlinx.coroutines.CancellationException

/**
 * Identifies this implementation of the question-extraction step, recorded as provenance.
 *
 * Bump it whenever [GeminiNanoQuestionExtractor.extract] could produce a different candidate for
 * an unchanged question (different generation settings, decode or model family). Not a version
 * of the prompt ([QUESTION_PROMPT_VERSION]) nor of the response shape ([QUESTION_SCHEMA_VERSION]).
 */
internal const val QUESTION_EXTRACTOR_VERSION: String = "gemini-nano-question-1"

/**
 * Version of the structured response shape the model is constrained to, i.e. of
 * [QuestionResponse]. Bump it when that class's shape or meaning changes.
 */
internal const val QUESTION_SCHEMA_VERSION: Int = 2

/**
 * Pulls the subject, action and date words and the question kind out of a question using Gemini
 * Nano on the device (prompt version q2, schema version 2, ADR-052). The question twin of [GeminiNanoActivityExtractor], behaving the same way:
 *
 * - **Shared client.** Handed the process-wide [OnDeviceModelCapability]; never makes its own.
 * - **Same generation settings**, by reference to the interpreter's constants.
 * - **Readiness gate, never a download.** Not [ModelReadiness.READY] means UNAVAILABLE without
 *   calling the model.
 * - **One shot, no repair.** Exactly one generation per [extract] call.
 * - **Untrusted output.** Success means only that the answer had the promised shape.
 * - **Nothing leaves, nothing is logged** (AGENTS.md #11). A failure carries a kind only, and
 *   every ML Kit type stays inside this module (ADR-023).
 */
class GeminiNanoQuestionExtractor(
    private val capability: OnDeviceModelCapability,
) : QuestionExtractor {

    override val provenance: InterpreterProvenance = InterpreterProvenance(
        interpreterVersion = QUESTION_EXTRACTOR_VERSION,
        promptVersion = QUESTION_PROMPT_VERSION,
        schemaVersion = QUESTION_SCHEMA_VERSION,
    )

    /**
     * Extracts from one question with a single on-device generation, mapping failures exactly as
     * [GeminiNanoActivityExtractor.extract] does: not ready -> UNAVAILABLE; busy or positive retry
     * delay -> RETRYABLE; no candidate or undecodable -> MALFORMED; anything else -> OTHER.
     * Cancellation propagates.
     */
    override suspend fun extract(questionText: String): QuestionExtractionResult {
        if (capability.readiness() != ModelReadiness.READY) {
            return questionFailure(InterpreterFailureKind.UNAVAILABLE)
        }
        val session = capability.session()
            ?: return questionFailure(InterpreterFailureKind.UNAVAILABLE)

        val candidateResponse = try {
            val response = session.generateContent(buildRequest(questionText))
            response.candidates.firstOrNull()
                ?: return questionFailure(InterpreterFailureKind.MALFORMED)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (genAi: GenAiException) {
            return if (genAi.isWorthRetrying()) {
                questionFailure(InterpreterFailureKind.RETRYABLE)
            } else {
                questionFailure(InterpreterFailureKind.OTHER)
            }
        } catch (expected: Exception) {
            return questionFailure(InterpreterFailureKind.OTHER)
        }

        val response = candidateResponse.response
            ?: return questionFailure(InterpreterFailureKind.MALFORMED)

        return when (val decoded = QuestionResponseDecoder.decode(response)) {
            is QuestionDecodeResult.Decoded -> QuestionExtractionResult.Success(decoded.candidate)
            is QuestionDecodeResult.Failed -> questionFailure(InterpreterFailureKind.MALFORMED)
        }
    }

    /**
     * Pre-loads the model so the first real question does not also pay for loading it. Does
     * nothing if the device is not ready; never throws for an underlying failure; cancellation
     * propagates.
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
    private fun buildRequest(questionText: String): GenerateTypedContentRequest<QuestionResponse> {
        val contentRequest = GenerateContentRequest.Builder(
            SystemInstruction(QUESTION_SYSTEM_INSTRUCTION),
            TextPart(buildQuestionPrompt(questionText)),
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
            QuestionResponse::class,
        ).apply {
            includeSchemaInPrompt = INCLUDE_SCHEMA_IN_PROMPT
        }.build()
    }
}

/** Every failure this extractor returns: a kind and nothing else. */
private fun questionFailure(kind: InterpreterFailureKind): QuestionExtractionResult.Failure =
    QuestionExtractionResult.Failure(kind)
