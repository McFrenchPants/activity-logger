package com.mcfrenchpants.activityledger.core.ai

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateTypedContentRequest
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.mcfrenchpants.activityledger.core.domain.interpretation.ActivityInterpreter
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import kotlinx.coroutines.CancellationException

/**
 * Identifies this implementation of the interpretation step, recorded as provenance with every
 * interpretation.
 *
 * Bump it whenever the behaviour of [GeminiNanoActivityInterpreter.interpret] changes in a way
 * that could change the candidate produced for an unchanged capture -- different generation
 * settings, a different decode, a different model family. It is NOT a version of the prompt
 * (that is [PROMPT_VERSION]) nor of the response shape (that is
 * [INTERPRETATION_SCHEMA_VERSION]); all three are recorded separately so a later audit can tell
 * which of them moved.
 */
internal const val INTERPRETER_VERSION: String = "gemini-nano-1"

/**
 * Version of the structured response shape the model is constrained to, i.e. of
 * [InterpretationResponse].
 *
 * Bump it when that class's *shape or meaning* changes: a field added, removed or renamed, a
 * field's type changed, or the permitted spellings in a `@Guide(enumValues = ...)` changed. A
 * purely editorial reword of a `@Guide` description that leaves the accepted values alone is a
 * prompt change, not a schema change, and belongs to [PROMPT_VERSION] instead.
 */
internal const val INTERPRETATION_SCHEMA_VERSION: Int = 1

// ---- Generation settings ------------------------------------------------------------------
//
// This is a classification task with a fixed output shape, not a creative one: the same capture
// and the same candidate shortlist must give the same answer every time, or the semantic
// regression corpus cannot mean anything. Every setting below is chosen for determinism, and
// each is `internal` so the tests assert the constant rather than a copied literal.

/** No sampling randomness at all: always take the most likely token. */
internal const val GENERATION_TEMPERATURE: Float = 0.0f

/** Greedy decoding, for the same reason as [GENERATION_TEMPERATURE]. */
internal const val GENERATION_TOP_K: Int = 1

/** One answer. There is no ranking or voting step here, so a second candidate has no use. */
internal const val GENERATION_CANDIDATE_COUNT: Int = 1

/**
 * Fixed so that two runs of the same capture on the same model build agree. The particular
 * value carries no meaning; only its fixedness does.
 */
internal const val GENERATION_SEED: Int = 20250917

/**
 * A deliberately tight ceiling, sized for [InterpretationResponse] and nothing larger.
 *
 * The response is seven short strings: two enum words, an id copied from the shortlist, a short
 * verb-first name, one more enum word, a few words of the user's own time phrasing, and a
 * confidence word -- plus the JSON framing of seven keys. A generous reading of that is roughly
 * 120-150 tokens. 256 leaves comfortable headroom for an unusually long id or name while still
 * cutting off a degenerate repeat loop quickly instead of letting it burn battery on a phone.
 */
internal const val GENERATION_MAX_OUTPUT_TOKENS: Int = 256

/** Thinking/scratchpad output is off: it costs latency and tokens and this task needs none. */
internal const val GENERATION_ENABLE_THINKING: Boolean = false

/**
 * Whether the JSON schema is also spelled out in the prompt text, on top of being enforced by
 * the structured-output decoder.
 *
 * PROVISIONAL, and knowingly so: it trades prompt tokens (and therefore latency) against how
 * reliably a small model fills the shape, and which way that trade falls cannot be settled
 * without measuring on real hardware. `true` is the safer default to start from -- a response
 * that decodes is worth more than a few hundred milliseconds, and the schema's `@Guide` texts
 * are written for the model to read. Revisit once the semantic regression corpus can be run on
 * a device.
 */
internal const val INCLUDE_SCHEMA_IN_PROMPT: Boolean = true

