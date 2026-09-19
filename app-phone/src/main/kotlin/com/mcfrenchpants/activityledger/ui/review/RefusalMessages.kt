package com.mcfrenchpants.activityledger.ui.review

import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.services.ServiceRefusal

/**
 * The plain-words message for a refused correction or resolution. Every refusal tells the user
 * what happened and, where there is something to do, what to do instead.
 *
 * Shared by every screen that corrects or resolves (Log, History).
 *
 * @param existingActivityName For [ServiceRefusal.NameMatchesExistingActivity]: the display
 *   name of the activity the typed name matches, if it could be read.
 */
internal fun refusalMessage(refusal: ServiceRefusal, existingActivityName: String? = null): UserMessage =
    when (refusal) {
        ServiceRefusal.OccurrenceNotFound -> UserMessage(R.string.refusal_occurrence_not_found)
        ServiceRefusal.OccurrenceHidden -> UserMessage(R.string.refusal_occurrence_hidden)
        ServiceRefusal.CaptureNotFound -> UserMessage(R.string.refusal_capture_not_found)
        ServiceRefusal.CaptureAlreadyHasOccurrence -> UserMessage(R.string.refusal_capture_already_logged)
        ServiceRefusal.ActivityNotFound -> UserMessage(R.string.refusal_activity_not_found)
        ServiceRefusal.ActivityNotActive -> UserMessage(R.string.refusal_activity_not_active)
        ServiceRefusal.OccurredAfterNow -> UserMessage(R.string.refusal_occurred_after_now)
        is ServiceRefusal.NameMatchesExistingActivity ->
            if (existingActivityName != null) {
                UserMessage(R.string.refusal_name_matches_existing, listOf(existingActivityName))
            } else {
                UserMessage(R.string.refusal_name_matches_existing_unnamed)
            }
        is ServiceRefusal.InvalidName -> when (refusal.reason) {
            NewActivityNameCheck.Reason.EMPTY -> UserMessage(R.string.refusal_name_empty)
            NewActivityNameCheck.Reason.TOO_LONG ->
                UserMessage(R.string.refusal_name_too_long, listOf(NewActivityNameCheck.MAX_LENGTH))
            NewActivityNameCheck.Reason.NO_MEANINGFUL_TEXT -> UserMessage(R.string.refusal_name_no_meaningful_text)
            NewActivityNameCheck.Reason.DIGITS_ONLY -> UserMessage(R.string.refusal_name_digits_only)
            NewActivityNameCheck.Reason.FIRST_PERSON_NARRATIVE -> UserMessage(R.string.refusal_name_first_person)
            NewActivityNameCheck.Reason.CONTAINS_TIME_WORD -> UserMessage(R.string.refusal_name_time_word)
            NewActivityNameCheck.Reason.CONTAINS_COMPLETION_WORD -> UserMessage(R.string.refusal_name_completion_word)
            NewActivityNameCheck.Reason.CONTAINS_FILLER_WORD -> UserMessage(R.string.refusal_name_filler_word)
        }
    }

/**
 * [refusalMessage] for [refusal], first reading the matching activity's display name from
 * [repository] when the refusal is a name clash (so the message can name it).
 */
internal suspend fun refusalMessageFor(repository: ActivityRepository, refusal: ServiceRefusal): UserMessage {
    val existingName = (refusal as? ServiceRefusal.NameMatchesExistingActivity)
        ?.let { repository.getActivity(it.activityId)?.displayName }
    return refusalMessage(refusal, existingName)
}
