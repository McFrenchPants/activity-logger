package com.mcfrenchpants.activityledger.core.domain.temporal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DurationResolverTest {

    private fun assertMinutes(expected: Int, text: String) =
        assertEquals(expected, DurationResolver.resolve(text), "minutes for \"$text\"")

    @Test
    fun `half an hour`() {
        assertMinutes(30, "for half an hour")
        assertMinutes(30, "half an hour")
        assertMinutes(30, "Half hour")
        assertMinutes(30, "about half an hour.")
    }

    @Test
    fun `a quarter of an hour`() {
        assertMinutes(15, "a quarter of an hour")
        assertMinutes(15, "quarter of an hour")
        assertMinutes(15, "for a quarter hour")
    }

    @Test
    fun `minutes with digits and hedges`() {
        assertMinutes(30, "for about 30 minutes")
        assertMinutes(30, "about 30 minutes")
        assertMinutes(30, "30 minutes")
        assertMinutes(40, "40 minutes")
        assertMinutes(40, "Spent 40 minutes")
        assertMinutes(45, "45 min")
        assertMinutes(45, "45min")
        assertMinutes(20, "around 20 mins")
        assertMinutes(25, "roughly 25 minutes")
        assertMinutes(10, "spent like 10 minutes")
        assertMinutes(90, "90 minutes")
        assertMinutes(1, "a minute")
    }

    @Test
    fun `minutes with number words`() {
        assertMinutes(5, "five minutes")
        assertMinutes(12, "twelve minutes")
        assertMinutes(20, "twenty minutes")
        assertMinutes(30, "thirty minutes")
        assertMinutes(40, "forty minutes")
        assertMinutes(45, "forty-five minutes")
        assertMinutes(45, "forty five minutes")
        assertMinutes(50, "fifty minutes")
        assertMinutes(60, "sixty minutes")
    }

    @Test
    fun `one hour`() {
        assertMinutes(60, "an hour")
        assertMinutes(60, "one hour")
        assertMinutes(60, "1 hour")
        assertMinutes(60, "for an hour")
        assertMinutes(60, "1 hr")
    }

    @Test
    fun `an hour and a half`() {
        assertMinutes(90, "an hour and a half")
        assertMinutes(90, "1.5 hours")
        assertMinutes(90, "90 minutes")
        assertMinutes(90, "one and a half hours")
        assertMinutes(150, "two hours and a half")
    }

    @Test
    fun `two hours`() {
        assertMinutes(120, "two hours")
        assertMinutes(120, "2 hours")
        assertMinutes(120, "2 hrs")
        assertMinutes(120, "for about two hours")
    }

    @Test
    fun `hours and minutes`() {
        assertMinutes(75, "an hour and 15 minutes")
        assertMinutes(130, "2 hours 10 minutes")
    }

    @Test
    fun `rounds to whole minutes`() {
        assertMinutes(75, "1.25 hours")
        assertMinutes(3, "2.5 minutes")
        assertMinutes(20, "0.33 hours")
    }

    @Test
    fun `limits`() {
        assertMinutes(24 * 60, "24 hours")
        assertNull(DurationResolver.resolve("25 hours"))
        assertNull(DurationResolver.resolve("0 minutes"))
        assertNull(DurationResolver.resolve("0.001 hours"))
    }

    @Test
    fun `absent or unreadable is null`() {
        listOf(
            null, "", "   ", "for", "about", "a while", "all afternoon", "30 to 40 minutes",
            "30-40 minutes", "half an hour or so", "two days", "yesterday", "minutes", "an hour ago",
            "seventy minutes", "!!",
        ).forEach { assertNull(DurationResolver.resolve(it), "expected null for \"$it\"") }
    }
}
