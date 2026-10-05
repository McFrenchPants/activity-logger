package com.mcfrenchpants.activityledger.core.domain.repository

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupEntry
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import java.time.Instant

/** Which tag (of one kind: subject or action) an entry should be saved under. */
sealed interface TagTarget {
    /** An existing tag, by id. It must be ACTIVE when the entry is saved. */
    data class Existing(val tagId: String) : TagTarget

    /**
     * A tag with this display name. The repository computes the comparison key itself
     * (TagNormalizer.key) and reuses an ACTIVE tag of the same kind with that key or alias key
     * before creating a new one; callers never pass a normalized form.
     */
    data class New(val displayName: String) : TagTarget
}

/**
 * Input of [TagRepository.acceptTagged]: save one capture as an occurrence of the subject +
 * action pair named by [subject] and [action].
 *
 * @property interpretation The interpretation to store; must be an interpretation of [captureId].
 * @property durationSeconds How long the activity lasted, in seconds; null or >= 0.
 * @property learnSubjectAlias Words to remember as an alias of the final subject tag (the user
 *   accepted a "did you mean ...?" suggestion), or null.
 * @property learnActionAlias Likewise for the final action tag, or null.
 */
data class TaggedAcceptRequest(
    val captureId: String,
    val interpretation: InterpretationRecord,
    val subject: TagTarget,
    val action: TagTarget,
    val occurredAt: Instant,
    val timePrecision: TimePrecision,
    val activityState: ActivityState,
    val durationSeconds: Long?,
    val learnSubjectAlias: String? = null,
    val learnActionAlias: String? = null,
)

/** A new duration for an entry: [seconds] (>= 0), or null to clear the duration. */
data class DurationChange(val seconds: Long?)

/**
 * Input of [TagRepository.correctTags]: change the tags and/or duration of one occurrence.
 *
 * @property subject The subject to move the entry to, or null to keep its current subject.
 * @property action Likewise for the action.
 * @property duration The new duration, or null to leave the duration alone (see [DurationChange]).
 * @property learnSubjectAlias Words to remember as an alias of the final subject tag, or null.
 *   The CALLER decides which words are safe to learn (see
 *   `com.mcfrenchpants.activityledger.core.domain.tagging.CorrectionAliases`); the repository
 *   applies no product policy and only skips aliases that are redundant for the final tag.
 * @property learnActionAlias Likewise for the final action tag.
 */
data class TagCorrectionRequest(
    val occurrenceId: String,
    val subject: TagTarget? = null,
    val action: TagTarget? = null,
    val duration: DurationChange? = null,
    val learnSubjectAlias: String? = null,
    val learnActionAlias: String? = null,
    val source: CorrectionSource,
    val reason: String?,
    val now: Instant,
)

/** Result of [TagRepository.renameTag]. */
sealed interface RenameOutcome {
    /** The tag now has the new display name (and its old name became an alias where needed). */
    data object Renamed : RenameOutcome

    /** The trimmed new name equals the current display name exactly; nothing was written. */
    data object NothingChanged : RenameOutcome

    /**
     * The new name's key equals the name key or an alias key of ANOTHER ACTIVE tag of the same
     * kind ([tagId], oldest first); nothing was written. The caller may offer a merge instead.
     */
    data class ConflictsWith(val tagId: String) : RenameOutcome
}

/**
 * Result of [TagRepository.mergeTags].
 *
 * @property movedOccurrences How many occurrences (visible and hidden) were moved to another
 *   pair, one correction row each.
 * @property mergedPairs How many ACTIVE pairs of the merged-away tag were marked MERGED.
 */
data class MergeOutcome(val movedOccurrences: Int, val mergedPairs: Int)

/**
 * The words the model extracted from a capture, as stored on an interpretation (verbatim).
 * Used only to decide which words may be learned as aliases (see
 * `tagging.CorrectionAliases`); never shown as a decision.
 *
 * @property subject The extracted subject words, or null.
 * @property action The extracted action words, or null.
 * @property durationExpression The extracted duration phrase, or null.
 */
data class ExtractedWords(val subject: String?, val action: String?, val durationExpression: String?)

