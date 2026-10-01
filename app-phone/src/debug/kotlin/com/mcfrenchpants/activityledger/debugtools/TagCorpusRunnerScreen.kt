package com.mcfrenchpants.activityledger.debugtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness

/*
 * DEBUG BUILD ONLY. The "AI test set" screen. Shows numbers and fixed wording only: never a corpus
 * sentence, a prompt or anything the model returned (AGENTS.md #11).
 */

/** Numbers-only summary of a finished run. */
data class RunSummary(
    val total: Int,
    val answered: Int,
    val failed: Int,
    val busyRetries: Int,
    val elapsedMs: Long,
    val medianLatencyMs: Long,
)

/** Where the runner screen is. */
sealed interface RunnerPhase {
    /** Asking the on-device model whether it is ready (never downloads). */
    data object Checking : RunnerPhase

    /** The model is not ready; Run stays disabled. */
    data class NotReady(val readiness: ModelReadiness) : RunnerPhase

    /** Ready to run. */
    data object Ready : RunnerPhase

    /** A run is going: [done] of [total] cases recorded. */
    data class Running(val done: Int, val total: Int) : RunnerPhase

    /** The run was cancelled because the screen left the foreground. */
    data object Stopped : RunnerPhase

    /** The run finished and the file was written. */
    data class Finished(val summary: RunSummary) : RunnerPhase

    /** The run finished or broke but the file could not be produced. */
    data object Failed : RunnerPhase
}

/** Everything the screen shows. [hasSavedFile]: a previous result file exists and can be shared. */
data class RunnerUiState(
    val phase: RunnerPhase,
    val hasSavedFile: Boolean,
) {
    /** Run is possible only when the model was found ready and nothing is running. */
    val runEnabled: Boolean
        get() = when (phase) {
            RunnerPhase.Ready, RunnerPhase.Stopped, RunnerPhase.Failed, is RunnerPhase.Finished -> true
            RunnerPhase.Checking, is RunnerPhase.NotReady, is RunnerPhase.Running -> false
        }

    /** Share is offered whenever a result file exists and no run is overwriting it. */
    val shareEnabled: Boolean
        get() = hasSavedFile && phase !is RunnerPhase.Running
}

internal const val TAG_RUN_BUTTON = "TagCorpusRunner:Run"
internal const val TAG_SHARE_BUTTON = "TagCorpusRunner:Share"
internal const val TAG_CLOSE_BUTTON = "TagCorpusRunner:Close"
internal const val TAG_STATUS = "TagCorpusRunner:Status"

@Composable
fun TagCorpusRunnerScreen(
    state: RunnerUiState,
    onRun: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.debugtools_runner_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.debugtools_runner_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = statusText(state.phase),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(TAG_STATUS),
            )
            if (state.phase is RunnerPhase.Running) {
                Text(
                    text = stringResource(R.string.debugtools_runner_keep_open),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.hasSavedFile && state.phase !is RunnerPhase.Finished && state.phase !is RunnerPhase.Running) {
                Text(
                    text = stringResource(R.string.debugtools_runner_previous_file),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onRun,
                enabled = state.runEnabled,
                modifier = Modifier.fillMaxWidth().testTag(TAG_RUN_BUTTON),
            ) {
                Text(stringResource(R.string.debugtools_runner_run))
            }
            Button(
                onClick = onShare,
                enabled = state.shareEnabled,
                modifier = Modifier.fillMaxWidth().testTag(TAG_SHARE_BUTTON),
            ) {
                Text(stringResource(R.string.debugtools_runner_share))
            }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().testTag(TAG_CLOSE_BUTTON),
            ) {
                Text(stringResource(R.string.debugtools_runner_close))
            }
        }
    }
}

@Composable
private fun statusText(phase: RunnerPhase): String = when (phase) {
    RunnerPhase.Checking -> stringResource(R.string.debugtools_runner_checking)
    is RunnerPhase.NotReady -> stringResource(notReadyText(phase.readiness))
    RunnerPhase.Ready -> stringResource(R.string.debugtools_runner_ready)
    is RunnerPhase.Running -> stringResource(R.string.debugtools_runner_progress, phase.done, phase.total)
    RunnerPhase.Stopped -> stringResource(R.string.debugtools_runner_stopped)
    RunnerPhase.Failed -> stringResource(R.string.debugtools_runner_failed)
    is RunnerPhase.Finished -> {
        val s = phase.summary
        val totalSeconds = s.elapsedMs / 1000
        stringResource(
            R.string.debugtools_runner_finished,
            s.answered,
            s.total,
            s.failed,
            s.busyRetries,
            totalSeconds / 60,
            totalSeconds % 60,
            s.medianLatencyMs / 1000.0,
        )
    }
}

private fun notReadyText(readiness: ModelReadiness): Int = when (readiness) {
    ModelReadiness.READY -> R.string.debugtools_runner_ready
    ModelReadiness.NOT_INSTALLED -> R.string.debugtools_runner_not_installed
    ModelReadiness.DOWNLOAD_IN_PROGRESS -> R.string.debugtools_runner_downloading
    ModelReadiness.UNSUPPORTED_DEVICE -> R.string.debugtools_runner_unsupported
    ModelReadiness.STRUCTURED_OUTPUT_UNSUPPORTED -> R.string.debugtools_runner_no_structured_output
    ModelReadiness.CHECK_FAILED -> R.string.debugtools_runner_check_failed
}
