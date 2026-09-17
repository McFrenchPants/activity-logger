package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.candidates.CandidateSelector
import com.mcfrenchpants.activityledger.core.domain.interpretation.ActivityInterpreter
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolution
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolver
import com.mcfrenchpants.activityledger.core.domain.validation.InterpretationValidator
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationInput
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationOutcome
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationReason
import java.time.Clock

/** What happened when one capture was processed by [CaptureInterpretationOrchestrator]. */
sealed interface CaptureProcessingOutcome {
    /** The interpretation was accepted automatically and occurrence [occurrenceId] was created. */
    data class AutoAccepted(val occurrenceId: String) : CaptureProcessingOutcome

    /** The interpretation was stored for review, for [reasons]; nothing was logged. */
    data class NeedsReview(val reasons: Set<ValidationReason>) : CaptureProcessingOutcome

    /**
     * The interpreter answer was structurally unacceptable, unparseable or failed, for
     * [reasons]. It was stored as INVALID and the capture awaits review; nothing was logged.
     */
    data class Rejected(val reasons: Set<ValidationReason>) : CaptureProcessingOutcome

    /**
     * The interpreter was unavailable or failed transiently ([kind]); the capture is now
     * FAILED_RETRYABLE and no interpretation was stored.
     */
    data class InterpreterUnavailable(val kind: InterpreterFailureKind) : CaptureProcessingOutcome

    /** The capture already has an occurrence; the interpreter was not called and nothing was written. */
    data object AlreadyHasOccurrence : CaptureProcessingOutcome
}

/**
 * Runs one stored capture through candidate selection, interpretation, deterministic time
 * resolution and validation, then persists exactly one outcome through [repository].
 *
 * Model output is never trusted directly: only a [ValidationOutcome.AUTO_ACCEPT] decision
 * creates an occurrence. Every other answer is stored as an interpretation for review (or, for
 * unavailable/retryable interpreter failures, only the capture state changes).
 *
 * Reads [clock] only for the stored interpretation's createdAt. No logging.
 */
