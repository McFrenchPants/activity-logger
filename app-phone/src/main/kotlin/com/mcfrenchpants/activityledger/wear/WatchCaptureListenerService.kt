package com.mcfrenchpants.activityledger.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.mcfrenchpants.activityledger.ActivityLedgerApplication
import com.mcfrenchpants.activityledger.core.wearprotocol.CAPTURE_ACK_PATH
import com.mcfrenchpants.activityledger.core.wearprotocol.CAPTURE_DATA_KEY
import com.mcfrenchpants.activityledger.core.wearprotocol.CAPTURE_PATH_PREFIX_WITH_SLASH
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin Android shell around [WatchCaptureReceiver]: turns Data Layer capture items into
 * `receive` calls and sends acks as Data Layer messages. All logic lives in the receiver.
 *
 * Callbacks arrive on a background thread, so blocking in [onDataChanged] is acceptable. If the
 * system kills the service mid-way, nothing is lost: the watch resends until acked and the
 * receiver is idempotent on the capture id. The data buffer is owned and released by the
 * framework after this callback returns. Never throws out of the callback and never logs.
 */
class WatchCaptureListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            val receiver = buildReceiver()
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val uri = event.dataItem.uri
                val path = uri.path ?: continue
                if (!path.startsWith(CAPTURE_PATH_PREFIX_WITH_SLASH)) continue
                val node = uri.host ?: continue
                val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(CAPTURE_DATA_KEY)
                    ?: continue
                runBlocking { receiver.receive(node, json) }
            }
        } catch (e: Exception) {
            // Never throw out of the callback; the watch resends, so a dropped delivery recovers.
        }
    }

    private fun buildReceiver(): WatchCaptureReceiver {
        val pipeline = (application as ActivityLedgerApplication).capturePipeline
        val messageClient = Wearable.getMessageClient(this)
        return WatchCaptureReceiver(
            repository = pipeline.repository,
            process = pipeline.taggedOrchestrator::process,
            sendAck = { nodeId, ackJson ->
                suspendCancellableCoroutine { cont ->
                    messageClient.sendMessage(nodeId, CAPTURE_ACK_PATH, ackJson.toByteArray(Charsets.UTF_8))
                        .addOnSuccessListener { cont.resume(Unit) }
                        .addOnFailureListener { cont.resumeWithException(it) }
                }
            },
            zone = { ZoneId.systemDefault() },
        )
    }
}
