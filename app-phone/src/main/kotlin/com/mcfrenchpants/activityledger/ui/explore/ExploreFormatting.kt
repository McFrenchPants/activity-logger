package com.mcfrenchpants.activityledger.ui.explore

import com.mcfrenchpants.activityledger.core.domain.stats.ChartBucket
import com.mcfrenchpants.activityledger.core.domain.stats.ChartSeries
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.stats.GapUnit
import com.mcfrenchpants.activityledger.core.domain.stats.RangeLabel
import com.mcfrenchpants.activityledger.core.domain.stats.TypicalGap
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Pure (no Android) helpers behind the Explore screen's wording. They return either structured
 * values the screen turns into string resources, or dates formatted with java.time for a locale.
 */

/** What the date chip stands for, before it is worded. */
sealed interface RangeText {
    data class Preset(val kind: DateRangePreset) : RangeText

    /** A whole month, worded like "August 2026". */
    data class Month(val month: YearMonth) : RangeText

    /** A whole year, worded like "2026". */
    data class Year(val year: Int) : RangeText

    /** Any other explicit range, worded like "Aug 1 – Aug 20, 2026". */
    data class Span(val start: LocalDate, val endInclusive: LocalDate) : RangeText
}

/** How [selection] is described: a preset, a month or year it was built from, or plain dates. */
fun rangeText(selection: DateRangeSelection): RangeText = when (selection) {
    is DateRangeSelection.Preset -> RangeText.Preset(selection.kind)
    is DateRangeSelection.Custom -> when (val label = selection.label) {
        is RangeLabel.Month -> RangeText.Month(label.month)
        is RangeLabel.Year -> RangeText.Year(label.year)
        null -> RangeText.Span(selection.start, selection.endInclusive)
    }
}

/** A day relative to today, before it is worded. */
sealed interface RelativeDay {
    data object Today : RelativeDay
    data object Yesterday : RelativeDay
    data class DaysAgo(val days: Long) : RelativeDay
}

/**
 * How long ago [then] was, by local calendar date in [zone] (not by 24-hour periods): the same
 * date is today, the date before is yesterday, otherwise N days ago. A future time reads as today.
 */
fun relativeDay(then: Instant, now: Instant, zone: ZoneId): RelativeDay {
    val days = ChronoUnit.DAYS.between(then.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        days <= 0L -> RelativeDay.Today
        days == 1L -> RelativeDay.Yesterday
        else -> RelativeDay.DaysAgo(days)
    }
}

/** The "usually every" wording of a [TypicalGap], before it is worded. */
sealed interface GapWords {
    data object EveryDay : GapWords
    data object EveryWeek : GapWords
    data class Every(val unit: GapUnit, val amount: Long) : GapWords
}

/** "every day" for one day, "every week" for seven days, otherwise "every N <unit>". */
fun gapWords(gap: TypicalGap): GapWords = when {
    gap.isEveryDay -> GapWords.EveryDay
    gap.isEveryWeek -> GapWords.EveryWeek
    else -> GapWords.Every(gap.unit, gap.amount)
}

/**
 * What the chart's description says besides its bucket size.
 *
 * @property most The bucket with the highest count (the earliest one on a tie); null when every
 *   bucket is empty.
 * @property emptyBuckets How many buckets have no entries.
 */
data class ChartFacts(val most: ChartBucket?, val emptyBuckets: Int)

/** The facts the chart's content description is built from. */
fun chartFacts(series: ChartSeries): ChartFacts {
    var most: ChartBucket? = null
    series.buckets.forEach { bucket ->
        if (bucket.count > 0 && (most == null || bucket.count > most!!.count)) most = bucket
    }
    return ChartFacts(most, series.buckets.count { it.count == 0 })
}

/** An average with at most one decimal and no trailing ".0" ("2.6", "3"). */
fun formatAverage(average: Double, locale: Locale): String =
    DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).format(average)

/**
 * [entries] split into runs of the same local date in [zone], in their given order. A date that
 * comes back after another date starts a new run (the order is the caller's sort).
 */
fun groupByDate(entries: List<ExploreEntry>, zone: ZoneId): List<Pair<LocalDate, List<ExploreEntry>>> {
    val groups = mutableListOf<Pair<LocalDate, MutableList<ExploreEntry>>>()
    entries.forEach { entry ->
        val date = entry.occurredAt.atZone(zone).toLocalDate()
        val last = groups.lastOrNull()
        if (last != null && last.first == date) last.second += entry else groups += date to mutableListOf(entry)
    }
    return groups
}

/** Dates worded with java.time for a locale (month and weekday names come from the locale). */
object ExploreDates {

    /** "August 2026". */
    fun month(month: YearMonth, locale: Locale): String =
        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))

    /**
     * An inclusive range: "Aug 1 – Aug 20, 2026"; across years "Dec 20, 2025 – Jan 3, 2026"; one
     * day "Aug 1, 2026".
     */
    fun span(start: LocalDate, endInclusive: LocalDate, locale: Locale): String {
        val withYear = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
        if (start == endInclusive) return start.format(withYear)
        val startPattern = if (start.year == endInclusive.year) DateTimeFormatter.ofPattern("MMM d", locale) else withYear
        return "${start.format(startPattern)} – ${endInclusive.format(withYear)}"
    }

    /** "Sep 21", or "Sep 21, 2025" when [date] is not in [today]'s year. */
    fun shortDate(date: LocalDate, today: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "MMM d" else "MMM d, yyyy", locale))

    /** "September 21", or "September 21, 2025" when [date] is not in [today]'s year. */
    fun longDate(date: LocalDate, today: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "MMMM d" else "MMMM d, yyyy", locale))

    /** "Sep 2026" (a month bucket in the chart's list). */
    fun shortMonth(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))

    /** "Monday, September 14", or with the year when [date] is not in [today]'s year. */
    fun header(date: LocalDate, today: LocalDate, locale: Locale): String = date.format(
        DateTimeFormatter.ofPattern(if (date.year == today.year) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy", locale),
    )
}
