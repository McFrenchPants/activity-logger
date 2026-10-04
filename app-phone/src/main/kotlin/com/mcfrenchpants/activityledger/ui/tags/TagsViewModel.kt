package com.mcfrenchpants.activityledger.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.services.TagManagementService
import com.mcfrenchpants.activityledger.core.domain.services.TagMergeResult
import com.mcfrenchpants.activityledger.core.domain.services.TagRenameResult
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.review.tagRefusalMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder of the Tags screen: lists subjects and actions, renames a tag and merges one tag
 * into another (ADR-048). Everything is done by [TagManagementService]; the only call that merges
 * is [confirmMerge], reached after the chooser and the confirmation dialog.
 *
 * One change at a time: a second action while one is running is ignored. A storage failure shows
 * a plain-words message and changes nothing. Nothing is logged, and no message contains a tag name
 * (names appear only as on-screen content).
 */
class TagsViewModel(private val service: TagManagementService) : ViewModel() {

    private val _state = MutableStateFlow(TagsUiState())

    /** The screen's single UI state. */
    val state: StateFlow<TagsUiState> = _state.asStateFlow()

    /** The screen came to the foreground, or the owner tapped Try again: (re)loads both lists. */
    fun onStart() {
        viewModelScope.launch { reload() }
    }

    /** Switches the list between subjects and actions (ignored while a dialog is open). */
    fun selectKind(kind: TagKind) {
        _state.update { if (it.dialogOpen) it else it.copy(kind = kind, notice = null) }
    }

    // ---- Rename -----------------------------------------------------------------------------

    /** Opens the rename dialog for [tagId] of the selected kind, pre-filled with its name. */
    fun openRename(tagId: String) {
        val current = _state.value
        if (current.dialogOpen) return
        val tag = current.tags.firstOrNull { it.id == tagId } ?: return
        _state.update {
            it.copy(rename = RenameDialog(TagRef(tag.id, tag.name), tag.name), message = null, notice = null)
        }
    }

    /** The owner edited the new name. Clears any earlier conflict or refusal. */
    fun onRenameText(text: String) {
        _state.update { current ->
            current.rename?.let { current.copy(rename = it.copy(text = text, conflict = null), message = null) } ?: current
        }
    }

    /** Closes the rename dialog without writing. */
    fun cancelRename() {
        _state.update { it.copy(rename = null, message = null) }
    }

    /** Saves the new name. Only possible for a non-blank, changed name. */
    fun saveRename() {
        val dialog = _state.value.rename ?: return
        if (!dialog.canSave) return
        val kind = _state.value.kind
        runAction {
            when (val result = service.rename(kind, dialog.tag.id, dialog.text)) {
                TagRenameResult.Renamed -> {
                    _state.update { it.copy(rename = null, notice = TagsNotice.Renamed) }
                    reload()
                }
                TagRenameResult.NothingChanged -> _state.update { it.copy(rename = null) }
                is TagRenameResult.NameInUse -> {
                    val other = _state.value.tags.firstOrNull { it.id == result.otherTagId }
                    if (other == null) {
                        // The list is out of date; say so plainly and refresh.
                        _state.update { it.copy(message = UserMessage(R.string.refusal_tag_not_found)) }
                        reload()
                    } else {
                        _state.update {
                            it.copy(rename = it.rename?.copy(conflict = TagRef(other.id, other.name)))
                        }
                    }
                }
                is TagRenameResult.Refused ->
                    _state.update { it.copy(message = tagRefusalMessage(result.refusal)) }
            }
        }
    }

    /** "Merge them instead": goes to the merge confirmation, this tag INTO the one with the name. */
    fun mergeInsteadOfRename() {
        val dialog = _state.value.rename ?: return
        val other = dialog.conflict ?: return
        _state.update { it.copy(rename = null, confirm = MergeConfirm(dialog.tag, other), message = null) }
    }

    // ---- Merge ------------------------------------------------------------------------------

    /** Opens the chooser of the OTHER tags for merging [tagId] into one of them. */
    fun openMerge(tagId: String) {
        val current = _state.value
        if (current.dialogOpen) return
        val tag = current.tags.firstOrNull { it.id == tagId } ?: return
        _state.update { it.copy(chooser = MergeChooser(TagRef(tag.id, tag.name)), message = null, notice = null) }
    }

    /** Closes the chooser without choosing. */
    fun cancelMergeChooser() {
        _state.update { it.copy(chooser = null) }
    }

    /** Chooses [intoTagId] in the chooser: opens the confirmation. Writes nothing. */
    fun chooseMergeTarget(intoTagId: String) {
        val current = _state.value
        val chooser = current.chooser ?: return
        val into = current.tags.firstOrNull { it.id == intoTagId && it.id != chooser.from.id } ?: return
        _state.update { it.copy(chooser = null, confirm = MergeConfirm(chooser.from, TagRef(into.id, into.name))) }
    }

    /** Closes the confirmation without merging. */
    fun cancelMerge() {
        _state.update { it.copy(confirm = null, message = null) }
    }

    /** The one call that merges: the owner confirmed. */
    fun confirmMerge() {
        val confirm = _state.value.confirm ?: return
        val kind = _state.value.kind
        runAction {
            when (val result = service.merge(kind, confirm.from.id, confirm.into.id)) {
                is TagMergeResult.Merged -> {
                    _state.update { it.copy(confirm = null, notice = TagsNotice.Merged(result.movedOccurrences)) }
                    reload()
                }
                is TagMergeResult.Refused ->
                    _state.update { it.copy(message = tagRefusalMessage(result.refusal)) }
            }
        }
    }

    // ---- Internals --------------------------------------------------------------------------

    /** Runs one action unless another is running; a failure keeps everything and sets the message. */
    private fun runAction(block: suspend () -> Unit) {
        if (_state.value.actionInFlight) return
        _state.update { it.copy(actionInFlight = true, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Exception) {
                _state.update { it.copy(message = UserMessage(R.string.log_action_failed)) }
            } finally {
                _state.update { it.copy(actionInFlight = false) }
            }
        }
    }

    private suspend fun reload() {
        val loaded = try {
            service.listTags(TagKind.SUBJECT) to service.listTags(TagKind.ACTION)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            _state.update { it.copy(loadFailed = true) }
            return
        }
        _state.update { it.copy(subjects = loaded.first, actions = loaded.second, loaded = true, loadFailed = false) }
    }
}
