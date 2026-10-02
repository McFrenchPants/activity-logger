package com.mcfrenchpants.activityledger.core.data.ledger

import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.id.IdFactory
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState

/**
 * The transactional write operations of the ledger. Occurrences and corrections
 * can be written ONLY through these functions: the underlying primitive
 * writes are `protected` inside LedgerWriteDao, whose only callable members are
 * the `@Transaction` operations this class delegates to.
 *
 * All functions are blocking (the DAOs are non-suspend) and must be called off
 * the main thread.
 */
internal class ActivityLedgerWriter(
    private val database: ActivityLedgerDatabase,
    private val idFactory: IdFactory,
) {
    /**
     * In one transaction: stores the interpretation, optionally creates a new
     * canonical activity, creates the occurrence and marks the raw capture
     * PERSISTED. Idempotent per raw capture: a repeat call returns the existing
     * occurrence id and writes nothing. Returns the occurrence id.
     *
     * @throws IllegalArgumentException for a malformed request (see LedgerWriteDao).
     * @throws android.database.sqlite.SQLiteConstraintException if a referenced row is missing;
     *   nothing is written.
     */
    fun acceptInterpretation(request: AcceptInterpretationRequest): String =
        database.ledgerWriteDao().acceptInterpretation(idFactory, request)

    /**
     * In one transaction: finds or creates the subject tag, the action tag and their pair,
     * stores the interpretation, creates the occurrence (with its duration), marks the raw
     * capture PERSISTED and records any requested AI_CONFIRMED aliases. Idempotent per raw
     * capture: a repeat call returns the existing occurrence id and writes nothing. Returns
     * the occurrence id.
     *
     * @throws IllegalArgumentException for a malformed request, an unknown or non-ACTIVE tag
     *   or pair (see LedgerWriteDao.acceptTagged); nothing is written.
     * @throws android.database.sqlite.SQLiteConstraintException if a referenced row is missing;
     *   nothing is written.
     */
    fun acceptTagged(request: AcceptTaggedRequest): String =
        database.ledgerWriteDao().acceptTagged(idFactory, request)

    /**
     * In one transaction: records one corrections row with previous/new values of
     * every field that actually changes, and updates the occurrence. Returns the
     * correction id, or **null if nothing would change** (then nothing is written).
     *
     * @throws IllegalArgumentException for an unknown occurrence, or an effective
     *   interpretation that is unknown or belongs to a different raw capture.
     */
    fun applyCorrection(
        occurrenceId: String,
        changes: OccurrenceChanges,
        source: CorrectionSource,
        reason: String?,
        now: Long,
    ): String? = database.ledgerWriteDao().applyCorrection(idFactory, occurrenceId, changes, source, reason, now)

    /**
     * In one transaction: creates [newActivity] (ACTIVE) and applies [changes] with the
     * occurrence moved to it, always recording one corrections row. [changes] must not
     * name a canonical activity. Returns the correction id.
     *
     * @throws IllegalArgumentException for an unknown occurrence or a malformed request;
     *   nothing is written.
     */
    fun applyCorrectionCreatingActivity(
        occurrenceId: String,
        newActivity: NewCanonicalActivity,
        changes: OccurrenceChanges,
        source: CorrectionSource,
        reason: String?,
        now: Long,
    ): String = database.ledgerWriteDao()
        .applyCorrectionCreatingActivity(idFactory, occurrenceId, newActivity, changes, source, reason, now)

    /**
     * In one transaction: optionally stores [interpretation] and sets the raw capture's
     * processing state to NEEDS_REVIEW, FAILED_RETRYABLE or FAILED_FINAL. Returns the new
     * interpretation id, or null if none was given.
     *
     * @throws IllegalArgumentException for an unknown capture, a capture that already has
     *   an occurrence, a disallowed state, or an interpretation of another capture.
     * @throws android.database.sqlite.SQLiteConstraintException if a referenced row is
     *   missing; nothing is written.
     */
    fun recordOutcome(
        rawCaptureId: String,
        interpretation: NewInterpretation?,
        processingState: ProcessingState,
        now: Long,
    ): String? = database.ledgerWriteDao().recordOutcome(idFactory, rawCaptureId, interpretation, processingState, now)

    /**
     * In one transaction: sets the occurrence's visibility ACTIVE -> HIDDEN with
     * updated_at = [now]. Returns true if it was hidden by this call, false if it was
     * already HIDDEN (nothing written). Never writes a corrections row.
     *
     * @throws IllegalArgumentException for an unknown occurrence; nothing is written.
     */
    fun hideOccurrence(occurrenceId: String, now: Long): Boolean =
        database.ledgerWriteDao().hideOccurrence(occurrenceId, now)
}