class CaptureInterpretationOrchestrator(
    private val repository: ActivityRepository,
    private val interpreter: ActivityInterpreter,
    private val clock: Clock,
    private val selector: CandidateSelector = CandidateSelector(),
    private val resolver: TemporalResolver = TemporalResolver(),
    private val validator: InterpretationValidator = InterpretationValidator(),
) {

    /**
     * Processes capture [captureId]. A capture in any state without an occurrence may be
     * (re)processed; each run appends at most one interpretation. Exceptions thrown by the
     * interpreter propagate.
     *
     * @throws IllegalArgumentException if the capture is unknown, or if the repository refuses
     *   the accept (e.g. the matched activity was archived meanwhile); nothing is written then.
     */
    suspend fun process(captureId: String): CaptureProcessingOutcome {
        val capture = repository.getCapture(captureId)
            ?: throw IllegalArgumentException("unknown capture $captureId")
        if (capture.hasOccurrence) return CaptureProcessingOutcome.AlreadyHasOccurrence

        val catalog = repository.loadCatalog()
        val selection = selector.select(catalog, capture.rawText)
        val result = interpreter.interpret(
            InterpretationInput(capture.rawText, capture.capturedAt, capture.zoneId, selection.candidates),
        )
        val provenance = interpreter.provenance

        when (result) {
            is InterpretationResult.Failure -> {
                val reason = when (result.kind) {
                    InterpreterFailureKind.UNAVAILABLE, InterpreterFailureKind.RETRYABLE -> {
                        repository.recordOutcome(captureId, null, ProcessingState.FAILED_RETRYABLE)
                        return CaptureProcessingOutcome.InterpreterUnavailable(result.kind)
                    }
                    InterpreterFailureKind.MALFORMED -> ValidationReason.INTERPRETER_OUTPUT_MALFORMED
                    InterpreterFailureKind.OTHER -> ValidationReason.INTERPRETER_FAILED
                }
                val record = InterpretationRecord(
                    createdAt = clock.instant(),
                    interpreterVersion = provenance.interpreterVersion,
                    promptVersion = provenance.promptVersion,
                    schemaVersion = provenance.schemaVersion,
                    operation = InterpretationOperation.UNSUPPORTED,
                    activityResolution = ActivityResolution.UNRESOLVED,
                    matchedActivityId = null,
                    proposedCanonicalName = null,
                    activityState = null,
                    temporalExpression = null,
                    resolvedOccurredAt = null,
                    timePrecision = null,
                    modelConfidenceBand = null,
                    candidateContextHash = selection.contextHash,
                    structuredResultJson = result.structuredResultJson,
                    validationStatus = ValidationStatus.INVALID,
                    validationReason = reason.name,
                )
                repository.recordOutcome(captureId, record, ProcessingState.NEEDS_REVIEW)
                return CaptureProcessingOutcome.Rejected(setOf(reason))
            }
            is InterpretationResult.Success -> {
                val candidate = result.candidate
                val temporal = resolver.resolve(candidate.temporalExpression, capture.capturedAt, capture.zoneId)
                val decision = validator.validate(
                    ValidationInput(candidate, selection.candidates, catalog, temporal, capture.speechConfidence),
                )
                val resolved = temporal as? TemporalResolution.Resolved
                // interpretations.matched_activity_id is a foreign key: an id the model invented
                // (or one not in the ACTIVE catalog) would make the write fail and the capture
                // would never reach review. Store it only if it is a catalog id; the model's
                // original answer is preserved in structuredResultJson, and the validator above
                // still judged the unmodified candidate.
                val storedMatchedId = candidate.matchedActivityId?.takeIf { id -> catalog.any { it.id == id } }
                val record = InterpretationRecord(
                    createdAt = clock.instant(),
                    interpreterVersion = provenance.interpreterVersion,
                    promptVersion = provenance.promptVersion,
                    schemaVersion = provenance.schemaVersion,
                    operation = candidate.operation,
                    activityResolution = candidate.activityResolution,
                    matchedActivityId = storedMatchedId,
                    proposedCanonicalName = candidate.proposedCanonicalName,
                    activityState = candidate.activityState,
                    temporalExpression = candidate.temporalExpression,
                    resolvedOccurredAt = resolved?.occurredAt,
                    timePrecision = resolved?.precision,
                    modelConfidenceBand = candidate.confidenceBand,
                    candidateContextHash = selection.contextHash,
                    structuredResultJson = result.structuredResultJson,
                    validationStatus = decision.validationStatus,
                    validationReason = decision.reasonCodes,
                )
                return when (decision.outcome) {
                    ValidationOutcome.AUTO_ACCEPT -> {
                        val target = when (candidate.activityResolution) {
                            ActivityResolution.EXISTING_ACTIVITY -> ActivityTarget.Existing(
                                checkNotNull(candidate.matchedActivityId) { "auto-accept without activity id, capture $captureId" },
                            )
                            ActivityResolution.NEW_ACTIVITY -> ActivityTarget.New(
                                checkNotNull(candidate.proposedCanonicalName) { "auto-accept without name, capture $captureId" },
                            )
                            ActivityResolution.AMBIGUOUS, ActivityResolution.UNRESOLVED ->
                                error("auto-accept with resolution ${candidate.activityResolution}, capture $captureId")
                        }
                        val time = checkNotNull(resolved) { "auto-accept without resolved time, capture $captureId" }
                        val state = checkNotNull(candidate.activityState) { "auto-accept without state, capture $captureId" }
                        val occurrenceId = repository.acceptInterpretation(
                            captureId, record, target, time.occurredAt, time.precision, state,
                        )
                        CaptureProcessingOutcome.AutoAccepted(occurrenceId)
                    }
                    ValidationOutcome.NEEDS_REVIEW -> {
                        repository.recordOutcome(captureId, record, ProcessingState.NEEDS_REVIEW)
                        CaptureProcessingOutcome.NeedsReview(decision.reasons)
                    }
                    ValidationOutcome.REJECT -> {
                        repository.recordOutcome(captureId, record, ProcessingState.NEEDS_REVIEW)
                        CaptureProcessingOutcome.Rejected(decision.reasons)
                    }
                }
            }
        }
    }
}
