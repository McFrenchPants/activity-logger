package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionGrounding
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionOutcome
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionPolicy
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import com.mcfrenchpants.activityledger.core.domain.temporal.DurationResolver
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolution
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolver
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationReason
import java.time.Clock
import java.time.Instant

/**
 * Everything a screen needs to show a "did you mean ...?" card for a capture that was not saved
 * silently, and to save it later without asking the model again.
 *
 * Holds the user's own extracted words and is therefore never logged.
 *
 * @property extractedSubject The subject words, verbatim from the extraction, if any.
 * @property extractedAction The action words, verbatim from the extraction, if any.
 * @property subject How the subject words resolved (after any inference, see [subjectInferred]).
 * @property action How the action words resolved.
 * @property subjectInferred True when the subject was filled in from the only known combination.
 * @property reasons Why the tag decision was not an automatic save (empty when only the time or
 *   state held an otherwise clean decision back).
 * @property occurredAt The resolved time, or null when it was in the future or unresolvable.
 * @property timePrecision Precision of [occurredAt]; null exactly when [occurredAt] is null.
 * @property durationExpression The grounded duration words, if any.
 * @property durationSeconds The resolved duration in seconds, if any.
 * @property activityState Completed / in progress, if the extraction stated it.
 */
data class TaggedProposal(
    val captureId: String,
    val extractedSubject: String?,
    val extractedAction: String?,
    val subject: TagResolution,
    val action: TagResolution,
    val subjectInferred: Boolean,
    val reasons: Set<TagDecisionReason>,
    val occurredAt: Instant?,
    val timePrecision: TimePrecision?,
    val durationExpression: String?,
    val durationSeconds: Long?,
    val activityState: ActivityState?,
)

/** What happened when one capture was processed by [TaggedCaptureOrchestrator]. */
sealed interface TaggedProcessingOutcome {
    /** The entry was saved automatically as occurrence [occurrenceId]. */
    data class AutoSaved(val occurrenceId: String) : TaggedProcessingOutcome

    /**
     * A tag was close to an existing one: [proposal] is a "did you mean ...?" card. The
     * interpretation was stored and the capture is NEEDS_REVIEW; nothing was logged.
     */
    data class NeedsConfirm(val proposal: TaggedProposal) : TaggedProcessingOutcome

    /**
     * The capture waits for review; nothing was logged. This is a NEEDS_REVIEW tag decision
     * ([reasons]), or an automatic save held back by a time or state problem ([problems]).
     *
     * @property reasons The tag decision's reasons (empty for a downgraded automatic save).
     * @property problems Time or state problems that downgraded an automatic save (TIME_IN_FUTURE,
     *   TIME_UNRESOLVABLE, STATE_MISSING); empty otherwise.
     * @property proposal The words and what resolved, for a review screen.
     */
    data class NeedsReview(
        val reasons: Set<TagDecisionReason>,
        val problems: Set<ValidationReason>,
        val proposal: TaggedProposal?,
    ) : TaggedProcessingOutcome

    /**
     * The extractor answer was unparseable or failed, for [reasons]. It was stored as INVALID and
     * the capture awaits review; nothing was logged.
     */
    data class Rejected(val reasons: Set<ValidationReason>) : TaggedProcessingOutcome

    /**
     * The extractor was unavailable or failed transiently ([kind]); the capture is now
     * FAILED_RETRYABLE and no interpretation was stored.
     */
    data class InterpreterUnavailable(val kind: InterpreterFailureKind) : TaggedProcessingOutcome

    /** The capture already has an occurrence; the extractor was not called and nothing was written. */
    data object AlreadyHasOccurrence : TaggedProcessingOutcome
}

/**
 * Runs one stored capture through word extraction, grounding, the deterministic tag decision and
 * time/duration resolution, then persists exactly one outcome through [repository]. This is the
 * subject + action twin of [CaptureInterpretationOrchestrator].
 *
 * Model output is never trusted directly: only an AUTO_SAVE tag decision with a clean time and
 * state creates an occurrence (through acceptTagged, with no alias learning). A CONFIRM or
 * NEEDS_REVIEW decision, and an AUTO_SAVE with a future/unresolvable time or a missing state
 * (downgraded to review, never to a question), store the interpretation and set the capture
 * NEEDS_REVIEW. Model confidence is not consulted (an extraction has none).
 *
 * Reads [clock] only for the stored interpretation's createdAt. No logging; exception messages
 * carry ids and enum names only, never the user's words.
 */
