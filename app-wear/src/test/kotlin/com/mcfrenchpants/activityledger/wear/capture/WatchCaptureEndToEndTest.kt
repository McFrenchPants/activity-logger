package com.mcfrenchpants.activityledger.wear.capture

import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechTranscriber
import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureEnvelope
import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxState
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolResult
import com.mcfrenchpants.activityledger.core.wearprotocol.WireCodec
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.EnqueueResult
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.FileOutboxStore
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.MAX_OUTBOX_RECORDS
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.Outbox
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.OutboxPolicy
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.OutboxRecord
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchCaptureEndToEndTest {
    private val secret = "SECRET-spoken-words-xyz"
    private val wait = OutboxPolicy.Default.ackWaitMillis
    private lateinit var dir: File
    private var now = 5_000_000L
    private var idCounter = 0L

    @Before fun setUp() {
        dir = Files.createTempDirectory("wc15a").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** Stands in for the phone receiver: one stored capture per captureId, acks on every receipt. */
    private class FakePhone {
        val stored = LinkedHashMap<String, CaptureEnvelope>()
        var receipts = 0
        var finalStatus = AckStatus.SAVED
        var sendFinalAck = true
        var dropAllAcks = false

        fun receive(json: String): List<CaptureAck> {
            receipts++
            val env = (WireCodec.decodeEnvelope(json) as ProtocolResult.Ok).value
            stored.getOrPut(env.captureId) { env }
            if (dropAllAcks) return emptyList()
            val acks = mutableListOf(CaptureAck(captureId = env.captureId, status = AckStatus.RECEIVED))
            if (sendFinalAck) {
                acks += CaptureAck(
                    captureId = env.captureId,
                    status = finalStatus,
                    canonicalActivityName = if (finalStatus == AckStatus.SAVED) "Running" else null,
                )
            }
            return acks
        }
    }

    private class FakeTransport(val phone: FakePhone) : CaptureTransport {
        var online = true
        val sent = mutableListOf<Triple<String, String, Long>>()
        val retracted = mutableListOf<String>()
        val inFlightAcks = mutableListOf<CaptureAck>()

        override suspend fun send(captureId: String, envelopeJson: String, attempt: Long): SendOutcome {
            sent += Triple(captureId, envelopeJson, attempt)
            if (!online) return SendOutcome.TRANSIENT_FAILURE
            inFlightAcks += phone.receive(envelopeJson)
            return SendOutcome.DELIVERED_TO_LINK
        }

        override suspend fun retract(captureId: String) {
            retracted += captureId
        }
    }

    private class FakeTranscriber : SpeechTranscriber {
        var text = ""
        override fun listen(): Flow<SpeechEvent> = flowOf(SpeechEvent.FinalTranscript(text, 0.9f, listOf("alt one")))
    }

    private inner class Rig(scope: CoroutineScope, startOutbox: Outbox? = null) {
        val phone = FakePhone()
        val transport = FakeTransport(phone)
        val store = FileOutboxStore(dir)
        var outbox = startOutbox ?: Outbox(store, { now })
        val transcriber = FakeTranscriber()
        var wakeups = 0
        var persistedAtWake = true
        val sink = OutboxCaptureSink(
            outbox = outbox,
            newId = { UUID(0L, ++idCounter).toString() },
            clock = { now },
            onEnqueued = {
                wakeups++
                persistedAtWake = persistedAtWake && FileOutboxStore(dir).listAll().isNotEmpty()
            },
        )
        val controller = CaptureSessionController(transcriber, sink, scope)
        val sender = OutboxSender(outbox, transport) { now }
        val forwarded = mutableListOf<CaptureAck>()
        val handler = AckHandler(outbox, transport) { forwarded += it; controller.onAck(it) }

        suspend fun deliverAcks() {
            val acks = transport.inFlightAcks.toList()
            transport.inFlightAcks.clear()
            for (a in acks) handler.onAckMessage(encode(a))
        }

        fun records() = FileOutboxStore(dir).listAll()
    }

    private fun encode(ack: CaptureAck): ByteArray =
        (WireCodec.encode(ack) as ProtocolResult.Ok).value.toByteArray(Charsets.UTF_8)

    private fun TestScope.rig() = Rig(CoroutineScope(StandardTestDispatcher(testScheduler)))

    private suspend fun TestScope.speak(r: Rig, text: String = secret) {
        r.transcriber.text = text
        r.controller.start()
        advanceUntilIdle()
    }

    @Test
    fun happyPath() = runTest {
        val r = rig()
        speak(r)
        assertEquals(CaptureUiState.Queued, r.controller.state.value)
        assertEquals(1, r.wakeups)
        assertTrue("persisted before the sender is woken", r.persistedAtWake)
        assertEquals(1, r.records().size)
        assertEquals(0, r.transport.sent.size)

        val next = r.sender.drain()
        assertEquals(1, r.transport.sent.size)
        assertEquals(now + wait, next)
        assertEquals(OutboxState.SENDING, r.records().single().state)
        assertEquals(1, r.phone.stored.size)
        val stored = r.phone.stored.values.single()
        assertEquals(secret, stored.rawText)
        assertEquals(listOf("alt one"), stored.alternatives)
        assertEquals(WATCH_LAUNCHER_SURFACE, stored.sourceSurface)
        assertEquals(0.9f.toDouble(), stored.speechConfidence!!, 0.0)

        r.deliverAcks()
        assertTrue(r.records().isEmpty())
        assertEquals(listOf(stored.captureId), r.transport.retracted)
        assertEquals(CaptureUiState.Saved("Running"), r.controller.state.value)
        assertNull(r.sender.drain())
        assertEquals(1, r.transport.sent.size)
    }

    @Test
    fun phoneOfflineThenBackDeliversExactlyOnce() = runTest {
        val r = rig()
        r.transport.online = false
        speak(r)
        assertEquals(now + 5_000L, r.sender.drain())
        assertEquals(1, r.transport.sent.size)
        assertEquals(OutboxState.RETRYABLE, r.records().single().state)

        assertEquals(now + 5_000L, r.sender.drain())
        assertEquals("not due yet, no hot loop", 1, r.transport.sent.size)

        now += 5_000L
        assertEquals(now + 30_000L, r.sender.drain())
        assertEquals(2, r.transport.sent.size)

        r.transport.online = true
        now += 30_000L
        r.sender.drain()
        assertEquals(3, r.transport.sent.size)
        r.deliverAcks()
        assertEquals(1, r.phone.stored.size)
        assertTrue(r.records().isEmpty())
        assertEquals(CaptureUiState.Saved("Running"), r.controller.state.value)
        val attempts = r.transport.sent.map { it.third }
        assertEquals(attempts.size, attempts.toSet().size)
    }

    @Test
    fun lostAckIsResentWithNewAttemptAndSameCapture() = runTest {
        val r = rig()
        speak(r)
        r.phone.dropAllAcks = true
        r.sender.drain()
        assertEquals(1, r.transport.sent.size)

        now += wait
        assertEquals("backoff after recoverStale", now + 5_000L, r.sender.drain())
        assertEquals(1, r.transport.sent.size)
        assertEquals(OutboxState.RETRYABLE, r.records().single().state)

        r.phone.dropAllAcks = false
        now += 5_000L
        r.sender.drain()
        assertEquals(2, r.transport.sent.size)
        val (first, second) = r.transport.sent
        assertEquals(first.first, second.first)
        assertEquals(first.second, second.second)
        assertNotEquals(first.third, second.third)
        assertEquals(2, r.phone.receipts)
        assertEquals(1, r.phone.stored.size)

        r.deliverAcks()
        assertTrue(r.records().isEmpty())
        assertEquals(CaptureUiState.Saved("Running"), r.controller.state.value)
    }

    @Test
    fun receivedButNeverSavedIsResentAfterDeadline() = runTest {
        val r = rig()
        speak(r)
        r.phone.sendFinalAck = false
        r.sender.drain()
        r.deliverAcks()
        assertEquals(OutboxState.PHONE_RECEIVED, r.records().single().state)

        now += wait - 1
        r.sender.drain()
        assertEquals(1, r.transport.sent.size)
        now += 1
        r.phone.sendFinalAck = true
        r.sender.drain()
        assertEquals(2, r.transport.sent.size)
        assertEquals(r.transport.sent[0].first, r.transport.sent[1].first)
        assertNotEquals(r.transport.sent[0].third, r.transport.sent[1].third)
        r.deliverAcks()
        assertEquals(1, r.phone.stored.size)
        assertTrue(r.records().isEmpty())
    }

    @Test
    fun attemptStrictlyIncreasesEvenWithAFrozenSenderClock() = runTest {
        val r = rig()
        r.transport.online = false
        speak(r)
        val sender = OutboxSender(r.outbox, r.transport) { 100L }
        sender.drain()
        now += 5_000L
        sender.drain()
        now += 30_000L
        sender.drain()
        val attempts = r.transport.sent.map { it.third }
        assertEquals(3, attempts.size)
        assertTrue(attempts[0] < attempts[1] && attempts[1] < attempts[2])
    }

    @Test
    fun processDeathMidSendIsRecoveredAndDeliveredOnce() = runTest {
        val r = rig()
        speak(r)
        val id = r.records().single().captureId
        // The old process marked it SENDING and died before anything was sent.
        r.outbox.markSendStarted(id)
        assertEquals(OutboxState.SENDING, r.records().single().state)

        val reopened = Outbox(FileOutboxStore(dir), { now })
        assertEquals(1, reopened.recoverOnStart())
        val r2 = Rig(CoroutineScope(StandardTestDispatcher(testScheduler)), reopened)
        // r2 builds its own phone/transport; its sender must use the reopened outbox.
        val sender = OutboxSender(reopened, r2.transport) { now }
        val handler = AckHandler(reopened, r2.transport) { }
        sender.drain()
        assertEquals(1, r2.transport.sent.size)
        for (a in r2.transport.inFlightAcks.toList()) handler.onAckMessage(encode(a))
        assertEquals(1, r2.phone.stored.size)
        assertTrue(r2.records().isEmpty())
    }

    @Test
    fun needsReviewAckCompletesRecordAndShowsNeedsReview() = runTest {
        val r = rig()
        r.phone.finalStatus = AckStatus.NEEDS_REVIEW
        speak(r)
        r.sender.drain()
        r.deliverAcks()
        assertTrue(r.records().isEmpty())
        assertEquals(1, r.transport.retracted.size)
        assertEquals(CaptureUiState.NeedsReview, r.controller.state.value)
    }

    @Test
    fun failedRetryableAckSchedulesRetry() = runTest {
        val r = rig()
        r.phone.finalStatus = AckStatus.FAILED_RETRYABLE
        speak(r)
        r.sender.drain()
        r.deliverAcks()
        val rec = r.records().single()
        assertEquals(OutboxState.RETRYABLE, rec.state)
        assertEquals(now + 5_000L, rec.nextAttemptAtEpochMillis)
        assertTrue(r.transport.retracted.isEmpty())
        assertEquals(1, r.transport.sent.size)

        r.phone.finalStatus = AckStatus.SAVED
        now += 5_000L
        r.sender.drain()
        assertEquals(2, r.transport.sent.size)
        r.deliverAcks()
        assertTrue(r.records().isEmpty())
        assertEquals(1, r.phone.stored.size)
    }

    @Test
    fun failedFinalAckLeavesFailedRetractsOnceAndNeverRetries() = runTest {
        val r = rig()
        r.phone.finalStatus = AckStatus.FAILED_FINAL
        speak(r)
        r.sender.drain()
        r.deliverAcks()
        assertEquals(OutboxState.FAILED, r.records().single().state)
        assertEquals(1, r.transport.retracted.size)
        assertEquals(CaptureUiState.Failure, r.controller.state.value)

        val id = r.records().single().captureId
        r.handler.onAckMessage(encode(CaptureAck(captureId = id, status = AckStatus.FAILED_FINAL)))
        assertEquals("duplicate does not retract again", 1, r.transport.retracted.size)

        now += 10 * OutboxPolicy.HOUR
        assertNull(r.sender.drain())
        assertEquals(1, r.transport.sent.size)
    }

    @Test
    fun poisonRecordFailsWithoutBlockingGoodRecord() = runTest {
        val r = rig()
        val poisonId = UUID(1L, 1L).toString()
        assertTrue(r.store.insertIfAbsent(OutboxRecord(poisonId, "{not json", OutboxState.QUEUED, 0, now, now - 10)))
        speak(r)
        r.sender.drain()
        val byId = r.records().associateBy { it.captureId }
        assertEquals(OutboxState.FAILED, byId.getValue(poisonId).state)
        assertEquals(1, r.transport.sent.size)
        assertNotEquals(poisonId, r.transport.sent.single().first)
        assertEquals(1, r.phone.stored.size)
        r.deliverAcks()
        assertEquals(listOf(poisonId), r.records().map { it.captureId })
    }

    @Test
    fun fullOutboxMakesEnqueueThrowAndStoresNothing() = runTest {
        val r = rig()
        repeat(MAX_OUTBOX_RECORDS) {
            val id = UUID(2L, it.toLong()).toString()
            assertEquals(
                EnqueueResult.Enqueued,
                r.outbox.enqueue(CaptureEnvelope(captureId = id, rawText = "x", capturedAtEpochMillis = 1, sourceSurface = "s")),
            )
        }
        val ex = runCatching { r.sink.enqueue(WatchTranscript(secret, null, emptyList())) }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertFalse(ex!!.message.orEmpty().contains(secret))
        assertNull(ex.cause)
        assertEquals(MAX_OUTBOX_RECORDS, r.records().size)
        assertEquals(0, r.wakeups)

        speak(r)
        assertEquals(CaptureUiState.Failure, r.controller.state.value)
        assertEquals(MAX_OUTBOX_RECORDS, r.records().size)
    }

    @Test
    fun blankOrOversizedTranscriptThrowsFixedMessageAndStoresNothing() = runTest {
        val r = rig()
        val ex = runCatching { r.sink.enqueue(WatchTranscript("   ", null, emptyList())) }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(r.records().isEmpty())
        assertEquals(0, r.wakeups)
    }

    @Test
    fun alternativesAreCleanedAndCapped() = runTest {
        val r = rig()
        r.sink.enqueue(WatchTranscript("a", null, listOf("", "  ", "b", "c", "d", "e", "f", "g")))
        val env = (WireCodec.decodeEnvelope(r.records().single().envelopeJson) as ProtocolResult.Ok).value
        assertEquals(listOf("b", "c", "d", "e", "f"), env.alternatives)
        assertNull(env.speechConfidence)
        r.sink.enqueue(WatchTranscript("a2", null, listOf(" ")))
        val second = r.records().last()
        assertNull((WireCodec.decodeEnvelope(second.envelopeJson) as ProtocolResult.Ok).value.alternatives)
    }

    @Test
    fun strayGarbageAndForeignAcksChangeNothing() = runTest {
        val r = rig()
        speak(r)
        r.sender.drain()
        val before = r.records()

        r.handler.onAckMessage(byteArrayOf(0x00, 0x7f, -1, -2))
        r.handler.onAckMessage("{}".toByteArray())
        r.handler.onAckMessage("""{"protocolVersion":99,"captureId":"x","status":"SAVED"}""".toByteArray())
        r.handler.onAckMessage(ByteArray(0))
        r.handler.onAckMessage(encode(CaptureAck(captureId = UUID(9L, 9L).toString(), status = AckStatus.SAVED)))

        assertEquals(before, r.records())
        assertTrue(r.forwarded.isEmpty())
        assertTrue(r.transport.retracted.isEmpty())
        assertEquals(CaptureUiState.Queued, r.controller.state.value)
    }

    @Test
    fun lateDuplicateAckDoesNotRetractTwiceOrThrow() = runTest {
        val r = rig()
        speak(r)
        r.sender.drain()
        val acks = r.transport.inFlightAcks.toList()
        r.deliverAcks()
        for (a in acks) r.handler.onAckMessage(encode(a))
        assertEquals(1, r.transport.retracted.size)
    }

    @Test
    fun cancellationFromTransportPropagatesButOtherErrorsAreTransient() = runTest {
        val r = rig()
        speak(r)
        val throwing = object : CaptureTransport {
            var mode = 0
            override suspend fun send(captureId: String, envelopeJson: String, attempt: Long): SendOutcome {
                if (mode == 0) throw IllegalStateException("boom")
                throw CancellationException("cancelled")
            }
            override suspend fun retract(captureId: String) = throw IllegalStateException("retract boom")
        }
        val sender = OutboxSender(r.outbox, throwing) { now }
        assertNotNull(sender.drain())
        assertEquals(OutboxState.RETRYABLE, r.records().single().state)
        throwing.mode = 1
        now += 5_000L
        val ex = runCatching { sender.drain() }.exceptionOrNull()
        assertTrue(ex is CancellationException)
    }

    @Test
    fun nothingLeaksTranscriptTextIntoRecordToString() = runTest {
        val r = rig()
        speak(r)
        r.records().forEach { assertFalse(it.toString().contains(secret)) }
    }
}
