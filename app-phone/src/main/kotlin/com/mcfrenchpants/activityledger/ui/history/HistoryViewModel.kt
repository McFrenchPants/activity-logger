package com.mcfrenchpants.activityledger.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.services.TagRefusal
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedCorrectionService
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.TaggedResolutionService
import com.mcfrenchpants.activityledger.core.domain.tagging.TagChoice
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionPolicy
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.temporal.DurationResolver
import com.mcfrenchpants.activityledger.ui.components.toHistoryRow
import com.mcfrenchpants.activityledger.ui.log.ResultCard
import com.mcfrenchpants.activityledger.ui.log.TagPickerState
import com.mcfrenchpants.activityledger.ui.review.CheckDraft
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.review.tagRefusalMessage
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
 * D7 filter chips, finishing a waiting capture with the Check card, and correcting a saved
 * entry's subject or action (or removing it) from its Edit sheet. ADR-047.
 *
 * Built from plain collaborators so it runs on the JVM ([HistoryViewModelFactory] wires the real
 * ones; ADR-032, no DI framework).
 *
 * Rules it keeps:
 * - A waiting capture's starting point is rebuilt from the words stored with it and the CURRENT
 *   tag catalog by [TagDecisionPolicy] -- no model is called. With no stored words both sides
 *   are blank. The time saved is the capture time (History does not have the resolved time).
 * - Saving goes through [resolution] and corrections through [correction]; the only direct write
 *   is hiding an occurrence. Raw captures are never changed.
 * - Writes are guarded like Log's: a storage failure shows a plain-words message (never the
 *   user's words) and a second action while one is in flight is ignored.
 * - Nothing is logged (AGENTS.md #11).
 */
class HistoryViewModel(
    private val repository: LedgerRepository,
    private val resolution: TaggedResolutionService,
    private val correction: TaggedCorrectionService,
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

    // ---- Waiting capture: the Check card ---------------------------------------------------

    /**
     * Opens the Check sheet for the waiting capture [captureId]. Only Needs-review and
     * Not-categorized rows can be opened. The starting sides come from the stored words and the
     * current catalog; no model is involved.
     */
    fun openCheck(captureId: String) {
        val row = _state.value.allRows.firstOrNull { it.captureId == captureId } ?: return
        if (!row.isAwaitingActivity || _state.value.sheetOpen) return
        runAction(failure = UserMessage(R.string.log_tags_not_loaded)) {
            val words = repository.loadExtractedWordsForCapture(captureId)
            val card = if (words == null) {
                CheckDraft.blank(row.captureId, row.rawText)
            } else {
                val catalog = repository.loadTagCatalog()
                val decision = TagDecisionPolicy.decide(
                    ExtractionCandidate(
                        operation = InterpretationOperation.LOG_ACTIVITY,
                        subject = words.subject,
                        action = words.action,
                        activityState = null,
                        temporalExpression = null,
                        durationExpression = words.durationExpression,
                    ),
                    catalog,
                )
                CheckDraft.from(
                    captureId = row.captureId,
                    rawText = row.rawText,
                    subjectWords = words.subject,
                    actionWords = words.action,
                    subject = decision.subject,
                    action = decision.action,
                    subjectInferred = decision.subjectInferred,
                    durationSeconds = DurationResolver.resolve(words.durationExpression)?.let { it * 60L },
                )
            }
            _state.update { if (it.sheetOpen) it else it.copy(check = card) }
        }
    }

    /** Chooses the offered close-match tag [tagId] for the [kind] side of the Check card. */
    fun chooseCandidate(kind: TagKind, tagId: String) {
        val card = _state.value.check ?: return
        val chosen = CheckDraft.candidateChoice(card, kind, tagId) ?: return
        updateCheck(card.captureId) { it.choose(kind, chosen) }
    }

    /** "Keep mine": chooses a new tag from the owner's words for the [kind] side. */
    fun keepMine(kind: TagKind) {
        val card = _state.value.check ?: return
        val chosen = CheckDraft.keepMineChoice(card, kind) ?: return
        updateCheck(card.captureId) { it.choose(kind, chosen) }
    }

    private fun updateCheck(captureId: String, transform: (ResultCard.Check) -> ResultCard.Check) {
        _state.update { current ->
            val shown = current.check
            if (shown == null || shown.captureId != captureId) current else current.copy(check = transform(shown), picker = null)
        }
    }

    /**
     * Saves the open capture with the chosen subject and action, at the capture time. Only
     * possible with both sides chosen; nothing is saved before this.
     */
    fun save() {
        val card = _state.value.check ?: return
        val subject = card.subject.chosen ?: return
        val action = card.action.chosen ?: return
        runAction(closePicker = true) {
            val result = resolution.resolve(
                captureId = card.captureId,
                subject = subject.choice,
                action = action.choice,
                time = card.time,
                durationSeconds = card.durationSeconds,
                activityState = card.activityState ?: ActivityState.COMPLETED,
            )
            when (result) {
                is TaggedResolutionResult.Resolved -> {
                    _state.update { if (it.check?.captureId == card.captureId) it.copy(check = null) else it }
                    reload()
                }
                is TaggedResolutionResult.Refused -> showTagRefusal(result.refusal)
            }
        }
    }

    /** Closes the Check sheet without writing; the capture stays waiting. */
    fun decideLater() {
        _state.update { it.copy(check = null, picker = null, message = null) }
    }

    // ---- Saved entry: the Edit sheet ---------------------------------------------------------

    /** Opens the Edit sheet for the saved tagged entry of the row of [captureId]. */
    fun openEdit(captureId: String) {
        val row = _state.value.allRows.firstOrNull { it.captureId == captureId } ?: return
        if (!row.isTagged || _state.value.sheetOpen) return
        _state.update {
            it.copy(
                edit = EditEntry(row.occurrenceId.orEmpty(), row.rawText, row.subjectName.orEmpty(), row.actionName.orEmpty()),
                message = null,
            )
        }
    }

    /** Closes the Edit sheet. */
    fun closeEdit() {
        _state.update { it.copy(edit = null, picker = null, message = null) }
    }

    private fun changeTag(kind: TagKind, choice: TagChoice) {
        val edit = _state.value.edit ?: return
        runAction(closePicker = true) {
            val result = if (kind == TagKind.SUBJECT) {
                correction.correct(edit.occurrenceId, subject = choice)
            } else {
                correction.correct(edit.occurrenceId, action = choice)
            }
            when (result) {
                is TaggedCorrectionResult.Applied -> reload()
                TaggedCorrectionResult.NothingChanged -> Unit
                is TaggedCorrectionResult.Refused -> showTagRefusal(result.refusal)
            }
        }
    }

    /**
     * "Remove from history": hides the entry. The raw words stay stored. The sheet closes only
     * once the hide is stored, so a failed removal leaves it open with a plain message.
     */
    fun removeFromHistory() {
        val edit = _state.value.edit ?: return
        runAction(closePicker = true) {
            try {
                repository.hideOccurrence(edit.occurrenceId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: IllegalArgumentException) {
                _state.update { it.copy(message = tagRefusalMessage(TagRefusal.OccurrenceNotFound)) }
                return@runAction
            }
            _state.update { if (it.edit?.occurrenceId == edit.occurrenceId) it.copy(edit = null) else it }
            reload()
        }
    }

    // ---- Picker -------------------------------------------------------------------------------

    /** Opens the tag picker for [kind] over the ACTIVE tags (optionally on the new-name field). */
    fun openPicker(kind: TagKind, startWithNewName: Boolean = false) {
        if (!_state.value.sheetOpen) return
        runAction(failure = UserMessage(R.string.log_tags_not_loaded)) {
            val tags = repository.loadTagCatalog().tagsOf(kind)
            _state.update { if (it.sheetOpen) it.copy(picker = TagPickerState(kind, tags, startWithNewName)) else it }
        }
    }

    /** The picker was closed without a choice (the sheet stays open). */
    fun closePicker() {
        _state.update { it.copy(picker = null) }
    }

    /** The picker returned [choice]: a side choice for the Check card, a correction for the Edit sheet. */
    fun onPickerChoice(choice: TagChoice) {
        val picker = _state.value.picker ?: return
        val card = _state.value.check
        if (card != null) {
            val chosen = CheckDraft.pickedChoice(picker, choice)
            if (chosen == null) closePicker() else updateCheck(card.captureId) { it.choose(picker.kind, chosen) }
        } else if (_state.value.edit != null) {
            changeTag(picker.kind, choice)
        } else {
            closePicker()
        }
    }

    // ---- Internals ----------------------------------------------------------------------------

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

    private fun showTagRefusal(refusal: TagRefusal) {
        _state.update { it.copy(message = tagRefusalMessage(refusal)) }
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
            // An open Edit sheet follows its entry's current names; if the entry is gone it closes.
            val edit = current.edit?.let { open ->
                rows.firstOrNull { it.occurrenceId == open.occurrenceId && it.isTagged }
                    ?.let { open.copy(subjectName = it.subjectName.orEmpty(), actionName = it.actionName.orEmpty()) }
            }
            current.copy(
                allRows = rows,
                loaded = true,
                edit = edit,
                message = if (current.message == UserMessage(R.string.history_not_loaded)) null else current.message,
            )
        }
    }
}
