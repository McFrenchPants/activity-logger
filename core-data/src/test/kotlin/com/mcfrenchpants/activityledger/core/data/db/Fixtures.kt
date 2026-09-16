package com.mcfrenchpants.activityledger.core.data.db

import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityAliasEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.ActivityOccurrenceEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CanonicalActivityEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.CorrectionEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.InterpretationEntity
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.AliasSource
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus

/** Fixture rows for schema tests. Synthetic text only. */
internal object Fixtures {
    const val T0 = 1_750_000_000_000L

    fun id(n: Int): String = "00000000-0000-7000-8000-%012d".format(n)

    fun rawCapture(id: String) = RawCaptureEntity(
        id = id,
        source = CaptureSource.WATCH_VOICE,
        sourceSurface = "fixture-surface",
        capturedAt = T0,
        capturedZoneId = "Europe/London",
        rawText = "fixture text",
        speechConfidence = 0.9,
        speechAlternativesJson = null,
        processingState = ProcessingState.PERSISTED,
        createdAt = T0,
        updatedAt = T0,
    )

    fun canonicalActivity(id: String, normalizedName: String = "fixture activity") = CanonicalActivityEntity(
        id = id,
        displayName = "Fixture activity",
        normalizedName = normalizedName,
        status = CanonicalActivityStatus.ACTIVE,
        createdAt = T0,
        updatedAt = T0,
        mergedIntoActivityId = null,
    )

    fun alias(id: String, activityId: String, normalizedAlias: String) = ActivityAliasEntity(
        id = id,
        canonicalActivityId = activityId,
        aliasText = normalizedAlias,
        normalizedAlias = normalizedAlias,
        source = AliasSource.MANUAL,
        confidence = null,
        createdAt = T0,
    )

    fun interpretation(id: String, rawCaptureId: String, matchedActivityId: String?) = InterpretationEntity(
        id = id,
        rawCaptureId = rawCaptureId,
        createdAt = T0,
        interpreterVersion = "fixture-interpreter",
        promptVersion = "fixture-prompt",
        schemaVersion = 1,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = ActivityResolution.EXISTING_ACTIVITY,
        matchedActivityId = matchedActivityId,
        proposedCanonicalName = null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = T0,
        timePrecision = TimePrecision.EXACT,
        modelConfidenceBand = ConfidenceBand.HIGH,
        candidateContextHash = null,
        structuredResultJson = null,
        validationStatus = ValidationStatus.VALID,
        validationReason = null,
    )

    fun occurrence(id: String, activityId: String, rawCaptureId: String, interpretationId: String) =
        ActivityOccurrenceEntity(
            id = id,
            canonicalActivityId = activityId,
            rawCaptureId = rawCaptureId,
            effectiveInterpretationId = interpretationId,
            capturedAt = T0,
            occurredAt = T0,
            timePrecision = TimePrecision.EXACT,
            activityState = ActivityState.COMPLETED,
            visibilityStatus = VisibilityStatus.ACTIVE,
            createdAt = T0,
            updatedAt = T0,
        )

    fun correction(
        id: String,
        occurrenceId: String,
        activityId: String,
        interpretationId: String,
    ) = CorrectionEntity(
        id = id,
        occurrenceId = occurrenceId,
        createdAt = T0,
        source = CorrectionSource.USER,
        reason = null,
        previousCanonicalActivityId = activityId,
        newCanonicalActivityId = activityId,
        previousOccurredAt = T0,
        newOccurredAt = T0 + 1,
        previousTimePrecision = TimePrecision.APPROXIMATE,
        newTimePrecision = TimePrecision.DATE_ONLY,
        previousActivityState = ActivityState.IN_PROGRESS,
        newActivityState = ActivityState.COMPLETED,
        previousEffectiveInterpretationId = interpretationId,
        newEffectiveInterpretationId = interpretationId,
    )
}
