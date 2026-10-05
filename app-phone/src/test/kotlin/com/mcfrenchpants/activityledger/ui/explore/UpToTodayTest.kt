package com.mcfrenchpants.activityledger.ui.explore

import androidx.compose.material3.ExperimentalMaterial3Api
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The custom-range picker offers days up to and including today, never later. */
@OptIn(ExperimentalMaterial3Api::class)
class UpToTodayTest {

    private val today = LocalDate.of(2026, 10, 4)
    private val rule = UpToToday(today)
    private fun millis(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun todayAndEarlierAreSelectable() {
        assertTrue(rule.isSelectableDate(millis(today)))
        assertTrue(rule.isSelectableDate(millis(LocalDate.of(2025, 1, 1))))
    }

    @Test
    fun laterDaysAndYearsAreNot() {
        assertFalse(rule.isSelectableDate(millis(today.plusDays(1))))
        assertTrue(rule.isSelectableYear(2026))
        assertFalse(rule.isSelectableYear(2027))
    }
}
