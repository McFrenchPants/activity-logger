package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.ColumnInfo
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

    /**
     * The extracted words of the capture's most recently created interpretation that has any
     * extracted_* column set (newest first, id breaks ties), or null. Plain read.
     */
    @Query(
        "SELECT extracted_subject AS extractedSubject, extracted_action AS extractedAction, " +
            "duration_expression AS durationExpression FROM interpretations " +
            "WHERE raw_capture_id = :rawCaptureId AND (extracted_subject IS NOT NULL OR " +
            "extracted_action IS NOT NULL OR duration_expression IS NOT NULL) " +
            "ORDER BY created_at DESC, id DESC LIMIT 1",
    )
    fun latestExtractedWordsForCapture(rawCaptureId: String): ExtractedWordsRow?

    /** The extracted words of the occurrence's effective interpretation, or null. Plain read. */
    @Query(
        "SELECT i.extracted_subject AS extractedSubject, i.extracted_action AS extractedAction, " +
            "i.duration_expression AS durationExpression FROM activity_occurrences o " +
            "JOIN interpretations i ON i.id = o.effective_interpretation_id WHERE o.id = :occurrenceId",
    )
    fun extractedWordsForOccurrence(occurrenceId: String): ExtractedWordsRow?
}

/** Projection of the three extracted_* columns of one interpretation. */
internal data class ExtractedWordsRow(
    @ColumnInfo(name = "extractedSubject") val extractedSubject: String?,
    @ColumnInfo(name = "extractedAction") val extractedAction: String?,
    @ColumnInfo(name = "durationExpression") val durationExpression: String?,
)
