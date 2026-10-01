package com.mcfrenchpants.activityledger.core.wearprotocol.outbox

import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureEnvelope
import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxState
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolFailureKind
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxTest {
    private val secret = "SECRET-sample-capture-text-xyz"
    private lateinit var dir: File
    private var now = 1_000_000L
    private val clock = { now }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("outbox-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun env(id: String = UUID.randomUUID().toString(), text: String = secret, at: Long = 1L) =
        CaptureEnvelope(captureId = id, rawText = text, capturedAtEpochMillis = at, sourceSurface = "tile")

    private fun ack(id: String, status: AckStatus) = CaptureAck(captureId = id, status = status)

    private fun newOutbox(store: OutboxStore = FileOutboxStore(dir)) = Outbox(store, clock)

    private fun sending(outbox: Outbox, id: String) {
        assertEquals(EnqueueResult.Enqueued, outbox.enqueue(env(id)))
        assertEquals(UpdateResult.Moved(OutboxState.SENDING), outbox.markSendStarted(id))
    }

    @Test
    fun enqueuePersistsAndSurvivesNewInstances() {
        val id = UUID.randomUUID().toString()
        assertEquals(EnqueueResult.Enqueued, newOutbox().enqueue(env(id)))
        val reopened = newOutbox()
        val due = assertNotNull(reopened.nextDue())
        assertEquals(id, due.captureId)
        assertEquals(OutboxState.QUEUED, due.state)
        assertTrue(File(dir, "$id.json").exists())
        assertEquals(1, reopened.pendingCount())
    }

    @Test
    fun enqueueIsIdempotent() {
        val outbox = newOutbox()
        val id = UUID.randomUUID().toString()
        outbox.enqueue(env(id))
        outbox.markSendStarted(id)
        val before = FileOutboxStore(dir).get(id)
        assertEquals(EnqueueResult.AlreadyPresent, outbox.enqueue(env(id, text = "different")))
        assertEquals(before, FileOutboxStore(dir).get(id))
        assertEquals(OutboxState.SENDING, before?.state)
    }

    @Test
    fun capEnforcedWithoutEviction() {
        val outbox = newOutbox(InMemoryOutboxStore())
        val ids = (1..MAX_OUTBOX_RECORDS).map { UUID.randomUUID().toString() }
        ids.forEach { assertEquals(EnqueueResult.Enqueued, outbox.enqueue(env(it))) }
        assertEquals(EnqueueResult.Full, outbox.enqueue(env()))
        assertEquals(EnqueueResult.AlreadyPresent, outbox.enqueue(env(ids[0])))
        assertEquals(MAX_OUTBOX_RECORDS, outbox.pendingCount())
        ids.forEach { assertEquals(UpdateResult.Moved(OutboxState.SENDING), outbox.markSendStarted(it)) }
    }

    @Test
    fun refusalCarriesOnlyKind() {
        val outbox = newOutbox()
        assertEquals(
            EnqueueResult.Refused(ProtocolFailureKind.BLANK_TEXT),
            outbox.enqueue(env(text = "   ")),
        )
        assertEquals(
            EnqueueResult.Refused(ProtocolFailureKind.INVALID_ID),
            outbox.enqueue(env(id = "../evil")),
        )
        assertEquals(0, outbox.pendingCount())
    }

    @Test
    fun backoffTableAndCap() {
        val p = OutboxPolicy.Default
        val expected = listOf(5_000L, 30_000L, 120_000L, 600_000L, 1_800_000L, 3_600_000L)
        expected.forEachIndexed { i, ms -> assertEquals(ms, p.delayAfterAttempt(i + 1)) }
        assertEquals(3_600_000L, p.delayAfterAttempt(7))
        assertEquals(3_600_000L, p.delayAfterAttempt(100_000))
        assertEquals(5_000L, p.delayAfterAttempt(0))
    }

    @Test
    fun transientFailureBecomesRetryableAndDueOnlyAfterDelay() {
        val outbox = newOutbox()
        val id = UUID.randomUUID().toString()
        sending(outbox, id)
        assertNull(outbox.nextDue())
        assertEquals(UpdateResult.Moved(OutboxState.RETRYABLE), outbox.markSendFailedTransient(id))
        val rec = assertNotNull(FileOutboxStore(dir).get(id))
        assertEquals(1, rec.attemptCount)
        assertEquals(now + 5_000L, rec.nextAttemptAtEpochMillis)
        assertNull(outbox.nextDue())
        now += 4_999
        assertNull(outbox.nextDue())
        now += 1
        assertEquals(id, outbox.nextDue()?.captureId)
    }

    @Test
    fun transientNeverBecomesPermanentByCount() {
        val outbox = newOutbox()
        val id = UUID.randomUUID().toString()
        outbox.enqueue(env(id))
        repeat(50) {
            assertIs<UpdateResult.Moved>(outbox.markSendStarted(id))
            assertEquals(UpdateResult.Moved(OutboxState.RETRYABLE), outbox.markSendFailedTransient(id))
            now += OutboxPolicy.HOUR
        }
        assertEquals(OutboxState.RETRYABLE, FileOutboxStore(dir).get(id)?.state)
        assertEquals(0, outbox.failedCount())
    }

    @Test
    fun permanentFailureKeptUntilDiscard() {
        val outbox = newOutbox()
        val id = UUID.randomUUID().toString()
        sending(outbox, id)
        assertEquals(UpdateResult.Moved(OutboxState.FAILED), outbox.markSendFailedPermanent(id))
        assertEquals(1, outbox.failedCount())
        assertEquals(0, outbox.pendingCount())
        assertNull(outbox.nextDue())
        assertEquals(1, newOutbox().failedCount())
        assertTrue(outbox.discard(id))
        assertEquals(0, outbox.failedCount())
        assertFalse(outbox.discard(id))
    }

    @Test
    fun ackReceivedKeepsRecord() {
        val outbox = newOutbox()
        val id = UUID.randomUUID().toString()
        sending(outbox, id)
        assertEquals(UpdateResult.Moved(OutboxState.PHONE_RECEIVED), outbox.applyAck(ack(id, AckStatus.RECEIVED)))
        assertEquals(OutboxState.PHONE_RECEIVED, FileOutboxStore(dir).get(id)?.state)
        assertNull(outbox.nextDue())
        assertEquals(UpdateResult.Unchanged, outbox.applyAck(ack(id, AckStatus.RECEIVED)))
        assertEquals(UpdateResult.Removed(OutboxState.ACKNOWLEDGED), outbox.applyAck(ack(id, AckStatus.SAVED)))
        assertNull(FileOutboxStore(dir).get(id))
    }

    @Test
    fun savedAndNeedsReviewRemoveRecord() {
        val outbox = newOutbox()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        sending(outbox, a)
        sending(outbox, b)
        assertEquals(UpdateResult.Removed(OutboxState.ACKNOWLEDGED), outbox.applyAck(ack(a, AckStatus.SAVED)))
        assertEquals(UpdateResult.Removed(OutboxState.PROCESSED), outbox.applyAck(ack(b, AckStatus.NEEDS_REVIEW)))
        assertEquals(0, outbox.pendingCount())
        assertTrue(FileOutboxStore(dir).listAll().isEmpty())
    }

    @Test
    fun failedRetryableAckBecomesRetryable() {
        val outbox = newOutbox()
        val id = UUID.randomUUID().toString()
        sending(outbox, id)
        assertEquals(
            UpdateResult.Moved(OutboxState.RETRYABLE),
            outbox.applyAck(ack(id, AckStatus.FAILED_RETRYABLE)),
        )
        val rec = assertNotNull(FileOutboxStore(dir).get(id))
        assertEquals(OutboxState.RETRYABLE, rec.state)
        assertEquals(1, rec.attemptCount)
        now += 5_000
        assertEquals(id, outbox.nextDue()?.captureId)
    }

    @Test
    fun failedFinalAckBecomesFailedAndIsKept() {
        val outbox = newOutbox()
        val id = UUID.randomUUID().toString()
        sending(outbox, id)
        assertEquals(UpdateResult.Moved(OutboxState.FAILED), outbox.applyAck(ack(id, AckStatus.FAILED_FINAL)))
        assertEquals(1, outbox.failedCount())
        assertEquals(UpdateResult.Unchanged, outbox.applyAck(ack(id, AckStatus.FAILED_FINAL)))
        assertEquals(UpdateResult.Unchanged, outbox.applyAck(ack(id, AckStatus.SAVED)))
        assertEquals(1, outbox.failedCount())
    }

    @Test
    fun ackForUnknownIdAndAckBeforeSendAreTyped() {
        val outbox = newOutbox()
        assertEquals(
            UpdateResult.UnknownCapture,
            outbox.applyAck(ack(UUID.randomUUID().toString(), AckStatus.SAVED)),
        )
        assertEquals(UpdateResult.UnknownCapture, outbox.markSendStarted("not-an-id"))
        val id = UUID.randomUUID().toString()
        outbox.enqueue(env(id))
        assertEquals(UpdateResult.Refused(OutboxState.QUEUED), outbox.applyAck(ack(id, AckStatus.SAVED)))
        assertEquals(OutboxState.QUEUED, FileOutboxStore(dir).get(id)?.state)
    }

    @Test
    fun recoverOnStartConvertsSendingAndIsIdempotent() {
        val outbox = newOutbox()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        sending(outbox, a)
        now += 1
        outbox.enqueue(env(b))
        now += 10
        val restarted = newOutbox()
        assertEquals(1, restarted.recoverOnStart())
        val rec = assertNotNull(FileOutboxStore(dir).get(a))
        assertEquals(OutboxState.RETRYABLE, rec.state)
        assertEquals(now, rec.nextAttemptAtEpochMillis)
        assertEquals(a, restarted.nextDue()?.captureId)
        assertEquals(0, restarted.recoverOnStart())
        assertEquals(OutboxState.QUEUED, FileOutboxStore(dir).get(b)?.state)
    }

    @Test
    fun strayTempAndCorruptFilesAreSkippedNotDeleted() {
        val outbox = newOutbox()
        val good = UUID.randomUUID().toString()
        outbox.enqueue(env(good))
        val corruptId = UUID.randomUUID().toString()
        val corrupt = File(dir, "$corruptId.json").apply { writeText("{ not json " + secret) }
        val tmp = File(dir, "${UUID.randomUUID()}.${UUID.randomUUID()}.tmp").apply { writeText("partial") }
        val reopened = FileOutboxStore(dir)
        assertEquals(listOf(good), reopened.listAll().map { it.captureId })
        assertNull(reopened.get(corruptId))
        assertTrue(corrupt.exists())
        assertTrue(tmp.exists())
        assertEquals(1, newOutbox().pendingCount())
    }

    @Test
    fun atomicReplaceNeverLeavesRecordUnreadable() {
        val store = FileOutboxStore(dir)
        val id = UUID.randomUUID().toString()
        val base = OutboxRecord(id, "{}", OutboxState.QUEUED, 0, 0, 1)
        assertTrue(store.insertIfAbsent(base))
        assertFalse(store.insertIfAbsent(base.copy(attemptCount = 9)))
        repeat(100) { i ->
            assertTrue(store.replace(base.copy(attemptCount = i, envelopeJson = "x".repeat(i * 50))))
            assertEquals(i, assertNotNull(store.get(id)).attemptCount)
        }
        assertEquals(listOf("$id.json"), dir.list()!!.toList())
        assertFalse(store.replace(base.copy(captureId = UUID.randomUUID().toString())))
    }

    @Test
    fun orderingIsOldestFirst() {
        val outbox = newOutbox()
        val ids = List(3) { UUID.randomUUID().toString() }
        ids.forEach {
            outbox.enqueue(env(it))
            now += 100
        }
        assertEquals(ids, FileOutboxStore(dir).listAll().map { it.captureId })
        assertEquals(ids[0], outbox.nextDue()?.captureId)
        outbox.markSendStarted(ids[0])
        assertEquals(ids[1], outbox.nextDue()?.captureId)
    }

    @Test
    fun noRawTextInResultsRecordsOrExceptions() {
        val outbox = newOutbox()
        val id = UUID.randomUUID().toString()
        val results = mutableListOf<Any?>()
        results += outbox.enqueue(env(id))
        results += outbox.enqueue(env(id))
        results += outbox.enqueue(env(text = " "))
        results += outbox.markSendStarted(id)
        results += outbox.markSendStarted(id)
        results += outbox.markSendFailedTransient(id)
        results += outbox.applyAck(ack(UUID.randomUUID().toString(), AckStatus.SAVED))
        results += outbox.applyAck(ack(id, AckStatus.SAVED))
        results.addAll(FileOutboxStore(dir).listAll())
        val other = UUID.randomUUID().toString()
        outbox.enqueue(env(other))
        results += FileOutboxStore(dir).get(other)
        val full = newOutbox(InMemoryOutboxStore())
        repeat(MAX_OUTBOX_RECORDS) { full.enqueue(env()) }
        results += full.enqueue(env())
        results.forEach { assertFalse(it.toString().contains(secret), "leaked in $it") }
        assertTrue(FileOutboxStore(dir).get(other)!!.envelopeJson.contains(secret))

        val ex = runCatching {
            FileOutboxStore(dir).insertIfAbsent(OutboxRecord("bad id", secret, OutboxState.QUEUED, 0, 0, 0))
        }.exceptionOrNull()
        assertNotNull(ex)
        assertFalse(ex.message.orEmpty().contains(secret))
        dir.list()!!.forEach { assertFalse(it.contains(secret)) }
    }
}
