package com.mcfrenchpants.activityledger.core.domain.repository

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import java.time.Instant

/**
 * The domain's persistence contract for the activity ledger, implemented by the data layer.
 *
 * Names: callers always pass display names. The repository normalizes names itself
 * (with the domain name normalizer) wherever it stores or matches a normalized form.
 *
 * Error contract (data integrity only; product policy belongs to callers):
 * - An unknown capture or occurrence id passed to a write function, a target activity that
 *   does not exist or is not ACTIVE (merged or archived), [recordOutcome] on a capture that
 *   already has an occurrence, and [recordOutcome] with a state other than NEEDS_REVIEW,
 *   FAILED_RETRYABLE or FAILED_FINAL all throw [IllegalArgumentException], and nothing is
 *   written. This includes a referenced row (e.g. an interpretation's matched activity)
 *   found missing only by the database itself: the whole operation is rolled back and
 *   reported as [IllegalArgumentException].
 * - [acceptInterpretation] on a capture that already has an occurrence is not an error: it
 *   returns the existing occurrence id and writes nothing.
 * - Read functions return null for unknown ids; they never throw for them.
 * - Timestamps of rows the repository creates or updates (created_at / updated_at) come from
 *   the implementation's own clock, except in [applyCorrection], which uses the caller's `now`.
 * - Exception messages carry ids and enum names only, never raw text or activity names.
 */
interface ActivityRepository {
    /** Stores a new, immutable raw capture and returns its generated id. */
    suspend fun createRawCapture(capture: NewRawCapture): String

    /** Returns the capture with [id], or null if none exists. */
    suspend fun getCapture(id: String): StoredCapture?

    /** Returns every ACTIVE canonical activity (never merged or archived ones). */
    suspend fun loadCatalog(): List<CatalogActivity>

    /**
     * Records a non-accepting outcome for a capture: NEEDS_REVIEW, an INVALID interpretation,
     * or FAILED_RETRYABLE.
     *
     * Atomic: the optional [interpretation] row and the capture's new [processingState] are
     * written in one transaction, or neither is. Never creates an occurrence.
     *
     * @throws IllegalArgumentException if the capture is unknown, already has an occurrence,
     *   [processingState] is not NEEDS_REVIEW, FAILED_RETRYABLE or FAILED_FINAL, or the
     *   interpretation references a missing row. Nothing is written.
     */
    suspend fun recordOutcome(
        captureId: String,
        interpretation: InterpretationRecord?,
        processingState: ProcessingState,
    )

    /**
     * Accepts an interpretation. In one transaction: stores [interpretation], resolves the
     * existing [target] activity or creates the new one (normalizing its name itself), creates
     * the occurrence with that interpretation as effective, and marks the capture PERSISTED.
     * Returns the occurrence id.
     *
     * Idempotent per capture: if an occurrence already exists for [captureId], returns the
     * existing occurrence id and writes nothing.
     *
     * @throws IllegalArgumentException if the capture is unknown, or an [ActivityTarget.Existing]
     *   target (or another referenced row) does not exist or is not ACTIVE. Nothing is written.
     */
    suspend fun acceptInterpretation(
        captureId: String,
        interpretation: InterpretationRecord,
        target: ActivityTarget,
        occurredAt: Instant,
        timePrecision: TimePrecision,
        activityState: ActivityState,
    ): String

    /**
     * Applies [changes] to an occurrence atomically, recording a correction with [source],
     * [reason] and [now]. Raw captures are never modified. A [ActivityTarget.New] target is
     * normalized by the repository.
     *
     * Returns [CorrectionOutcome.NothingChanged] and writes nothing when no requested value
     * differs from the occurrence's current value (including when every field is null).
     *
     * @throws IllegalArgumentException if the occurrence is unknown, or a changed
     *   [ActivityTarget.Existing] activity does not exist or is not ACTIVE. Nothing is written.
     */
    suspend fun applyCorrection(
        occurrenceId: String,
        changes: CorrectionChanges,
        source: CorrectionSource,
        reason: String?,
        now: Instant,
    ): CorrectionOutcome

    /** Returns the occurrence with [id], or null if none exists. */
    suspend fun getOccurrence(id: String): OccurrenceView?

    /** Returns the canonical activity with [id] (any status), or null if none exists. */
    suspend fun getActivity(id: String): ActivityView?

    /**
     * Returns the capture history: one [HistoryEntry] per raw capture that either has an
     * occurrence with ACTIVE visibility or has no occurrence at all. A capture whose
     * occurrence is HIDDEN is omitted.
     *
     * Ordered newest first by the occurrence's occurred_at (captures with an occurrence) or
     * the capture's captured_at (captures without one); ties are broken by capture id,
     * descending. An occurrence is shown with its current canonical activity, so one moved by
     * a correction shows the corrected activity.
     *
     * Uses a bounded number of queries regardless of how many rows exist (never one per row).
     */
    suspend fun loadHistory(): List<HistoryEntry>

    /**
     * Hides an occurrence: sets its visibility ACTIVE -> HIDDEN and its updated_at from the
     * implementation's own clock. Already HIDDEN: a no-op that writes nothing.
     *
     * Never touches raw captures, interpretations, corrections or activities. Hiding is not a
     * correction: no correction is recorded.
     *
     * @throws IllegalArgumentException if the occurrence is unknown. Nothing is written.
     */
    suspend fun hideOccurrence(occurrenceId: String)
}
