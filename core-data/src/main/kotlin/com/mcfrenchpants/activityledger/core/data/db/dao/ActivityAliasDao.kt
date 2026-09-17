package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityAliasEntity

@Dao
internal interface ActivityAliasDao {
    @Insert
    fun insert(row: ActivityAliasEntity)

    /**
     * Every alias of every ACTIVE canonical activity, grouped by activity and, within
     * one activity, oldest first (id breaks ties).
     */
    @Query(
        "SELECT activity_aliases.* FROM activity_aliases " +
            "INNER JOIN canonical_activities ON canonical_activities.id = activity_aliases.canonical_activity_id " +
            "WHERE canonical_activities.status = 'ACTIVE' " +
            "ORDER BY activity_aliases.canonical_activity_id ASC, activity_aliases.created_at ASC, activity_aliases.id ASC",
    )
    fun listForActiveActivities(): List<ActivityAliasEntity>
}
