package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CaptureProcessingOutcome
import com.mcfrenchpants.activityledger.core.domain.services.TaggedProcessingOutcome
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureEnvelope
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolResult
import com.mcfrenchpants.activityledger.core.wearprotocol.WireCodec
import com.mcfrenchpants.activityledger.wear.WatchCaptureReceiver
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The foreground catch-up (ADR-029): captures stored while the app was in the background are
 * interpreted, oldest first and once each, when the app is next in the foreground -- never
 * concurrently with the watch receiver or the Log screen, and never while in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundCatchUpTest {
    private val zone = ZoneId.of("America/Detroit")
    private val t0: Instant = ZonedDateTime.of(2026, 10, 14, 9, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(t0.plusSeconds(3_600), zone)
    private val memory = InMemoryActivityRepository(clock)
    private val lock = Mutex()

    private var foreground = true
    private val processed = mutableListOf<String>()
    private val inFlight = mutableSetOf<String>()
    private var concurrentOverlap = false

    /** Runs before [process] records its outcome; tests use it to suspend or flip the signal. */
    private var duringProcess: suspend (String) -> Unit = {}

    /** Records the call and sends the capture to review, like a model answer that needs a check. */
    private val process: suspend (String) -> TaggedProcessingOutcome = { captureId ->
        if (!inFlight.add(captureId)) concurrentOverlap = true
        processed += captureId
        try {
            duringProcess(captureId)
            memory.recordOutcome(captureId, null, ProcessingState.NEEDS_REVIEW)
        } finally {
            inFlight.remove(captureId)
        }
        TaggedProcessingOutcome.NeedsReview(emptySet(), emptySet(), null)
    }

    private fun seed(id: String, minutesAfterT0: Long, state: ProcessingState): String = runSuspend {
        memory.createRawCapture(
            NewRawCapture(
                CaptureSource.WATCH_VOICE, "watch-app", t0.plusSeconds(minutesAfterT0 * 60), zone,
                "words $id", null, null, state, id,
            ),
        )
    }

    private fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    private fun TestScope.catchUp() = ForegroundCatchUp(
        repository = memory,
        process = process,
        isForeground = { foreground },
        lock = lock,
        scope = worker(),
    )

    /**
     * A scope on the test scheduler that the test does not wait for (the catch-up's loop never
     * ends) and that, unlike backgroundScope, advanceUntilIdle still drives.
     */
    private fun TestScope.worker() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun state(id: String) = runSuspend { memory.getCapture(id)!!.processingState }

    @Test
    fun processesCapturedAndFailedRetryableOldestFirstExactlyOnce() = runTest {
        seed("c-new", 30, ProcessingState.CAPTURED)
        seed("c-old", 1, ProcessingState.CAPTURED)
        seed("c-mid", 10, ProcessingState.FAILED_RETRYABLE)
        val catchUp = catchUp()

        catchUp.onForeground()
        advanceUntilIdle()
        assertEquals(listOf("c-old", "c-mid", "c-new"), processed)

        catchUp.onForeground()
        advanceUntilIdle()
        assertEquals(3, processed.size, "already-handled captures are not processed again")
        assertEquals(ProcessingState.NEEDS_REVIEW, state("c-old"))
    }

    @Test
    fun skipsNeedsReviewFailedFinalAndSavedCaptures() = runTest {
        seed("review", 1, ProcessingState.NEEDS_REVIEW)
        seed("final", 2, ProcessingState.FAILED_FINAL)
        seed("pending", 3, ProcessingState.CAPTURED)
        // A saved capture: a real occurrence, made through the v3 orchestrator over a fake model.
        val mow = memory.seedActivity("Mow lawn")
        val interpreter = FakeActivityInterpreter().apply {
            respondWith {
                InterpretationResult.Success(
                    InterpretationCandidate(
                        InterpretationOperation.LOG_ACTIVITY, ActivityResolution.EXISTING_ACTIVITY, mow, null,
                        ActivityState.COMPLETED, null, ConfidenceBand.HIGH,
                    ),
                    null,
                )
            }
        }
        seed("saved", 0, ProcessingState.CAPTURED)
        val outcome = CaptureInterpretationOrchestrator(memory, interpreter, clock).process("saved")
        assertTrue(outcome is CaptureProcessingOutcome.AutoAccepted)

        catchUp().onForeground()
        advanceUntilIdle()

        assertEquals(listOf("pending"), processed)
        assertEquals(ProcessingState.NEEDS_REVIEW, state("review"))
        assertEquals(ProcessingState.FAILED_FINAL, state("final"))
    }

    @Test
    fun doesNotRunWhileInTheBackground() = runTest {
        seed("a", 1, ProcessingState.CAPTURED)
        foreground = false
        catchUp().onForeground()
        advanceUntilIdle()
        assertEquals(emptyList(), processed)
        assertEquals(ProcessingState.CAPTURED, state("a"))
    }

    @Test
    fun stopsWhenTheAppLeavesTheForegroundAndResumesOnTheNextTransition() = runTest {
        seed("a", 1, ProcessingState.CAPTURED)
        seed("b", 2, ProcessingState.CAPTURED)
        seed("c", 3, ProcessingState.CAPTURED)
        duringProcess = { foreground = false }
        val catchUp = catchUp()

        catchUp.onForeground()
        advanceUntilIdle()
        assertEquals(listOf("a"), processed)
        assertEquals(ProcessingState.CAPTURED, state("b"))

        duringProcess = {}
        foreground = true
        catchUp.onForeground()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), processed)
    }

    @Test
    fun runsAgainOnTheNextForegroundTransitionForNewCaptures() = runTest {
        seed("a", 1, ProcessingState.CAPTURED)
        val catchUp = catchUp()
        catchUp.onForeground()
        advanceUntilIdle()
        assertEquals(listOf("a"), processed)

        seed("b", 2, ProcessingState.CAPTURED)
        catchUp.onForeground()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), processed)
    }

    @Test
    fun aFailingProcessIsSwallowedAndTheSweepContinues() = runTest {
        seed("a", 1, ProcessingState.CAPTURED)
        seed("b", 2, ProcessingState.CAPTURED)
        duringProcess = { if (it == "a") throw IllegalStateException("boom") }
        catchUp().onForeground()
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), processed)
        assertEquals(ProcessingState.CAPTURED, state("a"))
        assertEquals(ProcessingState.NEEDS_REVIEW, state("b"))
    }

    @Test
    fun neverProcessesACaptureConcurrentlyWithTheWatchReceiver() = runTest {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val gate = CompletableDeferred<Unit>()
        duringProcess = { gate.await() }
        val receiver = WatchCaptureReceiver(
            repository = memory,
            process = process,
            sendAck = { _, _ -> },
            zone = { zone },
            isForeground = { foreground },
            lock = lock,
        )
        val envelope = (
            WireCodec.encode(
                CaptureEnvelope(
                    captureId = id, rawText = "words", capturedAtEpochMillis = t0.toEpochMilli(),
                    sourceSurface = "watch-app", speechConfidence = null, alternatives = null,
                ),
            ) as ProtocolResult.Ok
            ).value

        // The receiver stores and starts interpreting, then waits inside the model call.
        worker().launch { receiver.receive("node", envelope) }
        advanceUntilIdle()
        assertEquals(listOf(id), processed)

        // The capture is CAPTURED right now; the sweep must wait for the receiver, then skip it.
        catchUp().onForeground()
        advanceUntilIdle()
        assertEquals(listOf(id), processed)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(id), processed)
        assertEquals(false, concurrentOverlap)
        assertEquals(ProcessingState.NEEDS_REVIEW, state(id))
    }

    @Test
    fun doesNotProcessACaptureTheLogScreenIsInterpreting() = runTest {
        val gate = CompletableDeferred<Unit>()
        // Stands in for LogViewModel: create and interpret under the shared lock.
        worker().launch {
            lock.withLock {
                seed("typed", 1, ProcessingState.CAPTURED)
                gate.await()
                memory.recordOutcome("typed", null, ProcessingState.NEEDS_REVIEW)
            }
        }
        advanceUntilIdle()

        catchUp().onForeground()
        advanceUntilIdle()
        assertEquals(emptyList(), processed)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList(), processed)
        assertEquals(ProcessingState.NEEDS_REVIEW, state("typed"))
    }
}
