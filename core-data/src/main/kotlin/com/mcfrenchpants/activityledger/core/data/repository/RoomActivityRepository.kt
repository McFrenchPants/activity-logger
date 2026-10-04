package com.mcfrenchpants.activityledger.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.dao.ExtractedWordsRow
import com.mcfrenchpants.activityledger.core.data.db.dao.HistoryRow
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.data.id.IdFactory
import com.mcfrenchpants.activityledger.core.data.ledger.AcceptInterpretationRequest
import com.mcfrenchpants.activityledger.core.data.ledger.AcceptTaggedRequest
import com.mcfrenchpants.activityledger.core.data.ledger.ActivityLedgerWriter
import com.mcfrenchpants.activityledger.core.data.ledger.DurationSet
import com.mcfrenchpants.activityledger.core.data.ledger.NewCanonicalActivity
import com.mcfrenchpants.activityledger.core.data.ledger.TagCorrectionWrite
import com.mcfrenchpants.activityledger.core.data.ledger.NewInterpretation
import com.mcfrenchpants.activityledger.core.data.ledger.NewTagAlias
import com.mcfrenchpants.activityledger.core.data.ledger.OccurrenceChanges
import com.mcfrenchpants.activityledger.core.data.ledger.TagRef
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupEntry
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
import com.mcfrenchpants.activityledger.core.domain.repository.ExtractedWords
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryEntry
import com.mcfrenchpants.activityledger.core.domain.repository.HistoryOccurrence
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.OccurrenceView
import com.mcfrenchpants.activityledger.core.domain.repository.StoredCapture
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagNormalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [LedgerRepository] (activities plus subject + action tags) on top of the Room ledger.
 * Enforces data integrity only (see the error contracts on [ActivityRepository] and
 * [com.mcfrenchpants.activityledger.core.domain.repository.TagRepository]); product policy,
 * including any fuzzy tag matching, lives in the domain layer.
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
) : LedgerRepository {

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

    override suspend fun loadTagCatalog(): TagCatalog = io {
        // Five queries regardless of row counts; all in one read transaction for a consistent view.
        database.runInTransaction<TagCatalog> {
            val subjectAliases = database.subjectAliasDao().listForActiveSubjects()
                .groupBy({ it.subjectId }, { it.aliasText })
            val actionAliases = database.actionAliasDao().listForActiveActions()
                .groupBy({ it.actionId }, { it.aliasText })
            val subjects = database.subjectDao().listActive().map { row ->
                KnownTag(row.id, TagKind.SUBJECT, row.displayName, subjectAliases[row.id].orEmpty())
            }
            val actions = database.actionDao().listActive().map { row ->
                KnownTag(row.id, TagKind.ACTION, row.displayName, actionAliases[row.id].orEmpty())
            }
            val activeSubjectIds = subjects.mapTo(HashSet()) { it.id }
            val activeActionIds = actions.mapTo(HashSet()) { it.id }
            val pairs = database.canonicalActivityDao().listActive().mapNotNull { activity ->
                val subjectId = activity.subjectId
                val actionId = activity.actionId
                if (subjectId in activeSubjectIds && actionId in activeActionIds) {
                    KnownPair(checkNotNull(subjectId), checkNotNull(actionId))
                } else {
                    null
                }
            }
            TagCatalog(subjects = subjects, actions = actions, pairs = pairs)
        }
    }

    override suspend fun acceptTagged(request: TaggedAcceptRequest): String = io {
        translatingMissingReferences {
            writer.acceptTagged(
                AcceptTaggedRequest(
                    rawCaptureId = request.captureId,
                    interpretation = request.interpretation.toNew(request.captureId),
                    subject = request.subject.toRef(),
                    action = request.action.toRef(),
                    occurredAt = request.occurredAt.toEpochMilli(),
                    timePrecision = request.timePrecision,
                    activityState = request.activityState,
                    durationSeconds = request.durationSeconds,
                    subjectAlias = request.learnSubjectAlias?.toAlias(),
                    actionAlias = request.learnActionAlias?.toAlias(),
                    now = clock.millis(),
                ),
            )
        }
    }

    override suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome = io {
        val correctionId = translatingMissingReferences {
            writer.correctTags(
                TagCorrectionWrite(
                    occurrenceId = request.occurrenceId,
                    subject = request.subject?.toRef(),
                    action = request.action?.toRef(),
                    duration = request.duration?.let { DurationSet(it.seconds) },
                    subjectAlias = request.learnSubjectAlias?.toAlias(),
                    actionAlias = request.learnActionAlias?.toAlias(),
                    source = request.source,
                    reason = request.reason,
                    now = request.now.toEpochMilli(),
                ),
            )
        }
        if (correctionId == null) CorrectionOutcome.NothingChanged else CorrectionOutcome.Applied(correctionId)
    }

    override suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome = io {
        translatingConstraintFailures {
            writer.renameTag(kind, tagId, newDisplayName, TagNormalizer.key(newDisplayName), clock.millis())
        }
    }

    override suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome = io {
        translatingConstraintFailures { writer.mergeTags(kind, fromTagId, intoTagId, clock.millis()) }
    }

    override suspend fun loadExtractedWordsForCapture(captureId: String): ExtractedWords? = io {
        database.interpretationDao().latestExtractedWordsForCapture(captureId)?.toWords()
    }

    override suspend fun loadExtractedWordsForOccurrence(occurrenceId: String): ExtractedWords? = io {
        database.interpretationDao().extractedWordsForOccurrence(occurrenceId)?.toWords()
    }

    override suspend fun loadLookupEntries(): List<LookupEntry> = io {
        database.activityOccurrenceDao().loadLookupRows().map { row ->
            LookupEntry(
                occurrenceId = row.occurrenceId,
                subjectId = row.subjectId,
                subjectName = row.subjectName,
                actionId = row.actionId,
                actionName = row.actionName,
                occurredAt = Instant.ofEpochMilli(row.occurredAt),
                durationSeconds = row.durationSeconds,
            )
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

    /** Any constraint failure of a rename / merge is a caller error; the transaction rolled back. */
    private inline fun <T> translatingConstraintFailures(block: () -> T): T =
        try {
            block()
        } catch (e: SQLiteConstraintException) {
            throw IllegalArgumentException("A database constraint rejected the change; nothing was written", e)
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
        extractedSubject = extractedSubject,
        extractedAction = extractedAction,
        durationExpression = durationExpression,
        resolvedDurationSeconds = resolvedDurationSeconds,
    )

    private fun TagTarget.toRef(): TagRef = when (this) {
        is TagTarget.Existing -> TagRef.Existing(tagId)
        is TagTarget.New -> TagRef.New(displayName = displayName, key = TagNormalizer.key(displayName))
    }

    /** Null when the interpretation carries no extracted words at all (an untagged v3 entry). */
    private fun ExtractedWordsRow.toWords(): ExtractedWords? =
        if (extractedSubject == null && extractedAction == null && durationExpression == null) {
            null
        } else {
            ExtractedWords(extractedSubject, extractedAction, durationExpression)
        }

    private fun String.toAlias() = NewTagAlias(aliasText = trim(), key = TagNormalizer.key(this))

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
        durationSeconds = durationSeconds,
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
                subjectName = subjectName,
                actionName = actionName,
                durationSeconds = durationSeconds,
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
        subjectId = subjectId,
        actionId = actionId,
    )
}
