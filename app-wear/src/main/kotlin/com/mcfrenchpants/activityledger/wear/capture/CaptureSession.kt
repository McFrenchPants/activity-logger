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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 *
 * The watch is always either busy ([CaptureUiState.Listening], [CaptureUiState.Saving]) or
 * actionable. If the phone does not answer within [savingTimeout] the screen goes to
 * [CaptureUiState.Ready]; the capture is already in the durable outbox, which still delivers it.
 * Saved / NeedsReview results return to Ready after [resultHold]. Both timers run on [scope] and
 * are cancelled on every state change, on [start] and on [stop].
 */
class CaptureSessionController(
    private val transcriber: SpeechTranscriber,
    private val sink: CaptureSink,
    private val scope: CoroutineScope,
    private val savingTimeout: Duration = 5.seconds,
    private val resultHold: Duration = 3.seconds,
) {
    private val _state = MutableStateFlow<CaptureUiState>(CaptureUiState.Listening(null))
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var timer: Job? = null
    private var captureId: String? = null

    /** Starts a listening session. No-op while one is already running. */
    fun start() {
        cancelTimer()
        if (job?.isActive == true) return
        captureId = null
        setState(CaptureUiState.Listening(null))
        job = scope.launch {
            transcriber.listen().collect { event -> handle(event) }
        }
    }

    /** Cancels the session so the recognizer is released, and any pending timer. Repeatable. */
    fun stop() {
        cancelTimer()
        job?.cancel()
        job = null
        if (_state.value is CaptureUiState.Listening) {
            setState(CaptureUiState.Listening(null))
        }
    }

    /** Tap on an actionable screen (Ready, Failure, Saved, NeedsReview): starts a new session. */
    fun retry() {
        when (_state.value) {
            CaptureUiState.Ready,
            CaptureUiState.Failure,
            is CaptureUiState.Saved,
            CaptureUiState.NeedsReview,
            -> {
                stop()
                start()
            }
            else -> Unit
        }
    }

    /**
     * Called when the screen comes back to the foreground: starts listening unless a capture is
     * still Saving or a result is on screen (a new session would drop that ack). Those states
     * re-arm the timer [stop] cancelled, so the screen never sticks on them.
     */
    fun resume() {
        when (_state.value) {
            CaptureUiState.Saving -> schedule(savingTimeout)
            is CaptureUiState.Saved, CaptureUiState.NeedsReview -> schedule(resultHold)
            CaptureUiState.Ready -> {
                stop()
                start()
            }
            else -> start()
        }
    }

    /**
     * Applies a phone ack to the capture this controller queued; foreign ids are ignored, and so
     * is a late ack once the screen has moved on to Ready or a new listening session.
     */
    fun onAck(ack: CaptureAck) {
        val mine = captureId ?: return
        if (ack.captureId != mine) return
        when (_state.value) {
            CaptureUiState.Saving,
            is CaptureUiState.Saved,
            CaptureUiState.NeedsReview,
            CaptureUiState.Failure,
            -> Unit
            else -> return
        }
        val next = when (ack.status) {
            AckStatus.SAVED -> CaptureUiState.Saved(ack.canonicalActivityName)
            AckStatus.NEEDS_REVIEW -> CaptureUiState.NeedsReview
            AckStatus.FAILED_RETRYABLE, AckStatus.FAILED_FINAL -> CaptureUiState.Failure
            AckStatus.RECEIVED -> return
        }
        if (next == _state.value) return
        setState(next)
        if (next !is CaptureUiState.Failure) schedule(resultHold)
    }

    private fun setState(next: CaptureUiState) {
        cancelTimer()
        _state.value = next
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    /** After [after], goes to Ready and forgets the capture so a late ack cannot reclaim the screen. */
    private fun schedule(after: Duration) {
        cancelTimer()
        timer = scope.launch {
            delay(after)
            timer = null
            captureId = null
            _state.value = CaptureUiState.Ready
        }
    }

    private suspend fun handle(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.PartialTranscript -> setState(CaptureUiState.Listening(event.text))
            is SpeechEvent.FinalTranscript -> {
                if (event.text.isBlank()) {
                    setState(CaptureUiState.Failure)
                    return
                }
                try {
                    captureId = sink.enqueue(
                        WatchTranscript(event.text, event.confidence, event.alternatives),
                    )
                    setState(CaptureUiState.Saving)
                    schedule(savingTimeout)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expected: Exception) {
                    setState(CaptureUiState.Failure)
                }
            }
            is SpeechEvent.Failed -> when (event.failure) {
                SpeechFailure.PERMISSION_MISSING ->
                    setState(CaptureUiState.Unavailable(UnavailableReason.PERMISSION_MISSING))
                SpeechFailure.NO_ON_DEVICE_ENGINE ->
                    setState(CaptureUiState.Unavailable(UnavailableReason.NO_ENGINE))
                SpeechFailure.CANCELLED -> Unit
                SpeechFailure.NOTHING_HEARD,
                SpeechFailure.RECOGNIZER_BUSY,
                SpeechFailure.ENGINE_ERROR,
                -> setState(CaptureUiState.Failure)
            }
        }
    }
}
