package com.mcfrenchpants.activityledger.semantic

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcfrenchpants.activityledger.MainActivity
import com.mcfrenchpants.activityledger.core.ai.GeminiNanoActivityInterpreter
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.ai.OnDeviceModelCapability
import com.mcfrenchpants.activityledger.core.testing.corpus.CorpusInterpretationInput
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingEntry
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingSource
import com.mcfrenchpants.activityledger.core.testing.corpus.SemanticCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRecording
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

/**
 * Records what the REAL on-device model (Gemini Nano through core-ai) answers for every semantic
 * regression corpus case, and writes a [SemanticRecording] the JVM replay/gate can score
 * (core-testing's SemanticRegressionGateTest, reading `device-latest.json`).
 *
 * Normally run by `scripts/semantic/run-device-corpus.sh`, which also pulls the file back.
 *
 * - **Opt-in.** Runs only with the instrumentation argument `semanticCorpus=true`; otherwise it
 *   skips, so a plain `connectedDebugAndroidTest` stays fast.
 * - **Never downloads the model** (ADR-031): not READY means skip, naming the state.
 * - **Foreground held** (ADR-029): Google refuses on-device GenAI calls unless the app is the top
 *   foreground app, so [MainActivity] is RESUMED around the whole loop.
 * - **One call per case**, no retries, no repair: a failure is recorded as-is; it is data.
 * - **No database**: the model is called directly; the deterministic rest of the pipeline is
 *   replayed on the JVM from the recording.
 * - **Logs numbers and the file path only** (AGENTS.md #11): never corpus text, prompt or model
 *   output, not even in a failure message.
 * - **Atomic output**: written to a temp file and renamed, so an interrupted run never leaves a
 *   truncated file that looks like a complete recording.
 */
@RunWith(AndroidJUnit4::class)
class SemanticCorpusRecorderTest {

    private var capability: OnDeviceModelCapability? = null

    @After
    fun tearDown() {
        capability?.close()
    }

    @Test
    fun recordEveryCorpusCaseThroughTheOnDeviceModel() = runBlocking {
        val optIn = InstrumentationRegistry.getArguments().getString(ARG_OPT_IN)
        assumeTrue(
            "Skipped: the semantic corpus recorder is opt-in. Run scripts/semantic/run-device-corpus.sh, " +
                "or pass -Pandroid.testInstrumentationRunnerArguments.$ARG_OPT_IN=true.",
            optIn.equals("true", ignoreCase = true),
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val capability = OnDeviceModelCapability().also { this@SemanticCorpusRecorderTest.capability = it }
        val interpreter = GeminiNanoActivityInterpreter(capability)

        // Readiness only: never a download from here (ADR-031).
        val readiness = capability.readiness()
        assumeTrue(
            "Skipped: this device's on-device model readiness is $readiness, not ${ModelReadiness.READY}. " +
                "Nothing was downloaded; downloading the model is the owner's decision alone.",
            readiness == ModelReadiness.READY,
        )

        val corpus = SemanticCorpus.load()
        val recordedAt = Instant.now()
        val entries = ArrayList<RecordingEntry>(corpus.cases.size)
        val latencies = ArrayList<Long>(corpus.cases.size)

        // One launch around the whole loop: the app must stay the top foreground app (ADR-029).
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            // Once, untimed, so the first case does not also pay for loading the model.
            interpreter.warmUp()

            for (case in corpus.cases) {
                val caseInput = CorpusInterpretationInput.forCase(corpus, case)
                val startedAt = System.nanoTime()
                // Exactly one call: no retries, no repair.
                val result = interpreter.interpret(caseInput.input)
                val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
                latencies += latencyMs
                entries += RecordingEntry.of(case.id, caseInput.selection, result, latencyMs)
            }
        }

        val provenance = interpreter.provenance
        val recording = SemanticRecording(
            source = RecordingSource.DEVICE,
            modelLabel = MODEL_LABEL,
            deviceModel = Build.MODEL,
            interpreterVersion = provenance.interpreterVersion,
            promptVersion = provenance.promptVersion,
            schemaVersion = provenance.schemaVersion,
            corpusSha256 = corpus.sha256,
            recordedAt = recordedAt.toString(),
            notes = null,
            entries = entries,
        )

        val outputDir = File(
            requireNotNull(context.getExternalFilesDir(null)) { "external files dir is unavailable" },
            OUTPUT_SUBDIR,
        )
        val outputFile = writeAtomically(recording, outputDir)

        val failures = entries.count { it.failureKind != null }
        val total = latencies.sum()
        // Numbers and the file path only (AGENTS.md #11).
        Log.i(
            TAG,
            "cases=${entries.size} failures=$failures totalMs=$total medianMs=${median(latencies)} " +
                "file=${outputFile.absolutePath}",
        )

        assertEquals("every corpus case must have been recorded", corpus.cases.size, entries.size)
    }

    /** Writes to a temp file in [dir], syncs it, then renames it over [OUTPUT_FILE]. */
    private fun writeAtomically(recording: SemanticRecording, dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.absolutePath}")
        val target = File(dir, OUTPUT_FILE)
        val temp = File(dir, "$OUTPUT_FILE.tmp")
        try {
            FileOutputStream(temp).use { out ->
                SemanticRecording.write(recording, out)
                out.fd.sync()
            }
            // Same directory, same filesystem: renameTo replaces the target atomically on Linux.
            if (!temp.renameTo(target)) throw IOException("could not rename temp file to ${target.absolutePath}")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return target
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private companion object {
        const val TAG = "SemanticCorpusRecorder"
        const val ARG_OPT_IN = "semanticCorpus"
        const val MODEL_LABEL = "gemini-nano (AICore)"
        const val OUTPUT_SUBDIR = "semantic-corpus"
        const val OUTPUT_FILE = "device-recording.json"
    }
}