/**
 * Turns a capture into an [com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate]
 * using Gemini Nano on the device, through ML Kit's schema-constrained generation.
 *
 * ## What it composes with
 *
 * It does NOT create a model client. It is handed the process-wide [OnDeviceModelCapability],
 * which owns the single client, so however many interpreters exist and however many captures
 * they interpret, exactly one client is ever created.
 *
 * ## One shot, no repair
 *
 * Each [interpret] call runs generation exactly once. There is no retry, no second call, no
 * "ask the model to fix its own JSON" pass, and no fallback of any kind -- least of all a cloud
 * one (ADR-013). This is deliberate: the domain already has a correct destination for an answer
 * it cannot use, so a failed interpretation becomes an [InterpretationResult.Failure] that the
 * caller can record and move on from, rather than an escalating spend of the user's battery.
 *
 * ## Untrusted output
 *
 * A [InterpretationResult.Success] means the model's answer had the promised *shape*, nothing
 * more. Whether it makes sense -- whether the matched id was actually offered, whether a
 * resolution and its fields agree -- is core-domain's validator's decision (ADR-010,
 * AGENTS.md #5). No semantic check is made here, on purpose: a second copy of those rules would
 * be a defect, not defence in depth.
 *
 * ## Nothing leaves, nothing is logged
 *
 * No prompt, capture text, model output or exception message is logged, returned in a failure,
 * or transmitted anywhere (AGENTS.md #11). A failure carries a kind and nothing else. Every ML
 * Kit type stays inside this class's implementation: its public surface names only core-domain
 * and Kotlin types (ADR-023).
 */
