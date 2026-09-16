package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource

/** An alias for a canonical activity; unique per (activity, normalized alias). */
@Entity(
    tableName = "activity_aliases",
    foreignKeys = [
        ForeignKey(
            entity = CanonicalActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonical_activity_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["canonical_activity_id", "normalized_alias"], unique = true),
    ],
)
internal data class ActivityAliasEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "canonical_activity_id") val canonicalActivityId: String,
    @ColumnInfo(name = "alias_text") val aliasText: String,
    @ColumnInfo(name = "normalized_alias") val normalizedAlias: String,
    @ColumnInfo(name = "source") val source: AliasSource,
    @ColumnInfo(name = "confidence") val confidence: Double?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
