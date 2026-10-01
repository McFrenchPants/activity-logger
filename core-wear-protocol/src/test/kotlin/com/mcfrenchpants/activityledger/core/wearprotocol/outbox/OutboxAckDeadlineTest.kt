package com.mcfrenchpants.activityledger.core.wearprotocol.outbox

import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureEnvelope
import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutboxAckDeadlineTest {
    private var now = 1_000_000L
    private val store = InMemoryOutboxStore()
    private val outbox = Outbox(store, { now })
    private val wait = OutboxPolicy.Default.ackWaitMillis

    private fun queued(): String {
        val id = UUID.randomUUID().toString()
        outbox.enqueue(CaptureEnvelope(captureId = id, rawText = "t", capturedAtEpochMillis = 1L, sourceSurface = "s"))
        return id
    }

    @Test
    fun defaultAckWaitIsTwoMinutes() = assertEquals(120_000L, wait)

    @Test
    fun sendStartedSetsAckDeadline() {
        val id = queued()
        assertEquals(UpdateResult.Moved(OutboxState.SENDING), outbox.markSendStarted(id))
        assertEquals(now + wait, store.get(id)!!.nextAttemptAtEpochMillis)
    }

    @Test
    fun receivedAckSetsDeadlineAndResendAfterItPasses() {
        val id = queued()
        outbox.markSendStarted(id)
        now += 10_000
        assertEquals(
            UpdateResult.Moved(OutboxState.PHONE_RECEIVED),
            outbox.applyAck(CaptureAck(captureId = id, status = AckStatus.RECEIVED)),
        )
        assertEquals(now + wait, store.get(id)!!.nextAttemptAtEpochMillis)
        assertNull(outbox.nextDue())
        now += wait
        assertEquals(id, outbox.nextDue()?.captureId)
        assertEquals(UpdateResult.Moved(OutboxState.SENDING), outbox.markSendStarted(id))
    }

    @Test
    fun recoverStaleOnlyTouchesExpiredSending() {
        val a = queued()
        val b = queued()
        outbox.markSendStarted(a)
        now += wait / 2
        outbox.markSendStarted(b)
        assertEquals(0, outbox.recoverStale())
        now += wait / 2
        assertEquals(1, outbox.recoverStale())
        val ra = store.get(a)!!
        assertEquals(OutboxState.RETRYABLE, ra.state)
        assertEquals(1, ra.attemptCount)
        assertEquals(now + 5_000L, ra.nextAttemptAtEpochMillis)
        assertEquals(OutboxState.SENDING, store.get(b)!!.state)
        assertEquals(0, outbox.recoverStale())
    }

    @Test
    fun recoverOnStartStillMakesSendingDueNow() {
        val id = queued()
        outbox.markSendStarted(id)
        assertEquals(1, outbox.recoverOnStart())
        assertEquals(now, store.get(id)!!.nextAttemptAtEpochMillis)
        assertEquals(id, outbox.nextDue()?.captureId)
    }
}
