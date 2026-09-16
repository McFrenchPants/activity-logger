package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity

/** Read-only access to corrections; they are written only by [LedgerWriteDao.applyCorrection]. */
@Dao
internal interface CorrectionDao {
    /** Correction history of one occurrence, oldest first (id breaks ties). */
    @Query("SELECT * FROM corrections WHERE occurrence_id = :occurrenceId ORDER BY created_at ASC, id ASC")
    fun listForOccurrence(occurrenceId: String): List<CorrectionEntity>
}
