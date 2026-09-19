package com.mcfrenchpants.activityledger.ui.history

import com.mcfrenchpants.activityledger.ui.components.HistoryRowModel
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.review.PickerState
import com.mcfrenchpants.activityledger.ui.review.Suggestion
import com.mcfrenchpants.activityledger.ui.review.UserMessage

/** The History filter chips (UX_VISUAL_SPEC D7). Single-select; no counts. */
enum class HistoryFilter {
    /** Everything `loadHistory()` returns. */
    ALL,

    /** Captures with no occurrence whose processing state is NEEDS_REVIEW. */
    NEEDS_REVIEW,

    /** Captures with no occurrence in any other processing state. */
    NOT_CATEGORIZED,
    ;

    /** Whether [row] is shown under this filter. */
    fun matches(row: HistoryRowModel): Boolean = when (this) {
        ALL -> true
        NEEDS_REVIEW -> row.isAwaitingActivity && row.state == RowState.NEEDS_REVIEW
        NOT_CATEGORIZED -> row.isAwaitingActivity && row.state != RowState.NEEDS_REVIEW
    }
}

/**
 * The capture being resolved from its History row.
 *
 * @property needsReview True for a Needs-review capture (shows the reassurance), false for Not
 *   categorized.
 * @property suggestions At most three existing activities to offer.
 */
data class Resolution(
    val captureId: String,
    val rawText: String,
    val needsReview: Boolean,
    val suggestions: List<Suggestion>,
)

/**
 * Everything the History screen shows, as one immutable value (see [HistoryViewModel.state]).
 *
 * @property filter The selected filter chip.
 * @property allRows Every history row, newest first, in `loadHistory()` order.
 * @property loaded False until the first history load succeeded (so "empty" is never shown
 *   before anything was read).
 * @property resolution The capture whose resolution sheet is open, or null.
 * @property picker The open activity picker, or null.
 * @property actionInFlight A write (or picker load) is running; further ones are ignored.
 * @property message A plain-words message (a refusal or storage failure), or null.
 */
data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.ALL,
    val allRows: List<HistoryRowModel> = emptyList(),
    val loaded: Boolean = false,
    val resolution: Resolution? = null,
    val picker: PickerState? = null,
    val actionInFlight: Boolean = false,
    val message: UserMessage? = null,
) {
    /** The rows shown under [filter], newest first. */
    val rows: List<HistoryRowModel> get() = allRows.filter(filter::matches)
}
