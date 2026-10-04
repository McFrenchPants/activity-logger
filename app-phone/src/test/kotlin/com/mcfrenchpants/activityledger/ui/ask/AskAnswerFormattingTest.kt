package com.mcfrenchpants.activityledger.ui.ask

import com.mcfrenchpants.activityledger.core.domain.lookup.LookupTier
import org.junit.Test
import java.time.Duration
import kotlin.test.assertEquals

class AskAnswerFormattingTest {

    @Test
    fun `a gap under an hour is less than an hour`() {
        assertEquals(IntervalWords.LessThanAnHour, intervalWords(Duration.ZERO))
        assertEquals(IntervalWords.LessThanAnHour, intervalWords(Duration.ofMinutes(59)))
        assertEquals(IntervalWords.LessThanAnHour, intervalWords(Duration.ofMinutes(-5)))
    }

    @Test
    fun `a gap under a day is whole hours rounded down`() {
        assertEquals(IntervalWords.Hours(1), intervalWords(Duration.ofMinutes(60)))
        assertEquals(IntervalWords.Hours(5), intervalWords(Duration.ofMinutes(5 * 60 + 59)))
        assertEquals(IntervalWords.Hours(23), intervalWords(Duration.ofHours(24).minusSeconds(1)))
    }

    @Test
    fun `a gap of a day or more is whole days rounded down`() {
        assertEquals(IntervalWords.Days(1), intervalWords(Duration.ofHours(24)))
        assertEquals(IntervalWords.Days(1), intervalWords(Duration.ofHours(47)))
        assertEquals(IntervalWords.Days(12), intervalWords(Duration.ofDays(12).plusHours(3)))
    }

    @Test
    fun `a match that is not exact always gets the not-exact note`() {
        LookupTier.entries.forEach { tier ->
            assertEquals(MatchNote.NOT_EXACT, matchNote(exact = false, tier = tier))
        }
    }

    @Test
    fun `an exact partial-tier match gets the partial note`() {
        assertEquals(MatchNote.PARTIAL, matchNote(exact = true, tier = LookupTier.SUBJECT_ONLY))
        assertEquals(MatchNote.PARTIAL, matchNote(exact = true, tier = LookupTier.ACTION_ONLY))
    }

    @Test
    fun `an exact match on both sides gets no note`() {
        assertEquals(MatchNote.NONE, matchNote(exact = true, tier = LookupTier.BOTH))
    }
}
