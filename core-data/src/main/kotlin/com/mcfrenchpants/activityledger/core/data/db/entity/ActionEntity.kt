package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.TagStatus

/**
 * An action tag: what was done (e.g. "change filter", "mow").
 * `normalized_name` is the TagNormalizer key of the display name and is
 * deliberately NOT unique: uniqueness among ACTIVE actions is a repository rule.
 */
@Entity(
    tableName = "actions",
    foreignKeys = [
        ForeignKey(
            entity = ActionEntity::class,
            parentColumns = ["id"],
            childColumns = ["merged_into_action_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["normalized_name"]),
        Index(value = ["status"]),
        Index(value = ["merged_into_action_id"]),
    ],
)
internal data class ActionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "status") val status: TagStatus,
    @ColumnInfo(name = "merged_into_action_id") val mergedIntoActionId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
