package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource

/**
 * An alias for a subject tag; unique per (subject, normalized alias).
 * `normalized_alias` is the TagNormalizer key of `alias_text`.
 */
@Entity(
    tableName = "subject_aliases",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subject_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["subject_id", "normalized_alias"], unique = true),
    ],
)
internal data class SubjectAliasEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "alias_text") val aliasText: String,
    @ColumnInfo(name = "normalized_alias") val normalizedAlias: String,
    @ColumnInfo(name = "source") val source: AliasSource,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
