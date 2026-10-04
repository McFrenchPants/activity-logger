package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.DurationChange
import com.mcfrenchpants.activityledger.core.domain.repository.ExtractedWords
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.CorrectionAliases
import com.mcfrenchpants.activityledger.core.domain.tagging.LearnedAliases
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import java.time.Clock

/** Result of [TaggedResolutionService.resolve]. */
sealed interface TaggedResolutionResult {
    /** The capture was logged as occurrence [occurrenceId]. */
    data class Resolved(val occurrenceId: String) : TaggedResolutionResult

    /** The resolution was refused for [refusal]; nothing was written. */
    data class Refused(val refusal: TagRefusal) : TaggedResolutionResult
}

/** Result of [TaggedCorrectionService.correct]. */
sealed interface TaggedCorrectionResult {
    /** The correction [correctionId] was recorded. */
    data class Applied(val correctionId: String) : TaggedCorrectionResult

    /** Nothing requested differed from the current values; nothing was written. */
    data object NothingChanged : TaggedCorrectionResult

    /** The request was refused for [refusal]; nothing was written. */
    data class Refused(val refusal: TagRefusal) : TaggedCorrectionResult
}

/**
 * Logs a capture that is waiting for the user (a confirm card, or a review) the way the user
 * chose: stores a fresh, VALID user-resolution interpretation and accepts it through
 * `acceptTagged`. Earlier interpretations of the capture are never modified. Aliases are
 * learned only as [CorrectionAliases] allows (ADR-041, ADR-044). No logging; refusals carry no
 * user words.
 */
class TaggedResolutionService(
    private val repository: LedgerRepository,
    private val clock: Clock,
) {

    /**
     * Resolves capture [captureId] to ([subject], [action]). [time] defaults to the capture
     * instant with [TimePrecision.INFERRED_NOW]. [words] default to the extracted words stored
     * for the capture. "Now" is read once from the clock; checks run before any write. A repository
     * [IllegalArgumentException] (a tag merged between the check and the save) propagates.
     */
    suspend fun resolve(
        captureId: String,
        subject: TagChoice,
        action: TagChoice,
        time: OccurrenceTime? = null,
        durationSeconds: Long? = null,
        activityState: ActivityState = ActivityState.COMPLETED,
        words: ExtractedWords? = null,
    ): TaggedResolutionResult {
        val now = clock.instant()
        val capture = repository.getCapture(captureId)
            ?: return TaggedResolutionResult.Refused(TagRefusal.CaptureNotFound)
        if (capture.hasOccurrence) return TaggedResolutionResult.Refused(TagRefusal.CaptureAlreadyHasOccurrence)
        val chosen = time ?: OccurrenceTime(capture.capturedAt, TimePrecision.INFERRED_NOW)
        if (chosen.occurredAt.isAfter(now)) return TaggedResolutionResult.Refused(TagRefusal.OccurredAfterNow)
        if (durationSeconds != null && durationSeconds < 0) {
            return TaggedResolutionResult.Refused(TagRefusal.InvalidDuration)
        }
        val catalog = repository.loadTagCatalog()
        checkTagChoice(TagKind.SUBJECT, subject, catalog)?.let { return TaggedResolutionResult.Refused(it) }
        checkTagChoice(TagKind.ACTION, action, catalog)?.let { return TaggedResolutionResult.Refused(it) }

        val extracted = words ?: repository.loadExtractedWordsForCapture(captureId)
        val aliases = CorrectionAliases.aliasesToLearn(
            extractedSubject = extracted?.subject,
            extractedAction = extracted?.action,
            previousSubjectId = null,
            previousActionId = null,
            newSubject = subject,
            newAction = action,
            catalog = catalog,
        )
        val record = InterpretationRecord(
            createdAt = now,
            interpreterVersion = USER_RESOLUTION_INTERPRETER_VERSION,
            promptVersion = USER_RESOLUTION_PROMPT_VERSION,
            schemaVersion = USER_RESOLUTION_SCHEMA_VERSION,
            operation = InterpretationOperation.LOG_ACTIVITY,
            activityResolution = ActivityResolution.UNRESOLVED,
            matchedActivityId = null,
            proposedCanonicalName = null,
            activityState = activityState,
            temporalExpression = null,
            resolvedOccurredAt = chosen.occurredAt,
            timePrecision = chosen.precision,
            modelConfidenceBand = null,
            candidateContextHash = null,
            structuredResultJson = null,
            validationStatus = ValidationStatus.VALID,
            validationReason = null,
            extractedSubject = extracted?.subject,
            extractedAction = extracted?.action,
            durationExpression = extracted?.durationExpression,
            resolvedDurationSeconds = durationSeconds,
        )
        val occurrenceId = repository.acceptTagged(
            TaggedAcceptRequest(
                captureId = captureId,
                interpretation = record,
                subject = subject.toTarget(),
                action = action.toTarget(),
                occurredAt = chosen.occurredAt,
                timePrecision = chosen.precision,
                activityState = activityState,
                durationSeconds = durationSeconds,
                learnSubjectAlias = aliases.subjectAlias,
                learnActionAlias = aliases.actionAlias,
            ),
        )
        return TaggedResolutionResult.Resolved(occurrenceId)
    }
}

