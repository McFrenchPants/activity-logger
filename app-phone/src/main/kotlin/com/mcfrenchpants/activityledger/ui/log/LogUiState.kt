package com.mcfrenchpants.activityledger.ui.log

import androidx.annotation.StringRes
import com.mcfrenchpants.activityledger.core.domain.repository.CatalogActivity
import com.mcfrenchpants.activityledger.ui.components.RowState

/**
 * Everything the Log screen shows, as one immutable value (see [LogViewModel.state]).
 *
 * @property input The text currently in the capture field.
 * @property isStarted Whether the Log screen is in the foreground (ADR-029). Submitting is only
 *   possible while this is true.
 * @property isCapturing A capture is being stored and interpreted; submit is disabled meanwhile.
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
    val card: ResultCard? = null,
    val picker: PickerState? = null,
    val recent: List<RecentRow> = emptyList(),
    val recentLoaded: Boolean = false,
    val showAiNotReady: Boolean = false,
    val message: UserMessage? = null,
    val actionInFlight: Boolean = false,
) {
    /** Whether the submit action is currently available. */
    val canSubmit: Boolean get() = isStarted && !isCapturing && input.isNotBlank()
}

/** The result card shown after a capture (UX_VISUAL_SPEC 4.1). */
sealed interface ResultCard {
    /** The capture this card is about. */
    val captureId: String

    /** The user's own words, exactly as captured. */
    val rawText: String

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
    ) : ResultCard

    /** The words are saved but the interpretation needs the user; nothing was guessed. */
    data class NeedsReview(
        override val captureId: String,
        override val rawText: String,
        val suggestions: List<Suggestion>,
    ) : ResultCard

    /** The words are saved but could not be categorized (on-device AI unavailable). */
    data class NotCategorized(
        override val captureId: String,
        override val rawText: String,
    ) : ResultCard
}

/** An existing activity offered on the Needs-review card. */
data class Suggestion(val activityId: String, val displayName: String)

/**
 * The open activity picker.
 *
 * @property activities The ACTIVE catalog to choose from.
 * @property startWithNewActivity Open directly on the new-activity name field.
 */
data class PickerState(
    val activities: List<CatalogActivity>,
    val startWithNewActivity: Boolean,
)

/**
 * One Recent row (UX_VISUAL_SPEC 4.2).
 *
 * @property activityName The current activity's name, or null for an uninterpreted capture.
 * @property time The occurrence time (or, for an uninterpreted capture, the capture time),
 *   already formatted.
 * @property state The row's state tag, or null when none applies.
 */
data class RecentRow(
    val captureId: String,
    val activityName: String?,
    val time: String,
    val state: RowState?,
    val rawText: String,
)

/** A user-visible message: a string resource and its format arguments. */
data class UserMessage(@param:StringRes val text: Int, val args: List<Any> = emptyList())
