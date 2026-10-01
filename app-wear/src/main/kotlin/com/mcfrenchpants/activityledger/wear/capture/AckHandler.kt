package com.mcfrenchpants.activityledger.wear.capture

import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import com.mcfrenchpants.activityledger.core.wearprotocol.OutboxState
import com.mcfrenchpants.activityledger.core.wearprotocol.ProtocolResult
import com.mcfrenchpants.activityledger.core.wearprotocol.WireCodec
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.Outbox
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.UpdateResult
import kotlinx.coroutines.CancellationException

/**
 * Applies phone acks to the outbox. Undecodable payloads are ignored silently. Acks for ids the
 * outbox does not know are neither applied nor forwarded to [onAck].
 */
class AckHandler(
    private val outbox: Outbox,
    private val transport: CaptureTransport,
    private val onAck: (CaptureAck) -> Unit,
) {
    suspend fun onAckMessage(payload: ByteArray) {
        val ack = (WireCodec.decodeAck(String(payload, Charsets.UTF_8)) as? ProtocolResult.Ok)?.value ?: return
        val result = outbox.applyAck(ack)
        if (result == UpdateResult.UnknownCapture) return
        val retire = result is UpdateResult.Removed ||
            (ack.status == AckStatus.FAILED_FINAL && result == UpdateResult.Moved(OutboxState.FAILED))
        if (retire) {
            try {
                transport.retract(ack.captureId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Best effort; the outbox state is already final.
            }
        }
        try {
            onAck(ack)
        } catch (e: Exception) {
            // A failing listener must not undo the applied ack.
        }
    }
}
