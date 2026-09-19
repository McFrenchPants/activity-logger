package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision

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

    /**
     * The capture history in ONE statement (never one query per row): every raw capture
     * that has an ACTIVE-visibility occurrence or no occurrence at all, joined to its
     * occurrence and that occurrence's CURRENT canonical activity. Captures whose occurrence
     * is HIDDEN are excluded.
     *
     * Newest first by the occurrence's occurred_at, or the capture's captured_at when it has
     * no occurrence; ties broken by capture id descending.
     *
     * pending_matched_activity_id is computed only for captures without an occurrence: the
     * matched_activity_id of the capture's most recently created interpretation (created_at
     * descending, id breaks ties), or null.
     */
    @Query(
        "SELECT raw_captures.id AS capture_id, raw_captures.raw_text AS raw_text, " +
            "raw_captures.source AS source, raw_captures.captured_at AS captured_at, " +
            "raw_captures.captured_zone_id AS captured_zone_id, " +
            "raw_captures.processing_state AS processing_state, " +
            "activity_occurrences.id AS occurrence_id, " +
            "activity_occurrences.canonical_activity_id AS activity_id, " +
            "canonical_activities.display_name AS activity_display_name, " +
            "activity_occurrences.occurred_at AS occurred_at, " +
            "activity_occurrences.time_precision AS time_precision, " +
            "activity_occurrences.activity_state AS activity_state, " +
            "CASE WHEN activity_occurrences.id IS NULL THEN (" +
            "SELECT interpretations.matched_activity_id FROM interpretations " +
            "WHERE interpretations.raw_capture_id = raw_captures.id " +
            "ORDER BY interpretations.created_at DESC, interpretations.id DESC LIMIT 1" +
            ") ELSE NULL END AS pending_matched_activity_id " +
            "FROM raw_captures " +
            "LEFT JOIN activity_occurrences ON activity_occurrences.raw_capture_id = raw_captures.id " +
            "LEFT JOIN canonical_activities ON canonical_activities.id = activity_occurrences.canonical_activity_id " +
            "WHERE activity_occurrences.id IS NULL OR activity_occurrences.visibility_status = 'ACTIVE' " +
            "ORDER BY COALESCE(activity_occurrences.occurred_at, raw_captures.captured_at) DESC, " +
            "raw_captures.id DESC",
    )
    fun loadHistory(): List<HistoryRow>
}

/**
 * Row of [RawCaptureDao.loadHistory]. The occurrence columns are all null for a capture
 * without an occurrence; [pendingMatchedActivityId] is always null for one with an occurrence.
 */
internal data class HistoryRow(
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "source") val source: CaptureSource,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "captured_zone_id") val capturedZoneId: String,
    @ColumnInfo(name = "processing_state") val processingState: ProcessingState,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String?,
    @ColumnInfo(name = "activity_id") val activityId: String?,
    @ColumnInfo(name = "activity_display_name") val activityDisplayName: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long?,
    @ColumnInfo(name = "time_precision") val timePrecision: TimePrecision?,
    @ColumnInfo(name = "activity_state") val activityState: ActivityState?,
    @ColumnInfo(name = "pending_matched_activity_id") val pendingMatchedActivityId: String?,
)
