package com.mcfrenchpants.activityledger.wear.capture

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.mcfrenchpants.activityledger.core.wearprotocol.CAPTURE_ATTEMPT_KEY
import com.mcfrenchpants.activityledger.core.wearprotocol.CAPTURE_DATA_KEY
import com.mcfrenchpants.activityledger.core.wearprotocol.capturePath
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The real watch-to-phone link: one Data Layer DataItem per capture at `capturePath(id)`.
 * Never logs and never throws except for coroutine cancellation.
 */
class DataLayerCaptureTransport(context: Context) : CaptureTransport {
    private val dataClient = Wearable.getDataClient(context.applicationContext)

    override suspend fun send(captureId: String, envelopeJson: String, attempt: Long): SendOutcome =
        try {
            val request = PutDataMapRequest.create(capturePath(captureId)).also {
                it.dataMap.putString(CAPTURE_DATA_KEY, envelopeJson)
                it.dataMap.putLong(CAPTURE_ATTEMPT_KEY, attempt)
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request).await()
            SendOutcome.DELIVERED_TO_LINK
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SendOutcome.TRANSIENT_FAILURE
        }

    override suspend fun retract(captureId: String) {
        try {
            val uri = Uri.Builder()
                .scheme(PutDataRequest.WEAR_URI_SCHEME)
                .path(capturePath(captureId))
                .build()
            dataClient.deleteDataItems(uri).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // Best effort.
        }
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
