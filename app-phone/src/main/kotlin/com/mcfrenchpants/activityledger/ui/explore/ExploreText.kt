package com.mcfrenchpants.activityledger.ui.explore

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.stats.ActivitySort
import com.mcfrenchpants.activityledger.core.domain.stats.BucketSize
import com.mcfrenchpants.activityledger.core.domain.stats.ChartBucket
import com.mcfrenchpants.activityledger.core.domain.stats.ChartSeries
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.EntrySort
import com.mcfrenchpants.activityledger.core.domain.stats.GapUnit
import com.mcfrenchpants.activityledger.core.domain.stats.PartOfDay
import com.mcfrenchpants.activityledger.core.domain.stats.TypicalGap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/*
 * Composable wording for the Explore screen: turns the pure helpers' structured values into
 * string resources. All copy lives in strings.xml.
 */

internal fun DateRangePreset.labelRes(): Int = when (this) {
    DateRangePreset.LAST_7_DAYS -> R.string.explore_range_last_7_days
    DateRangePreset.LAST_30_DAYS -> R.string.explore_range_last_30_days
    DateRangePreset.LAST_12_MONTHS -> R.string.explore_range_last_12_months
    DateRangePreset.ALL_TIME -> R.string.explore_range_all_time
}

private fun DateRangePreset.phraseRes(): Int = when (this) {
    DateRangePreset.LAST_7_DAYS -> R.string.explore_range_phrase_last_7_days
    DateRangePreset.LAST_30_DAYS -> R.string.explore_range_phrase_last_30_days
    DateRangePreset.LAST_12_MONTHS -> R.string.explore_range_phrase_last_12_months
    DateRangePreset.ALL_TIME -> R.string.explore_range_phrase_all_time
}

/** The date chip's label: "Last 30 days", "August 2026", "2026", "Aug 1 – Aug 20, 2026". */
@Composable
internal fun rangeChipLabel(selection: DateRangeSelection, locale: Locale): String =
    when (val text = rangeText(selection)) {
        is RangeText.Preset -> stringResource(text.kind.labelRes())
        is RangeText.Month -> ExploreDates.month(text.month, locale)
        is RangeText.Year -> text.year.toString()
        is RangeText.Span -> ExploreDates.span(text.start, text.endInclusive, locale)
    }

/** The range inside a sentence: "the last 30 days", "all your history", "August 2026". */
@Composable
internal fun rangePhrase(selection: DateRangeSelection, locale: Locale): String =
    when (val text = rangeText(selection)) {
        is RangeText.Preset -> stringResource(text.kind.phraseRes())
        else -> rangeChipLabel(selection, locale)
    }

/** The range after "Entries per day, ": "last 30 days" for a preset, else the chip label. */
@Composable
internal fun rangeShortPhrase(selection: DateRangeSelection, locale: Locale): String {
    val label = rangeChipLabel(selection, locale)
    return if (selection is DateRangeSelection.Preset) label.replaceFirstChar { it.lowercase(locale) } else label
}

/** "today", "yesterday", "37 days ago". */
@Composable
internal fun relativeDayText(then: Instant, now: Instant, zone: ZoneId): String =
    when (val day = relativeDay(then, now, zone)) {
        RelativeDay.Today -> stringResource(R.string.explore_relative_today)
        RelativeDay.Yesterday -> stringResource(R.string.explore_relative_yesterday)
        is RelativeDay.DaysAgo -> {
            val n = day.days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            pluralStringResource(R.plurals.explore_relative_days_ago, n, n)
        }
    }

/** "every day", "every week", "every 3 days", "every 4 months". */
@Composable
internal fun gapText(gap: TypicalGap): String = when (val words = gapWords(gap)) {
    GapWords.EveryDay -> stringResource(R.string.explore_gap_every_day)
    GapWords.EveryWeek -> stringResource(R.string.explore_gap_every_week)
    is GapWords.Every -> {
        val n = words.amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val res = when (words.unit) {
            GapUnit.HOURS -> R.plurals.explore_gap_hours
            GapUnit.DAYS -> R.plurals.explore_gap_days
            GapUnit.MONTHS -> R.plurals.explore_gap_months
            GapUnit.YEARS -> R.plurals.explore_gap_years
        }
        pluralStringResource(res, n, n)
    }
}

