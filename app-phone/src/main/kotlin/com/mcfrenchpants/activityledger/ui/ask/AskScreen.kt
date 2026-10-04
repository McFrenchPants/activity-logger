package com.mcfrenchpants.activityledger.ui.ask

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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
import com.mcfrenchpants.activityledger.core.domain.lookup.LookupMatch
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.ui.components.DurationFormatter
import com.mcfrenchpants.activityledger.ui.components.EvidenceText
import com.mcfrenchpants.activityledger.ui.review.resolve
import com.mcfrenchpants.activityledger.ui.theme.LedgerShapes
import com.mcfrenchpants.activityledger.ui.time.OccurrenceTimeFormatter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Test tag of the Ask screen's root. */
const val ASK_SCREEN_TAG = "AskScreen"

/** Test tag of the question field. */
const val ASK_INPUT_TAG = "AskInput"

/** Test tag of the Send button. */
const val ASK_SEND_TAG = "AskSend"

/** Test tag of the microphone control. */
const val ASK_MIC_TAG = "AskMic"

/** Test tag of the "Listening..." live region. */
const val ASK_LISTENING_TAG = "AskListening"

/** Test tag of the live partial transcript shown while listening. */
const val ASK_PARTIAL_TAG = "AskPartial"

/** Test tag of the Clear button. */
const val ASK_CLEAR_TAG = "AskClear"

/** Test tag of the empty-state invitation. */
const val ASK_EMPTY_TAG = "AskEmpty"

/** Test tag of the thread list. */
const val ASK_THREAD_TAG = "AskThread"

/** Test tag of the pending-answer progress indicator. */
const val ASK_PENDING_TAG = "AskPending"

/** Most further matches listed under "Other matches". */
internal const val MAX_OTHER_MATCHES = 5

/**
 * The Ask destination: questions about the logged history, answered from the database.
 *
 * Owns the microphone permission request exactly as the Log screen does: asked on the first tap
 * only, never at launch.
 */
