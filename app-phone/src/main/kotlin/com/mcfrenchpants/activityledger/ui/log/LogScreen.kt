package com.mcfrenchpants.activityledger.ui.log

import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.ui.components.EvidenceText
import com.mcfrenchpants.activityledger.ui.components.HistoryRow
import com.mcfrenchpants.activityledger.ui.components.TagPicker
import com.mcfrenchpants.activityledger.ui.review.resolve
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes

/** Test tag of the Log screen's root. */
const val LOG_SCREEN_TAG = "LogScreen"

/** Test tag of the capture text field. */
const val LOG_INPUT_TAG = "LogInput"

/** Test tag of the submit button. */
const val LOG_SUBMIT_TAG = "LogSubmit"

/** Test tag of the microphone control. */
const val LOG_MIC_TAG = "LogMic"

/** Test tag of the "Listening..." live region. */
const val LOG_LISTENING_TAG = "LogListening"

/** Test tag of the live partial transcript shown while listening. */
const val LOG_PARTIAL_TAG = "LogPartial"

/** Test tag of the Recent section (dimmed and hidden from screen readers while listening). */
const val LOG_RECENT_TAG = "LogRecent"

/** How far Recent is faded back while listening. */
private const val LISTENING_DIM_ALPHA = 0.38f

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
    canAskAgain: () -> Boolean = defaultCanAskAgain(),
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

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        when {
            granted -> viewModel.startListening()
            // Refused this time but askable again: say so in plain words and leave typing alone.
            canAskAgain() -> viewModel.onMicrophonePermissionDenied()
            // Refused for good: say so once. Typing is still the whole screen, so no dead end.
            else -> viewModel.onMicrophonePermissionBlocked()
        }
    }

    LogContent(
        state = state,
        onInputChange = viewModel::onInputChange,
        onSubmit = viewModel::submit,
        onUndo = viewModel::undo,
        onChangeSubject = { viewModel.openPicker(TagKind.SUBJECT) },
        onChangeAction = { viewModel.openPicker(TagKind.ACTION) },
        onChooseCandidate = viewModel::chooseCandidate,
        onKeepMine = viewModel::keepMine,
        onPickTag = { viewModel.openPicker(it) },
        onSave = viewModel::save,
        onDecideLater = viewModel::decideLater,
        onCardTouched = viewModel::setCardTouched,
        onOpenHistory = onOpenHistory,
        onMicrophone = {
            when {
                state.isListening -> viewModel.stopListening()
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> viewModel.startListening()
                // First tap without the permission: ask for it, never at launch.
                else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onTryAgain = viewModel::retryListening,
        onTypeInstead = viewModel::dismissRecognitionFailure,
        modifier = modifier,
    )

    state.picker?.let { picker ->
        TagPicker(
            kind = picker.kind,
            tags = picker.tags,
            onChoose = viewModel::onPickerChoice,
            onDismiss = viewModel::closePicker,
            startWithNewName = picker.startWithNewName,
        )
    }
}

/**
 * Whether the microphone permission can still be asked for after a refusal: the system stops
 * offering the dialog once the user has refused for good. Kept behind a lambda so both refusal
 * paths can be driven in a host-side test.
 */
@Composable
private fun defaultCanAskAgain(): () -> Boolean {
    val activity = LocalActivity.current
    return remember(activity) {
        { activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true }
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
    onChangeSubject: () -> Unit,
    onChangeAction: () -> Unit,
    onChooseCandidate: (TagKind, String) -> Unit,
    onKeepMine: (TagKind) -> Unit,
    onPickTag: (TagKind) -> Unit,
    onSave: () -> Unit,
    onDecideLater: () -> Unit,
    onCardTouched: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
    onMicrophone: () -> Unit,
    onTryAgain: () -> Unit,
    onTypeInstead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputFocus = remember { FocusRequester() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(LOG_SCREEN_TAG)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CaptureField(
            state = state,
            onInputChange = onInputChange,
            onSubmit = onSubmit,
            onMicrophone = onMicrophone,
            focusRequester = inputFocus,
        )

        if (state.isListening) ListeningSection(state)

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
            is ResultCard.Saved ->
                SavedCard(card, actionsEnabled, onUndo, onChangeSubject, onChangeAction, onCardTouched)
            is ResultCard.Check -> CheckCard(
                card, actionsEnabled, onChooseCandidate, onKeepMine, onPickTag, onSave, onDecideLater, onCardTouched,
            )
            ResultCard.RecognitionFailed -> RecognitionFailedCard(
                enabled = actionsEnabled,
                onTryAgain = onTryAgain,
                onTypeInstead = {
                    onTypeInstead()
                    // "Type instead" has to land the user in the text field, not just drop a card.
                    inputFocus.requestFocus()
                },
                onTouched = onCardTouched,
            )
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

/**
 * The capture field, and beside it the microphone (UX_VISUAL_SPEC 5; owner decision 2026-09-19).
 *
 * The typed field stays the primary control: voice is an extra way in, sitting next to submit,
 * not a separate screen the user has to leave in order to type.
 */
@Composable
private fun CaptureField(
    state: LogUiState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onMicrophone: () -> Unit,
    focusRequester: FocusRequester,
) {
    // Close the keyboard on send so the result card is not hidden behind it.
    val focusManager = LocalFocusManager.current
    val submit = {
        if (state.canSubmit) focusManager.clearFocus()
        onSubmit()
    }
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
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(LOG_INPUT_TAG),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            IconButton(
                onClick = onMicrophone,
                enabled = state.canUseMicrophone,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(LOG_MIC_TAG),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isListening) R.drawable.ic_mic_stop else R.drawable.ic_mic,
                    ),
                    contentDescription = stringResource(
                        if (state.isListening) R.string.log_mic_stop else R.string.log_mic,
                    ),
                )
            }
            Button(
                onClick = submit,
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

/**
 * What listening looks like (UX_VISUAL_SPEC 5, 6): an announced "Listening..." and the words
 * heard so far, in the evidence voice because they are the user's words, not the app's.
 */
@Composable
private fun ListeningSection(state: LogUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.log_listening),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .testTag(LOG_LISTENING_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.partialTranscript.isNotBlank()) {
            EvidenceText(state.partialTranscript, modifier = Modifier.testTag(LOG_PARTIAL_TAG))
        }
    }
}

@Composable
private fun RecentSection(state: LogUiState, onOpenHistory: () -> Unit) {
    // While listening, Recent is scenery: faded back visually and taken out of the semantics
    // tree, so a screen reader does not wander into old rows instead of the words being heard.
    val listening = state.isListening
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .testTag(LOG_RECENT_TAG)
            .alpha(if (listening) LISTENING_DIM_ALPHA else 1f)
            .then(if (listening) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
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
