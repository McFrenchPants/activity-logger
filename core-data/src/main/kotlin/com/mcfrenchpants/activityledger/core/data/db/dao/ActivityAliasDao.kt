package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityAliasEntity

@Dao
internal interface ActivityAliasDao {
    @Insert
    fun insert(row: ActivityAliasEntity)
}
