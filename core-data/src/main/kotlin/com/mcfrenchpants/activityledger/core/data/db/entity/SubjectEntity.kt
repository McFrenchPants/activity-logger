package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.TagStatus

/**
 * A subject tag: what an activity was done to (e.g. "furnace", "hot tub").
 * `normalized_name` is the TagNormalizer key of the display name and is
 * deliberately NOT unique: uniqueness among ACTIVE subjects is a repository rule.
 */
@Entity(
    tableName = "subjects",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["merged_into_subject_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["normalized_name"]),
        Index(value = ["status"]),
        Index(value = ["merged_into_subject_id"]),
    ],
)
internal data class SubjectEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "status") val status: TagStatus,
    @ColumnInfo(name = "merged_into_subject_id") val mergedIntoSubjectId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
