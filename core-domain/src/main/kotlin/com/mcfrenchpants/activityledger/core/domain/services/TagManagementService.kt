package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind

/** Result of [TagManagementService.rename]. */
sealed interface TagRenameResult {
    /** The tag now has the new name. */
    data object Renamed : TagRenameResult

    /** The new name equals the current name; nothing was written. */
    data object NothingChanged : TagRenameResult

    /**
     * The new name already belongs to another active tag [otherTagId] of the same kind; nothing
     * was written. The screen can offer to merge the two instead.
     */
    data class NameInUse(val otherTagId: String) : TagRenameResult

    /** The rename was refused for [refusal]; nothing was written. */
    data class Refused(val refusal: TagRefusal) : TagRenameResult
}

/** Result of [TagManagementService.merge]. */
sealed interface TagMergeResult {
    /** The tags were merged: [movedOccurrences] entries moved, [mergedPairs] pairs retired. */
    data class Merged(val movedOccurrences: Int, val mergedPairs: Int) : TagMergeResult

    /** The merge was refused for [refusal]; nothing was written. */
    data class Refused(val refusal: TagRefusal) : TagMergeResult
}

/**
 * One active tag as the tag-management screen lists it.
 *
 * @property aliases Other names that mean this tag.
 * @property pairCount How many active subject + action pairs use this tag.
 */
data class ManagedTag(val id: String, val name: String, val aliases: List<String>, val pairCount: Int)

/**
 * Renames, merges and lists tags for the user (ADR-042 data layer, ADR-044). A rename conflict is
 * a result, not an exception. No logging; refusals carry no user words.
 */
class TagManagementService(private val repository: LedgerRepository) {

    /**
     * Renames tag [tagId] of [kind] to [newName] (trimmed). An invalid name is refused with
     * [TagRefusal.InvalidName]; an unknown, merged or wrong-kind tag with [TagRefusal.TagNotFound].
     */
    suspend fun rename(kind: TagKind, tagId: String, newName: String): TagRenameResult {
        val name = newName.trim()
        checkTagName(name)?.let { return TagRenameResult.Refused(it) }
        val outcome = try {
            repository.renameTag(kind, tagId, name)
        } catch (_: IllegalArgumentException) {
            return TagRenameResult.Refused(TagRefusal.TagNotFound)
        }
        return when (outcome) {
            RenameOutcome.Renamed -> TagRenameResult.Renamed
            RenameOutcome.NothingChanged -> TagRenameResult.NothingChanged
            is RenameOutcome.ConflictsWith -> TagRenameResult.NameInUse(outcome.tagId)
        }
    }

    /**
     * Merges [fromTagId] into [intoTagId] (same [kind]). The same tag is refused with
     * [TagRefusal.SameTag]; an unknown, merged or wrong-kind tag with [TagRefusal.TagNotFound].
     */
    suspend fun merge(kind: TagKind, fromTagId: String, intoTagId: String): TagMergeResult {
        if (fromTagId == intoTagId) return TagMergeResult.Refused(TagRefusal.SameTag)
        val outcome = try {
            repository.mergeTags(kind, fromTagId, intoTagId)
        } catch (_: IllegalArgumentException) {
            return TagMergeResult.Refused(TagRefusal.TagNotFound)
        }
        return TagMergeResult.Merged(outcome.movedOccurrences, outcome.mergedPairs)
    }

    /** Every active tag of [kind], in catalog (name) order, with its aliases and pair count. */
    suspend fun listTags(kind: TagKind): List<ManagedTag> {
        val catalog = repository.loadTagCatalog()
        return catalog.tagsOf(kind).map { tag ->
            val pairCount = catalog.pairs.count { pair ->
                when (kind) {
                    TagKind.SUBJECT -> pair.subjectId == tag.id
                    TagKind.ACTION -> pair.actionId == tag.id
                }
            }
            ManagedTag(tag.id, tag.displayName, tag.aliases, pairCount)
        }
    }
}
