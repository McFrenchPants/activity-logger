package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState

/**
 * Raw captures are write-once evidence: the only mutation offered is the
 * processing-state transition, implemented as an UPDATE that touches nothing
 * but `processing_state` and `updated_at`. `raw_text`, `source`, `captured_at`
 * and `captured_zone_id` can never be changed through this layer.
 */
@Dao
internal interface RawCaptureDao {
    /** Default ABORT conflict strategy: an existing id is an error, never an overwrite. */
    @Insert
    fun insert(row: RawCaptureEntity)

    /** Returns the number of rows updated (0 if the id does not exist). */
    @Query("UPDATE raw_captures SET processing_state = :state, updated_at = :updatedAt WHERE id = :id")
    fun updateProcessingState(id: String, state: ProcessingState, updatedAt: Long): Int

    @Query("SELECT * FROM raw_captures WHERE id = :id")
    fun getById(id: String): RawCaptureEntity?
}
