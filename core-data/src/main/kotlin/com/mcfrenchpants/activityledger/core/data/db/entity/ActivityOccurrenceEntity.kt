package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus

/** An accepted activity occurrence; at most one per raw capture. */
@Entity(
    tableName = "activity_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonical_activity_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = RawCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["raw_capture_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = InterpretationEntity::class,
            parentColumns = ["id"],
            childColumns = ["effective_interpretation_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["canonical_activity_id", "occurred_at"],
            orders = [Index.Order.ASC, Index.Order.DESC],
        ),
        Index(value = ["occurred_at"], orders = [Index.Order.DESC]),
        Index(value = ["raw_capture_id"], unique = true),
        Index(value = ["effective_interpretation_id"]),
    ],
)
internal data class ActivityOccurrenceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "canonical_activity_id") val canonicalActivityId: String,
    @ColumnInfo(name = "raw_capture_id") val rawCaptureId: String,
    @ColumnInfo(name = "effective_interpretation_id") val effectiveInterpretationId: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "time_precision") val timePrecision: TimePrecision,
    @ColumnInfo(name = "activity_state") val activityState: ActivityState,
    @ColumnInfo(name = "visibility_status") val visibilityStatus: VisibilityStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** How long the activity lasted, in seconds (schema v2); null when not stated or for v3-path rows. */
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long? = null,
)
