package com.mcfrenchpants.activityledger.core.domain.naming

import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck.Reason
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck.Result
import kotlin.test.Test
import kotlin.test.assertEquals

class NewActivityNameCheckTest {
    private fun invalid(reason: Reason) = Result.Invalid(reason)

    @Test
    fun `bad examples are invalid with specific reasons`() {
        assertEquals(invalid(Reason.FIRST_PERSON_NARRATIVE), NewActivityNameCheck.check("I flushed the water heater today"))
        assertEquals(invalid(Reason.CONTAINS_FILLER_WORD), NewActivityNameCheck.check("Water heater stuff"))
        assertEquals(invalid(Reason.CONTAINS_COMPLETION_WORD), NewActivityNameCheck.check("Completed dryer cleaning activity"))
    }

    @Test
    fun `good examples are ok`() {
        listOf(
            "Flush water heater", "Clean dryer vent", "Replace smoke detector battery",
            "Mow lawn", "Edge lawn", "Change oil",
        ).forEach { assertEquals(Result.Ok, NewActivityNameCheck.check(it), it) }
    }

    @Test
    fun `length bounds`() {
        assertEquals(Result.Ok, NewActivityNameCheck.check("a"))
        assertEquals(Result.Ok, NewActivityNameCheck.check("a".repeat(60)))
        assertEquals(Result.Ok, NewActivityNameCheck.check("  " + "a".repeat(60) + "  "))
        assertEquals(invalid(Reason.EMPTY), NewActivityNameCheck.check(""))
        assertEquals(invalid(Reason.EMPTY), NewActivityNameCheck.check(" \t\n "))
        assertEquals(invalid(Reason.TOO_LONG), NewActivityNameCheck.check("a".repeat(61)))
    }

    @Test
    fun `punctuation only is invalid`() {
        assertEquals(invalid(Reason.NO_MEANINGFUL_TEXT), NewActivityNameCheck.check("?!."))
    }

    @Test
    fun `digits only is invalid`() {
        assertEquals(invalid(Reason.DIGITS_ONLY), NewActivityNameCheck.check("12345"))
        assertEquals(invalid(Reason.DIGITS_ONLY), NewActivityNameCheck.check("42."))
        assertEquals(Result.Ok, NewActivityNameCheck.check("Change 2 filters"))
    }

    @Test
    fun `weekday and month names are invalid`() {
        assertEquals(invalid(Reason.CONTAINS_TIME_WORD), NewActivityNameCheck.check("Saturday gutter check"))
        assertEquals(invalid(Reason.CONTAINS_TIME_WORD), NewActivityNameCheck.check("Clean pool in September"))
    }

    @Test
    fun `morning walk is invalid by design`() {
        assertEquals(invalid(Reason.CONTAINS_TIME_WORD), NewActivityNameCheck.check("Morning walk"))
    }

    @Test
    fun `word rules match whole words only`() {
        assertEquals(Result.Ok, NewActivityNameCheck.check("Donate clothes"))
        assertEquals(Result.Ok, NewActivityNameCheck.check("Refinish deck"))
        assertEquals(Result.Ok, NewActivityNameCheck.check("Stuffing prep"))
        assertEquals(Result.Ok, NewActivityNameCheck.check("Ice rink"))
    }

    @Test
    fun `first person openers are invalid case-insensitively`() {
        listOf("I mowed", "i've mowed", "I'm mowing", "WE washed car", "We've washed car", "I’ve mowed")
            .forEach { assertEquals(invalid(Reason.FIRST_PERSON_NARRATIVE), NewActivityNameCheck.check(it), it) }
    }
}
