package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus

/** One interpretation attempt of a raw capture (a capture may have many). */
@Entity(
    tableName = "interpretations",
    foreignKeys = [
        ForeignKey(
            entity = RawCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["raw_capture_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CanonicalActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["matched_activity_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["raw_capture_id"]),
        Index(value = ["matched_activity_id"]),
    ],
)
internal data class InterpretationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "raw_capture_id") val rawCaptureId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "interpreter_version") val interpreterVersion: String,
    @ColumnInfo(name = "prompt_version") val promptVersion: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "operation") val operation: InterpretationOperation,
    @ColumnInfo(name = "activity_resolution") val activityResolution: ActivityResolution,
    @ColumnInfo(name = "matched_activity_id") val matchedActivityId: String?,
    @ColumnInfo(name = "proposed_canonical_name") val proposedCanonicalName: String?,
    @ColumnInfo(name = "activity_state") val activityState: ActivityState?,
    @ColumnInfo(name = "temporal_expression") val temporalExpression: String?,
    @ColumnInfo(name = "resolved_occurred_at") val resolvedOccurredAt: Long?,
    @ColumnInfo(name = "time_precision") val timePrecision: TimePrecision?,
    @ColumnInfo(name = "model_confidence_band") val modelConfidenceBand: ConfidenceBand?,
    @ColumnInfo(name = "candidate_context_hash") val candidateContextHash: String?,
    @ColumnInfo(name = "structured_result_json") val structuredResultJson: String?,
    @ColumnInfo(name = "validation_status") val validationStatus: ValidationStatus,
    @ColumnInfo(name = "validation_reason") val validationReason: String?,
    // Tag-path extraction (schema v2); all null for v3-path rows.
    /** The subject words the model extracted, verbatim. */
    @ColumnInfo(name = "extracted_subject") val extractedSubject: String? = null,
    /** The action words the model extracted, verbatim. */
    @ColumnInfo(name = "extracted_action") val extractedAction: String? = null,
    /** The duration phrase the model extracted, verbatim. */
    @ColumnInfo(name = "duration_expression") val durationExpression: String? = null,
    /** The duration resolved deterministically from [durationExpression], in seconds. */
    @ColumnInfo(name = "resolved_duration_seconds") val resolvedDurationSeconds: Long? = null,
)
