package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation

/**
 * Machine-readable reason an extraction response could not be decoded.
 *
 * Deliberately a closed set of field-shaped codes: a reason never carries, quotes or summarises
 * a captured value, so it is safe to persist or surface anywhere (AGENTS.md #11). The caller maps
 * any of these onto the domain's MALFORMED failure kind.
 */
internal enum class ExtractionDecodeFailure {
    /** `operation` was absent, empty or whitespace-only. */
    OPERATION_MISSING,

    /** `operation` was present but is not one of the permitted spellings. */
    OPERATION_UNRECOGNISED,

    /** `activityState` was present but is not one of the permitted spellings. */
    ACTIVITY_STATE_UNRECOGNISED,
}

/** Outcome of decoding one extraction response into a domain candidate. */
internal sealed interface ExtractionDecodeResult {
    /** The response had the shape the schema promised. The candidate is still untrusted. */
    data class Decoded(val candidate: ExtractionCandidate) : ExtractionDecodeResult

    /** The response broke the schema contract. */
    data class Failed(val reason: ExtractionDecodeFailure) : ExtractionDecodeResult
}

/**
 * Turns an [ExtractionResponse] into core-domain's [ExtractionCandidate].
 *
 * Pure and total: no clock, no I/O, no logging, no randomness, and it never throws for any
 * input. Failure is a returned [ExtractionDecodeResult.Failed] carrying only a reason code.
 *
 * Its entry point is `internal` on purpose: [ExtractionResponse] is an ML Kit-shaped type and
 * ADR-023 forbids one appearing in a hand-written public signature of this module.
 *
 * Shape only, on purpose. A LOG_ACTIVITY with neither subject nor action, a subject that is a
 * whole sentence, a QUERY_HISTORY: all decode successfully. Normalising the words, resolving
 * them against existing tags and judging whether the result makes sense is deterministic domain
 * logic (ADR-038, AGENTS.md #5); a second copy of those rules here would be a defect. There is
 * likewise no retry, repair or "fix the model's JSON" logic.
 */
internal object ExtractionResponseDecoder {

    internal fun decode(response: ExtractionResponse): ExtractionDecodeResult {
        val operation = matchEnum(response.operation, InterpretationOperation.entries)
            ?: return ExtractionDecodeResult.Failed(
                if (isAbsent(response.operation)) {
                    ExtractionDecodeFailure.OPERATION_MISSING
                } else {
                    ExtractionDecodeFailure.OPERATION_UNRECOGNISED
                },
            )

        // Optional enum: absent means "the model had nothing to say", but an unreadable spelling
        // means the schema contract is broken, so it fails rather than nulls out.
        val activityState = if (isAbsent(response.activityState)) {
            null
        } else {
            matchEnum(response.activityState, ActivityState.entries)
                ?: return ExtractionDecodeResult.Failed(
                    ExtractionDecodeFailure.ACTIVITY_STATE_UNRECOGNISED,
                )
        }

        return ExtractionDecodeResult.Decoded(
            ExtractionCandidate(
                operation = operation,
                subject = optionalText(response.subject),
                action = optionalText(response.action),
                activityState = activityState,
                temporalExpression = optionalText(response.temporalExpression),
                durationExpression = optionalText(response.durationExpression),
            ),
        )
    }

    /** True when the model supplied nothing usable at all for a field. */
    private fun isAbsent(raw: String?): Boolean = raw.isNullOrBlank()

    /**
     * Free text the domain keeps as the user's own words: surrounding whitespace is dropped, but
     * internal spacing, casing and punctuation are left exactly as received.
     */
    private fun optionalText(raw: String?): String? = raw?.trim()?.ifEmpty { null }

    /**
     * Case-insensitive, whitespace-tolerant match onto a domain enum constant. Nothing else is
     * normalised -- no punctuation stripping, no synonyms, no nearest-match guessing.
     */
    private fun <E : Enum<E>> matchEnum(raw: String?, entries: List<E>): E? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    }
}
