package com.mcfrenchpants.activityledger.core.domain.interpretation

import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import java.time.Instant
import java.time.ZoneId

/**
 * The structured proposal an [ActivityInterpreter] returns for one capture.
 *
 * This is untrusted model output: nothing here is validated by construction. Semantic
 * validation (resolution/field consistency, candidate membership, name rules) belongs to a
 * domain validator, never to this type.
 *
 * @property operation What the model thinks the user asked for.
 * @property activityResolution Whether the capture maps to an existing activity, a new one,
 *   is ambiguous, or could not be resolved.
 * @property matchedActivityId Id of the candidate activity the model picked, if any.
 * @property proposedCanonicalName Display name proposed for a new activity, if any.
 * @property activityState Whether the activity was completed or is in progress, if stated.
 * @property temporalExpression The time phrase from the capture (e.g. "yesterday"), verbatim;
 *   resolved deterministically by the domain, never by the model.
 * @property confidenceBand The model's self-reported confidence band, if any.
 */
data class InterpretationCandidate(
    val operation: InterpretationOperation,
    val activityResolution: ActivityResolution,
    val matchedActivityId: String?,
    val proposedCanonicalName: String?,
    val activityState: ActivityState?,
    val temporalExpression: String?,
    val confidenceBand: ConfidenceBand?,
)

/**
 * One existing activity offered to the interpreter as a possible match.
 *
 * @property id Canonical activity id.
 * @property displayName Human-readable name.
 * @property aliases Alternative names for the activity (display form).
 */
data class CandidateActivity(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
)

/**
 * Everything an [ActivityInterpreter] receives for a single capture.
 *
 * @property rawText The captured text, exactly as stored.
 * @property capturedAt When the capture happened.
 * @property zoneId The IANA zone the capture was made in.
 * @property candidates The bounded shortlist of existing activities the model may match.
 */
data class InterpretationInput(
    val rawText: String,
    val capturedAt: Instant,
    val zoneId: ZoneId,
    val candidates: List<CandidateActivity>,
)

/**
 * Versions identifying which interpreter, prompt and output schema produced a result.
 * Persisted with every interpretation so results stay reproducible and auditable.
 */
data class InterpreterProvenance(
    val interpreterVersion: String,
    val promptVersion: String,
    val schemaVersion: Int,
)

/** Coarse reason an interpreter call did not yield a usable candidate. */
enum class InterpreterFailureKind {
    /** The model or its runtime is not available on this device right now. */
    UNAVAILABLE,

    /** A transient failure; retrying later may succeed. */
    RETRYABLE,

    /** The model responded, but its output could not be parsed into a candidate. */
    MALFORMED,

    /** Any other failure. */
    OTHER,
}

/** Outcome of one [ActivityInterpreter.interpret] call. */
sealed interface InterpretationResult {
    /**
     * The model produced a parseable candidate (which may still be semantically invalid).
     *
     * @property structuredResultJson The raw structured output, kept for audit, if available.
     */
    data class Success(
        val candidate: InterpretationCandidate,
        val structuredResultJson: String?,
    ) : InterpretationResult

    /**
     * No usable candidate. Deliberately carries no user text and no exception message:
     * only the failure kind and, optionally, the structured JSON the model itself returned.
     */
    data class Failure(
        val kind: InterpreterFailureKind,
        val structuredResultJson: String?,
    ) : InterpretationResult
}

/**
 * Turns a capture's text into a structured [InterpretationCandidate]. Implementations run on
 * the phone only. Callers must treat every result as untrusted until validated.
 */
interface ActivityInterpreter {
    /** Versions recorded with every interpretation this interpreter produces. */
    val provenance: InterpreterProvenance

    /**
     * Interprets one capture. Implementations should report failures as
     * [InterpretationResult.Failure] rather than throwing.
     */
    suspend fun interpret(input: InterpretationInput): InterpretationResult
}

/**
 * One interpretation row as persisted: mirrors the interpretations table except its own id
 * and the raw capture id (the repository supplies both).
 *
 * @property createdAt When this interpretation was produced.
 * @property resolvedOccurredAt The domain's deterministic resolution of [temporalExpression], if any.
 * @property modelConfidenceBand The model's self-reported confidence band, if any.
 * @property candidateContextHash Hash of the candidate shortlist given to the model, for audit.
 * @property structuredResultJson The raw structured model output, if any.
 * @property validationReason Machine-readable reason when [validationStatus] is not VALID.
 * @property extractedSubject Tag path only: the subject words the model extracted, verbatim;
 *   null for the v3 activity path.
 * @property extractedAction Tag path only: the action words the model extracted, verbatim;
 *   null for the v3 activity path.
 * @property durationExpression Tag path only: the duration phrase the model extracted,
 *   verbatim, if any.
 * @property resolvedDurationSeconds The domain's deterministic resolution of
 *   [durationExpression], in seconds, if any.
 */
data class InterpretationRecord(
    val createdAt: Instant,
    val interpreterVersion: String,
    val promptVersion: String,
    val schemaVersion: Int,
    val operation: InterpretationOperation,
    val activityResolution: ActivityResolution,
    val matchedActivityId: String?,
    val proposedCanonicalName: String?,
    val activityState: ActivityState?,
    val temporalExpression: String?,
    val resolvedOccurredAt: Instant?,
    val timePrecision: TimePrecision?,
    val modelConfidenceBand: ConfidenceBand?,
    val candidateContextHash: String?,
    val structuredResultJson: String?,
    val validationStatus: ValidationStatus,
    val validationReason: String?,
    val extractedSubject: String? = null,
    val extractedAction: String? = null,
    val durationExpression: String? = null,
    val resolvedDurationSeconds: Long? = null,
)
