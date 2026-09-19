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
import androidx.compose.material3.OutlinedButton
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
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.ui.components.ActivityPicker
import com.mcfrenchpants.activityledger.ui.components.EvidenceText
import com.mcfrenchpants.activityledger.ui.components.HistoryRow
import com.mcfrenchpants.activityledger.ui.components.RowState
import com.mcfrenchpants.activityledger.ui.components.StateTag
import com.mcfrenchpants.activityledger.ui.review.UserMessage
import com.mcfrenchpants.activityledger.ui.review.resolve
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/** Test tag of the History screen's root. */
const val HISTORY_SCREEN_TAG = "HistoryScreen"

/** Test tag of the History list. */
const val HISTORY_LIST_TAG = "HistoryList"

/** Test tag of the resolution sheet's content. */
const val RESOLUTION_SHEET_TAG = "ResolutionSheet"

/** Test tag of the filter chip for [filter]. */
fun historyFilterTag(filter: HistoryFilter): String = "HistoryFilter:${filter.name}"

/**
 * The History destination (UX_VISUAL_SPEC D1, D7, 4.2): every logged or pending capture, newest
 * first, with the All / Needs review / Not categorized chips. Needs-review and Not-categorized
 * rows open a resolution sheet; interpreted rows are not clickable yet (the occurrence sheet is
 * a later slice).
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
        onOpenRow = viewModel::openResolution,
        modifier = modifier,
    )

    val resolution = state.resolution
    val picker = state.picker
    if (picker != null) {
        ActivityPicker(
            activities = picker.activities,
            onChoose = viewModel::onPickerChoice,
            onDismiss = viewModel::closePicker,
            startWithNewActivity = picker.startWithNewActivity,
        )
    } else if (resolution != null) {
        ResolutionSheet(
            resolution = resolution,
            message = state.message,
            enabled = !state.actionInFlight,
            onSuggestion = { viewModel.resolve(ActivityTarget.Existing(it)) },
            onChooseActivity = { viewModel.openPicker() },
            onCreateActivity = { viewModel.openPicker(startWithNewActivity = true) },
            onDecideLater = viewModel::decideLater,
        )
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
    onOpenRow: (String) -> Unit,
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

        // A message about the sheet is shown in the sheet; anything else here.
        if (state.resolution == null) {
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

        val actionLabel = stringResource(R.string.history_row_action)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(HISTORY_LIST_TAG),
        ) {
            items(rows, key = { it.captureId }) { row ->
                if (row.isAwaitingActivity) {
                    HistoryRow(row, onClick = { onOpenRow(row.captureId) }, onClickLabel = actionLabel)
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
private fun ResolutionSheet(
    resolution: Resolution,
    message: UserMessage?,
    enabled: Boolean,
    onSuggestion: (String) -> Unit,
    onChooseActivity: () -> Unit,
    onCreateActivity: () -> Unit,
    onDecideLater: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDecideLater, sheetState = sheetState) {
        ResolutionSheetContent(
            resolution = resolution,
            message = message,
            enabled = enabled,
            onSuggestion = onSuggestion,
            onChooseActivity = onChooseActivity,
            onCreateActivity = onCreateActivity,
            onDecideLater = onDecideLater,
        )
    }
}

/**
 * The resolution sheet's body: the state, the words, the reassurance (Needs review only),
 * "Which activity was this?" with up to three suggestions, Choose another activity, Create new
 * activity and Decide later. Any refusal is shown here, in the sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResolutionSheetContent(
    resolution: Resolution,
    message: UserMessage?,
    enabled: Boolean,
    onSuggestion: (String) -> Unit,
    onChooseActivity: () -> Unit,
    onCreateActivity: () -> Unit,
    onDecideLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RESOLUTION_SHEET_TAG)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateTag(if (resolution.needsReview) RowState.NEEDS_REVIEW else RowState.NOT_CATEGORIZED)
        EvidenceText(resolution.rawText)
        if (resolution.needsReview) {
            Text(
                text = stringResource(R.string.log_needs_review_reassurance),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.log_which_activity),
            style = MaterialTheme.typography.titleMedium,
        )
        if (resolution.suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                resolution.suggestions.forEach { suggestion ->
                    OutlinedButton(
                        onClick = { onSuggestion(suggestion.activityId) },
                        enabled = enabled,
                        shape = LedgerShapes.button,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(suggestion.displayName)
                    }
                }
            }
        }
        message?.let {
            Text(
                text = it.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetTextButton(stringResource(R.string.log_choose_another_activity), onChooseActivity, enabled)
            SheetTextButton(stringResource(R.string.log_create_new_activity), onCreateActivity, enabled)
            SheetTextButton(stringResource(R.string.log_decide_later), onDecideLater)
        }
    }
}

@Composable
private fun SheetTextButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text)
    }
}
