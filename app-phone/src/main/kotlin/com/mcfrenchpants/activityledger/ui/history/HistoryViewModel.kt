package com.mcfrenchpants.activityledger.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.services.ResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.ReviewResolutionService
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.components.toHistoryRow
import com.mcfrenchpants.activityledger.ui.review.PickerState
import com.mcfrenchpants.activityledger.ui.review.ReviewSuggestions
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.review.refusalMessageFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.Locale

/**
 * State holder of the History screen: every capture `loadHistory()` returns, newest first, the
 * D7 filter chips, and resolving Needs-review / Not-categorized captures from their rows.
 *
 * Built from plain collaborators so it runs on the JVM ([HistoryViewModelFactory] wires the real
 * ones; ADR-032, no DI framework).
 *
 * Rules it keeps:
 * - Resolution goes through [reviewResolutionService] only; nothing here writes directly.
 * - Writes are guarded like Log's: a storage failure shows a plain-words message (never the
 *   user's words) and a second action while one is in flight is ignored.
 * - No undo window here (D5: Remove from history belongs to the occurrence sheet).
 * - Nothing is logged (AGENTS.md #11).
 */
class HistoryViewModel(
    private val repository: ActivityRepository,
    private val reviewResolutionService: ReviewResolutionService,
    private val clock: Clock,
    private val locale: () -> Locale = { Locale.getDefault() },
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())

    /** The screen's single UI state. */
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    /** The History screen came to the foreground: (re)loads the list. */
    fun onStart() {
        viewModelScope.launch { reload() }
    }

    /** Selects a filter chip. */
    fun selectFilter(filter: HistoryFilter) {
        _state.update { it.copy(filter = filter) }
    }

    /**
     * Opens the resolution sheet for the row of [captureId], with its suggestions. Only
     * Needs-review and Not-categorized rows can be opened; interpreted rows are ignored.
     */
    fun openResolution(captureId: String) {
        val row = _state.value.allRows.firstOrNull { it.captureId == captureId } ?: return
        if (!row.isAwaitingActivity || _state.value.resolution != null) return
        runAction(failure = UserMessage(R.string.log_activities_not_loaded)) {
            val suggestions = ReviewSuggestions.suggest(repository, row.captureId, row.rawText)
            _state.update {
                it.copy(
                    resolution = Resolution(
                        captureId = row.captureId,
                        rawText = row.rawText,
                        needsReview = row.state == RowState.NEEDS_REVIEW,
                        suggestions = suggestions,
                    ),
                )
            }
        }
    }

    /** Logs the open capture as [target] via [ReviewResolutionService]. */
    fun resolve(target: ActivityTarget) {
        val resolution = _state.value.resolution ?: return
        runAction(closePicker = true) {
            when (val result = reviewResolutionService.resolve(resolution.captureId, target)) {
                is ResolutionResult.Resolved -> {
                    _state.update {
                        if (it.resolution?.captureId == resolution.captureId) it.copy(resolution = null) else it
                    }
                    reload()
                }
                is ResolutionResult.Refused -> {
                    val message = refusalMessageFor(repository, result.refusal)
                    _state.update { it.copy(message = message) }
                }
            }
        }
    }

    /** Closes the resolution sheet without writing; the capture stays waiting. */
    fun decideLater() {
        _state.update { it.copy(resolution = null, picker = null, message = null) }
    }

    /** Opens the activity picker over the ACTIVE catalog (optionally on the new-name field). */
    fun openPicker(startWithNewActivity: Boolean = false) {
        if (_state.value.resolution == null) return
        runAction(failure = UserMessage(R.string.log_activities_not_loaded)) {
            val catalog = repository.loadCatalog()
            _state.update {
                if (it.resolution == null) it else it.copy(picker = PickerState(catalog, startWithNewActivity))
            }
        }
    }

    /** The picker was closed without a choice (the resolution sheet stays open). */
    fun closePicker() {
        _state.update { it.copy(picker = null) }
    }

    /** The picker returned [target]. */
    fun onPickerChoice(target: ActivityTarget) {
        resolve(target)
    }

    /**
     * Runs one action unless another is already running -- a second tap while one is in flight
     * is ignored. A storage failure keeps what is shown and sets [failure].
     */
    private fun runAction(
        closePicker: Boolean = false,
        failure: UserMessage = UserMessage(R.string.log_action_failed),
        block: suspend () -> Unit,
    ) {
        if (_state.value.actionInFlight) return
        _state.update {
            it.copy(actionInFlight = true, message = null, picker = if (closePicker) null else it.picker)
        }
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Exception) {
                _state.update { it.copy(message = failure) }
            } finally {
                _state.update { it.copy(actionInFlight = false) }
            }
        }
    }

    private suspend fun reload() {
        val history = try {
            repository.loadHistory()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            _state.update { it.copy(message = UserMessage(R.string.history_not_loaded)) }
            return
        }
        val now = clock.instant()
        val rows = history.map { it.toHistoryRow(now, locale()) }
        _state.update { current ->
            current.copy(
                allRows = rows,
                loaded = true,
                message = if (current.message == UserMessage(R.string.history_not_loaded)) null else current.message,
            )
        }
    }
}
