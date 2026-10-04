package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Host-side tests for the busy retry in front of the on-device extractor; no test sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class BusyRetryExtractorTest {

    private class ScriptedExtractor : ActivityExtractor {
        var provenanceValue = InterpreterProvenance("v", "p", 1)
        override val provenance: InterpreterProvenance get() = provenanceValue
        val queue = ArrayDeque<ExtractionResult>()
        var fallback: () -> ExtractionResult = { success() }
        var callCount = 0
        val received = mutableListOf<ExtractionInput>()

        override suspend fun extract(input: ExtractionInput): ExtractionResult {
            callCount++
            received += input
            return queue.removeFirstOrNull() ?: fallback()
        }
    }

    private val delegate = ScriptedExtractor()
    private val waits = mutableListOf<Duration>()
    private val retrying = BusyRetryExtractor(delegate, wait = { waits += it })

    @Test
    fun `a retryable refusal then a success returns the success after one two second wait`() =
        runBlocking {
            val success = success()
            delegate.queue += failure(InterpreterFailureKind.RETRYABLE)
            delegate.queue += success

            assertSame(success, retrying.extract(INPUT))
            assertEquals(2, delegate.callCount)
            assertEquals(listOf(2.seconds), waits)
            assertEquals(listOf(INPUT, INPUT), delegate.received)
        }

    @Test
    fun `three retryable refusals return the last failure after waits of two and four seconds`() =
        runBlocking {
            val last = failure(InterpreterFailureKind.RETRYABLE)
            delegate.queue += failure(InterpreterFailureKind.RETRYABLE)
            delegate.queue += failure(InterpreterFailureKind.RETRYABLE)
            delegate.queue += last

            assertSame(last, retrying.extract(INPUT))
            assertEquals(3, delegate.callCount)
            assertEquals(listOf(2.seconds, 4.seconds), waits)
        }

    @Test
    fun `malformed is returned at once without a retry`() =
        assertReturnedAtOnce(failure(InterpreterFailureKind.MALFORMED))

    @Test
    fun `other is returned at once without a retry`() =
        assertReturnedAtOnce(failure(InterpreterFailureKind.OTHER))

    @Test
    fun `unavailable is returned at once without a retry`() =
        assertReturnedAtOnce(failure(InterpreterFailureKind.UNAVAILABLE))

    @Test
    fun `a success is returned at once without a retry`() = assertReturnedAtOnce(success())

    @Test
    fun `an answer after a busy refusal is never re-asked`() = runBlocking {
        val malformed = failure(InterpreterFailureKind.MALFORMED)
        delegate.queue += failure(InterpreterFailureKind.RETRYABLE)
        delegate.queue += malformed

        assertSame(malformed, retrying.extract(INPUT))
        assertEquals(2, delegate.callCount)
        assertEquals(listOf(2.seconds), waits)
    }

    @Test
    fun `provenance is the delegate's`() {
        val provenance = InterpreterProvenance("delegate-7", "prompt-3", 4)
        delegate.provenanceValue = provenance
        assertEquals(provenance, retrying.provenance)
    }

    @Test
    fun `the default wait uses coroutine time, two seconds then four`() = runTest {
        delegate.fallback = { failure(InterpreterFailureKind.RETRYABLE) }

        val result = BusyRetryExtractor(delegate).extract(INPUT)

        assertEquals(failure(InterpreterFailureKind.RETRYABLE), result)
        assertEquals(3, delegate.callCount)
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun `cancellation during the wait propagates and the delegate is not called again`() =
        runTest {
            delegate.fallback = { failure(InterpreterFailureKind.RETRYABLE) }
            val defaultWaits = BusyRetryExtractor(delegate)
            var finished = false

            val job = launch {
                defaultWaits.extract(INPUT)
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

    private fun assertReturnedAtOnce(expected: ExtractionResult) = runBlocking {
        delegate.queue += expected

        assertSame(expected, retrying.extract(INPUT))
        assertEquals(1, delegate.callCount)
        assertTrue(waits.isEmpty())
    }

    private companion object {
        val INPUT = ExtractionInput(
            rawText = "synthetic words",
            capturedAt = Instant.parse("2026-03-04T09:15:00Z"),
            zoneId = ZoneId.of("Europe/London"),
        )

        fun failure(kind: InterpreterFailureKind) = ExtractionResult.Failure(kind)

        fun success() = ExtractionResult.Success(
            ExtractionCandidate(
                operation = InterpretationOperation.LOG_ACTIVITY,
                subject = "lawn",
                action = "mow",
                activityState = ActivityState.COMPLETED,
                temporalExpression = null,
                durationExpression = null,
            ),
        )
    }
}