/**
 * Applies user corrections of an entry's subject, action and/or duration through `correctTags`
 * (source USER). Raw captures never change. A side that does not change never learns an alias
 * (ADR-041, ADR-044). No logging; refusals carry no user words.
 */
class TaggedCorrectionService(
    private val repository: LedgerRepository,
    private val clock: Clock,
) {

    /**
     * Corrects occurrence [occurrenceId]: a null [subject] / [action] keeps the current tag, a null
     * [duration] leaves the duration alone. [reason] is recorded as given. "Now" is read once from
     * the clock; checks run before any write. An untagged (earlier-design) entry needs both tags.
     */
    suspend fun correct(
        occurrenceId: String,
        subject: TagChoice? = null,
        action: TagChoice? = null,
        duration: DurationChange? = null,
        reason: String? = null,
    ): TaggedCorrectionResult {
        val now = clock.instant()
        val occurrence = repository.getOccurrence(occurrenceId)
            ?: return TaggedCorrectionResult.Refused(TagRefusal.OccurrenceNotFound)
        if (occurrence.visibilityStatus == VisibilityStatus.HIDDEN) {
            return TaggedCorrectionResult.Refused(TagRefusal.OccurrenceHidden)
        }
        val seconds = duration?.seconds
        if (seconds != null && seconds < 0) return TaggedCorrectionResult.Refused(TagRefusal.InvalidDuration)

        val current = repository.getActivity(occurrence.canonicalActivityId)
        val previousSubjectId = current?.subjectId
        val previousActionId = current?.actionId
        val untagged = previousSubjectId == null || previousActionId == null
        if (untagged && (subject == null || action == null)) {
            return TaggedCorrectionResult.Refused(TagRefusal.BothTagsNeeded)
        }

        val catalog = repository.loadTagCatalog()
        subject?.let { choice ->
            checkTagChoice(TagKind.SUBJECT, choice, catalog)?.let { return TaggedCorrectionResult.Refused(it) }
        }
        action?.let { choice ->
            checkTagChoice(TagKind.ACTION, choice, catalog)?.let { return TaggedCorrectionResult.Refused(it) }
        }

        val aliases = if (subject == null && action == null) {
            LearnedAliases.NONE
        } else {
            val words = repository.loadExtractedWordsForOccurrence(occurrenceId)
            CorrectionAliases.aliasesToLearn(
                extractedSubject = words?.subject,
                extractedAction = words?.action,
                previousSubjectId = previousSubjectId,
                previousActionId = previousActionId,
                newSubject = subject ?: TagChoice.Existing(checkNotNull(previousSubjectId)),
                newAction = action ?: TagChoice.Existing(checkNotNull(previousActionId)),
                catalog = catalog,
            )
        }
        val outcome = repository.correctTags(
            TagCorrectionRequest(
                occurrenceId = occurrenceId,
                subject = subject?.toTarget(),
                action = action?.toTarget(),
                duration = duration,
                learnSubjectAlias = aliases.subjectAlias,
                learnActionAlias = aliases.actionAlias,
                source = CorrectionSource.USER,
                reason = reason,
                now = now,
            ),
        )
        return when (outcome) {
            is CorrectionOutcome.Applied -> TaggedCorrectionResult.Applied(outcome.correctionId)
            CorrectionOutcome.NothingChanged -> TaggedCorrectionResult.NothingChanged
        }
    }
}