class GeminiNanoActivityInterpreter(
    private val capability: OnDeviceModelCapability,
) : ActivityInterpreter {

    override val provenance: InterpreterProvenance = InterpreterProvenance(
        interpreterVersion = INTERPRETER_VERSION,
        promptVersion = PROMPT_VERSION,
        schemaVersion = INTERPRETATION_SCHEMA_VERSION,
    )

    /**
     * Interprets one capture with a single on-device generation.
     *
     * Reports every underlying failure as [InterpretationResult.Failure] instead of throwing:
     *
     * - the device is not ready, or no client can be obtained -> [InterpreterFailureKind.UNAVAILABLE]
     * - ML Kit signalled a delay before a retry could work -> [InterpreterFailureKind.RETRYABLE]
     * - no candidate came back, or the one that did would not decode -> [InterpreterFailureKind.MALFORMED]
     * - anything else -> [InterpreterFailureKind.OTHER]
     *
     * When the device is not [ModelReadiness.READY] the model is not called at all, and in
     * particular [ModelReadiness.NOT_INSTALLED] does NOT start a download: fetching the model is
     * an explicit, user-initiated choice and nothing on this path may make it for them.
     *
     * Cancellation of the calling coroutine propagates rather than being reported as a failure,
     * matching [OnDeviceModelCapability.readiness].
     */
    override suspend fun interpret(input: InterpretationInput): InterpretationResult {
        if (capability.readiness() != ModelReadiness.READY) {
            return unavailable()
        }
        val session = capability.session() ?: return unavailable()

        val candidateResponse = try {
            val response = session.generateContent(buildRequest(input))
            // Only the first candidate is ever considered: candidateCount is 1, so a second one
            // would be ML Kit ignoring the request, not an alternative worth choosing between.
            response.candidates.firstOrNull() ?: return malformed()
        } catch (cancellation: CancellationException) {
            // Not a model failure: the caller went away. Rethrown so structured concurrency
            // still works, exactly as OnDeviceModelCapability.readiness does.
            throw cancellation
        } catch (genAi: GenAiException) {
            // A positive retry delay is ML Kit saying "this could work later" -- typically the
            // model being busy or throttled. Zero (or absent) is not a promise of anything, so
            // it is not reported as retryable; OTHER keeps the caller from looping on it.
            // Neither branch retries here, and neither carries the exception or its message.
            return if (genAi.isWorthRetrying()) {
                failure(InterpreterFailureKind.RETRYABLE)
            } else {
                failure(InterpreterFailureKind.OTHER)
            }
        } catch (expected: Exception) {
            // Deliberately broad: the interface promises a Failure rather than a throw, and any
            // exception from the runtime is one of the outcomes this returns. Errors are
            // deliberately NOT caught -- an OutOfMemoryError or a linkage failure is a broken
            // process, not an unusable answer, and swallowing one would hide it.
            return failure(InterpreterFailureKind.OTHER)
        }

        // ML Kit's typed candidate can hand back a null payload; that is an answer with nothing
        // in it, which is the same thing to the caller as one that would not decode.
        val response = candidateResponse.response ?: return malformed()

        return when (val decoded = InterpretationResponseDecoder.decode(response)) {
            is InterpretationDecodeResult.Decoded ->
                // structuredResultJson is null, not fabricated: see [structuredResultJson] below.
                InterpretationResult.Success(decoded.candidate, structuredResultJson = null)

            // A response that breaks its own schema contract. The reason code stays inside this
            // module; the domain only needs to know the answer was unusable.
            is InterpretationDecodeResult.Failed -> malformed()
        }
    }

    /**
     * Pre-loads the model so the first real capture does not also pay for loading it.
     *
     * Explicit and separate on purpose. It is expensive and it is a whole-process concern, so
     * hiding it inside [interpret] would make one arbitrary capture -- whichever happened to be
     * first -- silently slow, and would repeat the decision on every call. Call it once, from
     * somewhere that knows the user is about to start capturing (AI1.5's wiring); never from a
     * hot path, and never on a device that is not [ModelReadiness.READY].
     *
     * Does nothing if the device is not ready or no client is available, and never throws for an
     * underlying failure -- warming up is an optimisation, so a failed one is simply a slower
     * first capture. Cancellation propagates.
     */
    suspend fun warmUp() {
        if (capability.readiness() != ModelReadiness.READY) return
        val session = capability.session() ?: return
        try {
            session.warmup()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            // Nothing to report and nothing to log: the next real call will fail or succeed on
            // its own merits.
        }
    }

    /** The one request shape this interpreter ever sends. */
    private fun buildRequest(
        input: InterpretationInput,
    ): GenerateTypedContentRequest<InterpretationResponse> {
        val contentRequest = GenerateContentRequest.Builder(
            SystemInstruction(INTERPRETATION_SYSTEM_INSTRUCTION),
            TextPart(buildInterpretationPrompt(input)),
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
            InterpretationResponse::class,
        ).apply {
            includeSchemaInPrompt = INCLUDE_SCHEMA_IN_PROMPT
        }.build()
    }
}

/**
 * True when ML Kit itself indicated that waiting and asking again could work.
 *
 * Only the duration is read -- never the error code's meaning, the message or the cause.
 */
private fun GenAiException.isWorthRetrying(): Boolean =
    !retryDelay.isZero && !retryDelay.isNegative

/**
 * Every failure this interpreter returns.
 *
 * `structuredResultJson` is always null, and that is a considered answer rather than a gap: the
 * typed generation API hands back an already-decoded [InterpretationResponse] object, never the
 * raw text the model emitted, so there is no genuine model output to keep. Re-serialising the
 * decoded object and storing it in an audit field would be a fabrication -- it would record what
 * this code understood, dressed up as what the model said. If ML Kit later exposes the raw
 * response text, that is the moment to fill this field in for real.
 */
private fun failure(kind: InterpreterFailureKind): InterpretationResult.Failure =
    InterpretationResult.Failure(kind, structuredResultJson = null)

private fun unavailable(): InterpretationResult.Failure =
    failure(InterpreterFailureKind.UNAVAILABLE)

private fun malformed(): InterpretationResult.Failure =
    failure(InterpreterFailureKind.MALFORMED)