/**
 * The domain's persistence contract for subject + action tags, implemented by the data layer.
 *
 * Names: callers always pass display names / the user's words. The repository computes
 * comparison keys itself (TagNormalizer.key). It applies data-integrity rules only (exact key
 * equality); fuzzy matching and every save/confirm/review decision belong to callers.
 *
 * Error contract (as on [ActivityRepository]):
 * - An unknown capture, an interpretation of another capture, an [TagTarget.Existing] tag that
 *   does not exist or is not ACTIVE (merged), a [TagTarget.New] name with a blank key, a negative
 *   duration, or an existing subject + action pair that is not ACTIVE all throw
 *   [IllegalArgumentException], and nothing is written -- not even a tag the other target
 *   would have created. A referenced row found missing only by the database itself is
 *   reported the same way.
 * - [acceptTagged] on a capture that already has an occurrence is not an error: it returns
 *   the existing occurrence id and writes nothing.
 * - Exception messages carry ids and enum names only, never raw text or tag names.
 */
interface TagRepository {
    /**
     * Returns every ACTIVE subject and action tag (with its aliases, oldest first) and every
     * ACTIVE subject + action pair whose two tags are both ACTIVE. Merged tags and untagged
     * (v3-path) activities are never included. Tags are ordered by comparison key, then id;
     * pairs by the activity's normalized name, then id. Uses a bounded number of queries.
     */
    suspend fun loadTagCatalog(): TagCatalog

    /**
     * Saves an entry under a subject tag and an action tag. In one transaction: resolves or
     * creates both tags (each among tags of its own kind), resolves or creates their pair,
     * stores the interpretation, creates the occurrence (with the duration) and marks the
     * capture PERSISTED, then records any requested aliases. Returns the occurrence id.
     *
     * A [TagTarget.New] name reuses, in order: an ACTIVE tag of the same kind whose comparison
     * key equals the name's key, else one with an alias of that key (oldest first, then lowest
     * id), else creates a new tag. So concurrent captures of "Wi-Fi" and "WiFi" converge on
     * one tag.
     *
     * Aliases ([TaggedAcceptRequest.learnSubjectAlias] / learnActionAlias) are recorded as
     * AI_CONFIRMED and silently skipped when blank, equal to the tag's own key, or already an
     * alias of it; a redundant alias never fails the save.
     *
     * Idempotent per capture: if an occurrence already exists for the capture, returns its id
     * and writes nothing (no tag, pair, alias or interpretation).
     *
     * @throws IllegalArgumentException as listed in the interface's error contract. Nothing is
     *   written.
     */
    suspend fun acceptTagged(request: TaggedAcceptRequest): String

    /**
     * Corrects the subject + action tags and/or the duration of an occurrence, in one
     * transaction. Targets resolve exactly as in [acceptTagged] (Existing: must exist, be of the
     * right kind and ACTIVE; New: same-key / alias convergence, else a new tag). A null
     * [TagCorrectionRequest.subject] / action keeps the occurrence's current tag; an untagged
     * (v3) entry can only be retagged with BOTH targets given. The final ACTIVE pair is found or
     * created; an existing non-ACTIVE pair is an error.
     *
     * If neither the pair nor the duration changes (including a New name that converges on the
     * current tag), returns [CorrectionOutcome.NothingChanged] and writes NOTHING -- no tag,
     * pair, alias or correction row. Otherwise writes ONE corrections row (previous/new activity
     * only if the pair changed; previous/new duration only if the duration changed), updates only
     * the occurrence's activity, duration and updated_at (never the raw capture, captured_at,
     * effective interpretation or visibility) and records the requested aliases as
     * USER_CORRECTION, skipping silently any that are blank, equal to the final tag's key, or
     * already its alias.
     *
     * Split of responsibility: the repository stores the alias words it is given; deciding which
     * words are safe to learn is the caller's product policy
     * (`tagging.CorrectionAliases.aliasesToLearn`).
     *
     * @throws IllegalArgumentException for an unknown occurrence, an unknown / wrong-kind /
     *   non-ACTIVE Existing tag, a blank New key, a half-specified correction of an untagged
     *   entry, a negative duration, or a non-ACTIVE existing pair. Nothing is written.
     */
    suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome

