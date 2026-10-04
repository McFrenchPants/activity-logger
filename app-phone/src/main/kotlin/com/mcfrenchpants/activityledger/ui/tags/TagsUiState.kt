package com.mcfrenchpants.activityledger.ui.tags

import com.mcfrenchpants.activityledger.core.domain.services.ManagedTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.review.UserMessage

/** A tag named on a dialog: its id and the name shown on screen (never logged). */
data class TagRef(val id: String, val name: String)

/**
 * The rename dialog. [text] is what is typed; [conflict] is set once the service said the new
 * name already belongs to another tag, which also offers "Merge them instead".
 */
data class RenameDialog(
    val tag: TagRef,
    val text: String,
    val conflict: TagRef? = null,
) {
    /** Save needs a name that is not blank and not the current name (both after trimming). */
    val canSave: Boolean get() = text.trim().isNotEmpty() && text.trim() != tag.name
}

/** The "Merge into..." chooser for [from]. */
data class MergeChooser(val from: TagRef)

/** The merge confirmation: [from] will count as [into]. Nothing is written until confirmed. */
data class MergeConfirm(val from: TagRef, val into: TagRef)

/** A short confirmation shown on the screen after a change. */
sealed interface TagsNotice {
    data object Renamed : TagsNotice

    data class Merged(val movedEntries: Int) : TagsNotice
}

/** The Tags screen's single immutable UI state. */
data class TagsUiState(
    val kind: TagKind = TagKind.SUBJECT,
    val subjects: List<ManagedTag> = emptyList(),
    val actions: List<ManagedTag> = emptyList(),
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val rename: RenameDialog? = null,
    val chooser: MergeChooser? = null,
    val confirm: MergeConfirm? = null,
    val notice: TagsNotice? = null,
    /** A plain-words problem; shown in the open dialog, or on the screen when none is open. */
    val message: UserMessage? = null,
    val actionInFlight: Boolean = false,
) {
    /** The tags of the selected [kind], as the service listed them. */
    val tags: List<ManagedTag> get() = if (kind == TagKind.SUBJECT) subjects else actions

    val dialogOpen: Boolean get() = rename != null || chooser != null || confirm != null
}
