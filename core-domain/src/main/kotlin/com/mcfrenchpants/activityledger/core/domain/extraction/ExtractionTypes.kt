package com.mcfrenchpants.activityledger.core.domain.extraction

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import java.time.Instant
import java.time.ZoneId

/*
 * Extraction: the subject + action redesign of the AI step (ADR-038).
 *
 * The model is asked ONLY to pull the user's own words out of a capture -- what was done
 * (action), what it was done to (subject), when, for how long, and whether it is finished. It
 * is never shown the user's existing tags or activities. Resolving those words against existing
 * tags is deterministic domain logic, not the model's job.
 *
 * These types sit beside the interpretation types (com.mcfrenchpants.activityledger.core.domain.
 * interpretation), which the app keeps using until the switch-over.
 */

/**
 * Everything an [ActivityExtractor] receives for a single capture.
 *
 * Deliberately has no candidate or tag list: the extractor works from the sentence alone.
 *
 * @property rawText The captured text, exactly as stored.
 * @property capturedAt When the capture happened.
 * @property zoneId The IANA zone the capture was made in.
 */
data class ExtractionInput(
    val rawText: String,
    val capturedAt: Instant,
    val zoneId: ZoneId,
)

/**
 * The words an [ActivityExtractor] pulled out of one capture.
 *
 * This is UNTRUSTED model output: nothing here is validated by construction, and having the
 * right shape says nothing about whether it makes sense. Normalising the words, resolving them
 * against existing tags and deciding what to do with the capture all belong to deterministic
 * domain logic, never to this type or to the extractor.
 *
 * @property operation What the model thinks the user asked for.
 * @property subject The thing acted on, in the user's own words (e.g. "hot tub"), if named.
 * @property action What was done, as a short verb phrase in the user's own words
 *   (e.g. "change filter"), if stated.
 * @property activityState Whether the activity was completed or is in progress, if stated.
 * @property temporalExpression The words saying WHEN it happened (e.g. "yesterday"), verbatim;
 *   resolved deterministically by the domain, never by the model.
 * @property durationExpression The words saying HOW LONG it took (e.g. "for half an hour"),
 *   verbatim. A duration is never a time.
 */
data class ExtractionCandidate(
    val operation: InterpretationOperation,
    val subject: String?,
    val action: String?,
    val activityState: ActivityState?,
    val temporalExpression: String?,
    val durationExpression: String?,
)

/** Outcome of one [ActivityExtractor.extract] call. */
sealed interface ExtractionResult {
    /**
     * The model produced a candidate with the promised shape (which may still be meaningless).
     *
     * @property candidate The untrusted extracted words.
     */
    data class Success(val candidate: ExtractionCandidate) : ExtractionResult

    /**
     * No usable candidate. Deliberately carries no user text, no model output and no exception
     * message: only the failure kind.
     *
     * @property kind Coarse reason the call failed.
     */
    data class Failure(val kind: InterpreterFailureKind) : ExtractionResult
}

/**
 * Pulls subject, action, time, duration and state words out of a capture's text.
 * Implementations run on the phone only. Callers must treat every result as untrusted.
 */
interface ActivityExtractor {
    /** Versions recorded with every extraction this extractor produces. */
    val provenance: InterpreterProvenance

    /**
     * Extracts from one capture. Implementations report failures as [ExtractionResult.Failure]
     * rather than throwing; cancellation of the calling coroutine propagates.
     */
    suspend fun extract(input: ExtractionInput): ExtractionResult
}
