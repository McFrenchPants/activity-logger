package com.mcfrenchpants.activityledger.core.domain.repository

import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import java.time.Instant
import java.time.ZoneId

/**
 * An activity as offered for matching. By contract a catalog contains ACTIVE canonical
 * activities only; merged and archived activities are never included.
 *
 * @property normalizedName [displayName] passed through the name normalizer.
 * @property normalizedAliases The activity's aliases, normalized.
 * @property lastOccurredAt Most recent occurrence time, or null if none.
 */
data class CatalogActivity(
    val id: String,
    val displayName: String,
    val normalizedName: String,
    val normalizedAliases: List<String>,
    val lastOccurredAt: Instant?,
)

/**
 * A stored raw capture plus whether it already produced an occurrence.
 *
 * @property zoneId The IANA zone recorded at capture time.
 * @property hasOccurrence True if an occurrence already exists for this capture.
 */
data class StoredCapture(
    val id: String,
    val rawText: String,
    val source: CaptureSource,
    val capturedAt: Instant,
    val zoneId: ZoneId,
    val speechConfidence: Double?,
    val processingState: ProcessingState,
    val hasOccurrence: Boolean,
)

/** Read view of one activity occurrence. */
data class OccurrenceView(
    val id: String,
    val canonicalActivityId: String,
    val rawCaptureId: String,
    val effectiveInterpretationId: String,
    val capturedAt: Instant,
    val occurredAt: Instant,
    val timePrecision: TimePrecision,
    val activityState: ActivityState,
    val visibilityStatus: VisibilityStatus,
)

/** Read view of one canonical activity. */
data class ActivityView(
    val id: String,
    val displayName: String,
    val normalizedName: String,
    val status: CanonicalActivityStatus,
)

/**
 * A raw capture to be stored. Raw captures are immutable once written.
 *
 * @property sourceSurface Optional finer-grained origin (e.g. a specific entry point).
 * @property zoneId The IANA zone at capture time.
 * @property speechAlternativesJson Alternative speech hypotheses, as JSON, if any.
 */
data class NewRawCapture(
    val source: CaptureSource,
    val sourceSurface: String?,
    val capturedAt: Instant,
    val zoneId: ZoneId,
    val rawText: String,
    val speechConfidence: Double?,
    val speechAlternativesJson: String?,
    val processingState: ProcessingState,
)

/** Which canonical activity an occurrence should belong to. */
sealed interface ActivityTarget {
    /** An existing canonical activity, by id. */
    data class Existing(val activityId: String) : ActivityTarget

    /**
     * A new canonical activity with this display name. The repository normalizes the name
     * itself; callers never pass a normalized form.
     */
    data class New(val displayName: String) : ActivityTarget
}

/** Requested changes to an occurrence. A null field means "leave unchanged". */
data class CorrectionChanges(
    val activity: ActivityTarget? = null,
    val occurredAt: Instant? = null,
    val timePrecision: TimePrecision? = null,
    val activityState: ActivityState? = null,
)

/** Result of [ActivityRepository.applyCorrection]. */
sealed interface CorrectionOutcome {
    /** At least one value changed; a correction with [correctionId] was recorded. */
    data class Applied(val correctionId: String) : CorrectionOutcome

    /**
     * No requested value differed from the occurrence's current value (or nothing was
     * requested); nothing was written.
     */
    data object NothingChanged : CorrectionOutcome
}
