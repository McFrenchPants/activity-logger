package com.mcfrenchpants.activityledger.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.data.id.IdFactory
import com.mcfrenchpants.activityledger.core.data.ledger.AcceptInterpretationRequest
import com.mcfrenchpants.activityledger.core.data.ledger.OccurrenceChanges
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus

/**
 * The ONLY place occurrences and corrections are written.
 *
 * Encapsulation: every primitive write (occurrence insert/update, correction
 * insert, and the copies of the interpretation/activity/raw-capture writes the
 * operations need) is `protected abstract`, so nothing outside this class (or
 * Room's generated subclass) can call it. The only callable entry points are the
 * two `@Transaction` operations below; Room runs each in ONE database
 * transaction, so any exception rolls back every write it made. Application code
 * reaches them through core.data.ledger.ActivityLedgerWriter, which supplies the
 * IdFactory.
 *
 * Exception messages carry ids and enum names only, never user-entered text.
 */
@Dao
internal abstract class LedgerWriteDao {

    // --- primitives (not callable from outside this class) -----------------

    @Query("SELECT * FROM raw_captures WHERE id = :id")
    protected abstract fun findRawCapture(id: String): RawCaptureEntity?

    @Query("SELECT * FROM activity_occurrences WHERE raw_capture_id = :rawCaptureId")
    protected abstract fun findOccurrenceForRawCapture(rawCaptureId: String): ActivityOccurrenceEntity?

    @Query("SELECT * FROM activity_occurrences WHERE id = :id")
    protected abstract fun findOccurrence(id: String): ActivityOccurrenceEntity?

    @Query("SELECT raw_capture_id FROM interpretations WHERE id = :interpretationId")
    protected abstract fun findRawCaptureIdOfInterpretation(interpretationId: String): String?

    @Insert
    protected abstract fun insertInterpretation(row: InterpretationEntity)

    @Insert
    protected abstract fun insertCanonicalActivity(row: CanonicalActivityEntity)

    @Insert
    protected abstract fun insertOccurrence(row: ActivityOccurrenceEntity)

    @Insert
    protected abstract fun insertCorrection(row: CorrectionEntity)

    @Query("UPDATE raw_captures SET processing_state = :state, updated_at = :updatedAt WHERE id = :id")
    protected abstract fun updateRawCaptureProcessingState(id: String, state: ProcessingState, updatedAt: Long): Int

    /** Touches only the correctable columns (never raw_capture_id, captured_at, created_at, visibility). */
    @Query(
        "UPDATE activity_occurrences SET canonical_activity_id = :canonicalActivityId, " +
            "occurred_at = :occurredAt, time_precision = :timePrecision, activity_state = :activityState, " +
            "effective_interpretation_id = :effectiveInterpretationId, updated_at = :updatedAt WHERE id = :id",
    )
    protected abstract fun updateOccurrenceCorrectableFields(
        id: String,
        canonicalActivityId: String,
        occurredAt: Long,
        timePrecision: TimePrecision,
        activityState: ActivityState,
        effectiveInterpretationId: String,
        updatedAt: Long,
    ): Int

    // --- transactional operations -------------------------------------------

    /**
     * Accepts an interpretation of a raw capture as its occurrence. Idempotent:
     * if the capture already has an occurrence, its id is returned and nothing
     * is written (the rest of the request, including any new activity, is ignored).
     *
     * Write order: interpretation, new canonical activity (if requested),
     * occurrence, raw capture processing_state = PERSISTED.
     *
     * @throws IllegalArgumentException if not exactly one of newActivity /
     *   canonicalActivityId is given, the interpretation names a different raw
     *   capture, or the raw capture does not exist. Nothing is written.
     * @throws android.database.sqlite.SQLiteConstraintException if a referenced row
     *   (e.g. the canonical activity) does not exist; everything is rolled back.
     */
    @Transaction
    open fun acceptInterpretation(idFactory: IdFactory, request: AcceptInterpretationRequest): String {
        findOccurrenceForRawCapture(request.rawCaptureId)?.let { return it.id }

        require((request.newActivity == null) != (request.canonicalActivityId == null)) {
            "Exactly one of newActivity and canonicalActivityId must be given (raw capture ${request.rawCaptureId})"
        }
        require(request.interpretation.rawCaptureId == request.rawCaptureId) {
            "Interpretation belongs to raw capture ${request.interpretation.rawCaptureId}, " +
                "not ${request.rawCaptureId}"
        }
        val capture = requireNotNull(findRawCapture(request.rawCaptureId)) {
            "Unknown raw capture ${request.rawCaptureId}"
        }

        val interpretationId = idFactory.newId()
        insertInterpretation(request.interpretation.toEntity(interpretationId))

        val newActivity = request.newActivity
        val activityId = if (newActivity != null) {
            val id = idFactory.newId()
            insertCanonicalActivity(
                CanonicalActivityEntity(
                    id = id,
                    displayName = newActivity.displayName,
                    normalizedName = newActivity.normalizedName,
                    status = CanonicalActivityStatus.ACTIVE,
                    createdAt = request.now,
                    updatedAt = request.now,
                    mergedIntoActivityId = null,
                ),
            )
            id
        } else {
            checkNotNull(request.canonicalActivityId)
        }

        val occurrenceId = idFactory.newId()
        insertOccurrence(
            ActivityOccurrenceEntity(
                id = occurrenceId,
                canonicalActivityId = activityId,
                rawCaptureId = capture.id,
                effectiveInterpretationId = interpretationId,
                capturedAt = capture.capturedAt,
                occurredAt = request.occurredAt,
                timePrecision = request.timePrecision,
                activityState = request.activityState,
                visibilityStatus = VisibilityStatus.ACTIVE,
                createdAt = request.now,
                updatedAt = request.now,
            ),
        )
        check(updateRawCaptureProcessingState(capture.id, ProcessingState.PERSISTED, request.now) == 1) {
            "Raw capture ${capture.id} was not updated"
        }
        return occurrenceId
    }

