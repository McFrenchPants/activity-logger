package com.mcfrenchpants.activityledger.wear.capture

import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxState
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolResult
import com.mcfrenchpants.activityledger.core.wearprotocol.WireCodec
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.Outbox
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.UpdateResult
import kotlinx.coroutines.CancellationException

/**
 * Sends due outbox records through a [CaptureTransport]. Call [drain] from one coroutine at a time.
 * Logs nothing and never puts capture text in an exception or message.
 */
class OutboxSender(
    private val outbox: Outbox,
    private val transport: CaptureTransport,
    private val clock: () -> Long,
) {
    private var lastAttempt = 0L

    /**
     * Recovers records whose ack deadline passed, then sends every due record once. Returns the
     * earliest time the sender must act again, or null if nothing is pending.
     */
    suspend fun drain(): Long? {
        outbox.recoverStale()
        val handled = HashSet<String>()
        while (true) {
            val record = outbox.nextDue() ?: break
            if (!handled.add(record.captureId)) break
            val id = record.captureId
            if (WireCodec.decodeEnvelope(record.envelopeJson) !is ProtocolResult.Ok) {
                failPermanently(id)
                continue
            }
            if (outbox.markSendStarted(id) !is UpdateResult.Moved) continue
            val outcome = try {
                transport.send(id, record.envelopeJson, nextAttempt())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                SendOutcome.TRANSIENT_FAILURE
            }
            when (outcome) {
                SendOutcome.DELIVERED_TO_LINK -> Unit
                SendOutcome.TRANSIENT_FAILURE -> outbox.markSendFailedTransient(id)
                SendOutcome.PERMANENT_FAILURE -> failPermanently(id)
            }
        }
        return outbox.earliestPendingAttemptAt()
    }

    private suspend fun failPermanently(id: String) {
        val result = outbox.markSendFailedPermanent(id)
        if (result is UpdateResult.Moved && result.to == OutboxState.FAILED) {
            try {
                transport.retract(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Retract is best effort; the capture is already final.
            }
        }
    }

    private fun nextAttempt(): Long {
        lastAttempt = maxOf(lastAttempt + 1, clock())
        return lastAttempt
    }
}
