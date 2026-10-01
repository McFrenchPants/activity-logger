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
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.ai.OnDeviceModelCapability
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
import java.time.Instant

/**
 * DEBUG BUILD ONLY: the "AI test set" launcher entry (TG1.4a). Records the tag corpus through the
 * real on-device model with no PC attached, writes the same file the instrumented recorder does,
 * and lets the owner share it (e.g. Save to Drive) for scripts/semantic/import-tag-recording.sh.
 *
 * - **Never downloads the model** (ADR-031): readiness is only checked; anything but READY is
 *   shown in plain words and Run stays disabled.
 * - **Foreground only** (ADR-029): the screen is kept on during a run, and [onStop] (user leaves,
 *   screen locks) cancels the run; nothing partial is saved.
 * - **Numbers only** (AGENTS.md #11): nothing on screen or in logs shows a sentence, the prompt or
 *   model output. This class does not log at all.
 * - **Atomic output**: `filesDir/tag-corpus/tag-device-recording.json` via temp file + rename,
 *   shared read-only through the debug-only FileProvider limited to `files/tag-corpus/`.
 */
class TagCorpusRunnerActivity : ComponentActivity() {

    private var uiState by mutableStateOf(RunnerUiState(RunnerPhase.Checking, hasSavedFile = false))

    private var capability: OnDeviceModelCapability? = null
    private var runJob: Job? = null

    private val outputFile: File
        get() = File(File(filesDir, OUTPUT_SUBDIR), OUTPUT_FILE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capability = OnDeviceModelCapability().also { capability = it }
        uiState = RunnerUiState(RunnerPhase.Checking, hasSavedFile = outputFile.isFile)

        setContent {
            ActivityLedgerTheme {
                TagCorpusRunnerScreen(
                    state = uiState,
                    onRun = ::startRun,
                    onShare = ::shareResult,
                    onClose = ::finish,
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

    private fun startRun() {
        val capability = capability ?: return
        if (runJob?.isActive == true || !uiState.runEnabled) return
        runJob = lifecycleScope.launch {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                val readiness = capability.readiness()
                if (readiness != ModelReadiness.READY) {
                    uiState = uiState.copy(phase = phaseFor(readiness))
                    return@launch
                }
                val corpus = withContext(Dispatchers.IO) { TagCorpus.load() }
                uiState = uiState.copy(phase = RunnerPhase.Running(0, corpus.cases.size))

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
                ).run { done, total -> uiState = uiState.copy(phase = RunnerPhase.Running(done, total)) }
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

                withContext(Dispatchers.IO) { writeAtomically(result.recording, outputFile) }
                uiState = RunnerUiState(
                    phase = RunnerPhase.Finished(
                        RunSummary(
                            total = result.total,
                            answered = result.answered,
                            failed = result.failed,
                            busyRetries = result.busyRetries,
                            elapsedMs = elapsedMs,
                            medianLatencyMs = result.medianLatencyMs,
                        ),
                    ),
                    hasSavedFile = true,
                )
            } catch (cancelled: CancellationException) {
                // onStop already set Stopped; nothing partial is written.
                throw cancelled
            } catch (expected: Exception) {
                // No detail on screen or in a log: the message could carry anything.
                uiState = uiState.copy(phase = RunnerPhase.Failed, hasSavedFile = outputFile.isFile)
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun shareResult() {
        val file = outputFile
        if (!file.isFile) {
            uiState = uiState.copy(hasSavedFile = false)
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName$AUTHORITY_SUFFIX", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData too, so the read grant reaches whichever app the owner picks.
            clipData = ClipData.newRawUri(OUTPUT_FILE, uri)
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
        const val AUTHORITY_SUFFIX: String = ".debugtools.fileprovider"
        const val MIME_TYPE: String = "application/json"

        /** Writes to a temp file next to [target], syncs it, then renames it over [target]. */
        internal fun writeAtomically(recording: TagRecording, target: File) {
            val dir = requireNotNull(target.parentFile)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create output directory")
            val temp = File(dir, "${target.name}.tmp")
            try {
                FileOutputStream(temp).use { out ->
                    TagRecording.write(recording, out)
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
