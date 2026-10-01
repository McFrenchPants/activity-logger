package com.mcfrenchpants.activityledger.wear.probe

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * DEBUG-ONLY echo side of the hand-run Wear Data Layer probe (the phone side is
 * WearDataLayerProbeTest in app-phone/src/androidTest). Lives in src/debug so it cannot
 * exist in a release build. Carries only fixed probe strings and a numeric nonce; never
 * user content.
 *
 *  - message /probe/ping   -> message /probe/pong to the sender, fixed payload
 *  - data item /probe/item -> data item /probe/item-echo with the same nonce
 */
class WearDataLayerEchoService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_PING) return
        Wearable.getMessageClient(this)
            .sendMessage(event.sourceNodeId, PATH_PONG, PAYLOAD.toByteArray(Charsets.UTF_8))
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH_ITEM) continue
            val nonce = DataMapItem.fromDataItem(event.dataItem).dataMap.getLong(KEY_NONCE)
            val request = PutDataMapRequest.create(PATH_ITEM_ECHO).apply {
                dataMap.putLong(KEY_NONCE, nonce)
                dataMap.putString(KEY_PAYLOAD, PAYLOAD)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(this).putDataItem(request)
        }
    }

    private companion object {
        const val PATH_PING = "/probe/ping"
        const val PATH_PONG = "/probe/pong"
        const val PATH_ITEM = "/probe/item"
        const val PATH_ITEM_ECHO = "/probe/item-echo"
        const val KEY_NONCE = "nonce"
        const val KEY_PAYLOAD = "payload"
        const val PAYLOAD = "wd1-probe-payload"
    }
}
