package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionChanges
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import java.time.Clock

/**
 * A user's requested change to an occurrence. A null field means "leave unchanged".
 *
 * @property activity Move the occurrence to an existing or a new activity.
 * @property time New occurrence time together with its precision.
 * @property activityState New completed / in-progress state.
 */
data class CorrectionRequest(
    val activity: ActivityTarget? = null,
    val time: OccurrenceTime? = null,
    val activityState: ActivityState? = null,
)

/** Result of [CorrectionService.correct]. */
sealed interface CorrectionResult {
    /** The correction [correctionId] was recorded. */
    data class Applied(val correctionId: String) : CorrectionResult

    /** Nothing requested differed from the current values; nothing was written. */
    data object NothingChanged : CorrectionResult

    /** The request was refused for [refusal]; nothing was written. */
    data class Refused(val refusal: ServiceRefusal) : CorrectionResult
}

/**
 * Applies user corrections to occurrences, enforcing product policy (hidden occurrences,
 * inactive activities, new-name rules, no future times) before the repository's integrity
 * checks. Raw captures are never modified. No logging.
 */
class CorrectionService(
    private val repository: ActivityRepository,
    private val clock: Clock,
) {

    /**
     * Corrects occurrence [occurrenceId] with [request], recording [reason] as given, with
     * source USER. "Now" is read once from the clock; checks run before any write.
     */
    suspend fun correct(occurrenceId: String, request: CorrectionRequest, reason: String? = null): CorrectionResult {
        val now = clock.instant()
        val occurrence = repository.getOccurrence(occurrenceId)
            ?: return CorrectionResult.Refused(ServiceRefusal.OccurrenceNotFound)
        if (occurrence.visibilityStatus == VisibilityStatus.HIDDEN) {
            return CorrectionResult.Refused(ServiceRefusal.OccurrenceHidden)
        }
        request.activity?.let { target ->
            checkActivityTarget(repository, target)?.let { return CorrectionResult.Refused(it) }
        }
        val time = request.time
        if (time != null && time.occurredAt.isAfter(now)) {
            return CorrectionResult.Refused(ServiceRefusal.OccurredAfterNow)
        }
        val target = when (val activity = request.activity) {
            is ActivityTarget.New -> ActivityTarget.New(activity.displayName.trim())
            is ActivityTarget.Existing, null -> activity
        }
        val changes = CorrectionChanges(
            activity = target,
            occurredAt = time?.occurredAt,
            timePrecision = time?.precision,
            activityState = request.activityState,
        )
        return when (val outcome = repository.applyCorrection(occurrenceId, changes, CorrectionSource.USER, reason, now)) {
            is CorrectionOutcome.Applied -> CorrectionResult.Applied(outcome.correctionId)
            CorrectionOutcome.NothingChanged -> CorrectionResult.NothingChanged
        }
    }
}
