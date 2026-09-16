package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity

/** Interpretations are append-only: insert and read, never update or delete. */
@Dao
internal interface InterpretationDao {
    @Insert
    fun insert(row: InterpretationEntity)

    /** All interpretation attempts of one raw capture, oldest first (id breaks ties). */
    @Query("SELECT * FROM interpretations WHERE raw_capture_id = :rawCaptureId ORDER BY created_at ASC, id ASC")
    fun listForRawCapture(rawCaptureId: String): List<InterpretationEntity>
}
