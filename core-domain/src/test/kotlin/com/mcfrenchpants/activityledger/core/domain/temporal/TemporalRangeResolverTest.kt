package com.mcfrenchpants.activityledger.core.domain.temporal

import com.mcfrenchpants.activityledger.core.domain.stats.DateRangePreset
import com.mcfrenchpants.activityledger.core.domain.stats.DateRangeSelection
import com.mcfrenchpants.activityledger.core.domain.stats.RangeLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TemporalRangeResolverTest {

    private val resolver = TemporalRangeResolver()

    /** Standard today: Monday 2026-10-05. */
    private val monday = LocalDate.parse("2026-10-05")

    private fun d(text: String): LocalDate = LocalDate.parse(text)

    private fun resolve(
        expr: String?,
        today: LocalDate = monday,
        weekStart: DayOfWeek = DayOfWeek.MONDAY,
    ): TemporalRange {
        val result = resolver.resolve(expr, today, weekStart)
        assertInvariant(result, today, expr)
        return result
    }

    /** Every Resolved Custom range satisfies start <= endInclusive <= today. */
    private fun assertInvariant(result: TemporalRange, today: LocalDate, expr: String?) {
        val selection = (result as? TemporalRange.Resolved)?.selection as? DateRangeSelection.Custom ?: return
        assertTrue(!selection.start.isAfter(selection.endInclusive), "start after end for '$expr': $selection")
        assertTrue(!selection.endInclusive.isAfter(today), "end after today $today for '$expr': $selection")
    }

    private fun assertRange(
        expr: String?,
        start: String,
        end: String,
        label: RangeLabel? = null,
        today: LocalDate = monday,
        weekStart: DayOfWeek = DayOfWeek.MONDAY,
    ) {
        assertEquals(
            TemporalRange.Resolved(DateRangeSelection.Custom(d(start), d(end), label)),
            resolve(expr, today, weekStart),
            "expression: '$expr' today $today week start $weekStart",
        )
    }

    private fun assertDay(expr: String, day: String, today: LocalDate = monday) =
        assertRange(expr, day, day, null, today)

    private fun assertPreset(expr: String, kind: DateRangePreset, today: LocalDate = monday) =
        assertEquals(
            TemporalRange.Resolved(DateRangeSelection.Preset(kind)),
            resolve(expr, today),
            "expression: '$expr'",
        )

    private fun assertNoWindow(expr: String?) =
        assertEquals(TemporalRange.NoWindow, resolve(expr), "expression: '$expr'")

    private fun assertFuture(expr: String, today: LocalDate = monday) =
        assertEquals(TemporalRange.Future, resolve(expr, today), "expression: '$expr' today $today")

    private fun assertUnrecognised(expr: String, today: LocalDate = monday) =
        assertEquals(TemporalRange.Unrecognised, resolve(expr, today), "expression: '$expr' today $today")

    private fun month(text: String) = RangeLabel.Month(YearMonth.parse(text))
    private fun year(value: Int) = RangeLabel.Year(value)

    @Test
    fun standardTodayIsAMonday() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
    }

    // ---- No window ----

    @Test
    fun nullBlankPunctuationAndPlaceholdersAreNoWindow() {
        listOf(
            null, "", "   ", "?", "...", "UNRESOLVED", "unknown", "None", "null", "n/a", "N/A", "na",
            "undefined", "unspecified", "not specified", "Not given", "nothing", "nothing.",
        ).forEach { assertNoWindow(it) }
    }

    // ---- a. All time ----

    @Test
    fun allTimeWordsAreTheAllTimePreset() {
        listOf("ever", "all time", "of all time", "ever before", "at all", "for all time", "Ever?")
            .forEach { assertPreset(it, DateRangePreset.ALL_TIME) }
    }

    // ---- b. Rolling windows ----

    @Test
    fun rollingWindowsMapToPresetsExactly() {
        assertPreset("last 7 days", DateRangePreset.LAST_7_DAYS)
        assertPreset("the past seven days", DateRangePreset.LAST_7_DAYS)
        assertPreset("last 1 week", DateRangePreset.LAST_7_DAYS)
        assertPreset("the last week", DateRangePreset.LAST_7_DAYS)
        assertPreset("past week", DateRangePreset.LAST_7_DAYS)
        assertPreset("this past week", DateRangePreset.LAST_7_DAYS)
        assertPreset("last 30 days", DateRangePreset.LAST_30_DAYS)
        assertPreset("in the past 30 days", DateRangePreset.LAST_30_DAYS)
        assertPreset("last 12 months", DateRangePreset.LAST_12_MONTHS)
        assertPreset("the past twelve months", DateRangePreset.LAST_12_MONTHS)
        assertPreset("last 1 year", DateRangePreset.LAST_12_MONTHS)
        assertPreset("past year", DateRangePreset.LAST_12_MONTHS)
        assertPreset("the last year", DateRangePreset.LAST_12_MONTHS)
        assertPreset("this past year", DateRangePreset.LAST_12_MONTHS)
    }

    @Test
    fun nonPresetRollingWindowsEndToday() {
        assertRange("the last two weeks", "2026-09-22", "2026-10-05")
        assertRange("past 3 months", "2026-07-06", "2026-10-05")
        assertRange("last 10 days", "2026-09-26", "2026-10-05")
        assertRange("the past month", "2026-09-06", "2026-10-05")
        assertRange("this past month", "2026-09-06", "2026-10-05")
        assertRange("last 2 years", "2024-10-06", "2026-10-05")
        assertRange("the past couple of weeks", "2026-09-22", "2026-10-05")
        assertRange("past a couple of days", "2026-10-04", "2026-10-05")
        assertRange("the last 31 days", "2026-09-05", "2026-10-05")
    }

    @Test
    fun oneDayRollingWindowIsToday() {
        assertDay("past day", "2026-10-05")
        assertDay("the last day", "2026-10-05")
        assertDay("last 1 day", "2026-10-05")
    }

    @Test
    fun fillerWordsAreStripped() {
        assertPreset("during the past week", DateRangePreset.LAST_7_DAYS)
        assertRange("over the last 3 months", "2026-07-06", "2026-10-05")
        assertRange("within the last two days", "2026-10-04", "2026-10-05")
        assertRange("for the past 2 days", "2026-10-04", "2026-10-05")
    }

    // ---- c. Calendar periods ----

    @Test
    fun todayAndYesterday() {
        assertDay("today", "2026-10-05")
        assertDay("Yesterday?", "2026-10-04")
        assertDay("yesterday", "2026-12-31", today = d("2027-01-01"))
    }

    @Test
    fun thisWeekFollowsTheWeekStart() {
        // Today Monday: with a Monday start the week is just today.
        assertRange("this week", "2026-10-05", "2026-10-05", weekStart = DayOfWeek.MONDAY)
        assertRange("this week", "2026-10-04", "2026-10-05", weekStart = DayOfWeek.SUNDAY)
        // Thursday 2026-10-08.
        val thursday = d("2026-10-08")
        assertRange("this week", "2026-10-05", "2026-10-08", today = thursday, weekStart = DayOfWeek.MONDAY)
        assertRange("this week", "2026-10-04", "2026-10-08", today = thursday, weekStart = DayOfWeek.SUNDAY)
        // Sunday 2026-10-04 is the first day of a Sunday week, the last of a Monday week.
        val sunday = d("2026-10-04")
        assertRange("this week", "2026-10-04", "2026-10-04", today = sunday, weekStart = DayOfWeek.SUNDAY)
        assertRange("this week", "2026-09-28", "2026-10-04", today = sunday, weekStart = DayOfWeek.MONDAY)
    }

    @Test
    fun lastWeekIsThePreviousCalendarWeek() {
        assertRange("last week", "2026-09-28", "2026-10-04", weekStart = DayOfWeek.MONDAY)
        assertRange("last week", "2026-09-27", "2026-10-03", weekStart = DayOfWeek.SUNDAY)
    }

    @Test
    fun lastWeekAcrossAYearBoundary() {
        val friday = d("2027-01-01")
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertRange("last week", "2026-12-21", "2026-12-27", today = friday, weekStart = DayOfWeek.MONDAY)
        assertRange("last week", "2026-12-20", "2026-12-26", today = friday, weekStart = DayOfWeek.SUNDAY)
        val thursday = d("2026-01-01")
        assertRange("last week", "2025-12-22", "2025-12-28", today = thursday, weekStart = DayOfWeek.MONDAY)
    }

    @Test
    fun thisMonthIsClampedToToday() {
        assertRange("this month", "2026-10-01", "2026-10-05", month("2026-10"))
        assertRange("this month", "2026-10-01", "2026-10-01", month("2026-10"), today = d("2026-10-01"))
        assertRange("this month", "2028-02-01", "2028-02-29", month("2028-02"), today = d("2028-02-29"))
    }

    @Test
    fun lastMonthIsThePreviousCalendarMonth() {
        assertRange("last month", "2026-09-01", "2026-09-30", month("2026-09"))
        assertRange("last month", "2025-12-01", "2025-12-31", month("2025-12"), today = d("2026-01-15"))
        assertRange("last month", "2026-02-01", "2026-02-28", month("2026-02"), today = d("2026-03-31"))
        assertRange("last month", "2028-02-01", "2028-02-29", month("2028-02"), today = d("2028-03-31"))
    }

    @Test
    fun thisYearAndLastYearCarryYearLabels() {
        assertRange("this year", "2026-01-01", "2026-10-05", year(2026))
        assertRange("last year", "2025-01-01", "2025-12-31", year(2025))
        assertRange("this year", "2027-01-01", "2027-01-01", year(2027), today = d("2027-01-01"))
        assertRange("this year", "2026-01-01", "2026-12-31", year(2026), today = d("2026-12-31"))
        assertRange("last year", "2027-01-01", "2027-12-31", year(2027), today = d("2028-02-29"))
    }

    @Test
    fun forwardLookingWordsAreFuture() {
        listOf("next month", "next week", "next year", "next tuesday", "tomorrow", "later", "Tomorrow?")
            .forEach { assertFuture(it) }
    }

    // ---- d. Month without a year ----

    @Test
    fun namedMonthIsTheMostRecentStartedOne() {
        assertRange("in August", "2026-08-01", "2026-08-31", month("2026-08"))
        assertRange("in august", "2026-08-01", "2026-08-20", month("2026-08"), today = d("2026-08-20"))
        assertRange("in August", "2025-08-01", "2025-08-31", month("2025-08"), today = d("2026-03-10"))
        assertRange("october", "2026-10-01", "2026-10-05", month("2026-10"))
        assertRange("november", "2025-11-01", "2025-11-30", month("2025-11"))
        assertRange("during may", "2026-05-01", "2026-05-31", month("2026-05"))
    }

    @Test
    fun monthAbbreviations() {
        assertRange("aug", "2026-08-01", "2026-08-31", month("2026-08"))
        assertRange("aug.", "2026-08-01", "2026-08-31", month("2026-08"))
        assertRange("sept", "2026-09-01", "2026-09-30", month("2026-09"))
        assertRange("in sep", "2026-09-01", "2026-09-30", month("2026-09"))
        assertRange("feb", "2028-02-01", "2028-02-29", month("2028-02"), today = d("2028-02-29"))
        assertRange("december", "2026-12-01", "2026-12-31", month("2026-12"), today = d("2026-12-31"))
        assertRange("december", "2026-12-01", "2026-12-31", month("2026-12"), today = d("2027-01-01"))
        assertRange("january", "2027-01-01", "2027-01-01", month("2027-01"), today = d("2027-01-01"))
    }

    // ---- e. Month with a year ----

    @Test
    fun monthWithAYear() {
        listOf("august 2025", "aug 2025", "august of 2025", "august, 2025", "aug. 2025", "in August 2025")
            .forEach { assertRange(it, "2025-08-01", "2025-08-31", month("2025-08")) }
        assertRange("october 2026", "2026-10-01", "2026-10-05", month("2026-10"))
    }

    @Test
    fun futureOrAncientMonthWithAYear() {
        assertFuture("december 2026")
        assertFuture("november 2026")
        assertFuture("march 2031")
        assertUnrecognised("august 1850")
    }

    // ---- f. Year alone ----

    @Test
    fun yearAlone() {
        assertRange("in 2025", "2025-01-01", "2025-12-31", year(2025))
        assertRange("the year 2025", "2025-01-01", "2025-12-31", year(2025))
        assertRange("2026", "2026-01-01", "2026-10-05", year(2026))
        assertFuture("in 2030")
        assertFuture("2027")
        assertUnrecognised("1850")
    }

    // ---- g. Since ----

    @Test
    fun sinceAMonthOrYear() {
        assertRange("since June", "2026-06-01", "2026-10-05")
        assertRange("since november", "2025-11-01", "2026-10-05")
        assertRange("since june 2025", "2025-06-01", "2026-10-05")
        assertRange("since 2025", "2025-01-01", "2026-10-05")
        assertFuture("since 2030")
        assertFuture("since december 2026")
        assertUnrecognised("since 1850")
    }

    @Test
    fun sinceAPreviousPeriod() {
        assertRange("since last month", "2026-09-01", "2026-10-05")
        assertRange("since last year", "2025-01-01", "2026-10-05")
        assertRange("since last week", "2026-09-28", "2026-10-05", weekStart = DayOfWeek.MONDAY)
        assertRange("since last week", "2026-09-27", "2026-10-05", weekStart = DayOfWeek.SUNDAY)
        assertRange("since yesterday", "2026-10-04", "2026-10-05")
    }

    @Test
    fun sinceAWeekdayOrDate() {
        // Today is a Monday: "since monday" means a week ago.
        assertRange("since monday", "2026-09-28", "2026-10-05")
        assertRange("since last friday", "2026-10-02", "2026-10-05")
        assertRange("since june 3rd", "2026-06-03", "2026-10-05")
        assertRange("since june 3", "2026-06-03", "2026-10-05")
        assertRange("since 3 june", "2026-06-03", "2026-10-05")
        assertRange("since the 3rd of june", "2026-06-03", "2026-10-05")
        assertRange("since october 6", "2025-10-06", "2026-10-05")
        assertUnrecognised("since june 31")
        assertUnrecognised("since banana")
    }

    // ---- h. Spans ----

    @Test
    fun explicitSpans() {
        assertRange("from June to August", "2026-06-01", "2026-08-31")
        assertRange("between March and May 2026", "2026-03-01", "2026-05-31")
        assertRange("from 2024 to 2025", "2024-01-01", "2025-12-31")
        assertRange("from june 2025 through august", "2025-06-01", "2025-08-31")
        assertRange("from june until december 2026", "2026-06-01", "2026-10-05")
        assertRange("between 2025 and 2026", "2025-01-01", "2026-10-05")
        // Both without a year: Y is the most recent started month, X the latest not after it.
        assertRange("from august to june", "2025-08-01", "2026-06-30")
        assertRange("from september till november", "2025-09-01", "2025-11-30")
    }

    @Test
    fun reversedOrFutureSpans() {
        assertUnrecognised("from 2025 to 2024")
        assertUnrecognised("from august 2026 to june 2026")
        assertFuture("between 2030 and 2031")
        assertFuture("from november 2026 to december 2026")
        assertUnrecognised("from banana to august")
    }

    // ---- i. Single days ----

    @Test
    fun singleWeekdays() {
        assertDay("monday", "2026-09-28")
        assertDay("last friday", "2026-10-02")
        assertDay("on tuesday", "2026-09-29")
        assertDay("sunday", "2026-10-04")
    }

    @Test
    fun singleMonthDayDates() {
        listOf("august 3", "aug 3rd", "3 august", "the 3rd of august", "on august 3")
            .forEach { assertDay(it, "2026-08-03") }
        assertDay("august 3 2025", "2025-08-03")
        assertDay("august 3, 2025", "2025-08-03")
        assertDay("october 6", "2025-10-06")
        assertDay("february 29", "2024-02-29")
        assertDay("feb 29", "2028-02-29", today = d("2028-02-29"))
    }

    @Test
    fun invalidOrFutureSingleDates() {
        assertUnrecognised("feb 30")
        assertUnrecognised("june 31")
        assertUnrecognised("february 29 2026")
        assertFuture("december 25 2026")
    }

    // ---- j. Everything else ----

    @Test
    fun unknownWordsAreUnrecognised() {
        listOf(
            "banana", "when it rained", "this summer", "the weekend", "3 days ago", "q3",
            "christmas", "in", "it’s raining",
        ).forEach { assertUnrecognised(it) }
    }

    @Test
    fun curlyApostropheAndQuotesAreAccepted() {
        assertRange("‘last month’", "2026-09-01", "2026-09-30", month("2026-09"))
        assertRange("“this year”?", "2026-01-01", "2026-10-05", year(2026))
    }

    // ---- Invariant sweep ----

    @Test
    fun everyResolvedRangeStaysWithinTodayAcrossManyTodays() {
        val expressions = listOf(
            "today", "yesterday", "this week", "last week", "this month", "last month", "this year",
            "last year", "the last two weeks", "past 3 months", "last 2 years", "august", "february",
            "december", "january", "august 2025", "2026", "since june", "since monday", "since june 3rd",
            "since february 29", "from june to august", "from august to june", "monday", "august 3",
            "february 29", "past day", "the past month",
        )
        val todays = listOf("2026-10-05", "2027-01-01", "2028-02-29", "2026-12-31", "2026-03-31", "2028-03-01")
        for (today in todays.map(::d)) {
            for (weekStart in listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)) {
                for (expr in expressions) {
                    when (val result = resolver.resolve(expr, today, weekStart)) {
                        is TemporalRange.Resolved -> assertInvariant(result, today, expr)
                        TemporalRange.Future -> Unit
                        else -> fail("'$expr' on $today did not resolve: $result")
                    }
                }
            }
        }
    }
}
