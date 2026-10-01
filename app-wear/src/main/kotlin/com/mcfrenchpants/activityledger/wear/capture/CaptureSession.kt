package com.mcfrenchpants.activityledger.wear.capture

import com.mcfrenchpants.activityledger.core.speech.SpeechEvent
import com.mcfrenchpants.activityledger.core.speech.SpeechFailure
import com.mcfrenchpants.activityledger.core.speech.SpeechTranscriber
import com.mcfrenchpants.activityledger.core.wearprotocol.AckStatus
import com.mcfrenchpants.activityledger.core.wearprotocol.CaptureAck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A finished transcript, as handed to the [CaptureSink]. */
data class WatchTranscript(
    val text: String,
    val confidence: Float?,
    val alternatives: List<String>,
)

/** Where a finished transcript goes. WC1.5 implements this over the durable outbox. */
interface CaptureSink {
    /** Accepts [transcript] and returns the new captureId. */
    suspend fun enqueue(transcript: WatchTranscript): String
}

/**
 * Drives one capture screen: runs listening sessions, hands finals to the sink and applies
 * phone acks. Not thread-safe; use from a single (main) dispatcher. Persists nothing, logs
 * nothing, and never puts transcript text in any state except [CaptureUiState.Listening.partial].
 */
class CaptureSessionController(
    private val transcriber: SpeechTranscriber,
    private val sink: CaptureSink,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CaptureUiState>(CaptureUiState.Listening(null))
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var captureId: String? = null

    /** Starts a listening session. No-op while one is already running. */
    fun start() {
        if (job?.isActive == true) return
        captureId = null
        _state.value = CaptureUiState.Listening(null)
        job = scope.launch {
            transcriber.listen().collect { event -> handle(event) }
        }
    }

    /** Cancels the session so the recognizer is released. Safe to call repeatedly. */
    fun stop() {
        job?.cancel()
        job = null
        if (_state.value is CaptureUiState.Listening) {
            _state.value = CaptureUiState.Listening(null)
        }
    }

    /** Starts a new session, from [CaptureUiState.Failure] only. */
    fun retry() {
        if (_state.value !is CaptureUiState.Failure) return
        stop()
        start()
    }

    /** Applies a phone ack to the capture this controller queued; foreign ids are ignored. */
    fun onAck(ack: CaptureAck) {
        val mine = captureId ?: return
        if (ack.captureId != mine) return
        when (ack.status) {
            AckStatus.SAVED -> _state.value = CaptureUiState.Saved(ack.canonicalActivityName)
            AckStatus.NEEDS_REVIEW -> _state.value = CaptureUiState.NeedsReview
            AckStatus.FAILED_RETRYABLE, AckStatus.FAILED_FINAL -> _state.value = CaptureUiState.Failure
            AckStatus.RECEIVED -> Unit
        }
    }

    private suspend fun handle(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.PartialTranscript -> _state.value = CaptureUiState.Listening(event.text)
            is SpeechEvent.FinalTranscript -> {
                if (event.text.isBlank()) {
                    _state.value = CaptureUiState.Failure
                    return
                }
                try {
                    captureId = sink.enqueue(
                        WatchTranscript(event.text, event.confidence, event.alternatives),
                    )
                    _state.value = CaptureUiState.Queued
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    _state.value = CaptureUiState.Failure
                }
            }
            is SpeechEvent.Failed -> when (event.failure) {
                SpeechFailure.PERMISSION_MISSING ->
                    _state.value = CaptureUiState.Unavailable(UnavailableReason.PERMISSION_MISSING)
                SpeechFailure.NO_ON_DEVICE_ENGINE ->
                    _state.value = CaptureUiState.Unavailable(UnavailableReason.NO_ENGINE)
                SpeechFailure.CANCELLED -> Unit
                SpeechFailure.NOTHING_HEARD,
                SpeechFailure.RECOGNIZER_BUSY,
                SpeechFailure.ENGINE_ERROR,
                -> _state.value = CaptureUiState.Failure
            }
        }
    }
}
