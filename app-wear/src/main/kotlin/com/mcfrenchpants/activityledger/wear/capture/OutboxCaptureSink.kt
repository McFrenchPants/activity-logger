package com.mcfrenchpants.activityledger.wear.capture

import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureEnvelope
import com.mcfrenchpants.activityledger.core.wearprotocol.MAX_ALTERNATIVES
import com.mcfrenchpants.activityledger.core.wearprotocol.SOURCE_WATCH_VOICE
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.EnqueueResult
import com.mcfrenchpants.activityledger.core.wearprotocol.outbox.Outbox
import kotlinx.coroutines.CancellationException

/** Value of the envelope's sourceSurface for captures made on the watch's own launcher screen. */
const val WATCH_LAUNCHER_SURFACE: String = "watch_launcher"

/**
 * Turns a finished transcript into a durable outbox record, then wakes the sender. The record is
 * persisted by [Outbox.enqueue] before [onEnqueued] runs. Failures throw a fixed-message exception
 * that never contains transcript text.
 */
class OutboxCaptureSink(
    private val outbox: Outbox,
    private val newId: () -> String,
    private val clock: () -> Long,
    private val sourceSurface: String = WATCH_LAUNCHER_SURFACE,
    private val onEnqueued: () -> Unit,
) : CaptureSink {
    override suspend fun enqueue(transcript: WatchTranscript): String {
        val id = newId()
        val envelope = CaptureEnvelope(
            captureId = id,
            rawText = transcript.text,
            capturedAtEpochMillis = clock(),
            source = SOURCE_WATCH_VOICE,
            sourceSurface = sourceSurface,
            speechConfidence = transcript.confidence?.toDouble(),
            alternatives = transcript.alternatives
                .filter { it.isNotBlank() }
                .take(MAX_ALTERNATIVES)
                .ifEmpty { null },
        )
        val result = try {
            outbox.enqueue(envelope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw IllegalStateException(FAILURE_MESSAGE)
        }
        if (result != EnqueueResult.Enqueued) throw IllegalStateException(FAILURE_MESSAGE)
        onEnqueued()
        return id
    }

    private companion object {
        const val FAILURE_MESSAGE = "capture could not be queued"
    }
}
