package com.mcfrenchpants.activityledger.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.mcfrenchpants.activityledger.core.wearprotocol.CAPTURE_ACK_PATH
import kotlinx.coroutines.runBlocking

/**
 * Receives the phone's capture acks and hands them to the send engine. Callbacks arrive on a
 * background thread, so blocking is acceptable. Never throws out of the callback and never logs.
 */
class CaptureAckListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != CAPTURE_ACK_PATH) return
        try {
            val runtime = (application as WatchApplication).runtime
            runtime.ensureStarted()
            runBlocking { runtime.ackHandler.onAckMessage(event.data) }
        } catch (e: Exception) {
            // Never throw out of the callback; an unapplied ack is recovered by the ack deadline resend.
        }
    }
}
