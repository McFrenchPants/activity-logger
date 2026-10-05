package com.mcfrenchpants.activityledger.core.domain.stats

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** The fixed date-range choices on the Explore screen. Every preset ends on (and includes) today. */
enum class DateRangePreset {
    /** Today and the 6 days before. */
    LAST_7_DAYS,

    /** Today and the 29 days before. */
    LAST_30_DAYS,

    /** `today.minusMonths(12).plusDays(1)` through today. */
    LAST_12_MONTHS,

    /** The earliest entry's local date (or today when there is none) through today. */
    ALL_TIME,
}

/**
 * What a custom range was built from, when a question named a month or a year. Structured only;
 * wording is the UI's job.
 */
sealed interface RangeLabel {
    data class Month(val month: YearMonth) : RangeLabel

    data class Year(val year: Int) : RangeLabel
}

/** The date range chosen on the Explore screen, before it is pinned to concrete dates. */
sealed interface DateRangeSelection {
    data class Preset(val kind: DateRangePreset) : DateRangeSelection

    /**
     * An explicit range of local dates; both bounds are inclusive.
     *
     * @property label What the range stands for (a month or a year), if anything.
     */
    data class Custom(
        val start: LocalDate,
        val endInclusive: LocalDate,
        val label: RangeLabel? = null,
    ) : DateRangeSelection {
        init {
            require(!start.isAfter(endInclusive)) { "Custom range start $start is after its end $endInclusive" }
        }
    }
}

/**
 * A selection pinned to concrete local dates.
 *
 * @property start First included local date.
 * @property endInclusive Last included local date.
 * @property dayCount Number of local dates in the range (always >= 1).
 */
data class ResolvedRange(val start: LocalDate, val endInclusive: LocalDate, val dayCount: Long) {
    /** True when [date] lies within the range, bounds included. */
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    companion object {
        /** Builds a range from inclusive bounds, computing [dayCount]. */
        fun of(start: LocalDate, endInclusive: LocalDate): ResolvedRange {
            require(!start.isAfter(endInclusive)) { "Range start $start is after its end $endInclusive" }
            return ResolvedRange(start, endInclusive, ChronoUnit.DAYS.between(start, endInclusive) + 1)
        }
    }
}

/**
 * Pins [selection] to concrete local dates. Presets include [today]. For ALL_TIME the range starts
 * at [earliestEntryDate] (or [today] when null, or when the earliest entry is in the future).
 */
fun resolve(selection: DateRangeSelection, today: LocalDate, earliestEntryDate: LocalDate?): ResolvedRange =
    when (selection) {
        is DateRangeSelection.Custom -> ResolvedRange.of(selection.start, selection.endInclusive)
        is DateRangeSelection.Preset -> when (selection.kind) {
            DateRangePreset.LAST_7_DAYS -> ResolvedRange.of(today.minusDays(6), today)
            DateRangePreset.LAST_30_DAYS -> ResolvedRange.of(today.minusDays(29), today)
            DateRangePreset.LAST_12_MONTHS -> ResolvedRange.of(today.minusMonths(12).plusDays(1), today)
            DateRangePreset.ALL_TIME -> {
                val start = earliestEntryDate?.takeIf { !it.isAfter(today) } ?: today
                ResolvedRange.of(start, today)
            }
        }
    }
