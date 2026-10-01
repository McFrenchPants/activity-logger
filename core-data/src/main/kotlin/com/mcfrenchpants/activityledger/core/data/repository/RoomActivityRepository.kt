package com.mcfrenchpants.activityledger.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.dao.HistoryRow
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.data.id.IdFactory
import com.mcfrenchpants.activityledger.core.data.ledger.AcceptInterpretationRequest
import com.mcfrenchpants.activityledger.core.data.ledger.ActivityLedgerWriter
import com.mcfrenchpants.activityledger.core.data.ledger.NewCanonicalActivity
import com.mcfrenchpants.activityledger.core.data.ledger.NewInterpretation
import com.mcfrenchpants.activityledger.core.data.ledger.OccurrenceChanges
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityView
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionChanges
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryEntry
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryOccurrence
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.OccurrenceView
import com.mcfrenchpants.activityledger.core.domain.repository.StoredCapture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [ActivityRepository] on top of the Room ledger. Enforces data integrity only (see the
 * error contract on [ActivityRepository]); product policy lives in the domain layer.
 *
 * Every function runs its blocking DAO work on [dispatcher]. Instants are stored as epoch
 * milliseconds and zones as IANA id strings. Rows this class creates take their timestamps
 * from [clock], except corrections, which use the caller's `now`.
 *
 * No logging. Exception messages carry ids and enum names only.
 */