    /**
     * Renames a tag, in one transaction (ADR-042). The tag must exist in [kind]'s table and be
     * ACTIVE; [newDisplayName] must have a non-blank comparison key.
     *
     * - If the trimmed new name equals the current display name exactly: returns
     *   [RenameOutcome.NothingChanged], writes nothing.
     * - If the new key equals the name key or an alias key of ANOTHER ACTIVE tag of the same kind:
     *   returns [RenameOutcome.ConflictsWith] (oldest such tag first), writes nothing.
     * - Otherwise updates the tag's display name, key and updated_at; the old display name becomes
     *   an alias (source MANUAL) unless its key equals the new key or is already an alias of this
     *   tag; and the cached label of every pair using the tag is refreshed. Raw captures,
     *   interpretations, occurrences and corrections are untouched (a rename is not a correction).
     *
     * @throws IllegalArgumentException for an unknown, wrong-kind or MERGED tag, or a blank new
     *   name key. Nothing is written.
     */
    suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome

    /**
     * Merges tag [fromTagId] into [intoTagId] (same kind), in one transaction (ADR-042): the
     * source becomes MERGED; its name and aliases become aliases of the target (skipping blank,
     * equal-to-target and duplicate keys); each ACTIVE pair of the source is marked MERGED into
     * the (found or created) pair of the target with the same other tag; every occurrence on
     * those pairs, hidden or not, is moved with exactly one correction row (source USER, reason
     * [TAG_MERGE_REASON]). Raw captures and interpretations are untouched.
     *
     * @throws IllegalArgumentException if the two ids are equal, either tag is unknown, of the
     *   other kind or not ACTIVE, or a needed target pair exists but is not ACTIVE. Nothing is
     *   written.
     */
    suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome

    /**
     * The extracted words of the capture's most recently created interpretation that has any
     * extracted subject, action or duration expression set (newest first, id breaks ties), or
     * null if the capture is unknown or no interpretation has any. Read only; never throws for
     * an unknown id.
     */
    suspend fun loadExtractedWordsForCapture(captureId: String): ExtractedWords?

    /**
     * The extracted words of the occurrence's EFFECTIVE interpretation, or null if the
     * occurrence is unknown or that interpretation has no extracted words (an untagged v3
     * entry). Read only; never throws for an unknown id.
     */
    suspend fun loadExtractedWordsForOccurrence(occurrenceId: String): ExtractedWords?

    /**
     * Every logged entry that can answer a history lookup: each occurrence with ACTIVE
     * visibility (hidden / undone ones are excluded) whose canonical activity is a tagged pair
     * (subject and action both set) and whose pair, subject and action are all ACTIVE. Merged-away
     * tags and pairs never appear; a merge already moved their occurrences to the target pair, so
     * those occurrences appear under the target tag. Untagged (v3-path) entries are never
     * included. Names are the CURRENT display names. Ordered newest occurredAt first, occurrence
     * id descending as tie-break. Read only; an empty database yields an empty list. Uses one
     * query (no per-row queries).
     */
    suspend fun loadLookupEntries(): List<LookupEntry>

    /**
     * Every accepted, visible entry, for the Explore screen.
     *
     * Inclusion: each occurrence with ACTIVE visibility (hidden / undone ones are excluded) whose
     * canonical activity is ACTIVE. Captures still waiting or in review have no occurrence and
     * never appear. Unlike [loadLookupEntries], untagged (v3-path) activities ARE included.
     *
     * Fields:
     * - occurrenceId, occurredAt, timePrecision, durationSeconds: from the occurrence.
     * - activityId / activityName: the canonical activity's id and display name (always set).
     * - subjectId / subjectName and actionId / actionName: the canonical activity's subject /
     *   action and their CURRENT display names, read with LEFT JOIN semantics. A side is null when
     *   the canonical activity has no subject / action (old untagged rows), AND ALSO when that
     *   subject / action exists but is not ACTIVE (e.g. MERGED): a non-ACTIVE tag reads as null.
     * - rawText: the occurrence's raw capture text, returned unchanged.
     * - subjectAliases / actionAliases: the alias texts of that subject / action (oldest first);
     *   empty when it has none or when that side is null.
     *
     * Ordered newest occurredAt first, occurrence id descending as tie-break. Read only (writes
     * nothing, never logs); an empty database yields an empty list. Uses one query for the
     * entries plus at most one query per alias table (no per-row queries).
     */
    suspend fun loadExploreEntries(): List<ExploreEntry>
}

/** The fixed reason code stored on the correction rows a tag merge writes (never user text). */
const val TAG_MERGE_REASON: String = "TAG_MERGE"

/** The full ledger: the activity contract plus subject + action tags. */
interface LedgerRepository : ActivityRepository, TagRepository