    /**
     * Applies a correction to an occurrence and records it in ONE corrections row:
     * for each field whose requested value is non-null and differs from the
     * current value, previous = current and new = requested; unchanged fields are
     * null on both sides.
     *
     * Returns the new correction id, or **null if no requested field differs from
     * the current value** -- in that case nothing at all is written.
     *
     * @throws IllegalArgumentException if the occurrence does not exist, or the
     *   requested effective interpretation is unknown or belongs to another raw
     *   capture. Nothing is written.
     * @throws android.database.sqlite.SQLiteConstraintException if the requested
     *   canonical activity does not exist; everything is rolled back.
     */
    @Transaction
    open fun applyCorrection(
        idFactory: IdFactory,
        occurrenceId: String,
        changes: OccurrenceChanges,
        source: CorrectionSource,
        reason: String?,
        now: Long,
    ): String? {
        val current = requireNotNull(findOccurrence(occurrenceId)) { "Unknown occurrence $occurrenceId" }

        val newActivity = changes.canonicalActivityId?.takeIf { it != current.canonicalActivityId }
        val newOccurredAt = changes.occurredAt?.takeIf { it != current.occurredAt }
        val newPrecision = changes.timePrecision?.takeIf { it != current.timePrecision }
        val newState = changes.activityState?.takeIf { it != current.activityState }
        val newInterpretation = changes.effectiveInterpretationId?.takeIf { it != current.effectiveInterpretationId }

        if (newActivity == null && newOccurredAt == null && newPrecision == null &&
            newState == null && newInterpretation == null
        ) {
            return null
        }
        if (newInterpretation != null) {
            val owner = findRawCaptureIdOfInterpretation(newInterpretation)
            require(owner == current.rawCaptureId) {
                "Interpretation $newInterpretation does not belong to raw capture ${current.rawCaptureId} " +
                    "of occurrence $occurrenceId"
            }
        }

        val correctionId = idFactory.newId()
        insertCorrection(
            CorrectionEntity(
                id = correctionId,
                occurrenceId = occurrenceId,
                createdAt = now,
                source = source,
                reason = reason,
                previousCanonicalActivityId = newActivity?.let { current.canonicalActivityId },
                newCanonicalActivityId = newActivity,
                previousOccurredAt = newOccurredAt?.let { current.occurredAt },
                newOccurredAt = newOccurredAt,
                previousTimePrecision = newPrecision?.let { current.timePrecision },
                newTimePrecision = newPrecision,
                previousActivityState = newState?.let { current.activityState },
                newActivityState = newState,
                previousEffectiveInterpretationId = newInterpretation?.let { current.effectiveInterpretationId },
                newEffectiveInterpretationId = newInterpretation,
            ),
        )
        val updated = updateOccurrenceCorrectableFields(
            id = occurrenceId,
            canonicalActivityId = newActivity ?: current.canonicalActivityId,
            occurredAt = newOccurredAt ?: current.occurredAt,
            timePrecision = newPrecision ?: current.timePrecision,
            activityState = newState ?: current.activityState,
            effectiveInterpretationId = newInterpretation ?: current.effectiveInterpretationId,
            updatedAt = now,
        )
        check(updated == 1) { "Occurrence $occurrenceId was not updated" }
        return correctionId
    }
}
