package com.mcfrenchpants.activityledger.debugtools

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mcfrenchpants.activityledger.R
import com.mcfrenchpants.activityledger.core.ai.GeminiNanoActivityExtractor
import com.mcfrenchpants.activityledger.core.ai.GeminiNanoQuestionExtractor
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.ai.OnDeviceModelCapability
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRecording
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.TagRecording
import com.mcfrenchpants.activityledger.ui.theme.ActivityLedgerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant

/**
 * DEBUG BUILD ONLY: the "AI test set" launcher entry (TG1.4a; question set DH4.4). Records one of
 * two test sets through the real on-device model with no PC attached and lets the owner share the
 * file (e.g. Save to Drive):
 * - **Logging set**: the tag corpus through [GeminiNanoActivityExtractor] ([TagCorpusRun]), the
 *   same file the instrumented recorder writes, for scripts/semantic/import-tag-recording.sh.
 * - **Question set**: the question corpus through [GeminiNanoQuestionExtractor] built exactly as
 *   the app builds it, without the app's busy-retry wrapper (the run has its own backoff;
 *   [QuestionCorpusRun]), for scripts/semantic/import-question-recording.sh.
 *
 * Rules shared by both sets:
 * - **Never downloads the model** (ADR-031): readiness is only checked; anything but READY is
 *   shown in plain words and both Run buttons stay disabled.
 * - **One run at a time**: both Run buttons are disabled while either set runs.
 * - **Foreground only** (ADR-029): the screen is kept on during a run, and [onStop] (user leaves,
 *   screen locks) cancels the run; nothing partial is saved.
 * - **Numbers only** (AGENTS.md #11): nothing on screen or in logs shows a sentence, a question,
 *   the prompt or model output. This class does not log at all.
 * - **Atomic output**: `filesDir/tag-corpus/tag-device-recording.json` and
 *   `filesDir/question-corpus/question-device-recording.json` via temp file + rename, shared
 *   read-only through the debug-only FileProvider limited to those two directories.
 */
class TagCorpusRunnerActivity : ComponentActivity() {

    private var uiState by mutableStateOf(RunnerUiState(RunnerPhase.Checking, hasSavedFile = false))

    private var capability: OnDeviceModelCapability? = null
    private var runJob: Job? = null

    private val outputFile: File
        get() = File(File(filesDir, OUTPUT_SUBDIR), OUTPUT_FILE)

