package com.mcfrenchpants.activityledger.ui.explore

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.stats.BucketSize
import com.mcfrenchpants.activityledger.core.domain.stats.ChartBucket
import com.mcfrenchpants.activityledger.core.domain.stats.ChartSeries
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.ExploreEntry
import com.mcfrenchpants.activityledger.core.domain.stats.GapUnit
import com.mcfrenchpants.activityledger.core.domain.stats.RangeLabel
import com.mcfrenchpants.activityledger.core.domain.stats.TypicalGap
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals

/** Pure JVM tests of the Explore screen's wording helpers. */
class ExploreFormattingTest {

    private val zone = ZoneId.of("America/Detroit")
    private val locale = Locale.US
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant()

    @Test
    fun `range text maps presets, month and year labels and plain spans`() {
        assertEquals(RangeText.Preset(DateRangePreset.LAST_7_DAYS), rangeText(DateRangeSelection.Preset(DateRangePreset.LAST_7_DAYS)))
        val aug = YearMonth.of(2026, 8)
        assertEquals(
            RangeText.Month(aug),
            rangeText(DateRangeSelection.Custom(aug.atDay(1), aug.atEndOfMonth(), RangeLabel.Month(aug))),
        )
        assertEquals(
            RangeText.Year(2026),
            rangeText(DateRangeSelection.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), RangeLabel.Year(2026))),
        )
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 20)
        assertEquals(RangeText.Span(start, end), rangeText(DateRangeSelection.Custom(start, end)))
    }

    @Test
    fun `dates are worded for the locale`() {
        assertEquals("August 2026", ExploreDates.month(YearMonth.of(2026, 8), locale))
        assertEquals("Aug 1 – Aug 20, 2026", ExploreDates.span(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20), locale))
        assertEquals("Dec 20, 2025 – Jan 3, 2026", ExploreDates.span(LocalDate.of(2025, 12, 20), LocalDate.of(2026, 1, 3), locale))
        assertEquals("Aug 1, 2026", ExploreDates.span(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), locale))
        val today = LocalDate.of(2026, 9, 15)
        assertEquals("Sep 21", ExploreDates.shortDate(LocalDate.of(2026, 9, 21), today, locale))
        assertEquals("Sep 21, 2025", ExploreDates.shortDate(LocalDate.of(2025, 9, 21), today, locale))
        assertEquals("September 21", ExploreDates.longDate(LocalDate.of(2026, 9, 21), today, locale))
        assertEquals("Monday, September 14", ExploreDates.header(LocalDate.of(2026, 9, 14), today, locale))
        assertEquals("Sunday, September 14, 2025", ExploreDates.header(LocalDate.of(2025, 9, 14), today, locale))
        assertEquals("Sep 2026", ExploreDates.shortMonth(LocalDate.of(2026, 9, 1), locale))
    }

    @Test
    fun `relative days go by local calendar date`() {
        val now = at(2026, 9, 15, 8)
        assertEquals(RelativeDay.Today, relativeDay(at(2026, 9, 15, 0, 5), now, zone))
        assertEquals(RelativeDay.Yesterday, relativeDay(at(2026, 9, 14, 23, 55), now, zone))
        assertEquals(RelativeDay.DaysAgo(2), relativeDay(at(2026, 9, 13, 9), now, zone))
        assertEquals(RelativeDay.DaysAgo(37), relativeDay(at(2026, 8, 9), now, zone))
        // A time later than now (clock skew) never reads as "in the future".
        assertEquals(RelativeDay.Today, relativeDay(at(2026, 9, 16), now, zone))
    }

    @Test
    fun `gap words use every day and every week, else the unit and amount`() {
        assertEquals(GapWords.EveryDay, gapWords(TypicalGap.of(Duration.ofHours(25))))
        assertEquals(GapWords.EveryWeek, gapWords(TypicalGap.of(Duration.ofDays(7))))
        assertEquals(GapWords.Every(GapUnit.DAYS, 3), gapWords(TypicalGap.of(Duration.ofDays(3))))
        assertEquals(GapWords.Every(GapUnit.HOURS, 6), gapWords(TypicalGap.of(Duration.ofHours(6))))
        assertEquals(GapWords.Every(GapUnit.MONTHS, 4), gapWords(TypicalGap.of(Duration.ofDays(122))))
        assertEquals(GapWords.Every(GapUnit.YEARS, 3), gapWords(TypicalGap.of(Duration.ofDays(1100))))
    }

    @Test
    fun `chart facts find the largest bucket, earliest on a tie, and count empty buckets`() {
        val d = LocalDate.of(2026, 9, 1)
        val series = ChartSeries(
            BucketSize.DAY,
            listOf(ChartBucket(d, 0), ChartBucket(d.plusDays(1), 6), ChartBucket(d.plusDays(2), 6), ChartBucket(d.plusDays(3), 0)),
            average = 6.0,
        )
        assertEquals(ChartFacts(ChartBucket(d.plusDays(1), 6), 2), chartFacts(series))
        assertEquals(ChartFacts(null, 1), chartFacts(ChartSeries(BucketSize.DAY, listOf(ChartBucket(d, 0)))))
    }

    @Test
    fun `averages show at most one decimal`() {
        assertEquals("2.6", formatAverage(2.6, locale))
        assertEquals("2.7", formatAverage(2.66, locale))
        assertEquals("3", formatAverage(3.0, locale))
    }

    @Test
    fun `entries are grouped by local date in their given order`() {
        fun entry(id: String, instant: java.time.Instant) = ExploreEntry(
            occurrenceId = id, captureId = "c$id", occurredAt = instant, timePrecision = TimePrecision.EXACT,
            durationSeconds = null, activityId = "a", activityName = "A", subjectId = null, subjectName = null,
            actionId = null, actionName = null, rawText = null,
        )
        val groups = groupByDate(
            listOf(entry("1", at(2026, 9, 14, 23)), entry("2", at(2026, 9, 14, 1)), entry("3", at(2026, 9, 13, 22))),
            zone,
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 13)), groups.map { it.first })
        assertEquals(listOf("1", "2"), groups[0].second.map { it.occurrenceId })
    }
}
