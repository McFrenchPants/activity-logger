package com.mcfrenchpants.activityledger.wear.capture

/** Why voice capture cannot be offered at all on this watch right now. */
enum class UnavailableReason { PERMISSION_MISSING, NO_ENGINE }

/**
 * What the capture screen shows. Pure Kotlin. The only transcript text any state may hold is
 * [Listening.partial], which is display-only; every other state is text-free by construction.
 */
sealed interface CaptureUiState {
    /** Listening; [partial] is the live guess, for display only. */
    data class Listening(val partial: String?) : CaptureUiState

    /**
     * Final transcript handed to the sink (already durable in the outbox); briefly waiting for
     * the phone's answer. Busy: not tappable. Falls back to [Ready] if no answer comes in time.
     */
    data object Saving : CaptureUiState

    /** Idle and ready for a new capture: tapping starts listening. */
    data object Ready : CaptureUiState

    /** Phone saved it. [activityName] is the phone's canonical name, if it sent one. */
    data class Saved(val activityName: String?) : CaptureUiState

    /** Phone saved it but wants the user to review it there. */
    data object NeedsReview : CaptureUiState

    /** Capture failed; tapping retries. */
    data object Failure : CaptureUiState

    /** Capture cannot work; not retried by tap. */
    data class Unavailable(val reason: UnavailableReason) : CaptureUiState
}
