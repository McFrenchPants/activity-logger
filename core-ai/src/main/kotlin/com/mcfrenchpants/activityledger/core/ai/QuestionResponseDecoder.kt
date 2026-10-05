package com.mcfrenchpants.activityledger.core.ai

import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import java.util.Locale

/**
 * Longest subject, action or date-words text kept, in characters. The extraction decoder has no
 * cap of its own; this matches the domain's longest permitted activity name (60) so a runaway
 * model answer cannot reach the domain as a paragraph.
 */
internal const val QUESTION_FIELD_MAX_LENGTH: Int = 60

/** Machine-readable reason a question response could not be decoded. Never carries a value. */
internal enum class QuestionDecodeFailure {
    /** Reserved: no response shape is currently unusable, as every field is optional. */
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
 * Turns a [QuestionResponse] (schema version 2) into core-domain's [QuestionCandidate].
 *
 * Pure and total: no clock, no I/O, no logging, and it never throws. Subject, action and date
 * words are trimmed, blank becomes null, and each is capped at [QUESTION_FIELD_MAX_LENGTH]. The
 * kind is trimmed and upper-cased (Locale.ROOT) and must then spell one of the four pinned kinds
 * exactly; null, blank or any other spelling becomes [QuestionKind.UNKNOWN] rather than a
 * failure. Nothing else is validated: resolving the words and the date words is domain logic
 * (ADR-052). A question about nothing (every field empty) is a valid result. With only optional
 * fields there is no shape that is unusable, so [QuestionDecodeResult.Failed] is never produced
 * today; it exists so the extractor is wired like its activity twin.
 */
internal object QuestionResponseDecoder {

    /** The model spellings of [QuestionResponse.kind], pinned by its `@Guide(enumValues = ...)`. */
    private val KINDS: Map<String, QuestionKind> = mapOf(
        "LAST_TIME" to QuestionKind.LAST_TIME,
        "COUNT" to QuestionKind.COUNT,
        "HOW_OFTEN" to QuestionKind.HOW_OFTEN,
        "LIST" to QuestionKind.LIST,
    )

    internal fun decode(response: QuestionResponse): QuestionDecodeResult =
        QuestionDecodeResult.Decoded(
            QuestionCandidate(
                subject = clean(response.subject),
                action = clean(response.action),
                dateWindow = clean(response.dateWindow),
                kind = kind(response.kind),
            ),
        )

    private fun clean(raw: String?): String? =
        raw?.trim()?.take(QUESTION_FIELD_MAX_LENGTH)?.trim()?.ifEmpty { null }

    private fun kind(raw: String?): QuestionKind =
        raw?.trim()?.uppercase(Locale.ROOT)?.let { KINDS[it] } ?: QuestionKind.UNKNOWN
}
