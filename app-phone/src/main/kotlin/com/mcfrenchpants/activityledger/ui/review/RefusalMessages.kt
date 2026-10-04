package com.mcfrenchpants.activityledger.ui.review

import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.services.TagRefusal

/**
 * The plain-words message for a refused tag change (subject or action), shared by the Log
 * screen's Save and Change buttons. Never contains the owner's words: a rejected new name is
 * explained by its reason only.
 */
internal fun tagRefusalMessage(refusal: TagRefusal): UserMessage = when (refusal) {
    TagRefusal.CaptureNotFound -> UserMessage(R.string.refusal_capture_not_found)
    TagRefusal.CaptureAlreadyHasOccurrence -> UserMessage(R.string.refusal_capture_already_logged)
    TagRefusal.OccurrenceNotFound -> UserMessage(R.string.refusal_occurrence_not_found)
    TagRefusal.OccurrenceHidden -> UserMessage(R.string.refusal_occurrence_hidden)
    TagRefusal.OccurredAfterNow -> UserMessage(R.string.refusal_occurred_after_now)
    TagRefusal.InvalidDuration -> UserMessage(R.string.refusal_tag_invalid_duration)
    TagRefusal.TagNotFound -> UserMessage(R.string.refusal_tag_not_found)
    TagRefusal.BothTagsNeeded -> UserMessage(R.string.refusal_tag_both_needed)
    TagRefusal.SameTag -> UserMessage(R.string.refusal_tag_same)
    is TagRefusal.InvalidName -> invalidNameMessage(refusal.reason)
}

private fun invalidNameMessage(reason: NewActivityNameCheck.Reason): UserMessage = when (reason) {
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
