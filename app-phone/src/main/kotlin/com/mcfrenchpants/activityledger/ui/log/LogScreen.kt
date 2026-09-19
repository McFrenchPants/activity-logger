package com.mcfrenchpants.activityledger.ui.log

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcfrenchpants.activityledger.ActivityLedgerApplication
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.ui.components.ActivityPicker
import com.mcfrenchpants.activityledger.ui.components.HistoryRow
import com.mcfrenchpants.activityledger.ui.review.resolve
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/** Test tag of the Log screen's root. */
const val LOG_SCREEN_TAG = "LogScreen"

/** Test tag of the capture text field. */
const val LOG_INPUT_TAG = "LogInput"

/** Test tag of the submit button. */
const val LOG_SUBMIT_TAG = "LogSubmit"

/**
 * The Log destination (start destination; UX_VISUAL_SPEC 4.1, typed entry only): the capture
 * field, the result card, the AI-not-ready row and Recent.
 *
 * Tells [viewModel] when it is started and stopped (ADR-029), passes it the accessibility-adjusted
 * undo window (D5), and dismisses the Saved card when the user leaves Log (but not on rotation).
 *
 * @param onOpenHistory Switches to the History tab ("All history").
 */
@Composable
fun LogScreen(
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogViewModel = defaultLogViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }

    val context = LocalContext.current
    val undoTimeout = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
            ?.getRecommendedTimeoutMillis(
                DEFAULT_UNDO_TIMEOUT_MILLIS.toInt(),
                AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
            )
            ?.toLong()
            ?: DEFAULT_UNDO_TIMEOUT_MILLIS
    }
    LaunchedEffect(viewModel, undoTimeout) { viewModel.setUndoTimeoutMillis(undoTimeout) }

    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose {
            // A configuration change (rotation) recreates this composable but is not "leaving Log".
            if (activity?.isChangingConfigurations != true) viewModel.onLeftLog()
        }
    }

    LogContent(
        state = state,
        onInputChange = viewModel::onInputChange,
        onSubmit = viewModel::submit,
        onUndo = viewModel::undo,
        onChangeActivity = { viewModel.openPicker() },
        onSuggestion = { viewModel.resolve(ActivityTarget.Existing(it)) },
        onChooseActivity = { viewModel.openPicker() },
        onCreateActivity = { viewModel.openPicker(startWithNewActivity = true) },
        onDecideLater = viewModel::decideLater,
        onCardTouched = viewModel::setCardTouched,
        onOpenHistory = onOpenHistory,
        modifier = modifier,
    )

    state.picker?.let { picker ->
        ActivityPicker(
            activities = picker.activities,
            onChoose = viewModel::onPickerChoice,
            onDismiss = viewModel::closePicker,
            startWithNewActivity = picker.startWithNewActivity,
        )
    }
}

@Composable
private fun defaultLogViewModel(): LogViewModel {
    val application = LocalContext.current.applicationContext as ActivityLedgerApplication
    return viewModel(factory = LogViewModelFactory(application))
}

/** The Log screen's stateless body. */
@Composable
internal fun LogContent(
    state: LogUiState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onUndo: () -> Unit,
    onChangeActivity: () -> Unit,
    onSuggestion: (String) -> Unit,
    onChooseActivity: () -> Unit,
    onCreateActivity: () -> Unit,
    onDecideLater: () -> Unit,
    onCardTouched: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(LOG_SCREEN_TAG)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CaptureField(state = state, onInputChange = onInputChange, onSubmit = onSubmit)

        if (state.isCapturing) {
            val capturing = stringResource(R.string.log_capturing)
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = capturing
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }

        val actionsEnabled = !state.actionInFlight
        when (val card = state.card) {
            is ResultCard.Saved -> SavedCard(card, actionsEnabled, onUndo, onChangeActivity, onCardTouched)
            is ResultCard.NeedsReview -> NeedsReviewCard(
                card, actionsEnabled, onSuggestion, onChooseActivity, onCreateActivity, onDecideLater, onCardTouched,
            )
            is ResultCard.NotCategorized ->
                NotCategorizedCard(card, actionsEnabled, onChooseActivity, onDecideLater, onCardTouched)
            null -> Unit
        }

        state.message?.let { message ->
            Text(
                text = message.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (state.showAiNotReady) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = LedgerShapes.listContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.log_ai_not_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        RecentSection(state = state, onOpenHistory = onOpenHistory)
    }
}

@Composable
private fun CaptureField(state: LogUiState, onInputChange: (String) -> Unit, onSubmit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.log_prompt)) },
            shape = LedgerShapes.field,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LOG_INPUT_TAG),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                shape = LedgerShapes.button,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(LOG_SUBMIT_TAG),
            ) {
                Text(stringResource(R.string.log_submit))
            }
        }
    }
}

@Composable
private fun RecentSection(state: LogUiState, onOpenHistory: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.log_recent), style = MaterialTheme.typography.titleMedium)
        if (state.recentLoaded && state.recent.isEmpty()) {
            Text(
                text = stringResource(R.string.log_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.recent.forEach { row -> HistoryRow(row) }
        TextButton(onClick = onOpenHistory, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.log_all_history))
        }
    }
}