internal class RoomActivityRepository(
    private val database: ActivityLedgerDatabase,
    private val idFactory: IdFactory,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ActivityRepository {

    private val writer = ActivityLedgerWriter(database, idFactory)

    override suspend fun createRawCapture(capture: NewRawCapture): String = io {
        val supplied = capture.id
        require(supplied == null || supplied.isNotBlank()) { "supplied raw capture id must not be blank" }
        val id = supplied ?: idFactory.newId()
        val now = clock.millis()
        // Exists-check and insert share one transaction, so concurrent deliveries of the same
        // id serialize: the second sees the first's row and writes nothing.
        database.runInTransaction<String> {
            val dao = database.rawCaptureDao()
            if (supplied == null || dao.getById(id) == null) {
                dao.insert(
                    RawCaptureEntity(
                        id = id,
                        source = capture.source,
                        sourceSurface = capture.sourceSurface,
                        capturedAt = capture.capturedAt.toEpochMilli(),
                        capturedZoneId = capture.zoneId.id,
                        rawText = capture.rawText,
                        speechConfidence = capture.speechConfidence,
                        speechAlternativesJson = capture.speechAlternativesJson,
                        processingState = capture.processingState,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            id
        }
    }

    override suspend fun getCapture(id: String): StoredCapture? = io {
        database.runInTransaction<StoredCapture?> {
            database.rawCaptureDao().getById(id)?.let { row ->
                StoredCapture(
                    id = row.id,
                    rawText = row.rawText,
                    source = row.source,
                    capturedAt = Instant.ofEpochMilli(row.capturedAt),
                    zoneId = ZoneId.of(row.capturedZoneId),
                    speechConfidence = row.speechConfidence,
                    processingState = row.processingState,
                    hasOccurrence = database.activityOccurrenceDao().getByRawCaptureId(row.id) != null,
                )
            }
        }
    }

    override suspend fun loadCatalog(): List<CatalogActivity> = io {
        database.runInTransaction<List<CatalogActivity>> {
            val activities = database.canonicalActivityDao().listActive()
            val aliases = database.activityAliasDao().listForActiveActivities()
                .groupBy({ it.canonicalActivityId }, { it.normalizedAlias })
            val lastOccurred = database.activityOccurrenceDao().lastActiveOccurredAtPerActivity()
                .associate { it.canonicalActivityId to it.lastOccurredAt }
            activities.map { activity ->
                CatalogActivity(
                    id = activity.id,
                    displayName = activity.displayName,
                    normalizedName = activity.normalizedName,
                    normalizedAliases = aliases[activity.id].orEmpty(),
                    lastOccurredAt = lastOccurred[activity.id]?.let(Instant::ofEpochMilli),
                )
            }
        }
    }

    override suspend fun recordOutcome(
        captureId: String,
        interpretation: InterpretationRecord?,
        processingState: ProcessingState,
    ) {
        io {
            translatingMissingReferences {
                writer.recordOutcome(captureId, interpretation?.toNew(captureId), processingState, clock.millis())
            }
        }
    }

    override suspend fun acceptInterpretation(
        captureId: String,
        interpretation: InterpretationRecord,
        target: ActivityTarget,
        occurredAt: Instant,
        timePrecision: TimePrecision,
        activityState: ActivityState,
    ): String = io {
        translatingMissingReferences {
            writer.acceptInterpretation(
                AcceptInterpretationRequest(
                    rawCaptureId = captureId,
                    interpretation = interpretation.toNew(captureId),
                    newActivity = (target as? ActivityTarget.New)?.toNew(),
                    canonicalActivityId = (target as? ActivityTarget.Existing)?.activityId,
                    occurredAt = occurredAt.toEpochMilli(),
                    timePrecision = timePrecision,
                    activityState = activityState,
                    now = clock.millis(),
                ),
            )
        }
    }

    override suspend fun applyCorrection(
        occurrenceId: String,
        changes: CorrectionChanges,
        source: CorrectionSource,
        reason: String?,
        now: Instant,
    ): CorrectionOutcome = io {
        val occurrenceChanges = OccurrenceChanges(
            canonicalActivityId = (changes.activity as? ActivityTarget.Existing)?.activityId,
            occurredAt = changes.occurredAt?.toEpochMilli(),
            timePrecision = changes.timePrecision,
            activityState = changes.activityState,
        )
        val nowMillis = now.toEpochMilli()
        val correctionId = translatingMissingReferences {
            when (val activity = changes.activity) {
                is ActivityTarget.New -> writer.applyCorrectionCreatingActivity(
                    occurrenceId, activity.toNew(), occurrenceChanges, source, reason, nowMillis,
                )
                is ActivityTarget.Existing, null ->
                    writer.applyCorrection(occurrenceId, occurrenceChanges, source, reason, nowMillis)
            }
        }
        if (correctionId == null) CorrectionOutcome.NothingChanged else CorrectionOutcome.Applied(correctionId)
    }

    override suspend fun getOccurrence(id: String): OccurrenceView? = io {
        database.activityOccurrenceDao().getById(id)?.toView()
    }

    override suspend fun getActivity(id: String): ActivityView? = io {
        database.canonicalActivityDao().getById(id)?.toView()
    }

    override suspend fun loadHistory(): List<HistoryEntry> = io {
        database.rawCaptureDao().loadHistory().map { it.toEntry() }
    }

    override suspend fun hideOccurrence(occurrenceId: String) {
        io { writer.hideOccurrence(occurrenceId, clock.millis()) }
    }

    // --- helpers -------------------------------------------------------------

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }

    /**
     * A foreign-key failure means a referenced row (activity, capture, ...) is missing: a
     * caller error under the repository contract, so it surfaces as IllegalArgumentException.
     * The transaction has already rolled back. Other constraint failures propagate unchanged.
     */
    private inline fun <T> translatingMissingReferences(block: () -> T): T =
        try {
            block()
        } catch (e: SQLiteConstraintException) {
            if (e.message?.contains("FOREIGN KEY", ignoreCase = true) == true) {
                throw IllegalArgumentException("A referenced row does not exist; nothing was written", e)
            }
            throw e
        }

    private fun InterpretationRecord.toNew(captureId: String) = NewInterpretation(
        rawCaptureId = captureId,
        createdAt = createdAt.toEpochMilli(),
        interpreterVersion = interpreterVersion,
        promptVersion = promptVersion,
        schemaVersion = schemaVersion,
        operation = operation,
        activityResolution = activityResolution,
        matchedActivityId = matchedActivityId,
        proposedCanonicalName = proposedCanonicalName,
        activityState = activityState,
        temporalExpression = temporalExpression,
        resolvedOccurredAt = resolvedOccurredAt?.toEpochMilli(),
        timePrecision = timePrecision,
        modelConfidenceBand = modelConfidenceBand,
        candidateContextHash = candidateContextHash,
        structuredResultJson = structuredResultJson,
        validationStatus = validationStatus,
        validationReason = validationReason,
    )

    private fun ActivityTarget.New.toNew() =
        NewCanonicalActivity(displayName = displayName, normalizedName = NameNormalizer.normalize(displayName))

    private fun ActivityOccurrenceEntity.toView() = OccurrenceView(
        id = id,
        canonicalActivityId = canonicalActivityId,
        rawCaptureId = rawCaptureId,
        effectiveInterpretationId = effectiveInterpretationId,
        capturedAt = Instant.ofEpochMilli(capturedAt),
        occurredAt = Instant.ofEpochMilli(occurredAt),
        timePrecision = timePrecision,
        activityState = activityState,
        visibilityStatus = visibilityStatus,
    )

    private fun HistoryRow.toEntry(): HistoryEntry {
        val occurrence = occurrenceId?.let { id ->
            HistoryOccurrence(
                occurrenceId = id,
                activityId = checkNotNull(activityId) { "Occurrence $id has no activity id" },
                activityDisplayName = checkNotNull(activityDisplayName) { "Occurrence $id has no activity" },
                occurredAt = Instant.ofEpochMilli(checkNotNull(occurredAt) { "Occurrence $id has no occurred_at" }),
                timePrecision = checkNotNull(timePrecision) { "Occurrence $id has no time_precision" },
                activityState = checkNotNull(activityState) { "Occurrence $id has no activity_state" },
            )
        }
        return HistoryEntry(
            captureId = captureId,
            rawText = rawText,
            source = source,
            capturedAt = Instant.ofEpochMilli(capturedAt),
            zoneId = ZoneId.of(capturedZoneId),
            processingState = processingState,
            occurrence = occurrence,
            pendingMatchedActivityId = if (occurrence == null) pendingMatchedActivityId else null,
        )
    }

    private fun CanonicalActivityEntity.toView() = ActivityView(
        id = id,
        displayName = displayName,
        normalizedName = normalizedName,
        status = status,
    )
}
