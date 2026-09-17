package com.mcfrenchpants.activityledger.core.domain.temporal

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TemporalResolverTest {

    private val resolver = TemporalResolver()
    private val detroit = ZoneId.of("America/Detroit")

    /** Standard context: Tuesday 2026-09-15 20:00 America/Detroit. */
    private val standard = OffsetDateTime.parse("2026-09-15T20:00-04:00").toInstant()

    private fun at(text: String): Instant = OffsetDateTime.parse(text).toInstant()

    private fun local(date: String, time: String, zone: ZoneId = detroit): Instant =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun resolve(expr: String?, capturedAt: Instant = standard, zone: ZoneId = detroit) =
        resolver.resolve(expr, capturedAt, zone)

    private fun assertResolved(
        expected: Instant,
        precision: TimePrecision,
        expr: String?,
        capturedAt: Instant = standard,
        zone: ZoneId = detroit,
    ) {
        assertEquals(
            TemporalResolution.Resolved(expected, precision),
            resolve(expr, capturedAt, zone),
            "expression: $expr at $capturedAt",
        )
    }

    private fun assertFuture(expr: String, capturedAt: Instant = standard) =
        assertEquals(TemporalResolution.Future, resolve(expr, capturedAt), "expression: $expr")

    private fun assertUnresolvable(expr: String, capturedAt: Instant = standard) =
        assertEquals(TemporalResolution.Unresolvable, resolve(expr, capturedAt), "expression: $expr")

    // ---- Rule 1: null / blank ----

    @Test
    fun rule1_nullAndBlankAreInferredNow() {
        assertResolved(standard, TimePrecision.INFERRED_NOW, null)
        assertResolved(standard, TimePrecision.INFERRED_NOW, "   ")
        assertResolved(standard, TimePrecision.INFERRED_NOW, "")
    }

    // ---- Rule 2: immediate ----

    @Test
    fun rule2_immediatePhrasesAreInferredNow() {
        listOf("just now", "just", "now", "right now", "a moment ago", "just finished", "just did it")
            .forEach { assertResolved(standard, TimePrecision.INFERRED_NOW, it) }
    }

    // ---- Rule 3: yesterday part-of-day ----

    @Test
    fun rule3_yesterdayBands() {
        assertResolved(at("2026-09-14T09:00-04:00"), TimePrecision.APPROXIMATE, "yesterday morning")
        assertResolved(at("2026-09-14T15:00-04:00"), TimePrecision.APPROXIMATE, "yesterday afternoon")
        assertResolved(at("2026-09-14T19:00-04:00"), TimePrecision.APPROXIMATE, "yesterday evening")
    }

    // ---- Rule 4: this part-of-day ----

    @Test
    fun rule4_standardContextBands() {
        assertResolved(at("2026-09-15T09:00-04:00"), TimePrecision.APPROXIMATE, "this morning")
        assertResolved(at("2026-09-15T15:00-04:00"), TimePrecision.APPROXIMATE, "this afternoon")
        assertResolved(at("2026-09-15T19:00-04:00"), TimePrecision.APPROXIMATE, "this evening")
        assertResolved(standard, TimePrecision.APPROXIMATE, "tonight")
        assertResolved(at("2026-09-15T09:00-04:00"), TimePrecision.APPROXIMATE, "earlier this morning")
        assertResolved(at("2026-09-15T15:00-04:00"), TimePrecision.APPROXIMATE, "earlier this afternoon")
        assertResolved(at("2026-09-15T19:00-04:00"), TimePrecision.APPROXIMATE, "earlier this evening")
    }

    @Test
    fun rule4_bandsWithExplicitCaptureTimes() {
        val d = "2026-09-15"
        assertResolved(local(d, "08:30"), TimePrecision.APPROXIMATE, "this morning", local(d, "08:30"))
        assertFuture("this morning", local(d, "04:00"))
        assertResolved(local(d, "09:00"), TimePrecision.APPROXIMATE, "this morning", local(d, "13:00"))
        assertFuture("this afternoon", local(d, "10:00"))
        assertResolved(local(d, "15:00"), TimePrecision.APPROXIMATE, "this afternoon", local(d, "16:00"))
        assertResolved(local(d, "12:30"), TimePrecision.APPROXIMATE, "this afternoon", local(d, "12:30"))
        assertFuture("this evening", local(d, "16:00"))
        assertResolved(local(d, "21:00"), TimePrecision.APPROXIMATE, "tonight", local(d, "23:00"))
        assertResolved(local(d, "20:00"), TimePrecision.APPROXIMATE, "tonight", local(d, "20:00"))
        assertFuture("tonight", local(d, "16:00"))
    }

    // ---- Rule 5: earlier today ----

    @Test
    fun rule5_earlierTodayIsMidpoint() {
        assertResolved(at("2026-09-15T10:00-04:00"), TimePrecision.APPROXIMATE, "earlier today")
    }

    // ---- Rule 6: last night ----

    @Test
    fun rule6_lastNight() {
        assertResolved(at("2026-09-14T21:00-04:00"), TimePrecision.APPROXIMATE, "last night")
    }

    // ---- Rule 7: today / yesterday ----

    @Test
    fun rule7_todayAndYesterday() {
        assertResolved(at("2026-09-15T00:00-04:00"), TimePrecision.DATE_ONLY, "today")
        assertResolved(at("2026-09-14T00:00-04:00"), TimePrecision.DATE_ONLY, "yesterday")
    }

    // ---- Rule 8: minutes / hours ago ----

    @Test
    fun rule8_minutesAndHoursAgo() {
        val approx = TimePrecision.APPROXIMATE
        assertResolved(at("2026-09-15T19:00-04:00"), approx, "about an hour ago")
        assertResolved(at("2026-09-15T19:00-04:00"), approx, "an hour ago")
        assertResolved(at("2026-09-15T19:40-04:00"), approx, "20 minutes ago")
        assertResolved(at("2026-09-15T19:30-04:00"), approx, "half an hour ago")
        assertResolved(at("2026-09-15T18:00-04:00"), approx, "a couple of hours ago")
        assertResolved(at("2026-09-15T18:00-04:00"), approx, "around 2 hours ago")
        assertResolved(at("2026-09-15T19:59-04:00"), approx, "a minute ago")
        assertResolved(at("2026-09-15T19:55-04:00"), approx, "five mins ago")
        assertResolved(at("2026-09-15T19:58-04:00"), approx, "a couple of minutes ago")
        assertResolved(at("2026-09-15T08:00-04:00"), approx, "twelve hours ago")
        assertResolved(at("2026-09-15T19:59-04:00"), approx, "1 min ago")
        assertUnresolvable("0 minutes ago")
        assertUnresolvable("a few hours ago")
    }

    // ---- Rule 9: days / weeks ago ----

    @Test
    fun rule9_daysAndWeeksAgo() {
        val date = TimePrecision.DATE_ONLY
        assertResolved(at("2026-09-13T00:00-04:00"), date, "two days ago")
        assertResolved(at("2026-09-13T00:00-04:00"), date, "2 days ago")
        assertResolved(at("2026-09-08T00:00-04:00"), date, "a week ago")
        assertResolved(at("2026-09-01T00:00-04:00"), date, "a couple of weeks ago")
        assertResolved(at("2026-08-25T00:00-04:00"), date, "about three weeks ago")
        assertResolved(at("2026-09-03T00:00-04:00"), date, "twelve days ago")
        assertResolved(at("2026-09-14T00:00-04:00"), date, "1 day ago")
        assertResolved(at("2026-09-14T00:00-04:00"), date, "a day ago")
        assertResolved(at("2026-09-13T00:00-04:00"), date, "a couple of days ago")
        assertUnresolvable("a few days ago")
        assertUnresolvable("several days ago")
        assertUnresolvable("a month ago")
        assertUnresolvable("last week")
    }

    // ---- Rule 10: weekdays ----

    @Test
    fun rule10_allWeekdaysFromTuesday() {
        val expected = mapOf(
            "Monday" to "2026-09-14",
            "Sunday" to "2026-09-13",
            "Saturday" to "2026-09-12",
            "Friday" to "2026-09-11",
            "Thursday" to "2026-09-10",
            "Wednesday" to "2026-09-09",
            "Tuesday" to "2026-09-08",
        )
        expected.forEach { (name, date) ->
            assertResolved(at("${date}T00:00-04:00"), TimePrecision.DATE_ONLY, name)
        }
        assertResolved(at("2026-09-12T00:00-04:00"), TimePrecision.DATE_ONLY, "Saturday")
        assertEquals(resolve("Saturday"), resolve("last Saturday"))
        assertEquals(resolve("Saturday"), resolve("on Saturday"))
        assertEquals(resolve("Saturday"), resolve("SATURDAY"))
    }

    // ---- Rule 11: month-name dates ----

    @Test
    fun rule11_monthNameDates() {
        val sept1 = TemporalResolution.Resolved(at("2026-09-01T00:00-04:00"), TimePrecision.DATE_ONLY)
        assertEquals(sept1, resolve("September 1st"))
        listOf("Sept 1", "Sept. 1", "sep 1", "1 September", "the 1st of September", "1st of Sept.", "on September 1")
            .forEach { assertEquals(sept1, resolve(it), "expression: $it") }
        assertResolved(at("2025-12-25T00:00-05:00"), TimePrecision.DATE_ONLY, "December 25")
        assertResolved(at("2026-09-15T00:00-04:00"), TimePrecision.DATE_ONLY, "September 15")
        assertUnresolvable("September 31")
        assertUnresolvable("September 0")
        assertResolved(at("2024-02-29T00:00-05:00"), TimePrecision.DATE_ONLY, "February 29")
    }

    // ---- Rule 12: clock times ----

    @Test
    fun rule12_clockTimes() {
        assertResolved(at("2026-09-15T15:00-04:00"), TimePrecision.EXACT, "at 3pm")
        assertFuture("at 3pm", local("2026-09-15", "14:00"))
        assertResolved(at("2026-09-15T07:30-04:00"), TimePrecision.EXACT, "at 7:30 am")
        assertResolved(at("2026-09-15T15:00-04:00"), TimePrecision.EXACT, "3 pm")
        assertResolved(at("2026-09-15T15:00-04:00"), TimePrecision.EXACT, "3 o'clock this afternoon")
        assertResolved(at("2026-09-15T08:00-04:00"), TimePrecision.EXACT, "8 this morning")
        assertResolved(at("2026-09-15T19:15-04:00"), TimePrecision.EXACT, "7:15 this evening")
        assertFuture("9pm")
        assertUnresolvable("at 3")
        assertUnresolvable("13pm")
    }

    // ---- Rule 13: future ----

    @Test
    fun rule13_futurePhrases() {
        listOf("tomorrow", "next week", "next Saturday", "later", "later today", "in 2 hours", "in an hour")
            .forEach { assertFuture(it) }
    }

    // ---- Rule 14: unresolvable ----

    @Test
    fun rule14_unresolvablePhrases() {
        listOf(
            "9/1", "2026-09-01", "recently", "the other day", "earlier", "gibberish words",
            "last month", "a few days ago", "several days ago", "a month ago", "last week",
        ).forEach { assertUnresolvable(it) }
    }

    // ---- TEST_STRATEGY section 5 ----

    @Test
    fun testStrategySection5Cases() {
        assertResolved(at("2026-09-14T00:00-04:00"), TimePrecision.DATE_ONLY, "yesterday")
        assertResolved(at("2026-09-15T09:00-04:00"), TimePrecision.APPROXIMATE, "this morning")
        assertResolved(at("2026-09-12T00:00-04:00"), TimePrecision.DATE_ONLY, "Saturday")
        assertResolved(at("2026-09-15T19:00-04:00"), TimePrecision.APPROXIMATE, "about an hour ago")
        assertResolved(standard, TimePrecision.INFERRED_NOW, "just finished")
        assertResolved(at("2026-09-01T00:00-04:00"), TimePrecision.DATE_ONLY, "September 1st")
    }

    // ---- Punctuation / casing ----

    @Test
    fun punctuationAndCasingTolerance() {
        assertEquals(resolve("yesterday"), resolve("Yesterday."))
        assertEquals(resolve("this morning"), resolve("  this MORNING!  "))
        assertEquals(resolve("Saturday"), resolve("on Saturday,"))
    }

    // ---- DST ----

    @Test
    fun dstSpringForwardDetroit() {
        val capture = local("2026-03-08", "20:00")
        assertResolved(at("2026-03-08T00:00-05:00"), TimePrecision.DATE_ONLY, "today", capture)
        assertResolved(at("2026-03-07T00:00-05:00"), TimePrecision.DATE_ONLY, "yesterday", capture)
        val twoHours = resolve("2 hours ago", capture) as TemporalResolution.Resolved
        assertEquals(7200L, Duration.between(twoHours.occurredAt, capture).seconds)
    }

    @Test
    fun dstFallBackDetroit() {
        val capture = local("2026-11-01", "20:00")
        assertResolved(at("2026-11-01T00:00-04:00"), TimePrecision.DATE_ONLY, "today", capture)
        assertResolved(at("2026-10-31T00:00-04:00"), TimePrecision.DATE_ONLY, "yesterday", capture)

        val secondOneThirty = at("2026-11-01T01:30-05:00")
        val hour = resolve("an hour ago", secondOneThirty) as TemporalResolution.Resolved
        assertEquals(3600L, Duration.between(hour.occurredAt, secondOneThirty).seconds)
        assertEquals(TimePrecision.APPROXIMATE, hour.precision)
    }

    @Test
    fun dstMidnightGapSantiago() {
        val santiago = ZoneId.of("America/Santiago")
        val rules = santiago.rules
        // Find the 2026 spring-forward (offset increases) transition from tzdata.
        var transition = rules.nextTransition(at("2026-07-01T00:00Z"))
        while (!transition.isGap) transition = rules.nextTransition(transition.instant)
        val gapDate = transition.dateTimeAfter.toLocalDate()
        val startOfGapDay = gapDate.atStartOfDay(santiago)
        assertEquals(LocalTime.of(1, 0), startOfGapDay.toLocalTime(), "expected a midnight gap in tzdata")

        val capture = gapDate.atTime(20, 0).atZone(santiago).toInstant()
        assertResolved(startOfGapDay.toInstant(), TimePrecision.DATE_ONLY, "today", capture, santiago)
        assertResolved(
            gapDate.minusDays(1).atStartOfDay(santiago).toInstant(),
            TimePrecision.DATE_ONLY, "yesterday", capture, santiago,
        )
        // Just after the gap: start of day is 01:00, "earlier today" midpoint stays in range.
        val early = gapDate.atTime(1, 10).atZone(santiago).toInstant()
        val mid = resolve("earlier today", early, santiago) as TemporalResolution.Resolved
        assertEquals(startOfGapDay.toInstant().plusSeconds(300), mid.occurredAt)
    }

    // ---- Just after midnight ----

    @Test
    fun justAfterMidnight() {
        val capture = local("2026-09-16", "00:10")
        assertResolved(at("2026-09-15T00:00-04:00"), TimePrecision.DATE_ONLY, "yesterday", capture)
        assertResolved(at("2026-09-15T21:00-04:00"), TimePrecision.APPROXIMATE, "last night", capture)
        assertFuture("this morning", capture)
    }

    // ---- Global invariant ----

    private val allPhrases = listOf(
        null, "", "   ", "just now", "just", "now", "right now", "a moment ago", "just finished", "just did it",
        "yesterday morning", "yesterday afternoon", "yesterday evening",
        "this morning", "this afternoon", "this evening", "tonight",
        "earlier this morning", "earlier this afternoon", "earlier this evening",
        "earlier today", "last night", "today", "yesterday",
        "about an hour ago", "an hour ago", "20 minutes ago", "half an hour ago", "a couple of hours ago",
        "around 2 hours ago", "a minute ago", "five mins ago", "a couple of minutes ago", "twelve hours ago",
        "1 min ago", "0 minutes ago", "a few hours ago", "2 hours ago",
        "two days ago", "2 days ago", "a week ago", "a couple of weeks ago", "about three weeks ago",
        "twelve days ago", "1 day ago", "a day ago", "a couple of days ago", "999 weeks ago",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "last Saturday", "on Saturday", "SATURDAY", "on Saturday,",
        "September 1st", "Sept 1", "Sept. 1", "sep 1", "1 September", "the 1st of September", "1st of Sept.",
        "on September 1", "December 25", "September 15", "September 16", "September 31", "February 29",
        "January 1", "March 8", "November 1", "September 0",
        "at 3pm", "at 7:30 am", "3 pm", "3 o'clock this afternoon", "8 this morning", "7:15 this evening",
        "9pm", "11:59 pm", "12 am", "12:05 am", "1:30 am", "at 3", "13pm",
        "tomorrow", "next week", "next Saturday", "later", "later today", "in 2 hours", "in an hour",
        "9/1", "2026-09-01", "recently", "the other day", "earlier", "gibberish words", "last month",
        "a few days ago", "several days ago", "a month ago", "last week",
        "Yesterday.", "  this MORNING!  ",
    )

    @Test
    fun invariantNoResolvedResultIsAfterCapture() {
        val zones = listOf(detroit, ZoneId.of("America/Santiago"))
        val dates = listOf("2026-09-15", "2026-03-08", "2026-11-01", "2026-09-06")
        val times = listOf("00:10", "06:00", "12:00", "18:00", "23:50")
        var checked = 0
        for (zone in zones) for (date in dates) for (time in times) {
            val capture = local(date, time, zone)
            for (phrase in allPhrases) {
                val result = resolver.resolve(phrase, capture, zone)
                if (result is TemporalResolution.Resolved && result.occurredAt.isAfter(capture)) {
                    fail("'$phrase' at $capture in $zone resolved after capture: ${result.occurredAt}")
                }
                checked++
            }
        }
        assertTrue(checked > 0)
    }

    // ---- Orchestrator review fixes ----

    @Test
    fun hedgedClockTimeIsApproximateNotExact() {
        assertResolved(local("2026-09-15", "15:00"), TimePrecision.APPROXIMATE, "about 3pm")
        assertResolved(local("2026-09-15", "07:30"), TimePrecision.APPROXIMATE, "around 7:30 am")
        assertResolved(local("2026-09-15", "15:00"), TimePrecision.EXACT, "at 3pm")
    }

    @Test
    fun punctuationOnlyIsUnresolvableNotNow() {
        assertUnresolvable("?")
        assertUnresolvable("...")
    }

    @Test
    fun clockTimeInDstGapIsUnresolvable() {
        assertUnresolvable("2:30 am", capturedAt = local("2026-03-08", "20:00"))
        assertResolved(
            local("2026-03-08", "03:30"),
            TimePrecision.EXACT,
            "3:30 am",
            capturedAt = local("2026-03-08", "20:00"),
        )
    }
}
