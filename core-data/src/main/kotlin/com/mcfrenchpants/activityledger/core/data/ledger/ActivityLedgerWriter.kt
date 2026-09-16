package com.mcfrenchpants.activityledger.core.data.ledger

import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.id.IdFactory
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource

/**
 * The transactional write operations of the ledger. Occurrences and corrections
 * can be written ONLY through these two functions: the underlying primitive
 * writes are `protected` inside LedgerWriteDao, whose only callable members are
 * the two `@Transaction` operations this class delegates to.
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
}
