package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractor
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Host-side tests for the busy retry in front of the on-device question extractor; no test sleeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class BusyRetryQuestionExtractorTest {

    private class ScriptedExtractor : QuestionExtractor {
        var provenanceValue = InterpreterProvenance("v", "p", 1)
        override val provenance: InterpreterProvenance get() = provenanceValue
        val queue = ArrayDeque<QuestionExtractionResult>()
        var fallback: () -> QuestionExtractionResult = { success() }
        var callCount = 0
        val received = mutableListOf<String>()

        override suspend fun extract(questionText: String): QuestionExtractionResult {
            callCount++
            received += questionText
            return queue.removeFirstOrNull() ?: fallback()
        }
    }

    private val delegate = ScriptedExtractor()
    private val waits = mutableListOf<Duration>()
    private val retrying = BusyRetryQuestionExtractor(delegate, wait = { waits += it })

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

        val result = BusyRetryQuestionExtractor(delegate).extract(INPUT)

        assertEquals(failure(InterpreterFailureKind.RETRYABLE), result)
        assertEquals(3, delegate.callCount)
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun `cancellation during the wait propagates and the delegate is not called again`() =
        runTest {
            delegate.fallback = { failure(InterpreterFailureKind.RETRYABLE) }
            val defaultWaits = BusyRetryQuestionExtractor(delegate)
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

    private fun assertReturnedAtOnce(expected: QuestionExtractionResult) = runBlocking {
        delegate.queue += expected

        assertSame(expected, retrying.extract(INPUT))
        assertEquals(1, delegate.callCount)
        assertTrue(waits.isEmpty())
    }

    private companion object {
        const val INPUT = "when did I mow the lawn?"

        fun failure(kind: InterpreterFailureKind) = QuestionExtractionResult.Failure(kind)

        fun success() = QuestionExtractionResult.Success(QuestionCandidate(subject = "lawn", action = "mow"))
    }
}
