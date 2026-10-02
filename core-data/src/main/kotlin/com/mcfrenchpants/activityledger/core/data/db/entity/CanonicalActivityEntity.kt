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
 *
 * Since schema version 2 a canonical activity is also the *pair* of a subject
 * tag and an action tag (ADR-040). `subject_id` / `action_id` are nullable
 * because rows written by the v3 capture path carry no tags; the pair is unique
 * among tagged rows (SQLite treats NULLs as distinct, so untagged rows are not
 * constrained). Tightening to NOT NULL is a later cleanup once v3 is removed.
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
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ActionEntity::class,
            parentColumns = ["id"],
            childColumns = ["action_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["normalized_name"]),
        Index(value = ["status"]),
        Index(value = ["merged_into_activity_id"]),
        // Subject lookups are served by this index's prefix; no separate subject_id index.
        Index(value = ["subject_id", "action_id"], unique = true),
        Index(value = ["action_id"]),
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
    /** Subject tag of this pair (schema v2); null for rows written by the v3 path. */
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    /** Action tag of this pair (schema v2); null for rows written by the v3 path. */
    @ColumnInfo(name = "action_id") val actionId: String? = null,
)
