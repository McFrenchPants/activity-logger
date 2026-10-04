package com.mcfrenchpants.activityledger.ui.review

import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.services.TagRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tag refusals map to plain-words resources and never carry the owner's words. */
class RefusalMessagesTest {

    private val everyRefusal: List<TagRefusal> = listOf(
        TagRefusal.CaptureNotFound,
        TagRefusal.CaptureAlreadyHasOccurrence,
        TagRefusal.OccurrenceNotFound,
        TagRefusal.OccurrenceHidden,
        TagRefusal.OccurredAfterNow,
        TagRefusal.InvalidDuration,
        TagRefusal.TagNotFound,
        TagRefusal.BothTagsNeeded,
        TagRefusal.SameTag,
    ) + NewActivityNameCheck.Reason.entries.map { TagRefusal.InvalidName(it) }

    @Test
    fun everyRefusalHasAMessageAndNoneCarriesTheOwnersWords() {
        everyRefusal.forEach { refusal ->
            val message = tagRefusalMessage(refusal)
            // Only a number (the length limit) may be formatted into a message; never text.
            assertTrue("$refusal", message.args.all { it is Int })
        }
    }

    @Test
    fun specificRefusalsUseTheExpectedWording() {
        assertEquals(R.string.refusal_tag_not_found, tagRefusalMessage(TagRefusal.TagNotFound).text)
        assertEquals(
            R.string.refusal_capture_already_logged,
            tagRefusalMessage(TagRefusal.CaptureAlreadyHasOccurrence).text,
        )
        assertEquals(R.string.refusal_occurred_after_now, tagRefusalMessage(TagRefusal.OccurredAfterNow).text)
        assertEquals(
            R.string.refusal_name_filler_word,
            tagRefusalMessage(TagRefusal.InvalidName(NewActivityNameCheck.Reason.CONTAINS_FILLER_WORD)).text,
        )
        assertEquals(
            listOf<Any>(NewActivityNameCheck.MAX_LENGTH),
            tagRefusalMessage(TagRefusal.InvalidName(NewActivityNameCheck.Reason.TOO_LONG)).args,
        )
    }
}
