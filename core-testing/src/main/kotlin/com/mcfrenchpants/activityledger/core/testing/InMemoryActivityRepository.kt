package com.mcfrenchpants.activityledger.core.testing

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityView
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionChanges
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.OccurrenceView
import com.mcfrenchpants.activityledger.core.domain.repository.StoredCapture
import java.time.Clock
import java.time.Instant

/**
 * One correction recorded by [InMemoryActivityRepository]. For each field, previous/new are
 * both null when that field did not change.
 */
data class RecordedCorrection(
    val id: String,
    val occurrenceId: String,
    val source: CorrectionSource,
    val reason: String?,
    val createdAt: Instant,
    val previousActivityId: String?,
    val newActivityId: String?,
    val previousOccurredAt: Instant?,
    val newOccurredAt: Instant?,
    val previousTimePrecision: TimePrecision?,
    val newTimePrecision: TimePrecision?,
    val previousActivityState: ActivityState?,
    val newActivityState: ActivityState?,
)

/**
 * In-memory [ActivityRepository] for domain tests, honouring the interface's error contract:
 * every refusal throws [IllegalArgumentException] after validating everything and before
 * mutating anything, so a refused call writes nothing. Ids are deterministic per kind
 * ("capture-1", "interpretation-1", "activity-1", "occurrence-1", "correction-1").
 * Names are normalized with [NameNormalizer]. Not thread-safe.
 *
 * Besides the interface it offers test seeding ([seedActivity], [setVisibility]) and inspection
 * ([interpretationsFor], [corrections], [activities], [occurrences], [writeCount]). Seeding
 * helpers do not count as writes.
 */
class InMemoryActivityRepository(private val clock: Clock) : ActivityRepository {

    private data class Capture(val capture: NewRawCapture, var state: ProcessingState, var updatedAt: Instant)

    private data class Activity(
        val id: String,
        val displayName: String,
        val normalizedName: String,
        val status: CanonicalActivityStatus,
        val normalizedAliases: List<String>,
    )

    private val counters = mutableMapOf<String, Int>()
    private val captures = linkedMapOf<String, Capture>()
    private val activityRows = linkedMapOf<String, Activity>()
    private val interpretationRows = mutableListOf<Pair<String, InterpretationRecord>>()
    private val interpretationOwners = mutableMapOf<String, String>()
    private val occurrenceRows = linkedMapOf<String, OccurrenceView>()
    private val correctionRows = mutableListOf<RecordedCorrection>()

    /** Number of successful mutating interface calls (a call that writes nothing does not count). */
    var writeCount: Int = 0
        private set

    /** Every correction recorded so far, in insertion order. */
    val corrections: List<RecordedCorrection> get() = correctionRows.toList()

    /** Every activity (any status), in insertion order. */
    val activities: List<ActivityView> get() = activityRows.values.map { it.toView() }

    /** Every occurrence, in insertion order. */
    val occurrences: List<OccurrenceView> get() = occurrenceRows.values.toList()

    /** The interpretations stored for [captureId], in insertion order. */
    fun interpretationsFor(captureId: String): List<InterpretationRecord> =
        interpretationRows.filter { interpretationOwners[it.first] == captureId }.map { it.second }

    /** Creates an activity directly (test seeding; not counted as a write). Returns its id. */
    fun seedActivity(
        displayName: String,
        status: CanonicalActivityStatus = CanonicalActivityStatus.ACTIVE,
        aliases: List<String> = emptyList(),
    ): String {
        val id = nextId("activity")
        activityRows[id] = Activity(
            id, displayName, NameNormalizer.normalize(displayName), status, aliases.map(NameNormalizer::normalize),
        )
        return id
    }

    /** Sets an occurrence's visibility directly (test seeding; not counted as a write). */
    fun setVisibility(occurrenceId: String, visibility: VisibilityStatus) {
        val occurrence = requireNotNull(occurrenceRows[occurrenceId]) { "unknown occurrence $occurrenceId" }
        occurrenceRows[occurrenceId] = occurrence.copy(visibilityStatus = visibility)
    }

    override suspend fun createRawCapture(capture: NewRawCapture): String {
        val id = nextId("capture")
        captures[id] = Capture(capture, capture.processingState, clock.instant())
        writeCount++
        return id
    }

    override suspend fun getCapture(id: String): StoredCapture? = captures[id]?.let { row ->
        StoredCapture(
            id = id,
            rawText = row.capture.rawText,
            source = row.capture.source,
            capturedAt = row.capture.capturedAt,
            zoneId = row.capture.zoneId,
            speechConfidence = row.capture.speechConfidence,
            processingState = row.state,
            hasOccurrence = occurrenceForCapture(id) != null,
        )
    }

    override suspend fun loadCatalog(): List<CatalogActivity> =
        activityRows.values
            .filter { it.status == CanonicalActivityStatus.ACTIVE }
            .sortedWith(compareBy<Activity> { it.normalizedName }.thenBy { it.id })
            .map { activity ->
                CatalogActivity(
                    id = activity.id,
                    displayName = activity.displayName,
                    normalizedName = activity.normalizedName,
                    normalizedAliases = activity.normalizedAliases,
                    lastOccurredAt = occurrenceRows.values
                        .filter { it.canonicalActivityId == activity.id && it.visibilityStatus == VisibilityStatus.ACTIVE }
                        .maxOfOrNull { it.occurredAt },
                )
            }

