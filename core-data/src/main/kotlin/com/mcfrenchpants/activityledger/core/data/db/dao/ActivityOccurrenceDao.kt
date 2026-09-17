package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.ColumnInfo
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

    /** The occurrence created from raw capture [rawCaptureId] (at most one), or null. */
    @Query("SELECT * FROM activity_occurrences WHERE raw_capture_id = :rawCaptureId")
    fun getByRawCaptureId(rawCaptureId: String): ActivityOccurrenceEntity?

    /**
     * For every activity with at least one ACTIVE-visibility occurrence, the greatest
     * `occurred_at` among those occurrences. One grouped query (never one per activity);
     * activities with no ACTIVE occurrence are simply absent.
     */
    @Query(
        "SELECT canonical_activity_id, MAX(occurred_at) AS last_occurred_at FROM activity_occurrences " +
            "WHERE visibility_status = 'ACTIVE' GROUP BY canonical_activity_id",
    )
    fun lastActiveOccurredAtPerActivity(): List<ActivityLastOccurredAt>
}

/** Row of [ActivityOccurrenceDao.lastActiveOccurredAtPerActivity]. */
internal data class ActivityLastOccurredAt(
    @ColumnInfo(name = "canonical_activity_id") val canonicalActivityId: String,
    @ColumnInfo(name = "last_occurred_at") val lastOccurredAt: Long,
)
