package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity

@Dao
internal interface CanonicalActivityDao {
    @Insert
    fun insert(row: CanonicalActivityEntity)

    @Query("SELECT * FROM canonical_activities WHERE id = :id")
    fun getById(id: String): CanonicalActivityEntity?

    /** Every ACTIVE canonical activity, ordered by normalized name, then id. */
    @Query("SELECT * FROM canonical_activities WHERE status = 'ACTIVE' ORDER BY normalized_name ASC, id ASC")
    fun listActive(): List<CanonicalActivityEntity>
}
