package com.mcfrenchpants.activityledger.wear

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CaptureProcessingOutcome
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureEnvelope
import com.mcfrenchpants.activityledger.core.wearprotocol.MAX_RAW_TEXT_LENGTH
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolResult
import com.mcfrenchpants.activityledger.core.wearprotocol.SOURCE_WATCH_VOICE
import com.mcfrenchpants.activityledger.core.wearprotocol.WireCodec
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WatchCaptureReceiverTest {
    private val zone = ZoneId.of("America/Detroit")
    private val capturedAt: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(capturedAt.plusSeconds(30), zone)
    private val memory = InMemoryActivityRepository(clock)
    private val mowLawn = memory.seedActivity("Mow lawn", aliases = listOf("cut grass"))
    private val interpreter = FakeActivityInterpreter()

    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val text = "  I cut the grass yesterday.  "
    private val node = "node-1"

    /** Records what the receiver asks the repository to store, delegating everything else. */
    private class RecordingRepository(private val inner: ActivityRepository) : ActivityRepository by inner {
        val created = mutableListOf<NewRawCapture>()
        override suspend fun createRawCapture(capture: NewRawCapture): String {
            created += capture
            return inner.createRawCapture(capture)
        }
    }

    private val repository = RecordingRepository(memory)
    private val orchestrator = CaptureInterpretationOrchestrator(repository, interpreter, clock)
    private val acks = mutableListOf<CaptureAck>()
    private val rawAckJson = mutableListOf<String>()
    private var sendFails = false
    private var processOverride: (suspend (String) -> CaptureProcessingOutcome)? = null
    private var processCalls = 0

    private val receiver = WatchCaptureReceiver(
        repository = repository,
        process = { captureId ->
            processCalls++
            (processOverride ?: { orchestrator.process(it) })(captureId)
        },
        sendAck = { n, json ->
            assertEquals(node, n)
            if (sendFails) throw IllegalStateException("send failed")
            synchronized(acks) {
                rawAckJson += json
                acks += (WireCodec.decodeAck(json) as ProtocolResult.Ok).value
            }
        },
        zone = { zone },
    )

    private fun envelope(
        rawText: String = text,
        alternatives: List<String>? = listOf("I cut the grass yesterday", "I cut the glass yesterday"),
        confidence: Double? = 0.91,
    ): String = (
        WireCodec.encode(
            CaptureEnvelope(
                captureId = id,
                rawText = rawText,
                capturedAtEpochMillis = capturedAt.toEpochMilli(),
                sourceSurface = "watch-app",
                speechConfidence = confidence,
                alternatives = alternatives,
            ),
        ) as ProtocolResult.Ok
        ).value

    private fun autoAccept() = interpreter.respondWith {
        InterpretationResult.Success(
            InterpretationCandidate(
                InterpretationOperation.LOG_ACTIVITY,
                ActivityResolution.EXISTING_ACTIVITY,
                mowLawn,
                null,
                ActivityState.COMPLETED,
                "yesterday",
                ConfidenceBand.HIGH,
            ),
            "{\"synthetic\":true}",
        )
    }

    private fun send(json: String = envelope()) = runBlocking { receiver.receive(node, json) }

    private fun stored(captureId: String = id) = runBlocking { repository.getCapture(captureId) }

    @Test
    fun sourceConstantMatchesDomainEnum() {
        assertEquals(CaptureSource.WATCH_VOICE.name, SOURCE_WATCH_VOICE)
    }

    @Test
    fun firstDeliveryStoresRawCaptureExactlyAndAcksReceivedThenSaved() {
        autoAccept()
        send()

        val c = repository.created.single()
        assertEquals(id, c.id)
        assertEquals(CaptureSource.WATCH_VOICE, c.source)
        assertEquals("watch-app", c.sourceSurface)
        assertEquals(text, c.rawText)
        assertEquals(capturedAt, c.capturedAt)
        assertEquals(zone, c.zoneId)
        assertEquals(0.91, c.speechConfidence)
        assertEquals("[\"I cut the grass yesterday\",\"I cut the glass yesterday\"]", c.speechAlternativesJson)
        assertEquals(ProcessingState.CAPTURED, c.processingState)

        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.SAVED), acks.map { it.status })
        val saved = acks.last()
        assertFalse(saved.needsReview)
        assertEquals("Mow lawn", saved.canonicalActivityName)
        assertNotNull(saved.occurredAtEpochMillis)
        assertEquals(1, memory.occurrences.size)
        assertEquals(ProcessingState.PERSISTED, stored()!!.processingState)
    }

    @Test
    fun nullAlternativesAreStoredAsNull() {
        autoAccept()
        send(envelope(alternatives = null, confidence = null))
        assertEquals(null, repository.created.single().speechAlternativesJson)
        assertEquals(null, repository.created.single().speechConfidence)
    }

    @Test
    fun alternativesJsonEscapesQuotesAndBackslashes() {
        assertEquals("[\"a\\\"b\",\"c\\\\d\",\"e\\nf\"]", WatchCaptureReceiver.jsonArray(listOf("a\"b", "c\\d", "e\nf")))
    }

    @Test
    fun needsReviewOutcomeAcksNeedsReview() {
        interpreter.respondWith {
            InterpretationResult.Success(
                InterpretationCandidate(
                    InterpretationOperation.LOG_ACTIVITY,
                    ActivityResolution.EXISTING_ACTIVITY,
                    mowLawn,
                    null,
                    ActivityState.COMPLETED,
                    "yesterday",
                    ConfidenceBand.MEDIUM,
                ),
                null,
            )
        }
        send()
        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.NEEDS_REVIEW), acks.map { it.status })
        assertTrue(acks.last().needsReview)
        assertEquals(0, memory.occurrences.size)
    }

    @Test
    fun interpreterUnavailableAcksNeedsReviewAndKeepsCapture() {
        interpreter.respondWith { InterpretationResult.Failure(InterpreterFailureKind.UNAVAILABLE, null) }
        send()
        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.NEEDS_REVIEW), acks.map { it.status })
        assertTrue(acks.last().needsReview)
        assertEquals(ProcessingState.FAILED_RETRYABLE, stored()!!.processingState)
    }

    @Test
    fun processThrowingAcksFailedRetryableAndKeepsCapture() {
        processOverride = { throw IllegalStateException("boom") }
        send()
        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.FAILED_RETRYABLE), acks.map { it.status })
        assertEquals(text, stored()!!.rawText)
        assertEquals(0, memory.occurrences.size)
    }

    @Test
    fun duplicateAfterSavedDoesNotReprocessAndReAcksSaved() {
        autoAccept()
        send()
        val calls = interpreter.callCount
        acks.clear()
        send()
        send()
        assertEquals(1, calls)
        assertEquals(1, interpreter.callCount)
        assertEquals(1, processCalls)
        assertEquals(1, repository.created.size)
        assertEquals(1, memory.occurrences.size)
        assertEquals(listOf(AckStatus.SAVED, AckStatus.SAVED), acks.map { it.status })
    }

    @Test
    fun duplicateWhileCapturedIsProcessedOnceNow() {
        autoAccept()
        runBlocking {
            memory.createRawCapture(
                NewRawCapture(
                    CaptureSource.WATCH_VOICE, "watch-app", capturedAt, zone, text, null, null,
                    ProcessingState.CAPTURED, id,
                ),
            )
        }
        send()
        assertEquals(1, interpreter.callCount)
        assertEquals(1, memory.occurrences.size)
        assertEquals(listOf(AckStatus.SAVED), acks.map { it.status })
        send()
        assertEquals(1, interpreter.callCount)
        assertEquals(AckStatus.SAVED, acks.last().status)
    }

    @Test
    fun duplicateWhileFailedRetryableReprocesses() {
        interpreter.enqueue(InterpretationResult.Failure(InterpreterFailureKind.UNAVAILABLE, null))
        autoAccept()
        send()
        assertEquals(ProcessingState.FAILED_RETRYABLE, stored()!!.processingState)
        acks.clear()
        send()
        assertEquals(2, interpreter.callCount)
        assertEquals(1, memory.occurrences.size)
        assertEquals(listOf(AckStatus.SAVED), acks.map { it.status })
        assertEquals(1, memory.activities.count { it.displayName == "Mow lawn" })
    }

    @Test
    fun duplicateInNeedsReviewIsNotReprocessed() {
        interpreter.respondWith { InterpretationResult.Failure(InterpreterFailureKind.MALFORMED, null) }
        send()
        assertEquals(ProcessingState.NEEDS_REVIEW, stored()!!.processingState)
        acks.clear()
        send()
        assertEquals(1, interpreter.callCount)
        assertEquals(listOf(AckStatus.NEEDS_REVIEW), acks.map { it.status })
        assertTrue(acks.single().needsReview)
    }

    @Test
    fun duplicateInFailedFinalReAcksFailedFinal() {
        runBlocking {
            memory.createRawCapture(
                NewRawCapture(CaptureSource.WATCH_VOICE, "s", capturedAt, zone, text, null, null, ProcessingState.CAPTURED, id),
            )
            memory.recordOutcome(id, null, ProcessingState.FAILED_FINAL)
        }
        send()
        assertEquals(0, interpreter.callCount)
        assertEquals(listOf(AckStatus.FAILED_FINAL), acks.map { it.status })
    }

    @Test
    fun undecodableEnvelopesWriteNothingAndSendNoAck() {
        val big = "x".repeat(MAX_RAW_TEXT_LENGTH + 1)
        val bad = listOf(
            "not json",
            "{}",
            "",
            "{\"protocolVersion\":99,\"captureId\":\"$id\",\"rawText\":\"hi\",\"capturedAtEpochMillis\":1,\"sourceSurface\":\"s\"}",
            "{\"protocolVersion\":1,\"captureId\":\"$id\",\"rawText\":\"   \",\"capturedAtEpochMillis\":1,\"sourceSurface\":\"s\"}",
            "{\"protocolVersion\":1,\"captureId\":\"$id\",\"rawText\":\"$big\",\"capturedAtEpochMillis\":1,\"sourceSurface\":\"s\"}",
            "{\"protocolVersion\":1,\"captureId\":\"nope\",\"rawText\":\"hi\",\"capturedAtEpochMillis\":1,\"sourceSurface\":\"s\"}",
        )
        for (json in bad) send(json)
        assertTrue(repository.created.isEmpty())
        assertTrue(acks.isEmpty())
        assertEquals(null, stored())
        assertEquals(0, processCalls)
    }

    @Test
    fun concurrentIdenticalDeliveriesCreateOneCaptureAndOneOccurrence() {
        autoAccept()
        val json = envelope()
        runBlocking {
            (1..12).map { async(Dispatchers.Default) { receiver.receive(node, json) } }.awaitAll()
        }
        assertEquals(1, repository.created.size)
        assertEquals(1, memory.occurrences.size)
        assertEquals(1, interpreter.callCount)
        assertEquals(1, acks.count { it.status == AckStatus.RECEIVED })
        assertEquals(12, acks.count { it.status == AckStatus.SAVED })
    }

    @Test
    fun sendAckFailureDoesNotLoseCaptureOrPropagate() {
        autoAccept()
        sendFails = true
        send()
        assertEquals(text, stored()!!.rawText)
        assertEquals(1, memory.occurrences.size)
    }

    @Test
    fun cancellationFromProcessIsRethrownAndCaptureRemainsStored() {
        processOverride = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { send() }
        assertEquals(text, stored()!!.rawText)
        assertEquals(listOf(AckStatus.RECEIVED), acks.map { it.status })
    }

    @Test
    fun cancellationFromSendAckIsRethrown() {
        val r = WatchCaptureReceiver(repository, { orchestrator.process(it) }, { _, _ -> throw CancellationException("c") }) { zone }
        autoAccept()
        assertFailsWith<CancellationException> { runBlocking { r.receive(node, envelope()) } }
        assertEquals(text, stored()!!.rawText)
    }

    @Test
    fun createFailureAcksFailedRetryableWithoutProcessing() {
        val failing = object : ActivityRepository by memory {
            override suspend fun createRawCapture(capture: NewRawCapture): String = throw IllegalArgumentException("db")
        }
        val r = WatchCaptureReceiver(failing, { processCalls++; CaptureProcessingOutcome.AlreadyHasOccurrence }, { _, j ->
            acks += (WireCodec.decodeAck(j) as ProtocolResult.Ok).value
        }) { zone }
        runBlocking { r.receive(node, envelope()) }
        assertEquals(listOf(AckStatus.FAILED_RETRYABLE), acks.map { it.status })
        assertEquals(0, processCalls)
    }

    @Test
    fun alreadyHasOccurrenceOutcomeAcksSaved() {
        processOverride = { CaptureProcessingOutcome.AlreadyHasOccurrence }
        send()
        assertEquals(listOf(AckStatus.RECEIVED, AckStatus.SAVED), acks.map { it.status })
    }

    @Test
    fun rejectedOutcomeAcksNeedsReview() {
        processOverride = { CaptureProcessingOutcome.Rejected(emptySet()) }
        send()
        assertEquals(AckStatus.NEEDS_REVIEW, acks.last().status)
        assertTrue(acks.last().needsReview)
    }

    @Test
    fun acksNeverContainRawText() {
        autoAccept()
        send()
        send()
        assertTrue(rawAckJson.isNotEmpty())
        assertTrue(rawAckJson.none { it.contains("grass") })
    }
}
