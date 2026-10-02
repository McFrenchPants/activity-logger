package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.ActionAliasEntity

/** Read/insert access to action aliases. No update or delete (see ActivityLedgerDatabase). */
@Dao
internal interface ActionAliasDao {
    @Insert
    fun insert(row: ActionAliasEntity)

    /**
     * Every alias of every ACTIVE action, grouped by action and, within one
     * action, oldest first (id breaks ties).
     */
    @Query(
        "SELECT action_aliases.* FROM action_aliases " +
            "INNER JOIN actions ON actions.id = action_aliases.action_id " +
            "WHERE actions.status = 'ACTIVE' " +
            "ORDER BY action_aliases.action_id ASC, action_aliases.created_at ASC, action_aliases.id ASC",
    )
    fun listForActiveActions(): List<ActionAliasEntity>
}
