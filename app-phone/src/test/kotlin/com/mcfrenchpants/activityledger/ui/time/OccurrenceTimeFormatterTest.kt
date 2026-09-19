package com.mcfrenchpants.activityledger.ui.time

import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolution
import com.mcfrenchpants.activityledger.core.domain.temporal.TemporalResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Pure-JVM tests for [OccurrenceTimeFormatter] against UX_VISUAL_SPEC 4.3. */
class OccurrenceTimeFormatterTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    /** Saturday 2026-09-19, 15:30 in New York. */
    private val now: Instant = local(2026, 9, 19, 15, 30)

    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int, z: ZoneId = zone): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant()

    private fun format(at: Instant, precision: TimePrecision, z: ZoneId = zone, n: Instant = now) =
        OccurrenceTimeFormatter.format(at, precision, z, n, Locale.US)

    // ---- EXACT / INFERRED_NOW ----

    @Test
    fun exactToday() {
        assertEquals("Today, 3:12 PM", format(local(2026, 9, 19, 15, 12), TimePrecision.EXACT))
    }

    @Test
    fun exactOtherDay() {
        assertEquals("Sep 12, 9:40 AM", format(local(2026, 9, 12, 9, 40), TimePrecision.EXACT))
    }

    @Test
    fun inferredNowTodayAndOtherDay() {
        assertEquals("Today, 3:12 PM", format(local(2026, 9, 19, 15, 12), TimePrecision.INFERRED_NOW))
        assertEquals("Sep 12, 9:40 AM", format(local(2026, 9, 12, 9, 40), TimePrecision.INFERRED_NOW))
    }

    @Test
    fun yesterdayIsNotSpecialCased() {
        assertEquals("Sep 18, 8:05 PM", format(local(2026, 9, 18, 20, 5), TimePrecision.EXACT))
        assertEquals("Sep 18, evening", format(local(2026, 9, 18, 19, 0), TimePrecision.APPROXIMATE))
        assertEquals("Fri, Sep 18", format(local(2026, 9, 18, 0, 0), TimePrecision.DATE_ONLY))
    }

    // ---- APPROXIMATE ----

    @Test
    fun approximateToday() {
        assertEquals("Today, afternoon", format(local(2026, 9, 19, 15, 0), TimePrecision.APPROXIMATE))
    }

    @Test
    fun approximateOtherDay() {
        assertEquals("Aug 23, morning", format(local(2026, 8, 23, 9, 0), TimePrecision.APPROXIMATE))
    }

    // ---- DATE_ONLY ----

    @Test
    fun dateOnlyOtherDay() {
        assertEquals("Sat, Sep 12", format(local(2026, 9, 12, 0, 0), TimePrecision.DATE_ONLY))
    }

    @Test
    fun dateOnlyTodayNeverSaysToday() {
        assertEquals("Sat, Sep 19", format(local(2026, 9, 19, 0, 0), TimePrecision.DATE_ONLY))
    }

    // ---- Year rule ----

    @Test
    fun otherYearAppendsYear() {
        assertEquals("Sep 12, 2025, 9:40 AM", format(local(2025, 9, 12, 9, 40), TimePrecision.EXACT))
        assertEquals("Sep 12, 2025, 9:40 AM", format(local(2025, 9, 12, 9, 40), TimePrecision.INFERRED_NOW))
        assertEquals("Aug 23, 2025, morning", format(local(2025, 8, 23, 9, 0), TimePrecision.APPROXIMATE))
        // 2025-09-12 was a Friday.
        assertEquals("Fri, Sep 12, 2025", format(local(2025, 9, 12, 0, 0), TimePrecision.DATE_ONLY))
    }

    @Test
    fun sameYearNeverAppendsYear() {
        assertEquals("Jan 1, 12:00 AM", format(local(2026, 1, 1, 0, 0), TimePrecision.EXACT))
    }

    // ---- Zone boundary ----

    @Test
    fun zoneDecidesToday() {
        val n = Instant.parse("2026-09-19T12:00:00Z")
        val at = Instant.parse("2026-09-19T02:30:00Z")
        // London (UTC+1): 03:30 on the 19th, same day as now.
        assertEquals("Today, 3:30 AM", format(at, TimePrecision.EXACT, ZoneId.of("Europe/London"), n))
        // Los Angeles (UTC-7): 19:30 on the 18th, while now is the 19th there.
        assertEquals("Sep 18, 7:30 PM", format(at, TimePrecision.EXACT, ZoneId.of("America/Los_Angeles"), n))
        assertEquals("Sep 18, evening", format(at, TimePrecision.APPROXIMATE, ZoneId.of("America/Los_Angeles"), n))
        assertEquals("Today, night", format(at, TimePrecision.APPROXIMATE, ZoneId.of("Europe/London"), n))
    }

    @Test
    fun zoneDecidesYear() {
        val n = Instant.parse("2027-01-01T12:00:00Z")
        val at = Instant.parse("2027-01-01T03:00:00Z")
        // New York: still 2026-12-31 22:00 -> different year from now's 2027.
        assertEquals("Dec 31, 2026, 10:00 PM", format(at, TimePrecision.EXACT, zone, n))
        // UTC: same day.
        assertEquals("Today, 3:00 AM", format(at, TimePrecision.EXACT, ZoneId.of("UTC"), n))
    }

    // ---- Part of day ----

    @Test
    fun partOfDayBoundaries() {
        val cases = mapOf(
            LocalTime.of(0, 0) to "night",
            LocalTime.of(4, 59) to "night",
            LocalTime.of(5, 0) to "morning",
            LocalTime.of(11, 59) to "morning",
            LocalTime.of(12, 0) to "afternoon",
            LocalTime.of(16, 59) to "afternoon",
            LocalTime.of(17, 0) to "evening",
            LocalTime.of(20, 59) to "evening",
            LocalTime.of(21, 0) to "night",
            LocalTime.of(23, 59) to "night",
        )
        cases.forEach { (time, expected) ->
            assertEquals("at $time", expected, OccurrenceTimeFormatter.partOfDay(time))
        }
    }

    @Test
    fun partOfDayRoundTripsTemporalResolverAnchors() {
        val resolver = TemporalResolver()
        val cases = mapOf(
            "yesterday morning" to "Sep 18, morning",
            "yesterday afternoon" to "Sep 18, afternoon",
            "yesterday evening" to "Sep 18, evening",
            "last night" to "Sep 18, night",
        )
        cases.forEach { (phrase, expected) ->
            val resolved = resolver.resolve(phrase, now, zone) as TemporalResolution.Resolved
            assertEquals(phrase, TimePrecision.APPROXIMATE, resolved.precision)
            assertEquals(phrase, expected, format(resolved.occurredAt, resolved.precision))
        }
        // "this morning" at 15:30 resolves to today's 09:00 anchor.
        val thisMorning = resolver.resolve("this morning", now, zone) as TemporalResolution.Resolved
        assertEquals("Today, morning", format(thisMorning.occurredAt, thisMorning.precision))
        val thisAfternoon = resolver.resolve("this afternoon", now, zone) as TemporalResolution.Resolved
        assertEquals("Today, afternoon", format(thisAfternoon.occurredAt, thisAfternoon.precision))
    }

    // ---- Never a clock time the user did not give ----

    @Test
    fun approximateAndDateOnlyNeverContainAClockTime() {
        val clock = Regex("\\d:\\d")
        val zones = listOf(zone, ZoneId.of("UTC"), ZoneId.of("Asia/Kolkata"))
        for (z in zones) {
            for (day in listOf(-400L, -2L, -1L, 0L)) {
                for (minuteOfDay in 0 until 24 * 60 step 7) {
                    val at = now.atZone(z).toLocalDate().plusDays(day)
                        .atStartOfDay(z).plusMinutes(minuteOfDay.toLong()).toInstant()
                    if (at.isAfter(now)) continue
                    for (precision in listOf(TimePrecision.APPROXIMATE, TimePrecision.DATE_ONLY)) {
                        val text = format(at, precision, z)
                        assertFalse("$precision rendered a clock time: $text", clock.containsMatchIn(text))
                    }
                }
            }
        }
    }
}
