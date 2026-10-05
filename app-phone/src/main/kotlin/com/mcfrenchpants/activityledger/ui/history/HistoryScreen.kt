package com.mcfrenchpants.activityledger.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcfrenchpants.activityledger.ActivityLedgerApplication
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.components.EvidenceText
import com.mcfrenchpants.activityledger.ui.components.HistoryRow
import com.mcfrenchpants.activityledger.ui.log.CheckCard
import com.mcfrenchpants.activityledger.ui.log.ResultCard
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.review.resolve

/** Test tag of the History screen's root. */
const val HISTORY_SCREEN_TAG = "HistoryScreen"

/** Test tag of the History list. */
const val HISTORY_LIST_TAG = "HistoryList"

/** Test tag of the waiting-capture sheet's content (it holds the shared Check card). */
const val CHECK_SHEET_TAG = "HistoryCheckSheet"

/** Test tag of the Edit sheet's content. */
const val EDIT_SHEET_TAG = "HistoryEditSheet"

/** Test tags of the Edit sheet's buttons. */
const val EDIT_CHANGE_SUBJECT_TAG = "HistoryEditChangeSubject"
const val EDIT_CHANGE_ACTION_TAG = "HistoryEditChangeAction"
const val EDIT_REMOVE_TAG = "HistoryEditRemove"
const val EDIT_DONE_TAG = "HistoryEditDone"

/** Test tag of the filter chip for [filter]. */
fun historyFilterTag(filter: HistoryFilter): String = "HistoryFilter:${filter.name}"

/**
 * The History destination (UX_VISUAL_SPEC D1, D7, 4.2): every logged or pending capture, newest
 * first, with the All / Needs review / Not categorized chips. A waiting row opens the same Check
 * card the Log screen uses; a saved tagged row opens a small Edit sheet; a row from the old
 * pipeline is not clickable (ADR-047).
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = defaultHistoryViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { }
    }

    HistoryContent(
        state = state,
        onSelectFilter = viewModel::selectFilter,
        onOpenCheck = viewModel::openCheck,
        onOpenEdit = viewModel::openEdit,
        modifier = modifier,
    )

    // The Check sheet is History-only; the tag picker and the Edit sheet are shared with Explore.
    val check = state.check
    if (check != null && state.picker == null) {
        CheckSheet(
            card = check,
            message = state.message,
            enabled = !state.actionInFlight,
            onChooseCandidate = viewModel::chooseCandidate,
            onKeepMine = viewModel::keepMine,
            onPick = { viewModel.openPicker(it) },
            onSave = viewModel::save,
            onDecideLater = viewModel::decideLater,
        )
    } else {
        HistoryEntrySheets(viewModel = viewModel, state = state)
    }
}

@Composable
private fun defaultHistoryViewModel(): HistoryViewModel {
    val application = LocalContext.current.applicationContext as ActivityLedgerApplication
    return viewModel(factory = HistoryViewModelFactory(application))
}

/** The History screen's stateless body. */
@Composable
internal fun HistoryContent(
    state: HistoryUiState,
    onSelectFilter: (HistoryFilter) -> Unit,
    onOpenCheck: (String) -> Unit,
    onOpenEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(HISTORY_SCREEN_TAG)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_history),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(top = 16.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        FilterChips(selected = state.filter, onSelect = onSelectFilter)

        // A message about a sheet is shown in the sheet; anything else here.
        if (!state.sheetOpen) {
            state.message?.let { message ->
                Text(
                    text = message.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        val rows = state.rows
        val emptyText = when {
            !state.loaded -> null
            state.allRows.isEmpty() -> R.string.log_recent_empty
            rows.isNotEmpty() -> null
            state.filter == HistoryFilter.NEEDS_REVIEW -> R.string.history_empty_needs_review
            state.filter == HistoryFilter.NOT_CATEGORIZED -> R.string.history_empty_not_categorized
            else -> null
        }
        if (emptyText != null) {
            Text(
                text = stringResource(emptyText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }

        val checkLabel = stringResource(R.string.history_row_action_check)
        val editLabel = stringResource(R.string.history_row_action_edit)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(HISTORY_LIST_TAG),
        ) {
            items(rows, key = { it.captureId }) { row ->
                if (row.isAwaitingActivity) {
                    HistoryRow(row, onClick = { onOpenCheck(row.captureId) }, onClickLabel = checkLabel)
                } else if (row.isTagged) {
                    HistoryRow(row, onClick = { onOpenEdit(row.captureId) }, onClickLabel = editLabel)
                } else {
                    HistoryRow(row)
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(selected: HistoryFilter, onSelect: (HistoryFilter) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filter.labelRes())) },
                // M3 chips keep a 48dp touch target around their 32dp visual height.
                modifier = Modifier.testTag(historyFilterTag(filter)),
            )
        }
    }
}

private fun HistoryFilter.labelRes(): Int = when (this) {
    HistoryFilter.ALL -> R.string.history_filter_all
    HistoryFilter.NEEDS_REVIEW -> R.string.history_filter_needs_review
    HistoryFilter.NOT_CATEGORIZED -> R.string.history_filter_not_categorized
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckSheet(
    card: ResultCard.Check,
    message: UserMessage?,
    enabled: Boolean,
    onChooseCandidate: (TagKind, String) -> Unit,
    onKeepMine: (TagKind) -> Unit,
    onPick: (TagKind) -> Unit,
    onSave: () -> Unit,
    onDecideLater: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDecideLater, sheetState = sheetState) {
        CheckSheetContent(card, message, enabled, onChooseCandidate, onKeepMine, onPick, onSave, onDecideLater)
    }
}

/**
 * The waiting-capture sheet's body: the same Check card the Log screen shows (both sides chosen
 * before Save, close matches with "Keep mine", Decide later), then any refusal in plain words.
 */
@Composable
internal fun CheckSheetContent(
    card: ResultCard.Check,
    message: UserMessage?,
    enabled: Boolean,
    onChooseCandidate: (TagKind, String) -> Unit,
    onKeepMine: (TagKind) -> Unit,
    onPick: (TagKind) -> Unit,
    onSave: () -> Unit,
    onDecideLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CHECK_SHEET_TAG)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckCard(
            card = card,
            enabled = enabled,
            onChooseCandidate = onChooseCandidate,
            onKeepMine = onKeepMine,
            onPick = onPick,
            onSave = onSave,
            onDecideLater = onDecideLater,
            onTouched = {},
        )
        SheetMessage(message)
    }
}

