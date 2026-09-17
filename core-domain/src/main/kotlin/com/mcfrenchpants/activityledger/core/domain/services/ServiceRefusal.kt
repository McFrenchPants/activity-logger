package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget

/**
 * Why a user-initiated service request (a correction or a review resolution) was refused.
 * A refusal always means nothing was written. Carries ids and enum values only.
 */
sealed interface ServiceRefusal {
    /** The occurrence to correct does not exist. */
    data object OccurrenceNotFound : ServiceRefusal

    /** The occurrence to correct is hidden; hidden occurrences are not corrected. */
    data object OccurrenceHidden : ServiceRefusal

    /** The capture to resolve does not exist. */
    data object CaptureNotFound : ServiceRefusal

    /** The capture to resolve already produced an occurrence. */
    data object CaptureAlreadyHasOccurrence : ServiceRefusal

    /** The chosen existing activity does not exist. */
    data object ActivityNotFound : ServiceRefusal

    /** The chosen existing activity is merged or archived. */
    data object ActivityNotActive : ServiceRefusal

    /** The chosen occurrence time is after the current time. */
    data object OccurredAfterNow : ServiceRefusal

    /** The typed new activity name fails the new-name rules, for [reason]. */
    data class InvalidName(val reason: NewActivityNameCheck.Reason) : ServiceRefusal

    /**
     * The typed new activity name normalizes to the name or an alias of the existing ACTIVE
     * activity [activityId]; the UI should offer that activity instead of creating a duplicate.
     */
    data class NameMatchesExistingActivity(val activityId: String) : ServiceRefusal
}

/**
 * Checks a user-chosen activity target: an [ActivityTarget.Existing] must exist and be ACTIVE;
 * an [ActivityTarget.New] name must pass [NewActivityNameCheck] and must not normalize to the
 * name or alias of any catalog activity. Returns the refusal, or null if the target is usable.
 * Reads only; never writes.
 */
internal suspend fun checkActivityTarget(repository: ActivityRepository, target: ActivityTarget): ServiceRefusal? =
    when (target) {
        is ActivityTarget.Existing -> {
            val activity = repository.getActivity(target.activityId)
            when {
                activity == null -> ServiceRefusal.ActivityNotFound
                activity.status != CanonicalActivityStatus.ACTIVE -> ServiceRefusal.ActivityNotActive
                else -> null
            }
        }
        is ActivityTarget.New -> checkNewActivityName(repository, target.displayName)
    }

/** The shared new-name check used by both user-facing services. See [checkActivityTarget]. */
internal suspend fun checkNewActivityName(repository: ActivityRepository, displayName: String): ServiceRefusal? {
    val check = NewActivityNameCheck.check(displayName)
    if (check is NewActivityNameCheck.Result.Invalid) return ServiceRefusal.InvalidName(check.reason)
    val normalized = NameNormalizer.normalize(displayName)
    val match = repository.loadCatalog().firstOrNull { entry ->
        entry.normalizedName == normalized || entry.normalizedAliases.any { it == normalized }
    }
    return match?.let { ServiceRefusal.NameMatchesExistingActivity(it.id) }
}