@Composable
fun AskScreen(
    modifier: Modifier = Modifier,
    viewModel: AskViewModel = defaultAskViewModel(),
    canAskAgain: () -> Boolean = defaultCanAskAgain(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // A voice session must not keep listening once the screen is no longer showing.
    LifecycleStartEffect(viewModel) {
        onStopOrDispose { viewModel.stopListening() }
    }

    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        when {
            granted -> viewModel.startListening()
            canAskAgain() -> viewModel.onMicrophonePermissionDenied()
            else -> viewModel.onMicrophonePermissionBlocked()
        }
    }

    AskContent(
        state = state,
        onInputChange = viewModel::onInputChange,
        onAsk = viewModel::ask,
        onClear = viewModel::clear,
        onDismissMessage = viewModel::dismissMessage,
        onMicrophone = {
            when {
                state.isListening -> viewModel.stopListening()
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> viewModel.startListening()
                else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun defaultCanAskAgain(): () -> Boolean {
    val activity = LocalActivity.current
    return remember(activity) {
        { activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true }
    }
}

@Composable
private fun defaultAskViewModel(): AskViewModel {
    val application = LocalContext.current.applicationContext as ActivityLedgerApplication
    return viewModel(factory = AskViewModelFactory(application))
}

/**
 * The Ask screen's stateless body.
 *
 * @param zone The zone times are shown in (the entries carry none of their own).
 * @param now The moment "Today" is judged against.
 */
@Composable
internal fun AskContent(
    state: AskUiState,
    onInputChange: (String) -> Unit,
    onAsk: () -> Unit,
    onClear: () -> Unit,
    onDismissMessage: () -> Unit,
    onMicrophone: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.thread.size) {
        if (state.thread.isNotEmpty()) listState.animateScrollToItem(state.thread.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(ASK_SCREEN_TAG)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.nav_ask),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            if (state.thread.isNotEmpty()) {
                TextButton(
                    onClick = onClear,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(ASK_CLEAR_TAG),
                ) {
                    Text(
                        text = stringResource(R.string.ask_clear),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.thread.isEmpty()) {
            Text(
                text = stringResource(R.string.ask_empty_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 16.dp)
                    .testTag(ASK_EMPTY_TAG),
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(ASK_THREAD_TAG),
            ) {
                items(state.thread, key = { turn -> turn.id }) { turn ->
                    TurnView(turn, zone, now)
                }
            }
        }

        InputBar(
            state = state,
            onInputChange = onInputChange,
            onAsk = onAsk,
            onDismissMessage = onDismissMessage,
            onMicrophone = onMicrophone,
        )
    }
}

@Composable
private fun TurnView(turn: AskTurn, zone: ZoneId, now: Instant) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = LedgerShapes.card,
            modifier = Modifier
                .align(Alignment.End)
                .fillMaxWidth(0.85f),
        ) {
            Text(
                text = turn.question,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        Card(
            shape = LedgerShapes.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(0.95f),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                when (val outcome = turn.outcome) {
                    null -> PendingAnswer()
                    is AskOutcome.Answered -> AnsweredBody(outcome, zone, now)
                    AskOutcome.NotEnoughHistory -> BodyText(R.string.ask_not_enough_history)
                    AskOutcome.NotAQuestion -> {
                        BodyText(R.string.ask_not_a_question)
                        QuietText(stringResource(R.string.ask_not_a_question_pointer))
                    }
                    AskOutcome.AiUnavailable -> BodyText(R.string.ask_ai_unavailable)
                    AskOutcome.TryAgainLater -> BodyText(R.string.ask_try_again_later)
                }
            }
        }
    }
}

@Composable
private fun PendingAnswer() {
    val description = stringResource(R.string.ask_pending)
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ASK_PENDING_TAG)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
private fun BodyText(resId: Int) {
    Text(text = stringResource(resId), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun QuietText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The database fact first, then the earlier occurrence, then any further matches. */
@Composable
private fun AnsweredBody(outcome: AskOutcome.Answered, zone: ZoneId, now: Instant) {
    val result = outcome.result
    val top = result.top ?: return
    val resources = LocalContext.current.resources
    val locale = Locale.getDefault()
    fun time(match: LookupMatch) = OccurrenceTimeFormatter.format(
        match.entry.occurredAt, TimePrecision.EXACT, zone, now, locale,
    )

    Text(
        text = stringResource(R.string.ask_last_logged, top.entry.subjectName, top.entry.actionName),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(text = time(top), style = MaterialTheme.typography.bodyLarge)
    top.entry.durationSeconds?.let { seconds ->
        Text(
            text = stringResource(R.string.ask_duration, DurationFormatter.format(resources, seconds)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    result.previous?.let { previous ->
        Text(
            text = stringResource(R.string.ask_before_that, time(previous)),
            style = MaterialTheme.typography.bodyLarge,
        )
        result.intervalFromPrevious?.let { interval ->
            Text(
                text = stringResource(R.string.ask_gap, intervalText(interval)),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    when (matchNote(top.exact, top.tier)) {
        MatchNote.NONE -> Unit
        MatchNote.NOT_EXACT -> QuietText(stringResource(R.string.ask_note_not_exact))
        MatchNote.PARTIAL -> QuietText(stringResource(R.string.ask_note_partial))
    }

    if (result.matches.size > 1) {
        Text(
            text = stringResource(R.string.ask_other_matches),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        result.matches.drop(1).take(MAX_OTHER_MATCHES).forEach { match ->
            Column {
                Text(
                    text = stringResource(R.string.ask_other_match, match.entry.subjectName, match.entry.actionName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                QuietText(time(match))
            }
        }
    }
}

@Composable
private fun intervalText(interval: Duration): String = when (val words = intervalWords(interval)) {
    is IntervalWords.Days -> pluralStringResource(R.plurals.ask_interval_days, words.count, words.count)
    is IntervalWords.Hours -> pluralStringResource(R.plurals.ask_interval_hours, words.count, words.count)
    IntervalWords.LessThanAnHour -> stringResource(R.string.ask_interval_less_than_hour)
}

@Composable
private fun InputBar(
    state: AskUiState,
    onInputChange: (String) -> Unit,
    onAsk: () -> Unit,
    onDismissMessage: () -> Unit,
    onMicrophone: () -> Unit,
) {
    val canSend = state.input.isNotBlank() && !state.isAsking
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onDismissMessage, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ask_dismiss))
                }
            }
        }

        if (state.isListening) {
            Text(
                text = stringResource(R.string.ask_listening),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .testTag(ASK_LISTENING_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state.partialTranscript.isNotBlank()) {
                EvidenceText(state.partialTranscript, modifier = Modifier.testTag(ASK_PARTIAL_TAG))
            }
        }

        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.ask_input_label)) },
            shape = LedgerShapes.field,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onAsk() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ASK_INPUT_TAG),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            IconButton(
                onClick = onMicrophone,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(ASK_MIC_TAG),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isListening) R.drawable.ic_mic_stop else R.drawable.ic_mic,
                    ),
                    contentDescription = stringResource(
                        if (state.isListening) R.string.ask_mic_stop else R.string.ask_mic,
                    ),
                )
            }
            Button(
                onClick = onAsk,
                enabled = canSend,
                shape = LedgerShapes.button,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(ASK_SEND_TAG),
            ) {
                Text(stringResource(R.string.ask_send))
            }
        }
    }
}
