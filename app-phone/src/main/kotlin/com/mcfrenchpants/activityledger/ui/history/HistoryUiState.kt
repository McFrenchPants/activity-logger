package com.mcfrenchpants.activityledger.ui.history

import com.mcfrenchpants.activityledger.ui.components.HistoryRowModel
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.log.ResultCard
import com.mcfrenchpants.activityledger.ui.log.TagPickerState
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
 * The saved entry whose Edit sheet is open.
 *
 * @property rawText The owner's own words (shown in the evidence style, never in a message).
 * @property subjectName The entry's current subject name.
 * @property actionName The entry's current action name.
 */
data class EditEntry(
    val occurrenceId: String,
    val rawText: String,
    val subjectName: String,
    val actionName: String,
)

/**
 * Everything the History screen shows, as one immutable value (see [HistoryViewModel.state]).
 *
 * @property filter The selected filter chip.
 * @property allRows Every history row, newest first, in `loadHistory()` order.
 * @property loaded False until the first history load succeeded (so "empty" is never shown
 *   before anything was read).
 * @property check The waiting capture being finished (the same Check card the Log screen uses),
 *   or null.
 * @property edit The saved entry whose Edit sheet is open, or null.
 * @property picker The open tag picker (subject or action), or null.
 * @property actionInFlight A write (or picker load) is running; further ones are ignored.
 * @property message A plain-words message (a refusal or storage failure), or null.
 */
data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.ALL,
    val allRows: List<HistoryRowModel> = emptyList(),
    val loaded: Boolean = false,
    val check: ResultCard.Check? = null,
    val edit: EditEntry? = null,
    val picker: TagPickerState? = null,
    val actionInFlight: Boolean = false,
    val message: UserMessage? = null,
) {
    /** The rows shown under [filter], newest first. */
    val rows: List<HistoryRowModel> get() = allRows.filter(filter::matches)

    /** Whether a sheet is open (its messages are shown in it, not on the screen). */
    val sheetOpen: Boolean get() = check != null || edit != null
}
