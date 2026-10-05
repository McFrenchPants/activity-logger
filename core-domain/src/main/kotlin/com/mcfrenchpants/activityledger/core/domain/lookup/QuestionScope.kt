package com.mcfrenchpants.activityledger.core.domain.lookup

import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection

/** What became of a question's date words. */
enum class DateWords {
    /** The question gave no date words (or the model's words were not in the question). */
    NONE,

    /** The date words were understood and set [QuestionScope.range]. */
    USED,

    /** The question had date words that no rule understood; the range fell back to all time. */
    NOT_UNDERSTOOD,
}

/**
 * The filter values a question resolved to, all decided by program logic: the kind by
 * [QuestionKindDetector] (else the model's kind), the range by `TemporalRangeResolver` from words
 * that occur in the question. Never a count, a date the model computed, or answer text.
 *
 * @property kind What the question asks for.
 * @property range The date range the question names; all time when it names none.
 * @property dateWords What became of the question's date words.
 */
data class QuestionScope(
    val kind: QuestionKind,
    val range: DateRangeSelection,
    val dateWords: DateWords,
)