class TaggedCaptureOrchestrator(
    private val repository: LedgerRepository,
    private val extractor: ActivityExtractor,
    private val clock: Clock,
    private val resolver: TemporalResolver = TemporalResolver(),
) {

    /**
     * Processes capture [captureId]. A capture in any state without an occurrence may be
     * (re)processed; each run appends at most one interpretation. Exceptions thrown by the
     * extractor propagate.
     *
     * @throws IllegalArgumentException if the capture is unknown, or if the repository refuses
     *   the save (e.g. a tag was merged meanwhile); nothing is written then.
     */
    suspend fun process(captureId: String): TaggedProcessingOutcome {
        val capture = repository.getCapture(captureId)
            ?: throw IllegalArgumentException("unknown capture $captureId")
        if (capture.hasOccurrence) return TaggedProcessingOutcome.AlreadyHasOccurrence

        val catalog = repository.loadTagCatalog()
        val result = extractor.extract(ExtractionInput(capture.rawText, capture.capturedAt, capture.zoneId))
        val provenance = extractor.provenance

        when (result) {
            is ExtractionResult.Failure -> {
                val reason = when (result.kind) {
                    InterpreterFailureKind.UNAVAILABLE, InterpreterFailureKind.RETRYABLE -> {
                        repository.recordOutcome(captureId, null, ProcessingState.FAILED_RETRYABLE)
                        return TaggedProcessingOutcome.InterpreterUnavailable(result.kind)
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
                    candidateContextHash = null,
                    structuredResultJson = null,
                    validationStatus = ValidationStatus.INVALID,
                    validationReason = reason.name,
                )
                repository.recordOutcome(captureId, record, ProcessingState.NEEDS_REVIEW)
                return TaggedProcessingOutcome.Rejected(setOf(reason))
            }
            is ExtractionResult.Success -> {
                val grounded = ExtractionGrounding.ground(result.candidate, capture.rawText).candidate
                val decision = TagDecisionPolicy.decide(grounded, catalog)
                val temporal = resolver.resolve(grounded.temporalExpression, capture.capturedAt, capture.zoneId)
                val resolved = temporal as? TemporalResolution.Resolved
                val durationSeconds = DurationResolver.resolve(grounded.durationExpression)?.times(60L)

                // Time and state only matter for a silent save; they never promote or demote a
                // CONFIRM / NEEDS_REVIEW decision.
                val problems = mutableSetOf<ValidationReason>()
                if (decision.outcome == TagDecisionOutcome.AUTO_SAVE) {
                    when (temporal) {
                        TemporalResolution.Future -> problems += ValidationReason.TIME_IN_FUTURE
                        TemporalResolution.Unresolvable -> problems += ValidationReason.TIME_UNRESOLVABLE
                        is TemporalResolution.Resolved -> Unit
                    }
                    if (grounded.activityState == null) problems += ValidationReason.STATE_MISSING
                }
                val saveSilently = decision.outcome == TagDecisionOutcome.AUTO_SAVE && problems.isEmpty()

                val reasonCodes = (decision.reasons.map { it.name } + problems.map { it.name }).sorted()
                val record = InterpretationRecord(
                    createdAt = clock.instant(),
                    interpreterVersion = provenance.interpreterVersion,
                    promptVersion = provenance.promptVersion,
                    schemaVersion = provenance.schemaVersion,
                    operation = grounded.operation,
                    activityResolution = ActivityResolution.UNRESOLVED,
                    matchedActivityId = null,
                    proposedCanonicalName = null,
                    activityState = grounded.activityState,
                    temporalExpression = grounded.temporalExpression,
                    resolvedOccurredAt = resolved?.occurredAt,
                    timePrecision = resolved?.precision,
                    modelConfidenceBand = null,
                    candidateContextHash = null,
                    structuredResultJson = null,
                    validationStatus = if (saveSilently) ValidationStatus.VALID else ValidationStatus.NEEDS_REVIEW,
                    validationReason = if (reasonCodes.isEmpty()) null else reasonCodes.joinToString(","),
                    extractedSubject = grounded.subject,
                    extractedAction = grounded.action,
                    durationExpression = grounded.durationExpression,
                    resolvedDurationSeconds = durationSeconds,
                )

                if (saveSilently) {
                    val time = checkNotNull(resolved) { "auto-save without resolved time, capture $captureId" }
                    val state = checkNotNull(grounded.activityState) { "auto-save without state, capture $captureId" }
                    val occurrenceId = repository.acceptTagged(
                        TaggedAcceptRequest(
                            captureId = captureId,
                            interpretation = record,
                            subject = targetOf(decision.subject, "subject", captureId),
                            action = targetOf(decision.action, "action", captureId),
                            occurredAt = time.occurredAt,
                            timePrecision = time.precision,
                            activityState = state,
                            durationSeconds = durationSeconds,
                        ),
                    )
                    return TaggedProcessingOutcome.AutoSaved(occurrenceId)
                }

                val proposal = TaggedProposal(
                    captureId = captureId,
                    extractedSubject = grounded.subject,
                    extractedAction = grounded.action,
                    subject = decision.subject,
                    action = decision.action,
                    subjectInferred = decision.subjectInferred,
                    reasons = decision.reasons,
                    occurredAt = resolved?.occurredAt,
                    timePrecision = resolved?.precision,
                    durationExpression = grounded.durationExpression,
                    durationSeconds = durationSeconds,
                    activityState = grounded.activityState,
                )
                repository.recordOutcome(captureId, record, ProcessingState.NEEDS_REVIEW)
                return when (decision.outcome) {
                    TagDecisionOutcome.CONFIRM -> TaggedProcessingOutcome.NeedsConfirm(proposal)
                    TagDecisionOutcome.NEEDS_REVIEW, TagDecisionOutcome.AUTO_SAVE ->
                        TaggedProcessingOutcome.NeedsReview(decision.reasons, problems, proposal)
                }
            }
        }
    }

    private fun targetOf(resolution: TagResolution, side: String, captureId: String): TagTarget = when (resolution) {
        is TagResolution.Exact -> TagTarget.Existing(resolution.tag.id)
        is TagResolution.New -> TagTarget.New(resolution.name)
        TagResolution.Empty, is TagResolution.Near ->
            error("auto-save with unresolved $side, capture $captureId")
    }
}
