package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.interpretation.CandidateActivity
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CaptureProcessingOutcome
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Host-side (no device, no model) tests for the bounded busy retry the phone pipeline puts in
 * front of the on-device interpreter. No test really sleeps: waits are either recorded by an
 * injected wait function or run on kotlinx-coroutines-test virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusyRetryInterpreterTest {

    private val delegate = FakeActivityInterpreter()
    private val waits = mutableListOf<Duration>()
    private val retrying = BusyRetryInterpreter(delegate, wait = { waits += it })

    @Test
    fun `the production schedule is two seconds then four`() {
        assertEquals(listOf(2.seconds, 4.seconds), RETRY_WAITS)
    }

    @Test
    fun `a retryable refusal then a success returns the success after one two second wait`() =
        runBlocking {
            val success = success()
            delegate.enqueue(failure(InterpreterFailureKind.RETRYABLE), success)

            val result = retrying.interpret(INPUT)

            assertSame(success, result)
            assertEquals(2, delegate.callCount)
            assertEquals(listOf(2.seconds), waits)
            assertEquals(listOf(INPUT, INPUT), delegate.receivedInputs)
        }

    @Test
    fun `three retryable refusals return the last failure after waits of two and four seconds`() =
        runBlocking {
            val last = failure(InterpreterFailureKind.RETRYABLE)
            delegate.enqueue(
                failure(InterpreterFailureKind.RETRYABLE),
                failure(InterpreterFailureKind.RETRYABLE),
                last,
            )
            // A fourth call would find a success here; it must never be made.
            delegate.respondWith { success() }

            val result = retrying.interpret(INPUT)

            assertSame(last, result)
            assertEquals(3, delegate.callCount)
            assertEquals(listOf(2.seconds, 4.seconds), waits)
        }

    @Test
    fun `malformed is returned at once without a retry`() = assertReturnedAtOnce(
        failure(InterpreterFailureKind.MALFORMED),
    )

    @Test
    fun `other is returned at once without a retry`() = assertReturnedAtOnce(
        failure(InterpreterFailureKind.OTHER),
    )

    @Test
    fun `unavailable is returned at once without a retry`() = assertReturnedAtOnce(
        failure(InterpreterFailureKind.UNAVAILABLE),
    )

    @Test
    fun `a success is returned at once without a retry`() = assertReturnedAtOnce(success())

    @Test
    fun `an answer after a busy refusal is never re-asked`() = runBlocking {
        val malformed = failure(InterpreterFailureKind.MALFORMED)
        delegate.enqueue(failure(InterpreterFailureKind.RETRYABLE), malformed)
        delegate.respondWith { success() }

        assertSame(malformed, retrying.interpret(INPUT))
        assertEquals(2, delegate.callCount)
        assertEquals(listOf(2.seconds), waits)
    }

    @Test
    fun `provenance is the delegate's`() {
        val provenance = InterpreterProvenance(
            interpreterVersion = "delegate-7",
            promptVersion = "prompt-3",
            schemaVersion = 4,
        )
        delegate.provenance = provenance

        assertEquals(provenance, retrying.provenance)
    }

    @Test
    fun `the default wait uses coroutine time, two seconds then four`() = runTest {
        delegate.respondWith { failure(InterpreterFailureKind.RETRYABLE) }
        val defaultWaits = BusyRetryInterpreter(delegate)

        val result = defaultWaits.interpret(INPUT)

        assertEquals(failure(InterpreterFailureKind.RETRYABLE), result)
        assertEquals(3, delegate.callCount)
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun `cancellation during the wait propagates and the delegate is not called again`() =
        runTest {
            delegate.respondWith { failure(InterpreterFailureKind.RETRYABLE) }
            val defaultWaits = BusyRetryInterpreter(delegate)
            var finished = false

            val job = launch {
                defaultWaits.interpret(INPUT)
                finished = true
            }
            runCurrent()
            assertEquals(1, delegate.callCount)

            advanceTimeBy(1_000L)
            job.cancel()
            advanceUntilIdle()

            assertTrue(job.isCancelled)
            assertFalse(finished)
            assertEquals(1, delegate.callCount)
        }

    @Test
    fun `a busy refusal then a success through the orchestrator ends logged`() = runBlocking {
        val zone = ZoneId.of("America/Detroit")
        val capturedAt = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
        val captureText = "I cut the grass yesterday."
        val clock = MutableClock(capturedAt, zone)
        val repository = InMemoryActivityRepository(clock)
        // Composed exactly as CapturePipeline.create does, with the identity decorator.
        val orchestrator = CaptureInterpretationOrchestrator(
            repository = repository,
            interpreter = retrying,
            clock = clock,
        )
        val mowLawn = repository.seedActivity("Mow lawn")
        val captureId = repository.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = null,
                capturedAt = capturedAt,
                zoneId = zone,
                rawText = captureText,
                speechConfidence = null,
                speechAlternativesJson = null,
                processingState = ProcessingState.CAPTURED,
            ),
        )
        delegate.enqueue(failure(InterpreterFailureKind.RETRYABLE), success(mowLawn))

        val outcome = orchestrator.process(captureId)

        val accepted = assertIs<CaptureProcessingOutcome.AutoAccepted>(outcome)
        val occurrence = assertNotNull(repository.getOccurrence(accepted.occurrenceId))
        assertEquals(captureId, occurrence.rawCaptureId)
        assertEquals(mowLawn, occurrence.canonicalActivityId)
        val stored = assertNotNull(repository.getCapture(captureId))
        assertTrue(stored.hasOccurrence)
        assertTrue(stored.processingState != ProcessingState.FAILED_RETRYABLE)
        assertEquals(captureText, stored.rawText)
        assertEquals(2, delegate.callCount)
        assertEquals(listOf(2.seconds), waits)
    }

    private fun assertReturnedAtOnce(expected: InterpretationResult) = runBlocking {
        delegate.enqueue(expected)
        delegate.respondWith { success() }

        assertSame(expected, retrying.interpret(INPUT))
        assertEquals(1, delegate.callCount)
        assertTrue(waits.isEmpty())
    }

    private fun failure(kind: InterpreterFailureKind) =
        InterpretationResult.Failure(kind, structuredResultJson = null)

    private fun success(activityId: String = "act-1") = InterpretationResult.Success(
        candidate = InterpretationCandidate(
            operation = InterpretationOperation.LOG_ACTIVITY,
            activityResolution = ActivityResolution.EXISTING_ACTIVITY,
            matchedActivityId = activityId,
            proposedCanonicalName = null,
            activityState = ActivityState.COMPLETED,
            temporalExpression = "yesterday",
            confidenceBand = ConfidenceBand.HIGH,
        ),
        structuredResultJson = null,
    )

    private companion object {
        val INPUT = InterpretationInput(
            rawText = "mowed the lawn yesterday",
            capturedAt = Instant.parse("2026-03-04T09:15:00Z"),
            zoneId = ZoneId.of("Europe/London"),
            candidates = listOf(CandidateActivity("act-1", "mow lawn", listOf("cut grass"))),
        )
    }
}
