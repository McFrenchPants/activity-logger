package com.mcfrenchpants.activityledger.pipeline

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The resumed-activity foreground signal: transitions, balance across activities, and its floor. */
class ForegroundSignalTest {
    private val signal = ForegroundSignal()

    @Test
    fun startsInTheBackground() {
        assertFalse(signal.isForeground.value)
    }

    @Test
    fun firstResumeIsTheOnlyTransitionUntilEverythingPauses() {
        assertTrue(signal.onResumed())
        assertTrue(signal.isForeground.value)
        assertFalse(signal.onResumed(), "a second resumed activity is not a new transition")
        signal.onPaused()
        assertTrue(signal.isForeground.value, "one activity is still resumed")
        signal.onPaused()
        assertFalse(signal.isForeground.value)
        assertTrue(signal.onResumed(), "coming back is a new transition")
    }

    @Test
    fun configurationChangePauseThenResumeIsBalanced() {
        signal.onResumed()
        // Old instance pauses, new instance resumes: one fresh transition, and still balanced.
        signal.onPaused()
        assertFalse(signal.isForeground.value)
        assertTrue(signal.onResumed())
        signal.onPaused()
        assertFalse(signal.isForeground.value)
    }

    @Test
    fun anUnmatchedPauseNeverDrivesTheCountNegative() {
        signal.onPaused()
        signal.onPaused()
        assertFalse(signal.isForeground.value)
        assertTrue(signal.onResumed(), "one resume still reaches the foreground")
        signal.onPaused()
        assertFalse(signal.isForeground.value, "and one pause leaves it; nothing is stuck")
    }
}
