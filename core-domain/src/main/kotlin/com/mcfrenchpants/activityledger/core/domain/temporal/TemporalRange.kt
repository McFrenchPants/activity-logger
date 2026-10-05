package com.mcfrenchpants.activityledger.core.domain.temporal

import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection

/** Outcome of resolving a question's date-window words against today's local date. */
sealed interface TemporalRange {
    /**
     * The words name [selection]. A [DateRangeSelection.Custom] never starts after its end and
     * never ends after today.
     */
    data class Resolved(val selection: DateRangeSelection) : TemporalRange

    /** No date words were given (null, blank, punctuation-only or a model placeholder). */
    data object NoWindow : TemporalRange

    /** The window lies entirely after today; there is nothing logged in it yet. */
    data object Future : TemporalRange

    /** Non-blank words that match no rule. No window is guessed. */
    data object Unrecognised : TemporalRange
}
