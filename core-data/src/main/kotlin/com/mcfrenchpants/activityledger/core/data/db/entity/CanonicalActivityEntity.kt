package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus

/**
 * A canonical activity. `normalized_name` is deliberately NOT unique: two
 * distinct activities may normalise to the same name.
 */
@Entity(
    tableName = "canonical_activities",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["merged_into_activity_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["normalized_name"]),
        Index(value = ["status"]),
        Index(value = ["merged_into_activity_id"]),
    ],
)
internal data class CanonicalActivityEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "status") val status: CanonicalActivityStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "merged_into_activity_id") val mergedIntoActivityId: String?,
)
