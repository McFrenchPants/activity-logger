package com.mcfrenchpants.activityledger.ui.ask

import com.mcfrenchpants.activityledger.core.domain.lookup.LookupTier
import java.time.Duration

/** The gap between two occurrences in plain words, before it is turned into text. */
sealed interface IntervalWords {
    data class Days(val count: Int) : IntervalWords
    data class Hours(val count: Int) : IntervalWords
    data object LessThanAnHour : IntervalWords
}

/**
 * Whole days when the gap is a day or more, whole hours when it is under a day, and
 * "less than an hour" below that. Always rounds down; a negative gap reads as less than an hour.
 */
fun intervalWords(interval: Duration): IntervalWords {
    val days = interval.toDays()
    val hours = interval.toHours()
    return when {
        days >= 1 -> IntervalWords.Days(days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        hours >= 1 -> IntervalWords.Hours(hours.toInt())
        else -> IntervalWords.LessThanAnHour
    }
}

/** The quiet note under the best match, if any. */
enum class MatchNote { NONE, NOT_EXACT, PARTIAL }

/**
 * Which note goes with the best match: a match that is not exact says so; an exact match that
 * covers only the subject or only the action says it is partial; an exact match on both says
 * nothing.
 */
fun matchNote(exact: Boolean, tier: LookupTier): MatchNote = when {
    !exact -> MatchNote.NOT_EXACT
    tier != LookupTier.BOTH -> MatchNote.PARTIAL
    else -> MatchNote.NONE
}
