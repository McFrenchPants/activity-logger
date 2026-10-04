package com.mcfrenchpants.activityledger.core.data.ledger

import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus

/**
 * An interpretation to store. It has no id: the id is always assigned by the
 * write operation. [rawCaptureId] must equal the capture being accepted.
 * The four tag-path extraction fields (schema v2) default to null, as they are
 * for every v3-path interpretation.
 */
internal data class NewInterpretation(
    val rawCaptureId: String,
    val createdAt: Long,
    val interpreterVersion: String,
    val promptVersion: String,
    val schemaVersion: Int,
    val operation: InterpretationOperation,
    val activityResolution: ActivityResolution,
    val matchedActivityId: String?,
    val proposedCanonicalName: String?,
    val activityState: ActivityState?,
    val temporalExpression: String?,
    val resolvedOccurredAt: Long?,
    val timePrecision: TimePrecision?,
    val modelConfidenceBand: ConfidenceBand?,
    val candidateContextHash: String?,
    val structuredResultJson: String?,
    val validationStatus: ValidationStatus,
    val validationReason: String?,
    val extractedSubject: String? = null,
    val extractedAction: String? = null,
    val durationExpression: String? = null,
    val resolvedDurationSeconds: Long? = null,
) {
    internal fun toEntity(id: String) = InterpretationEntity(
        id = id,
        rawCaptureId = rawCaptureId,
        createdAt = createdAt,
        interpreterVersion = interpreterVersion,
        promptVersion = promptVersion,
        schemaVersion = schemaVersion,
        operation = operation,
        activityResolution = activityResolution,
        matchedActivityId = matchedActivityId,
        proposedCanonicalName = proposedCanonicalName,
        activityState = activityState,
        temporalExpression = temporalExpression,
        resolvedOccurredAt = resolvedOccurredAt,
        timePrecision = timePrecision,
        modelConfidenceBand = modelConfidenceBand,
        candidateContextHash = candidateContextHash,
        structuredResultJson = structuredResultJson,
        validationStatus = validationStatus,
        validationReason = validationReason,
        extractedSubject = extractedSubject,
        extractedAction = extractedAction,
        durationExpression = durationExpression,
        resolvedDurationSeconds = resolvedDurationSeconds,
    )
}

/** A canonical activity to create alongside an accepted occurrence (created with status ACTIVE). */
internal data class NewCanonicalActivity(
    val displayName: String,
    val normalizedName: String,
)

/**
 * Input of [ActivityLedgerWriter.acceptInterpretation].
 *
 * Exactly one of [newActivity] and [canonicalActivityId] must be non-null: the
 * occurrence points at the newly created activity, or at the existing one.
 */
internal data class AcceptInterpretationRequest(
    val rawCaptureId: String,
    val interpretation: NewInterpretation,
    val newActivity: NewCanonicalActivity?,
    val canonicalActivityId: String?,
    val occurredAt: Long,
    val timePrecision: TimePrecision,
    val activityState: ActivityState,
    val now: Long,
)

/**
 * Requested changes to an occurrence. A null field means "not requested"; a
 * non-null field equal to the current value is also treated as no change.
 */
internal data class OccurrenceChanges(
    val canonicalActivityId: String? = null,
    val occurredAt: Long? = null,
    val timePrecision: TimePrecision? = null,
    val activityState: ActivityState? = null,
    val effectiveInterpretationId: String? = null,
)

/** A new duration for an occurrence: [seconds] (null or >= 0; null clears the duration). */
internal data class DurationSet(val seconds: Long?)

/**
 * Input of [ActivityLedgerWriter.correctTags]: a correction of an occurrence's subject + action
 * pair and/or duration. A null [subject] / [action] keeps the occurrence's current tag; a null
 * [duration] leaves the duration alone. [subjectAlias] / [actionAlias] are learned (as
 * USER_CORRECTION) for the final tags, subject to the skip rules of LedgerWriteDao.correctTags.
 */
internal data class TagCorrectionWrite(
    val occurrenceId: String,
    val subject: TagRef?,
    val action: TagRef?,
    val duration: DurationSet?,
    val subjectAlias: NewTagAlias?,
    val actionAlias: NewTagAlias?,
    val source: CorrectionSource,
    val reason: String?,
    val now: Long,
)

/**
 * One tag (subject or action) of a tagged entry, as the write operation receives it: either an
 * existing tag id, or a display name with its precomputed TagNormalizer key. Which table it
 * refers to is given by the field of [AcceptTaggedRequest] it appears in.
 */
internal sealed interface TagRef {
    /** An existing tag; it must exist in the right table and be ACTIVE. */
    data class Existing(val tagId: String) : TagRef

    /**
     * Find-or-create by [key] (must be non-blank): an ACTIVE tag with normalized_name == key,
     * else an ACTIVE tag with an alias of that key, else a new tag named [displayName].
     */
    data class New(val displayName: String, val key: String) : TagRef
}

/** An alias to remember for a tag: its text (trimmed) and its TagNormalizer key (may be blank: then skipped). */
internal data class NewTagAlias(
    val aliasText: String,
    val key: String,
)

/**
 * Input of [ActivityLedgerWriter.acceptTagged]: one capture saved as an occurrence of the
 * subject + action pair named by [subject] and [action].
 *
 * @property durationSeconds null or >= 0.
 * @property subjectAlias Alias to learn for the final subject tag, or null.
 * @property actionAlias Alias to learn for the final action tag, or null.
 */
internal data class AcceptTaggedRequest(
    val rawCaptureId: String,
    val interpretation: NewInterpretation,
    val subject: TagRef,
    val action: TagRef,
    val occurredAt: Long,
    val timePrecision: TimePrecision,
    val activityState: ActivityState,
    val durationSeconds: Long?,
    val subjectAlias: NewTagAlias?,
    val actionAlias: NewTagAlias?,
    val now: Long,
)
