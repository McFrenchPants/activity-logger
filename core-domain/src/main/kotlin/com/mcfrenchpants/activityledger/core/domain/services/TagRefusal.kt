package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagNormalizer
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget

/**
 * Why a user-initiated tag request (resolving a capture, correcting an entry, renaming or
 * merging a tag) was refused. A refusal always means nothing was written. Carries ids and enum
 * values only, never user words.
 *
 * Deliberately separate from [ServiceRefusal]: that type belongs to the earlier activity
 * services and the phone's exhaustive `when` over it must keep compiling (ADR-044).
 */
sealed interface TagRefusal {
    /** The capture to resolve does not exist. */
    data object CaptureNotFound : TagRefusal

    /** The capture to resolve already produced an occurrence. */
    data object CaptureAlreadyHasOccurrence : TagRefusal

    /** The occurrence to correct does not exist. */
    data object OccurrenceNotFound : TagRefusal

    /** The occurrence to correct is hidden; hidden occurrences are not corrected. */
    data object OccurrenceHidden : TagRefusal

    /** The chosen occurrence time is after the current time. */
    data object OccurredAfterNow : TagRefusal

    /** The duration is negative. */
    data object InvalidDuration : TagRefusal

    /** A chosen existing tag does not exist, is merged away, or is of the other kind. */
    data object TagNotFound : TagRefusal

    /** A typed tag name fails the new-name rules, for [reason]. */
    data class InvalidName(val reason: NewActivityNameCheck.Reason) : TagRefusal

    /** An untagged (earlier-design) entry can only be retagged with both a subject and an action. */
    data object BothTagsNeeded : TagRefusal

    /** A merge was asked to merge a tag into itself. */
    data object SameTag : TagRefusal
}

/** Checks a typed tag name (trimmed first): the new-name rules, and a non-empty comparison key. */
internal fun checkTagName(name: String): TagRefusal.InvalidName? {
    val trimmed = name.trim()
    val check = NewActivityNameCheck.check(trimmed)
    if (check is NewActivityNameCheck.Result.Invalid) return TagRefusal.InvalidName(check.reason)
    if (TagNormalizer.key(trimmed).isEmpty()) return TagRefusal.InvalidName(NewActivityNameCheck.Reason.NO_MEANINGFUL_TEXT)
    return null
}

/**
 * Checks one user-chosen tag: an Existing id must be an ACTIVE tag of [kind] in [catalog]; a New
 * name must pass [checkTagName]. A New name equal to an existing ACTIVE tag is fine (the
 * repository converges it). Reads only.
 */
internal fun checkTagChoice(kind: TagKind, choice: TagChoice, catalog: TagCatalog): TagRefusal? = when (choice) {
    is TagChoice.Existing -> if (catalog.tag(kind, choice.tagId) == null) TagRefusal.TagNotFound else null
    is TagChoice.New -> checkTagName(choice.name)
}

/** Maps a choice to the repository's target, trimming a typed name. */
internal fun TagChoice.toTarget(): TagTarget = when (this) {
    is TagChoice.Existing -> TagTarget.Existing(tagId)
    is TagChoice.New -> TagTarget.New(name.trim())
}
