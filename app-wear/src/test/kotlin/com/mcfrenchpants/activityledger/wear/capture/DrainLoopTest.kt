package com.mcfrenchpants.activityledger.wear.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrainLoopTest {
    private class FakeScheduler : RetryScheduling {
        val calls = mutableListOf<Long?>()
        override fun scheduleAt(timeMillis: Long?) {
            calls += timeMillis
        }
    }

    @Test
    fun wakeDuringDrainCausesExactlyOneMorePassAndNeverOverlaps() = runTest {
        val scheduler = FakeScheduler()
        val gate = CompletableDeferred<Unit>()
        var passes = 0
        var active = 0
        var maxActive = 0
        val loop = DrainLoop(
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            {
                passes++
                active++
                maxActive = maxOf(maxActive, active)
                if (passes == 1) gate.await()
                active--
                passes * 100L
            },
            scheduler,
        )
        loop.wake()
        runCurrent()
        assertEquals(1, passes)
        loop.wake()
        loop.wake()
        runCurrent()
        assertEquals(1, passes)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, passes)
        assertEquals(1, maxActive)
        assertEquals(listOf<Long?>(100L, 200L), scheduler.calls)
        loop.wake()
        advanceUntilIdle()
        assertEquals(3, passes)
    }

    @Test
    fun nullReturnCancelsAndFailureDoesNotStopLaterWakes() = runTest {
        val scheduler = FakeScheduler()
        val results = ArrayDeque<Long?>(listOf(null, -1L, 7L))
        var passes = 0
        val loop = DrainLoop(
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            {
                passes++
                val r = results.removeFirst()
                if (r == -1L) throw IllegalStateException("boom")
                r
            },
            scheduler,
        )
        loop.wake()
        advanceUntilIdle()
        loop.wake()
        advanceUntilIdle()
        loop.wake()
        advanceUntilIdle()
        assertEquals(3, passes)
        assertEquals(listOf<Long?>(null, 7L), scheduler.calls)
        assertTrue(scheduler.calls.first() == null)
    }
}
