package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.ActionEntity

/** Read/insert access to action tags. No update or delete (see ActivityLedgerDatabase). */
@Dao
internal interface ActionDao {
    @Insert
    fun insert(row: ActionEntity)

    @Query("SELECT * FROM actions WHERE id = :id")
    fun getById(id: String): ActionEntity?

    /** Every ACTIVE action, ordered by normalized name, then id. */
    @Query("SELECT * FROM actions WHERE status = 'ACTIVE' ORDER BY normalized_name ASC, id ASC")
    fun listActive(): List<ActionEntity>
}
