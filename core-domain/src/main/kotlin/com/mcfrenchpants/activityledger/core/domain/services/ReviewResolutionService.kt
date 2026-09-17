package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import java.time.Clock

/** Interpreter version recorded on interpretations created by a user resolving a review. */
const val USER_RESOLUTION_INTERPRETER_VERSION: String = "user-resolution"

/** Prompt version recorded on user-resolution interpretations (no prompt is involved). */
const val USER_RESOLUTION_PROMPT_VERSION: String = "none"

/** Schema version recorded on user-resolution interpretations. */
const val USER_RESOLUTION_SCHEMA_VERSION: Int = 1

/** Result of [ReviewResolutionService.resolve]. */
sealed interface ResolutionResult {
    /** The capture was logged as occurrence [occurrenceId]. */
    data class Resolved(val occurrenceId: String) : ResolutionResult

    /** The resolution was refused for [refusal]; nothing was written. */
    data class Refused(val refusal: ServiceRefusal) : ResolutionResult
}

/**
 * Logs a capture that is awaiting review (or failed) the way the user chose: stores a fresh,
 * VALID user-resolution interpretation and accepts it. Earlier interpretations of the capture
 * are never modified. No logging.
 */
class ReviewResolutionService(
    private val repository: ActivityRepository,
    private val clock: Clock,
) {

    /**
     * Resolves capture [captureId] to [activity]. [time] defaults to the capture instant with
     * [TimePrecision.INFERRED_NOW]. "Now" is read once from the clock; checks run before any write.
     */
    suspend fun resolve(
        captureId: String,
        activity: ActivityTarget,
        time: OccurrenceTime? = null,
        activityState: ActivityState = ActivityState.COMPLETED,
    ): ResolutionResult {
        val now = clock.instant()
        val capture = repository.getCapture(captureId)
            ?: return ResolutionResult.Refused(ServiceRefusal.CaptureNotFound)
        if (capture.hasOccurrence) return ResolutionResult.Refused(ServiceRefusal.CaptureAlreadyHasOccurrence)
        checkActivityTarget(repository, activity)?.let { return ResolutionResult.Refused(it) }
        val chosen = time ?: OccurrenceTime(capture.capturedAt, TimePrecision.INFERRED_NOW)
        if (chosen.occurredAt.isAfter(now)) return ResolutionResult.Refused(ServiceRefusal.OccurredAfterNow)

        val target: ActivityTarget = when (activity) {
            is ActivityTarget.Existing -> activity
            is ActivityTarget.New -> ActivityTarget.New(activity.displayName.trim())
        }
        val record = InterpretationRecord(
            createdAt = now,
            interpreterVersion = USER_RESOLUTION_INTERPRETER_VERSION,
            promptVersion = USER_RESOLUTION_PROMPT_VERSION,
            schemaVersion = USER_RESOLUTION_SCHEMA_VERSION,
            operation = InterpretationOperation.LOG_ACTIVITY,
            activityResolution = when (target) {
                is ActivityTarget.Existing -> ActivityResolution.EXISTING_ACTIVITY
                is ActivityTarget.New -> ActivityResolution.NEW_ACTIVITY
            },
            matchedActivityId = (target as? ActivityTarget.Existing)?.activityId,
            proposedCanonicalName = (target as? ActivityTarget.New)?.displayName,
            activityState = activityState,
            temporalExpression = null,
            resolvedOccurredAt = chosen.occurredAt,
            timePrecision = chosen.precision,
            modelConfidenceBand = null,
            candidateContextHash = null,
            structuredResultJson = null,
            validationStatus = ValidationStatus.VALID,
            validationReason = null,
        )
        val occurrenceId = repository.acceptInterpretation(
            captureId, record, target, chosen.occurredAt, chosen.precision, activityState,
        )
        return ResolutionResult.Resolved(occurrenceId)
    }
}
