package com.mcfrenchpants.activityledger.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource

/**
 * An alias for an action tag; unique per (action, normalized alias).
 * `normalized_alias` is the TagNormalizer key of `alias_text`.
 */
@Entity(
    tableName = "action_aliases",
    foreignKeys = [
        ForeignKey(
            entity = ActionEntity::class,
            parentColumns = ["id"],
            childColumns = ["action_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["action_id", "normalized_alias"], unique = true),
    ],
)
internal data class ActionAliasEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "action_id") val actionId: String,
    @ColumnInfo(name = "alias_text") val aliasText: String,
    @ColumnInfo(name = "normalized_alias") val normalizedAlias: String,
    @ColumnInfo(name = "source") val source: AliasSource,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
