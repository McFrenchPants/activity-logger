package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate

/**
 * Longest subject or action text kept, in characters. The extraction decoder has no cap of its
 * own; this matches the domain's longest permitted activity name (60) so a runaway model answer
 * cannot reach the domain as a paragraph.
 */
internal const val QUESTION_FIELD_MAX_LENGTH: Int = 60

/** Machine-readable reason a question response could not be decoded. Never carries a value. */
internal enum class QuestionDecodeFailure {
    /** Reserved: no response shape is currently unusable, as both fields are optional. */
    UNUSABLE,
}

/** Outcome of decoding one question response into a domain candidate. */
internal sealed interface QuestionDecodeResult {
    /** The response had the promised shape. The candidate is still untrusted. */
    data class Decoded(val candidate: QuestionCandidate) : QuestionDecodeResult

    /** The response broke the schema contract. */
    data class Failed(val reason: QuestionDecodeFailure) : QuestionDecodeResult
}

/**
 * Turns a [QuestionResponse] into core-domain's [QuestionCandidate].
 *
 * Pure and total: no clock, no I/O, no logging, and it never throws. Text is trimmed, blank
 * becomes null, and each field is capped at [QUESTION_FIELD_MAX_LENGTH]. Nothing else is
 * validated: resolving the words is domain logic (ADR-052). A question about nothing (both
 * fields null) is a valid result. With two optional free-text fields there is no shape that is
 * unusable, so [QuestionDecodeResult.Failed] is never produced today; it exists so the extractor
 * is wired like its activity twin.
 */
internal object QuestionResponseDecoder {

    internal fun decode(response: QuestionResponse): QuestionDecodeResult =
        QuestionDecodeResult.Decoded(
            QuestionCandidate(
                subject = clean(response.subject),
                action = clean(response.action),
            ),
        )

    private fun clean(raw: String?): String? =
        raw?.trim()?.take(QUESTION_FIELD_MAX_LENGTH)?.trim()?.ifEmpty { null }
}
