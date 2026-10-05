package com.mcfrenchpants.activityledger.ui.history

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.components.TagPicker
import com.mcfrenchpants.activityledger.ui.review.UserMessage

/**
 * The saved-entry sheets of [viewModel], shared by the History and Explore screens: the tag
 * picker when one is open, otherwise the Edit sheet (Change subject / Change action / Remove
 * from history / Done) when an entry is open, otherwise nothing. The waiting-capture Check sheet
 * is not here: it stays on the History screen only.
 *
 * @param state [viewModel]'s current state, collected by the caller.
 */
@Composable
internal fun HistoryEntrySheets(viewModel: HistoryViewModel, state: HistoryUiState) {
    val picker = state.picker
    val edit = state.edit
    if (picker != null) {
        TagPicker(
            kind = picker.kind,
            tags = picker.tags,
            onChoose = viewModel::onPickerChoice,
            onDismiss = viewModel::closePicker,
            startWithNewName = picker.startWithNewName,
        )
    } else if (edit != null) {
        EditSheet(
            edit = edit,
            message = state.message,
            enabled = !state.actionInFlight,
            onChangeSubject = { viewModel.openPicker(TagKind.SUBJECT) },
            onChangeAction = { viewModel.openPicker(TagKind.ACTION) },
            onRemove = viewModel::removeFromHistory,
            onDone = viewModel::closeEdit,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(
    edit: EditEntry,
    message: UserMessage?,
    enabled: Boolean,
    onChangeSubject: () -> Unit,
    onChangeAction: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = sheetState) {
        EditSheetContent(edit, message, enabled, onChangeSubject, onChangeAction, onRemove, onDone)
    }
}
