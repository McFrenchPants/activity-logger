package com.mcfrenchpants.activityledger.core.domain.stats

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypicalGapTest {

    private val base = Instant.parse("2026-01-01T00:00:00Z")

    private fun daysAt(vararg days: Long) = days.map { base.plus(Duration.ofDays(it)) }

    @Test
    fun `fewer than three entries gives no gap`() {
        assertNull(TypicalGap.fromTimes(emptyList()))
        assertNull(TypicalGap.fromTimes(daysAt(0)))
        assertNull(TypicalGap.fromTimes(daysAt(0, 5)))
    }

    @Test
    fun `median of odd gap count`() {
        // gaps 1, 10, 3 -> sorted 1, 3, 10 -> median 3
        val g = TypicalGap.fromTimes(daysAt(0, 1, 11, 14))!!
        assertEquals(Duration.ofDays(3), g.duration)
        assertEquals(GapUnit.DAYS, g.unit)
        assertEquals(3, g.amount)
    }

    @Test
    fun `median of even gap count is mean of the two middle gaps`() {
        // gaps 2, 8 -> 5
        val g = TypicalGap.fromTimes(daysAt(0, 2, 10))!!
        assertEquals(Duration.ofDays(5), g.duration)
        // gaps 1, 2, 4, 30 -> (2 + 4) / 2 = 3
        val g2 = TypicalGap.fromTimes(daysAt(37, 0, 1, 3, 7))!!
        assertEquals(Duration.ofDays(3), g2.duration)
    }

    @Test
    fun `hours under twenty hours with minimum one`() {
        assertEquals(TypicalGap(Duration.ofHours(19), GapUnit.HOURS, 19), TypicalGap.of(Duration.ofHours(19)))
        assertEquals(TypicalGap(Duration.ofHours(20), GapUnit.DAYS, 1), TypicalGap.of(Duration.ofHours(20)))
        assertEquals(TypicalGap(Duration.ofHours(47), GapUnit.DAYS, 2), TypicalGap.of(Duration.ofHours(47)))
        assertEquals(1, TypicalGap.of(Duration.ofMinutes(10)).amount)
        assertEquals(GapUnit.HOURS, TypicalGap.of(Duration.ZERO).unit)
        assertEquals(1, TypicalGap.of(Duration.ZERO).amount)
        assertEquals(2, TypicalGap.of(Duration.ofMinutes(90)).amount)
    }

    @Test
    fun `two days switches to days`() {
        val g = TypicalGap.of(Duration.ofDays(2))
        assertEquals(GapUnit.DAYS, g.unit)
        assertEquals(2, g.amount)
    }

    @Test
    fun `days round half up`() {
        assertEquals(3, TypicalGap.of(Duration.ofHours(60)).amount) // 2.5 days
        assertEquals(2, TypicalGap.of(Duration.ofHours(59)).amount)
    }

    @Test
    fun `59 days is days and 60 days is months`() {
        assertEquals(TypicalGap(Duration.ofDays(59), GapUnit.DAYS, 59), TypicalGap.of(Duration.ofDays(59)))
        assertEquals(TypicalGap(Duration.ofDays(60), GapUnit.MONTHS, 2), TypicalGap.of(Duration.ofDays(60)))
    }

    @Test
    fun `729 days is months and 730 days is years`() {
        assertEquals(TypicalGap(Duration.ofDays(729), GapUnit.MONTHS, 24), TypicalGap.of(Duration.ofDays(729)))
        assertEquals(TypicalGap(Duration.ofDays(730), GapUnit.YEARS, 2), TypicalGap.of(Duration.ofDays(730)))
        assertEquals(5, TypicalGap.of(Duration.ofDays(1826)).amount)
    }

    @Test
    fun `every day and every week helpers`() {
        assertTrue(TypicalGap.of(Duration.ofDays(1)).isEveryDay)
        assertTrue(TypicalGap.of(Duration.ofHours(23)).isEveryDay)
        assertTrue(TypicalGap(Duration.ofDays(1), GapUnit.DAYS, 1).isEveryDay)
        assertTrue(TypicalGap.of(Duration.ofDays(7)).isEveryWeek)
        assertFalse(TypicalGap.of(Duration.ofDays(8)).isEveryWeek)
        assertFalse(TypicalGap(Duration.ofDays(7), GapUnit.MONTHS, 7).isEveryWeek)
    }
}