    private val questionOutputFile: File
        get() = File(File(filesDir, QUESTION_OUTPUT_SUBDIR), QUESTION_OUTPUT_FILE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capability = OnDeviceModelCapability().also { capability = it }
        uiState = RunnerUiState(
            RunnerPhase.Checking,
            hasSavedFile = outputFile.isFile,
            hasSavedQuestionFile = questionOutputFile.isFile,
        )

        setContent {
            ActivityLedgerTheme {
                TagCorpusRunnerScreen(
                    state = uiState,
                    onRun = { startRun(CorpusSet.LOGGING) },
                    onShare = { shareResult(outputFile) },
                    onClose = ::finish,
                    onRunQuestions = { startRun(CorpusSet.QUESTIONS) },
                    onShareQuestions = { shareResult(questionOutputFile) },
                )
            }
        }

        lifecycleScope.launch {
            // Readiness only; never a download from here (ADR-031).
            val readiness = capability.readiness()
            if (uiState.phase == RunnerPhase.Checking) {
                uiState = uiState.copy(phase = phaseFor(readiness))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The on-device model only answers while the app is in the foreground (ADR-029).
        val job = runJob
        if (job != null && job.isActive && !isChangingConfigurations) {
            uiState = uiState.copy(phase = RunnerPhase.Stopped)
            job.cancel()
        }
    }

    override fun onDestroy() {
        runJob?.cancel()
        capability?.close()
        capability = null
        super.onDestroy()
    }

    /** Starts a run of [set]; ignored while any run is going or the model is not ready. */
    private fun startRun(set: CorpusSet) {
        val capability = capability ?: return
        if (runJob?.isActive == true || !uiState.runEnabled) return
        uiState = uiState.copy(activeSet = set)
        runJob = lifecycleScope.launch {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                val readiness = capability.readiness()
                if (readiness != ModelReadiness.READY) {
                    uiState = uiState.copy(phase = phaseFor(readiness))
                    return@launch
                }
                val onProgress = { done: Int, total: Int -> uiState = uiState.copy(phase = RunnerPhase.Running(done, total)) }
                val summary: RunSummary = when (set) {
                    CorpusSet.LOGGING -> {
                        val corpus = withContext(Dispatchers.IO) { TagCorpus.load() }
                        onProgress(0, corpus.cases.size)
                        val extractor = GeminiNanoActivityExtractor(capability)
                        // Once, untimed, so the first case does not also pay for loading the model.
                        extractor.warmUp()
                        val startedAt = System.nanoTime()
                        val result = TagCorpusRun(
                            extractor = extractor,
                            corpus = corpus,
                            deviceModel = Build.MODEL,
                            nanoTime = System::nanoTime,
                            now = Instant::now,
                            delay = { delay(it) },
                        ).run(onProgress)
                        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                        withContext(Dispatchers.IO) { writeAtomically(result.recording, outputFile) }
                        RunSummary(result.total, result.answered, result.failed, result.busyRetries, elapsedMs, result.medianLatencyMs)
                    }
                    CorpusSet.QUESTIONS -> {
                        val corpus = withContext(Dispatchers.IO) { QuestionCorpus.load() }
                        onProgress(0, corpus.cases.size)
                        // Built exactly as the app builds it, minus the app's busy-retry wrapper:
                        // the run has its own backoff (QuestionCorpusRun).
                        val extractor = GeminiNanoQuestionExtractor(capability)
                        extractor.warmUp()
                        val startedAt = System.nanoTime()
                        val result = QuestionCorpusRun(
                            extractor = extractor,
                            corpus = corpus,
                            deviceModel = Build.MODEL,
                            nanoTime = System::nanoTime,
                            now = Instant::now,
                            delay = { delay(it) },
                        ).run(onProgress)
                        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                        withContext(Dispatchers.IO) { writeAtomically(result.recording, questionOutputFile) }
                        RunSummary(result.total, result.answered, result.failed, result.busyRetries, elapsedMs, result.medianLatencyMs)
                    }
                }
                uiState = RunnerUiState(
                    phase = RunnerPhase.Finished(summary),
                    hasSavedFile = outputFile.isFile,
                    hasSavedQuestionFile = questionOutputFile.isFile,
                    activeSet = set,
                )
            } catch (cancelled: CancellationException) {
                // onStop already set Stopped; nothing partial is written.
                throw cancelled
            } catch (expected: Exception) {
                // No detail on screen or in a log: the message could carry anything.
                uiState = uiState.copy(
                    phase = RunnerPhase.Failed,
                    hasSavedFile = outputFile.isFile,
                    hasSavedQuestionFile = questionOutputFile.isFile,
                )
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /** Shares [file] (one of the two result files) through the debug-only FileProvider. */
    private fun shareResult(file: File) {
        if (!file.isFile) {
            uiState = uiState.copy(hasSavedFile = outputFile.isFile, hasSavedQuestionFile = questionOutputFile.isFile)
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName$AUTHORITY_SUFFIX", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData too, so the read grant reaches whichever app the owner picks.
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.debugtools_runner_share_chooser)))
    }

    private fun phaseFor(readiness: ModelReadiness): RunnerPhase =
        if (readiness == ModelReadiness.READY) RunnerPhase.Ready else RunnerPhase.NotReady(readiness)

    companion object {
        /** Under filesDir; the FileProvider can reach this directory and nothing else. */
        const val OUTPUT_SUBDIR: String = "tag-corpus"
        const val OUTPUT_FILE: String = "tag-device-recording.json"
        const val QUESTION_OUTPUT_SUBDIR: String = "question-corpus"
        const val QUESTION_OUTPUT_FILE: String = "question-device-recording.json"
        const val AUTHORITY_SUFFIX: String = ".debugtools.fileprovider"
        const val MIME_TYPE: String = "application/json"

        /** Writes the tag [recording] to [target] atomically (see [writeAtomically]). */
        internal fun writeAtomically(recording: TagRecording, target: File) =
            writeAtomically(target) { TagRecording.write(recording, it) }

        /** Writes the question [recording] to [target] atomically (see [writeAtomically]). */
        internal fun writeAtomically(recording: QuestionRecording, target: File) =
            writeAtomically(target) { QuestionRecording.write(recording, it) }

        /** Writes to a temp file next to [target], syncs it, then renames it over [target]. */
        private fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
            val dir = requireNotNull(target.parentFile)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create output directory")
            val temp = File(dir, "${target.name}.tmp")
            try {
                FileOutputStream(temp).use { out ->
                    write(out)
                    out.fd.sync()
                }
                // Same directory, same filesystem: renameTo replaces the target atomically on Linux.
                if (!temp.renameTo(target)) throw IOException("could not rename temp file")
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
    }
}
