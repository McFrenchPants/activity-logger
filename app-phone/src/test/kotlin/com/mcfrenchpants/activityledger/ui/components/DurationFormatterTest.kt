package com.mcfrenchpants.activityledger.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Checks of the plain-words duration formatter: the rounding rules and the final wording. */
@RunWith(AndroidJUnit4::class)
class DurationFormatterTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun words(seconds: Long) = DurationFormatter.format(resources, seconds)

    @Test
    fun minutesOnly() {
        assertEquals("30 min", words(1_800))
        assertEquals("1 min", words(60))
        assertEquals("59 min", words(59 * 60L))
    }

    @Test
    fun hoursAndMinutes() {
        assertEquals("1 h 15 min", words(75 * 60L))
        assertEquals("2 h 5 min", words(125 * 60L))
    }

    @Test
    fun wholeHoursDropTheMinutes() {
        assertEquals("1 h", words(3_600))
        assertEquals("2 h", words(7_200))
    }

    @Test
    fun roundsToTheNearestMinuteButNeverShowsARealDurationAsNothing() {
        assertEquals("1 min", words(20)) // under a minute but real: at least one minute
        assertEquals("1 min", words(89))
        assertEquals("2 min", words(90))
        assertEquals("0 min", words(0))
        assertEquals("0 min", words(-5))
    }

    @Test
    fun partsSplitHoursFromMinutes() {
        assertEquals(DurationFormatter.Parts(1, 15), DurationFormatter.parts(75 * 60L))
        assertEquals(DurationFormatter.Parts(0, 0), DurationFormatter.parts(0))
        assertEquals(DurationFormatter.Parts(3, 0), DurationFormatter.parts(3 * 3_600L + 20))
    }
}
