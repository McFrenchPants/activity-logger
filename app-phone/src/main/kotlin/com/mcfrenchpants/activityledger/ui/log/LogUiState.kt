package com.mcfrenchpants.activityledger.ui.log

import com.mcfrenchpants.activityledger.ui.components.HistoryRowModel
import com.mcfrenchpants.activityledger.ui.review.PickerState
import com.mcfrenchpants.activityledger.ui.review.Suggestion
import com.mcfrenchpants.activityledger.ui.review.UserMessage

/**
 * Everything the Log screen shows, as one immutable value (see [LogViewModel.state]).
 *
 * @property input The text currently in the capture field.
 * @property isStarted Whether the Log screen is in the foreground (ADR-029). Submitting is only
 *   possible while this is true.
 * @property isCapturing A capture is being stored and interpreted; submit is disabled meanwhile.
 * @property isListening Speech recognition is running: the microphone shows its stop
 *   affordance, the partial transcript is shown, Recent is dimmed and typed submit is blocked.
 * @property partialTranscript The words heard so far while [isListening], or empty. Shown in the
 *   evidence voice because they are the user's words, not the app's.
 * @property card The current result card, or null when none is shown.
 * @property picker The open activity picker, or null when it is closed.
 * @property recent The newest history rows (at most three), newest first.
 * @property recentLoaded False until the first history load finished (so "empty" is not shown
 *   before anything was read).
 * @property showAiNotReady Whether the "On-device AI isn't ready" row is shown.
 * @property message A plain-words message (usually a refusal), or null.
 * @property actionInFlight A card action (Undo, Change activity, resolve, open picker) is
 *   running; further card actions are ignored and the card's buttons are disabled meanwhile.
 */
data class LogUiState(
    val input: String = "",
    val isStarted: Boolean = false,
    val isCapturing: Boolean = false,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val card: ResultCard? = null,
    val picker: PickerState? = null,
    val recent: List<HistoryRowModel> = emptyList(),
    val recentLoaded: Boolean = false,
    val showAiNotReady: Boolean = false,
    val message: UserMessage? = null,
    val actionInFlight: Boolean = false,
) {
    /** Whether the submit action is currently available. */
    val canSubmit: Boolean get() = isStarted && !isCapturing && !isListening && input.isNotBlank()

    /** Whether the microphone control can be used (the same gate submit uses, minus the text). */
    val canUseMicrophone: Boolean get() = isStarted && !isCapturing
}

/**
 * The result card shown after a capture (UX_VISUAL_SPEC 4.1, 6).
 *
 * Most cards are about words that were stored, so they carry the capture they are about
 * ([ForCapture]). [RecognitionFailed] is deliberately not one of them: nothing was heard, so
 * there is no capture and no text to quote, and the type says so rather than holding blanks.
 */
sealed interface ResultCard {

    /** A card about a raw capture that exists in storage. */
    sealed interface ForCapture : ResultCard {
        /** The capture this card is about. */
        val captureId: String

        /** The user's own words, exactly as captured. */
        val rawText: String
    }

    /**
     * A card whose capture is stored but still has no occurrence: the user says which activity
     * it was (resolve) or leaves it for later. These are the only cards the picker resolves.
     */
    sealed interface Unresolved : ForCapture

    /**
     * The capture was logged as occurrence [occurrenceId]. Undo and Change activity are offered
     * while the undo window runs; [undoFractionRemaining] drains from 1 to 0.
     */
    data class Saved(
        override val captureId: String,
        override val rawText: String,
        val occurrenceId: String,
        val activityName: String,
        val time: String,
        val undoFractionRemaining: Float = 1f,
    ) : ForCapture

    /** The words are saved but the interpretation needs the user; nothing was guessed. */
    data class NeedsReview(
        override val captureId: String,
        override val rawText: String,
        val suggestions: List<Suggestion>,
    ) : Unresolved

    /** The words are saved but could not be categorized (on-device AI unavailable). */
    data class NotCategorized(
        override val captureId: String,
        override val rawText: String,
    ) : Unresolved

    /**
     * Speech recognition heard nothing usable (UX_VISUAL_SPEC 6). Nothing was captured and
     * nothing was saved, so this card has no captureId, no rawText and no fabricated words of
     * any kind -- only "Try again" and "Type instead".
     */
    data object RecognitionFailed : ResultCard
}
