package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision

/**
 * Read-only access to occurrences. Occurrences are written ONLY by the
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

    /**
     * Every ACTIVE-visibility occurrence on an ACTIVE tagged pair whose subject and action are
     * ACTIVE, with the CURRENT tag display names. Newest first, occurrence id descending as
     * tie-break. One JOIN query.
     */
    @Query(
        "SELECT activity_occurrences.id AS occurrence_id, subjects.id AS subject_id, " +
            "subjects.display_name AS subject_name, actions.id AS action_id, " +
            "actions.display_name AS action_name, activity_occurrences.occurred_at AS occurred_at, " +
            "activity_occurrences.duration_seconds AS duration_seconds " +
            "FROM activity_occurrences " +
            "JOIN canonical_activities ON canonical_activities.id = activity_occurrences.canonical_activity_id " +
            "JOIN subjects ON subjects.id = canonical_activities.subject_id " +
            "JOIN actions ON actions.id = canonical_activities.action_id " +
            "WHERE activity_occurrences.visibility_status = 'ACTIVE' " +
            "AND canonical_activities.status = 'ACTIVE' AND subjects.status = 'ACTIVE' " +
            "AND actions.status = 'ACTIVE' " +
            "ORDER BY activity_occurrences.occurred_at DESC, activity_occurrences.id DESC",
    )
    fun loadLookupRows(): List<LookupRow>

    /**
     * Every ACTIVE-visibility occurrence whose canonical activity is ACTIVE, tagged or not, with
     * the activity's display name, the CURRENT subject / action ids and names (null when the
     * activity has no such tag or the tag is not ACTIVE) and the raw capture's text. Newest
     * first, occurrence id descending as tie-break. One JOIN query; read only.
     */
    @Query(
        "SELECT activity_occurrences.id AS occurrence_id, " +
            "activity_occurrences.occurred_at AS occurred_at, " +
            "activity_occurrences.time_precision AS time_precision, " +
            "activity_occurrences.duration_seconds AS duration_seconds, " +
            "canonical_activities.id AS activity_id, " +
            "canonical_activities.display_name AS activity_name, " +
            "subjects.id AS subject_id, subjects.display_name AS subject_name, " +
            "actions.id AS action_id, actions.display_name AS action_name, " +
            "raw_captures.raw_text AS raw_text " +
            "FROM activity_occurrences " +
            "JOIN canonical_activities ON canonical_activities.id = activity_occurrences.canonical_activity_id " +
            "LEFT JOIN subjects ON subjects.id = canonical_activities.subject_id " +
            "AND subjects.status = 'ACTIVE' " +
            "LEFT JOIN actions ON actions.id = canonical_activities.action_id " +
            "AND actions.status = 'ACTIVE' " +
            "LEFT JOIN raw_captures ON raw_captures.id = activity_occurrences.raw_capture_id " +
            "WHERE activity_occurrences.visibility_status = 'ACTIVE' " +
            "AND canonical_activities.status = 'ACTIVE' " +
            "ORDER BY activity_occurrences.occurred_at DESC, activity_occurrences.id DESC",
    )
    fun loadExploreRows(): List<ExploreRow>
}

/** Row of [ActivityOccurrenceDao.loadLookupRows]. */
internal data class LookupRow(
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "subject_name") val subjectName: String,
    @ColumnInfo(name = "action_id") val actionId: String,
    @ColumnInfo(name = "action_name") val actionName: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long?,
)

/**
 * Row of [ActivityOccurrenceDao.loadExploreRows]. The subject / action columns are null for an
 * untagged activity or a non-ACTIVE tag; [rawText] is null only if the capture row is missing.
 */
internal data class ExploreRow(
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "time_precision") val timePrecision: TimePrecision,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long?,
    @ColumnInfo(name = "activity_id") val activityId: String,
    @ColumnInfo(name = "activity_name") val activityName: String,
    @ColumnInfo(name = "subject_id") val subjectId: String?,
    @ColumnInfo(name = "subject_name") val subjectName: String?,
    @ColumnInfo(name = "action_id") val actionId: String?,
    @ColumnInfo(name = "action_name") val actionName: String?,
    @ColumnInfo(name = "raw_text") val rawText: String?,
)

/** Row of [ActivityOccurrenceDao.lastActiveOccurredAtPerActivity]. */
internal data class ActivityLastOccurredAt(
    @ColumnInfo(name = "canonical_activity_id") val canonicalActivityId: String,
    @ColumnInfo(name = "last_occurred_at") val lastOccurredAt: Long,
)
