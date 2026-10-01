package com.mcfrenchpants.activityledger.wear.capture

import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.speech.SpeechTranscriber
import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSessionControllerTest {

    /** Each listen() hands out a fresh channel so tests can drive events and see cancellation. */
    private class FakeTranscriber : SpeechTranscriber {
        var listens = 0
        var cancelled = false
        var channel = Channel<SpeechEvent>(Channel.UNLIMITED)

        override fun listen(): Flow<SpeechEvent> {
            listens++
            channel = Channel(Channel.UNLIMITED)
            val mine = channel
            return flow {
                try {
                    emitAll(mine.consumeAsFlow())
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
        }

        private suspend fun kotlinx.coroutines.flow.FlowCollector<SpeechEvent>.emitAll(f: Flow<SpeechEvent>) {
            f.collect { emit(it) }
        }
    }

    private class FakeSink(var fail: Boolean = false) : CaptureSink {
        val received = mutableListOf<WatchTranscript>()
        override suspend fun enqueue(transcript: WatchTranscript): String {
            if (fail) throw IllegalStateException("boom")
            received += transcript
            return "id-${received.size}"
        }
    }

    private fun TestScope.make(
        sink: FakeSink = FakeSink(),
        t: FakeTranscriber = FakeTranscriber(),
    ): Triple<CaptureSessionController, FakeTranscriber, FakeSink> {
        val scope = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(testScheduler))
        return Triple(CaptureSessionController(t, sink, scope), t, sink)
    }

    private fun ack(id: String, status: AckStatus, name: String? = null) =
        CaptureAck(captureId = id, status = status, canonicalActivityName = name)

    @Test
    fun partialThenFinalQueuesOnceWithExactPayload() = runTest {
        val (c, t, sink) = make()
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.PartialTranscript("ran fi"))
        advanceUntilIdle()
        assertEquals(CaptureUiState.Listening("ran fi"), c.state.value)
        t.channel.trySend(SpeechEvent.FinalTranscript("ran five km", 0.8f, listOf("ran 5 km")))
        t.channel.close()
        advanceUntilIdle()
        assertEquals(CaptureUiState.Queued, c.state.value)
        assertEquals(listOf(WatchTranscript("ran five km", 0.8f, listOf("ran 5 km"))), sink.received)
    }

    @Test
    fun blankFinalFailsAndWritesNothing() = runTest {
        val (c, t, sink) = make()
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.FinalTranscript("   ", null, emptyList()))
        t.channel.close()
        advanceUntilIdle()
        assertEquals(CaptureUiState.Failure, c.state.value)
        assertTrue(sink.received.isEmpty())
    }

    private fun failureCase(kind: SpeechFailure, expected: CaptureUiState) = runTest {
        val (c, t, _) = make()
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.Failed(kind))
        t.channel.close()
        advanceUntilIdle()
        assertEquals(expected, c.state.value)
    }

    @Test
    fun permissionMissingIsUnavailable() = failureCase(
        SpeechFailure.PERMISSION_MISSING,
        CaptureUiState.Unavailable(UnavailableReason.PERMISSION_MISSING),
    )

    @Test
    fun noEngineIsUnavailable() = failureCase(
        SpeechFailure.NO_ON_DEVICE_ENGINE,
        CaptureUiState.Unavailable(UnavailableReason.NO_ENGINE),
    )

    @Test
    fun cancelledLeavesStateUnchanged() = failureCase(SpeechFailure.CANCELLED, CaptureUiState.Listening(null))

    @Test
    fun nothingHeardIsFailure() = failureCase(SpeechFailure.NOTHING_HEARD, CaptureUiState.Failure)

    @Test
    fun recognizerBusyIsFailure() = failureCase(SpeechFailure.RECOGNIZER_BUSY, CaptureUiState.Failure)

    @Test
    fun engineErrorIsFailure() = failureCase(SpeechFailure.ENGINE_ERROR, CaptureUiState.Failure)

    @Test
    fun enqueueThrowingFailsWithoutText() = runTest {
        val (c, t, _) = make(sink = FakeSink(fail = true))
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.FinalTranscript("secret words", null, emptyList()))
        t.channel.close()
        advanceUntilIdle()
        assertEquals(CaptureUiState.Failure, c.state.value)
        assertFalse(c.state.value.toString().contains("secret"))
    }

    @Test
    fun stopCancelsUnderlyingFlowAndIsRepeatable() = runTest {
        val (c, t, _) = make()
        c.start()
        advanceUntilIdle()
        assertFalse(t.cancelled)
        c.stop()
        c.stop()
        advanceUntilIdle()
        assertTrue(t.cancelled)
    }

    @Test
    fun stopClearsPartialText() = runTest {
        val (c, t, _) = make()
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.PartialTranscript("private"))
        advanceUntilIdle()
        c.stop()
        assertEquals(CaptureUiState.Listening(null), c.state.value)
    }

    @Test
    fun startTwiceListensOnce() = runTest {
        val (c, t, _) = make()
        c.start()
        c.start()
        advanceUntilIdle()
        c.start()
        assertEquals(1, t.listens)
    }

    @Test
    fun retryFromFailureStartsNewSession() = runTest {
        val (c, t, _) = make()
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.Failed(SpeechFailure.NOTHING_HEARD))
        t.channel.close()
        advanceUntilIdle()
        assertEquals(CaptureUiState.Failure, c.state.value)
        c.retry()
        advanceUntilIdle()
        assertEquals(2, t.listens)
        assertEquals(CaptureUiState.Listening(null), c.state.value)
    }

    @Test
    fun retryIgnoredOutsideFailure() = runTest {
        val (c, t, _) = make()
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.Failed(SpeechFailure.NO_ON_DEVICE_ENGINE))
        t.channel.close()
        advanceUntilIdle()
        c.retry()
        advanceUntilIdle()
        assertEquals(1, t.listens)
        assertEquals(CaptureUiState.Unavailable(UnavailableReason.NO_ENGINE), c.state.value)
    }

    private fun TestScope.queued(): Pair<CaptureSessionController, String> {
        val (c, t, sink) = make()
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.FinalTranscript("x", null, emptyList()))
        t.channel.close()
        advanceUntilIdle()
        return c to "id-${sink.received.size}"
    }

    @Test
    fun resumeKeepsQueuedCaptureSoItsAckStillLands() = runTest {
        val (c, id) = queued()
        c.resume()
        advanceUntilIdle()
        assertEquals(CaptureUiState.Queued, c.state.value)
        c.onAck(ack(id, AckStatus.SAVED, "Run"))
        assertEquals(CaptureUiState.Saved("Run"), c.state.value)
        c.resume()
        assertEquals(CaptureUiState.Saved("Run"), c.state.value)
    }

    @Test
    fun tapOnFinishedCaptureStartsNewSession() = runTest {
        val (c, id) = queued()
        c.onAck(ack(id, AckStatus.SAVED, "Run"))
        c.retry()
        advanceUntilIdle()
        assertEquals(CaptureUiState.Listening(null), c.state.value)
    }

    @Test
    fun ackMatchingAppliesAndIsIdempotent() = runTest {
        val (c, id) = queued()
        c.onAck(ack(id, AckStatus.SAVED, "Run"))
        assertEquals(CaptureUiState.Saved("Run"), c.state.value)
        c.onAck(ack(id, AckStatus.SAVED, "Run"))
        assertEquals(CaptureUiState.Saved("Run"), c.state.value)
    }

    @Test
    fun ackStatusesMap() = runTest {
        val (c, id) = queued()
        c.onAck(ack(id, AckStatus.NEEDS_REVIEW))
        assertEquals(CaptureUiState.NeedsReview, c.state.value)
        c.onAck(ack(id, AckStatus.FAILED_RETRYABLE))
        assertEquals(CaptureUiState.Failure, c.state.value)
        c.onAck(ack(id, AckStatus.FAILED_FINAL))
        assertEquals(CaptureUiState.Failure, c.state.value)
    }

    @Test
    fun receivedAckAndForeignIdAreIgnored() = runTest {
        val (c, id) = queued()
        c.onAck(ack(id, AckStatus.RECEIVED))
        assertEquals(CaptureUiState.Queued, c.state.value)
        c.onAck(ack("someone-else", AckStatus.SAVED, "Run"))
        assertEquals(CaptureUiState.Queued, c.state.value)
    }

    @Test
    fun noPartialTextSurvivesPastListening() = runTest {
        val (c, t, _) = make()
        c.start()
        advanceUntilIdle()
        t.channel.trySend(SpeechEvent.PartialTranscript("hello partial"))
        t.channel.trySend(SpeechEvent.FinalTranscript("hello", null, emptyList()))
        t.channel.close()
        advanceUntilIdle()
        assertEquals(CaptureUiState.Queued, c.state.value)
        assertFalse(c.state.value.toString().contains("hello"))
    }
}