/** "Average 2.6 per day on days you logged something" (week / month wording); null with no average. */
@Composable
internal fun chartSentence(series: ChartSeries, locale: Locale): String? {
    val average = series.average ?: return null
    val res = when (series.bucketSize) {
        BucketSize.DAY -> R.string.explore_chart_average_day
        BucketSize.WEEK -> R.string.explore_chart_average_week
        BucketSize.MONTH -> R.string.explore_chart_average_month
    }
    return stringResource(res, formatAverage(average, locale))
}

/** A bucket's label in the list and under the chart: "Sep 21", "Week of Sep 21", "Sep 2026". */
@Composable
internal fun bucketLabel(bucket: ChartBucket, size: BucketSize, today: LocalDate, locale: Locale): String =
    when (size) {
        BucketSize.DAY -> ExploreDates.shortDate(bucket.start, today, locale)
        BucketSize.WEEK -> stringResource(R.string.explore_chart_week_of, ExploreDates.shortDate(bucket.start, today, locale))
        BucketSize.MONTH -> ExploreDates.shortMonth(bucket.start, locale)
    }

/**
 * The chart's content description: "Entries per day, last 30 days. Most: 6 on September 21.
 * 12 days with no entries."
 */
@Composable
internal fun chartDescription(
    series: ChartSeries,
    range: DateRangeSelection,
    today: LocalDate,
    locale: Locale,
): String {
    val facts = chartFacts(series)
    val phrase = rangeShortPhrase(range, locale)
    val head = when (series.bucketSize) {
        BucketSize.DAY -> stringResource(R.string.explore_chart_cd_day, phrase)
        BucketSize.WEEK -> stringResource(R.string.explore_chart_cd_week, phrase)
        BucketSize.MONTH -> stringResource(R.string.explore_chart_cd_month, phrase)
    }
    val most = facts.most
    val mostText = if (most == null) {
        stringResource(R.string.explore_chart_cd_nothing)
    } else {
        when (series.bucketSize) {
            BucketSize.DAY -> stringResource(R.string.explore_chart_cd_most, most.count, ExploreDates.longDate(most.start, today, locale))
            BucketSize.WEEK -> stringResource(R.string.explore_chart_cd_most_week, most.count, ExploreDates.longDate(most.start, today, locale))
            BucketSize.MONTH -> stringResource(R.string.explore_chart_cd_most_month, most.count, ExploreDates.month(java.time.YearMonth.from(most.start), locale))
        }
    }
    val emptyRes = when (series.bucketSize) {
        BucketSize.DAY -> R.plurals.explore_chart_cd_empty_days
        BucketSize.WEEK -> R.plurals.explore_chart_cd_empty_weeks
        BucketSize.MONTH -> R.plurals.explore_chart_cd_empty_months
    }
    val parts = mutableListOf(head, mostText)
    if (most != null) parts += pluralStringResource(emptyRes, facts.emptyBuckets, facts.emptyBuckets)
    return parts.joinToString(" ")
}

internal fun EntrySort.labelRes(): Int = when (this) {
    EntrySort.NEWEST -> R.string.explore_sort_newest
    EntrySort.OLDEST -> R.string.explore_sort_oldest
}

internal fun ActivitySort.labelRes(): Int = when (this) {
    ActivitySort.MOST_LOGGED -> R.string.explore_sort_most_logged
    ActivitySort.LAST_DONE -> R.string.explore_sort_last_done
    ActivitySort.LONGEST_SINCE -> R.string.explore_sort_longest_since
    ActivitySort.NAME -> R.string.explore_sort_name
}

internal fun PartOfDay.labelRes(): Int = when (this) {
    PartOfDay.MORNING -> R.string.explore_part_morning
    PartOfDay.AFTERNOON -> R.string.explore_part_afternoon
    PartOfDay.EVENING -> R.string.explore_part_evening
    PartOfDay.NIGHT -> R.string.explore_part_night
}

/** "Subject · Action" when both names are known, else whichever is known, else [fallback]. */
@Composable
internal fun pairName(subjectName: String?, actionName: String?, fallback: String): String = when {
    subjectName != null && actionName != null -> stringResource(R.string.explore_activity_name, subjectName, actionName)
    subjectName != null -> subjectName
    actionName != null -> actionName
    else -> fallback
}