/**
 * The Edit sheet's body: the owner's words, the subject and action with Change subject / Change
 * action, Remove from history with a one-line explanation, and Done. Any refusal is shown here.
 */
@Composable
internal fun EditSheetContent(
    edit: EditEntry,
    message: UserMessage?,
    enabled: Boolean,
    onChangeSubject: () -> Unit,
    onChangeAction: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(EDIT_SHEET_TAG)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.history_edit_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        EvidenceText(edit.rawText)
        Text(
            text = stringResource(R.string.log_check_subject_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(text = edit.subjectName, style = MaterialTheme.typography.titleMedium)
        SheetTextButton(stringResource(R.string.log_change_subject), onChangeSubject, enabled, EDIT_CHANGE_SUBJECT_TAG)
        Text(
            text = stringResource(R.string.log_check_action_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(text = edit.actionName, style = MaterialTheme.typography.titleMedium)
        SheetTextButton(stringResource(R.string.log_change_action), onChangeAction, enabled, EDIT_CHANGE_ACTION_TAG)
        HorizontalDivider()
        Text(
            text = stringResource(R.string.history_edit_remove_explain),
            style = MaterialTheme.typography.bodyMedium,
        )
        SheetTextButton(stringResource(R.string.history_edit_remove), onRemove, enabled, EDIT_REMOVE_TAG)
        SheetMessage(message)
        SheetTextButton(stringResource(R.string.history_edit_done), onDone, true, EDIT_DONE_TAG)
    }
}

@Composable
private fun SheetMessage(message: UserMessage?) {
    message?.let {
        Text(
            text = it.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun SheetTextButton(text: String, onClick: () -> Unit, enabled: Boolean, tag: String) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        Text(text)
    }
}