    override suspend fun recordOutcome(
        captureId: String,
        interpretation: InterpretationRecord?,
        processingState: ProcessingState,
    ) {
        val capture = requireNotNull(captures[captureId]) { "unknown capture $captureId" }
        require(occurrenceForCapture(captureId) == null) { "capture $captureId already has an occurrence" }
        require(processingState in OUTCOME_STATES) { "processing state $processingState is not an outcome state" }
        interpretation?.let { requireReferencedRowsExist(it) }

        capture.state = processingState
        capture.updatedAt = clock.instant()
        interpretation?.let { storeInterpretation(captureId, it) }
        writeCount++
    }

    override suspend fun acceptInterpretation(
        captureId: String,
        interpretation: InterpretationRecord,
        target: ActivityTarget,
        occurredAt: Instant,
        timePrecision: TimePrecision,
        activityState: ActivityState,
    ): String {
        occurrenceForCapture(captureId)?.let { return it.id }
        val capture = requireNotNull(captures[captureId]) { "unknown capture $captureId" }
        if (target is ActivityTarget.Existing) requireActive(target.activityId)
        requireReferencedRowsExist(interpretation)

        val now = clock.instant()
        val interpretationId = storeInterpretation(captureId, interpretation)
        val activityId = when (target) {
            is ActivityTarget.Existing -> target.activityId
            is ActivityTarget.New -> createActivity(target.displayName)
        }
        val occurrenceId = nextId("occurrence")
        occurrenceRows[occurrenceId] = OccurrenceView(
            id = occurrenceId,
            canonicalActivityId = activityId,
            rawCaptureId = captureId,
            effectiveInterpretationId = interpretationId,
            capturedAt = capture.capture.capturedAt,
            occurredAt = occurredAt,
            timePrecision = timePrecision,
            activityState = activityState,
            visibilityStatus = VisibilityStatus.ACTIVE,
        )
        capture.state = ProcessingState.PERSISTED
        capture.updatedAt = now
        writeCount++
        return occurrenceId
    }

    override suspend fun applyCorrection(
        occurrenceId: String,
        changes: CorrectionChanges,
        source: CorrectionSource,
        reason: String?,
        now: Instant,
    ): CorrectionOutcome {
        val current = requireNotNull(occurrenceRows[occurrenceId]) { "unknown occurrence $occurrenceId" }
        val existingTarget = (changes.activity as? ActivityTarget.Existing)?.activityId
            ?.takeIf { it != current.canonicalActivityId }
        val newTarget = changes.activity as? ActivityTarget.New
        val newOccurredAt = changes.occurredAt?.takeIf { it != current.occurredAt }
        val newPrecision = changes.timePrecision?.takeIf { it != current.timePrecision }
        val newState = changes.activityState?.takeIf { it != current.activityState }
        if (existingTarget == null && newTarget == null && newOccurredAt == null && newPrecision == null && newState == null) {
            return CorrectionOutcome.NothingChanged
        }
        existingTarget?.let { requireActive(it) }

        val newActivityId = existingTarget ?: newTarget?.let { createActivity(it.displayName) }
        val correctionId = nextId("correction")
        correctionRows += RecordedCorrection(
            id = correctionId,
            occurrenceId = occurrenceId,
            source = source,
            reason = reason,
            createdAt = now,
            previousActivityId = newActivityId?.let { current.canonicalActivityId },
            newActivityId = newActivityId,
            previousOccurredAt = newOccurredAt?.let { current.occurredAt },
            newOccurredAt = newOccurredAt,
            previousTimePrecision = newPrecision?.let { current.timePrecision },
            newTimePrecision = newPrecision,
            previousActivityState = newState?.let { current.activityState },
            newActivityState = newState,
        )
        occurrenceRows[occurrenceId] = current.copy(
            canonicalActivityId = newActivityId ?: current.canonicalActivityId,
            occurredAt = newOccurredAt ?: current.occurredAt,
            timePrecision = newPrecision ?: current.timePrecision,
            activityState = newState ?: current.activityState,
        )
        writeCount++
        return CorrectionOutcome.Applied(correctionId)
    }

    override suspend fun getOccurrence(id: String): OccurrenceView? = occurrenceRows[id]

    override suspend fun getActivity(id: String): ActivityView? = activityRows[id]?.toView()

    // --- helpers -------------------------------------------------------------

    private fun nextId(kind: String): String {
        val n = (counters[kind] ?: 0) + 1
        counters[kind] = n
        return "$kind-$n"
    }

    private fun occurrenceForCapture(captureId: String): OccurrenceView? =
        occurrenceRows.values.firstOrNull { it.rawCaptureId == captureId }

    private fun requireActive(activityId: String) {
        val status = requireNotNull(activityRows[activityId]) { "unknown activity $activityId" }.status
        require(status == CanonicalActivityStatus.ACTIVE) { "activity $activityId is $status, not ACTIVE" }
    }

    /** Mirrors the database's foreign key from an interpretation's matched activity. */
    private fun requireReferencedRowsExist(interpretation: InterpretationRecord) {
        interpretation.matchedActivityId?.let { id ->
            require(activityRows.containsKey(id)) { "interpretation references unknown activity $id" }
        }
    }

    private fun storeInterpretation(captureId: String, record: InterpretationRecord): String {
        val id = nextId("interpretation")
        interpretationRows += id to record
        interpretationOwners[id] = captureId
        return id
    }

    private fun createActivity(displayName: String): String {
        val id = nextId("activity")
        activityRows[id] = Activity(
            id, displayName, NameNormalizer.normalize(displayName), CanonicalActivityStatus.ACTIVE, emptyList(),
        )
        return id
    }

    private fun Activity.toView() = ActivityView(id, displayName, normalizedName, status)

    private companion object {
        val OUTCOME_STATES = setOf(
            ProcessingState.NEEDS_REVIEW,
            ProcessingState.FAILED_RETRYABLE,
            ProcessingState.FAILED_FINAL,
        )
    }
}
