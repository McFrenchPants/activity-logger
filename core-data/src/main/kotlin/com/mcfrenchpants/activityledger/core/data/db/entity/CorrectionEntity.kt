package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision

/** Audit record of a change applied to an occurrence (previous/new values). */
@Entity(
    tableName = "corrections",
    foreignKeys = [
        ForeignKey(
            entity = ActivityOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["occurrence_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CanonicalActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["previous_canonical_activity_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CanonicalActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["new_canonical_activity_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = InterpretationEntity::class,
            parentColumns = ["id"],
            childColumns = ["previous_effective_interpretation_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = InterpretationEntity::class,
            parentColumns = ["id"],
            childColumns = ["new_effective_interpretation_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["occurrence_id"]),
        Index(value = ["previous_canonical_activity_id"]),
        Index(value = ["new_canonical_activity_id"]),
        Index(value = ["previous_effective_interpretation_id"]),
        Index(value = ["new_effective_interpretation_id"]),
    ],
)
internal data class CorrectionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "source") val source: CorrectionSource,
    @ColumnInfo(name = "reason") val reason: String?,
    @ColumnInfo(name = "previous_canonical_activity_id") val previousCanonicalActivityId: String?,
    @ColumnInfo(name = "new_canonical_activity_id") val newCanonicalActivityId: String?,
    @ColumnInfo(name = "previous_occurred_at") val previousOccurredAt: Long?,
    @ColumnInfo(name = "new_occurred_at") val newOccurredAt: Long?,
    @ColumnInfo(name = "previous_time_precision") val previousTimePrecision: TimePrecision?,
    @ColumnInfo(name = "new_time_precision") val newTimePrecision: TimePrecision?,
    @ColumnInfo(name = "previous_activity_state") val previousActivityState: ActivityState?,
    @ColumnInfo(name = "new_activity_state") val newActivityState: ActivityState?,
    @ColumnInfo(name = "previous_effective_interpretation_id") val previousEffectiveInterpretationId: String?,
    @ColumnInfo(name = "new_effective_interpretation_id") val newEffectiveInterpretationId: String?,
)
