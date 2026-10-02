package com.mcfrenchpants.activityledger.core.domain.repository

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
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
}

/** The full ledger: the activity contract plus subject + action tags. */
interface LedgerRepository : ActivityRepository, TagRepository
