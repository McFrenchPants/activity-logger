package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity

/**
 * Read-only access to occurrences. Occurrences are written ONLY by the two
 * transactional operations in [LedgerWriteDao] (surfaced through
 * core.data.ledger.ActivityLedgerWriter).
 */
@Dao
internal interface ActivityOccurrenceDao {
    @Query("SELECT * FROM activity_occurrences WHERE id = :id")
    fun getById(id: String): ActivityOccurrenceEntity?

    /**
     * The ACTIVE occurrence of [activityId] with the greatest `occurred_at`, or null.
     * Served by index_activity_occurrences_canonical_activity_id_occurred_at.
     */
    @Query(
        "SELECT * FROM activity_occurrences WHERE canonical_activity_id = :activityId " +
            "AND visibility_status = 'ACTIVE' ORDER BY occurred_at DESC LIMIT 1",
    )
    fun latestOccurrenceOfActivity(activityId: String): ActivityOccurrenceEntity?
}
