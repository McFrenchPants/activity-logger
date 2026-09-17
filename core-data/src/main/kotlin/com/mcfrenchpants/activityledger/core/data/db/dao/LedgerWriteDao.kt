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
import com.mcfrenchpants.activityledger.core.data.ledger.NewCanonicalActivity
import com.mcfrenchpants.activityledger.core.data.ledger.NewInterpretation
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
 * four `@Transaction` operations below (acceptInterpretation, applyCorrection,
 * applyCorrectionCreatingActivity, recordOutcome); Room runs each in ONE database
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

    @Query("SELECT status FROM canonical_activities WHERE id = :activityId")
    protected abstract fun findCanonicalActivityStatus(activityId: String): CanonicalActivityStatus?

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
     *   capture, the raw capture does not exist, or the existing canonical activity
     *   is not ACTIVE (merged/archived). Nothing is written.
     * @throws android.database.sqlite.SQLiteConstraintException if a referenced row
     *   (e.g. the canonical activity) does not exist; everything is rolled back.
     *   (A missing activity deliberately stays a foreign-key failure here, not a
     *   precondition, so the historical contract of this operation is unchanged;
     *   the repository layer translates it.)
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
        request.canonicalActivityId?.let { activityId ->
            val status = findCanonicalActivityStatus(activityId)
            require(status == null || status == CanonicalActivityStatus.ACTIVE) {
                "Canonical activity $activityId is $status, not ACTIVE"
            }
        }

        val interpretationId = idFactory.newId()
        insertInterpretation(request.interpretation.toEntity(interpretationId))

        val newActivity = request.newActivity
        val activityId = if (newActivity != null) {
            insertNewActiveActivity(idFactory, newActivity, request.now)
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
     * @throws IllegalArgumentException if the occurrence does not exist, the
     *   requested effective interpretation is unknown or belongs to another raw
     *   capture, or a changed canonical activity does not exist or is not ACTIVE.
     *   Nothing is written.
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
        return correct(idFactory, current, changes, source, reason, now)
    }

    /**
     * In ONE transaction: creates [newActivity] as an ACTIVE canonical activity, then
     * applies [changes] with the occurrence's canonical activity set to the new id
     * (exactly as [applyCorrection]). Because the new activity always differs from the
     * current one, a corrections row is always written. Returns the correction id.
     *
     * Write order: canonical activity, correction, occurrence update.
     *
     * @throws IllegalArgumentException if [changes] already names a canonical activity,
     *   the occurrence does not exist, or the requested effective interpretation is
     *   invalid (see [applyCorrection]). Nothing is written.
     */
    @Transaction
    open fun applyCorrectionCreatingActivity(
        idFactory: IdFactory,
        occurrenceId: String,
        newActivity: NewCanonicalActivity,
        changes: OccurrenceChanges,
        source: CorrectionSource,
        reason: String?,
        now: Long,
    ): String {
        require(changes.canonicalActivityId == null) {
            "canonicalActivityId must be null when creating an activity (occurrence $occurrenceId)"
        }
        val current = requireNotNull(findOccurrence(occurrenceId)) { "Unknown occurrence $occurrenceId" }
        val activityId = insertNewActiveActivity(idFactory, newActivity, now)
        return checkNotNull(
            correct(idFactory, current, changes.copy(canonicalActivityId = activityId), source, reason, now),
        ) { "Correction of occurrence $occurrenceId to new activity $activityId wrote nothing" }
    }

    /**
     * Records a non-accepting outcome of a raw capture: optionally stores
     * [interpretation] and sets the capture's processing_state to [processingState]
     * (updated_at = [now]), in ONE transaction. Never creates an occurrence.
     * Returns the new interpretation id, or null if none was given.
     *
     * Write order: processing-state update, then interpretation insert (so a failing
     * interpretation insert provably rolls back the already-applied state change).
     *
     * @throws IllegalArgumentException if the raw capture does not exist, already has
     *   an occurrence (an accepted capture's outcome is never rewritten),
     *   [processingState] is not NEEDS_REVIEW, FAILED_RETRYABLE or FAILED_FINAL, or the
     *   interpretation names a different raw capture. Nothing is written.
     * @throws android.database.sqlite.SQLiteConstraintException if the interpretation
     *   references a missing row (e.g. matched activity); everything is rolled back.
     */
    @Transaction
    open fun recordOutcome(
        idFactory: IdFactory,
        rawCaptureId: String,
        interpretation: NewInterpretation?,
        processingState: ProcessingState,
        now: Long,
    ): String? {
        val capture = requireNotNull(findRawCapture(rawCaptureId)) { "Unknown raw capture $rawCaptureId" }
        require(findOccurrenceForRawCapture(capture.id) == null) {
            "Raw capture ${capture.id} already has an occurrence; its outcome cannot be rewritten"
        }
        require(processingState in OUTCOME_STATES) {
            "Processing state $processingState is not an outcome state (raw capture ${capture.id})"
        }
        if (interpretation != null) {
            require(interpretation.rawCaptureId == capture.id) {
                "Interpretation belongs to raw capture ${interpretation.rawCaptureId}, not ${capture.id}"
            }
        }

        check(updateRawCaptureProcessingState(capture.id, processingState, now) == 1) {
            "Raw capture ${capture.id} was not updated"
        }
        if (interpretation == null) return null
        val interpretationId = idFactory.newId()
        insertInterpretation(interpretation.toEntity(interpretationId))
        return interpretationId
    }

    // --- shared logic (not callable from outside this class) ----------------

    private fun insertNewActiveActivity(idFactory: IdFactory, newActivity: NewCanonicalActivity, now: Long): String {
        val id = idFactory.newId()
        insertCanonicalActivity(
            CanonicalActivityEntity(
                id = id,
                displayName = newActivity.displayName,
                normalizedName = newActivity.normalizedName,
                status = CanonicalActivityStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
                mergedIntoActivityId = null,
            ),
        )
        return id
    }

    /** The body of [applyCorrection] once the occurrence is known. Must run inside a transaction. */
    private fun correct(
        idFactory: IdFactory,
        current: ActivityOccurrenceEntity,
        changes: OccurrenceChanges,
        source: CorrectionSource,
        reason: String?,
        now: Long,
    ): String? {
        val occurrenceId = current.id
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
        if (newActivity != null) {
            val status = findCanonicalActivityStatus(newActivity)
            require(status == CanonicalActivityStatus.ACTIVE) {
                if (status == null) "Unknown canonical activity $newActivity"
                else "Canonical activity $newActivity is $status, not ACTIVE"
            }
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

/** The only processing states [LedgerWriteDao.recordOutcome] may set. */
private val OUTCOME_STATES = setOf(
    ProcessingState.NEEDS_REVIEW,
    ProcessingState.FAILED_RETRYABLE,
    ProcessingState.FAILED_FINAL,
)
