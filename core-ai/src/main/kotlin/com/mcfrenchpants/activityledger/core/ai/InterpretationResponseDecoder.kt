package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation

/**
 * Machine-readable reason a model response could not be decoded.
 *
 * Deliberately a closed set of field-shaped codes: a reason never carries, quotes or
 * summarises a captured value, so it is safe to persist or surface anywhere (AGENTS.md
 * #11). The caller maps any of these onto the domain's MALFORMED failure kind.
 */
internal enum class InterpretationDecodeFailure {
    /** `operation` was absent, empty or whitespace-only. */
    OPERATION_MISSING,

    /** `operation` was present but is not one of the permitted spellings. */
    OPERATION_UNRECOGNISED,

    /** `activityResolution` was absent, empty or whitespace-only. */
    ACTIVITY_RESOLUTION_MISSING,

    /** `activityResolution` was present but is not one of the permitted spellings. */
    ACTIVITY_RESOLUTION_UNRECOGNISED,

    /** `activityState` was present but is not one of the permitted spellings. */
    ACTIVITY_STATE_UNRECOGNISED,

    /** `confidenceBand` was present but is not one of the permitted spellings. */
    CONFIDENCE_BAND_UNRECOGNISED,
}

/** Outcome of decoding one model response into a domain candidate. */
internal sealed interface InterpretationDecodeResult {
    /** The response had the shape the schema promised. The candidate is still untrusted. */
    data class Decoded(val candidate: InterpretationCandidate) : InterpretationDecodeResult

    /** The response broke the schema contract. */
    data class Failed(val reason: InterpretationDecodeFailure) : InterpretationDecodeResult
}

/**
 * Turns an [InterpretationResponse] into core-domain's [InterpretationCandidate].
 *
 * Pure and total: no clock, no I/O, no logging, no randomness, and it never throws for
 * any input. Failure is a returned [InterpretationDecodeResult.Failed] carrying only a
 * reason code.
 *
 * Its entry point is `internal` on purpose: [InterpretationResponse] is an ML Kit-shaped
 * type and ADR-023 forbids one appearing in a hand-written public signature of this
 * module.
 *
 * This decoder does NO semantic validation, on purpose. EXISTING_ACTIVITY with no
 * matched id, NEW_ACTIVITY with no proposed name, an id that was never offered, a
 * QUERY_HISTORY operation: all decode successfully. Judging whether a candidate makes
 * sense is `InterpretationValidator`'s job in core-domain (ADR-010, AGENTS.md #5), and
 * duplicating it here would be a second, divergent copy of the rules -- a defect, not
 * defence in depth. There is likewise no retry, repair or "fix the model's JSON" logic.
 */
internal object InterpretationResponseDecoder {

    internal fun decode(response: InterpretationResponse): InterpretationDecodeResult {
        val operation = matchEnum(response.operation, InterpretationOperation.entries)
            ?: return InterpretationDecodeResult.Failed(
                if (isAbsent(response.operation)) {
                    InterpretationDecodeFailure.OPERATION_MISSING
                } else {
                    InterpretationDecodeFailure.OPERATION_UNRECOGNISED
                },
            )

        val activityResolution =
            matchEnum(response.activityResolution, ActivityResolution.entries)
                ?: return InterpretationDecodeResult.Failed(
                    if (isAbsent(response.activityResolution)) {
                        InterpretationDecodeFailure.ACTIVITY_RESOLUTION_MISSING
                    } else {
                        InterpretationDecodeFailure.ACTIVITY_RESOLUTION_UNRECOGNISED
                    },
                )

        // Optional enums: absent means "the model had nothing to say", but an unreadable
        // spelling means the schema contract is broken, so it fails rather than nulls out.
        val activityState = if (isAbsent(response.activityState)) {
            null
        } else {
            matchEnum(response.activityState, ActivityState.entries)
                ?: return InterpretationDecodeResult.Failed(
                    InterpretationDecodeFailure.ACTIVITY_STATE_UNRECOGNISED,
                )
        }

        val confidenceBand = if (isAbsent(response.confidenceBand)) {
            null
        } else {
            matchEnum(response.confidenceBand, ConfidenceBand.entries)
                ?: return InterpretationDecodeResult.Failed(
                    InterpretationDecodeFailure.CONFIDENCE_BAND_UNRECOGNISED,
                )
        }

        return InterpretationDecodeResult.Decoded(
            InterpretationCandidate(
                operation = operation,
                activityResolution = activityResolution,
                matchedActivityId = optionalText(response.matchedActivityId),
                proposedCanonicalName = optionalText(response.proposedCanonicalName),
                activityState = activityState,
                temporalExpression = optionalText(response.temporalExpression),
                confidenceBand = confidenceBand,
            ),
        )
    }

    /** True when the model supplied nothing usable at all for a field. */
    private fun isAbsent(raw: String?): Boolean = raw.isNullOrBlank()

    /**
     * Free text the domain keeps as the user's own words: surrounding whitespace is
     * dropped, but internal spacing, casing and punctuation are left exactly as received.
     */
    private fun optionalText(raw: String?): String? = raw?.trim()?.ifEmpty { null }

    /**
     * Case-insensitive, whitespace-tolerant match onto a domain enum constant.
     *
     * Case-insensitivity is deliberate: the schema pins the spellings, but small-model
     * output drifts in case, and treating "log_activity" as unreadable would throw away a
     * correct answer over a cosmetic difference. Nothing else is normalised -- no
     * punctuation stripping, no synonyms, no nearest-match guessing.
     */
    private fun <E : Enum<E>> matchEnum(raw: String?, entries: List<E>): E? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    }
}
