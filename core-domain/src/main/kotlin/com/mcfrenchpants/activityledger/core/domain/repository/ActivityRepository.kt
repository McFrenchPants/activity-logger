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
}
