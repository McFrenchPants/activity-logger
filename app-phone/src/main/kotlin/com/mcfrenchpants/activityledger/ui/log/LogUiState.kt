package com.mcfrenchpants.activityledger.ui.log

import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.services.OccurrenceTime
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.components.HistoryRowModel
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
 * @property picker The open tag picker (subject or action), or null when it is closed.
 * @property recent The newest history rows (at most three), newest first.
 * @property recentLoaded False until the first history load finished (so "empty" is not shown
 *   before anything was read).
 * @property showAiNotReady Whether the "On-device AI isn't ready" row is shown.
 * @property message A plain-words message (usually a refusal), or null.
 * @property actionInFlight A card action (Undo, Change subject/action, Save, open picker) is
 *   running; further card actions are ignored and the card's buttons are disabled meanwhile.
 */
data class LogUiState(
    val input: String = "",
    val isStarted: Boolean = false,
    val isCapturing: Boolean = false,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val card: ResultCard? = null,
    val picker: TagPickerState? = null,
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
 * An open tag picker.
 *
 * @property kind Whether the owner is choosing a subject or an action.
 * @property tags The ACTIVE tags of [kind] to offer (names and aliases, for searching).
 * @property startWithNewName Open directly on the new-name field.
 */
data class TagPickerState(
    val kind: TagKind,
    val tags: List<KnownTag>,
    val startWithNewName: Boolean = false,
)

/** A tag the owner has chosen (or that was understood exactly): the choice and its shown name. */
data class ChosenTag(val choice: TagChoice, val name: String)

/**
 * One side (subject or action) of the Check card.
 *
 * @property kind Which side this is.
 * @property words What the owner said for this side, if anything was understood. Their own
 *   words: shown in the evidence style, never in a message resource.
 * @property chosen What is chosen for this side, or null when the owner still has to pick.
 * @property candidates Close existing tags offered as one-tap options (a "did you mean" side).
 * @property keepMine The name a brand-new tag would get from the owner's words, offered as
 *   "Keep mine" next to [candidates]; null when there are no candidates.
 * @property assumed True when [chosen] was filled in from the owner's usual combination rather
 *   than from their words.
 */
data class CheckSide(
    val kind: TagKind,
    val words: String? = null,
    val chosen: ChosenTag? = null,
    val candidates: List<KnownTag> = emptyList(),
    val keepMine: String? = null,
    val assumed: Boolean = false,
)

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

    /** A card whose capture is stored but still has no occurrence: the owner decides or waits. */
    sealed interface Unresolved : ForCapture

    /**
     * The capture was logged as occurrence [occurrenceId], as [subjectName] and [actionName].
     * Undo and Change subject / Change action are offered while the undo window runs;
     * [undoFractionRemaining] drains from 1 to 0.
     *
     * @property durationSeconds How long it took, when known.
     */
    data class Saved(
        override val captureId: String,
        override val rawText: String,
        val occurrenceId: String,
        val subjectName: String,
        val actionName: String,
        val durationSeconds: Long?,
        val time: String,
        val undoFractionRemaining: Float = 1f,
    ) : ForCapture

    /**
     * The words are saved but nothing was saved as an entry: the owner checks the subject and the
     * action. Serves a close match, a needs-review result, a rejection and an unavailable AI.
     * Nothing is saved until Save is tapped, and Save needs both sides chosen.
     *
     * @property time The time to save with, only when the proposal had a clean one; null means
     *   "when it was captured".
     * @property durationSeconds The duration from the proposal, if any.
     * @property activityState The state from the proposal, if the owner's words stated one.
     */
    data class Check(
        override val captureId: String,
        override val rawText: String,
        val subject: CheckSide,
        val action: CheckSide,
        val time: OccurrenceTime? = null,
        val durationSeconds: Long? = null,
        val activityState: ActivityState? = null,
    ) : Unresolved {
        /** Save is only possible once both sides are chosen. */
        val canSave: Boolean get() = subject.chosen != null && action.chosen != null

        /** The side of [kind]. */
        fun side(kind: TagKind): CheckSide = if (kind == TagKind.SUBJECT) subject else action

        /** A copy with [chosen] set on the [kind] side. */
        fun choose(kind: TagKind, chosen: ChosenTag): Check =
            if (kind == TagKind.SUBJECT) {
                copy(subject = subject.copy(chosen = chosen, assumed = false))
            } else {
                copy(action = action.copy(chosen = chosen, assumed = false))
            }
    }

    /**
     * Speech recognition heard nothing usable (UX_VISUAL_SPEC 6). Nothing was captured and
     * nothing was saved, so this card has no captureId, no rawText and no fabricated words of
     * any kind -- only "Try again" and "Type instead".
     */
    data object RecognitionFailed : ResultCard
}
