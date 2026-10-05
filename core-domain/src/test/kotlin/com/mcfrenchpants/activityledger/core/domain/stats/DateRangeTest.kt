package com.mcfrenchpants.activityledger.core.domain.stats

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DateRangeTest {

    private val today = LocalDate.of(2026, 11, 15)

    private fun preset(kind: DateRangePreset) = DateRangeSelection.Preset(kind)

    @Test
    fun `last 7 days is today and the 6 days before`() {
        val r = resolve(preset(DateRangePreset.LAST_7_DAYS), today, null)
        assertEquals(ResolvedRange(LocalDate.of(2026, 11, 9), today, 7), r)
    }

    @Test
    fun `last 30 days is today and the 29 days before`() {
        val r = resolve(preset(DateRangePreset.LAST_30_DAYS), today, null)
        assertEquals(ResolvedRange(LocalDate.of(2026, 10, 17), today, 30), r)
        assertTrue(today in r)
    }

    @Test
    fun `last 12 months starts the day after the same date a year ago`() {
        val r = resolve(preset(DateRangePreset.LAST_12_MONTHS), today, null)
        assertEquals(LocalDate.of(2025, 11, 16), r.start)
        assertEquals(today, r.endInclusive)
        assertEquals(365, r.dayCount)
    }

    @Test
    fun `all time starts at earliest entry date or today`() {
        val earliest = LocalDate.of(2024, 2, 3)
        val r = resolve(preset(DateRangePreset.ALL_TIME), today, earliest)
        assertEquals(earliest, r.start)
        assertEquals(today, r.endInclusive)
        assertEquals(java.time.temporal.ChronoUnit.DAYS.between(earliest, today) + 1, r.dayCount)

        val none = resolve(preset(DateRangePreset.ALL_TIME), today, null)
        assertEquals(ResolvedRange(today, today, 1), none)

        val future = resolve(preset(DateRangePreset.ALL_TIME), today, today.plusDays(3))
        assertEquals(ResolvedRange(today, today, 1), future)
    }

    @Test
    fun `custom bounds are inclusive`() {
        val c = DateRangeSelection.Custom(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), RangeLabel.Month(YearMonth.of(2026, 8)))
        val r = resolve(c, today, null)
        assertEquals(31, r.dayCount)
        assertTrue(LocalDate.of(2026, 8, 1) in r)
        assertTrue(LocalDate.of(2026, 8, 31) in r)
        assertFalse(LocalDate.of(2026, 7, 31) in r)
        assertFalse(LocalDate.of(2026, 9, 1) in r)

        val single = resolve(DateRangeSelection.Custom(today, today, RangeLabel.Year(2026)), today, null)
        assertEquals(1, single.dayCount)
    }

    @Test
    fun `custom start after end is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DateRangeSelection.Custom(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1))
        }
    }

    @Test
    fun `filter default and scope kinds`() {
        assertTrue(ExploreFilter().isDefault)
        assertTrue(ExploreFilter(words = "  ").isDefault)
        assertFalse(ExploreFilter(words = "x").isDefault)
        assertFalse(ExploreFilter(subjectId = "s").isDefault)
        assertFalse(ExploreFilter(range = preset(DateRangePreset.LAST_7_DAYS)).isDefault)

        assertEquals(ScopeKind.MANY, ExploreFilter().scopeKind)
        assertEquals(ScopeKind.MANY, ExploreFilter(words = "furnace").scopeKind)
        assertEquals(ScopeKind.ONE_SUBJECT, ExploreFilter(subjectId = "s").scopeKind)
        assertEquals(ScopeKind.ONE_ACTION, ExploreFilter(actionId = "a").scopeKind)
        assertEquals(ScopeKind.ONE_ACTIVITY, ExploreFilter(subjectId = "s", actionId = "a").scopeKind)
    }
}
