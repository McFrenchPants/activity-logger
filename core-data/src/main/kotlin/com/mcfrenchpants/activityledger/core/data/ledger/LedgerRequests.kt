package com.mcfrenchpants.activityledger.core.data.ledger

import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus

/**
 * An interpretation to store. It has no id: the id is always assigned by the
 * write operation. [rawCaptureId] must equal the capture being accepted.
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
