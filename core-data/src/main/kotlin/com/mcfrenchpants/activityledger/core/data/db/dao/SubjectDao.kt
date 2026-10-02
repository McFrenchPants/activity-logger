package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.SubjectEntity

/** Read/insert access to subject tags. No update or delete (see ActivityLedgerDatabase). */
@Dao
internal interface SubjectDao {
    @Insert
    fun insert(row: SubjectEntity)

    @Query("SELECT * FROM subjects WHERE id = :id")
    fun getById(id: String): SubjectEntity?

    /** Every ACTIVE subject, ordered by normalized name, then id. */
    @Query("SELECT * FROM subjects WHERE status = 'ACTIVE' ORDER BY normalized_name ASC, id ASC")
    fun listActive(): List<SubjectEntity>
}
