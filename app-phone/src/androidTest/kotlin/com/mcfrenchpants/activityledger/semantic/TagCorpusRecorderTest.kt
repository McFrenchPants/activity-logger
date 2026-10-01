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
import com.mcfrenchpants.activityledger.core.ai.GeminiNanoActivityExtractor
import com.mcfrenchpants.activityledger.core.ai.ModelReadiness
import com.mcfrenchpants.activityledger.core.ai.OnDeviceModelCapability
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingSource
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.TagCorpusExtractionInput
import com.mcfrenchpants.activityledger.core.testing.corpus.TagRecording
import com.mcfrenchpants.activityledger.core.testing.corpus.TagRecordingEntry
import kotlinx.coroutines.delay
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
 * Records what the REAL on-device model (Gemini Nano through core-ai's EXTRACTION prompt, v4,
 * ADR-038) extracts for every tag corpus case, and writes a [TagRecording].
 *
 * The tag-corpus twin of [SemanticCorpusRecorderTest], with the same safety behaviour; the small
 * duplication is deliberate so the v3 recorder stays untouched. Normally run by
 * `scripts/semantic/run-device-corpus.sh --tags`, which also pulls the file back.
 *
 * - **Opt-in.** Runs only with the instrumentation argument `tagCorpus=true`; otherwise it skips.
 * - **Never downloads the model** (ADR-031): not READY means skip, naming the state.
 * - **Foreground held** (ADR-029): [MainActivity] is RESUMED around the whole loop.
 * - **One answer per case**, no repair, except a *fast refusal* (a failure in under
 *   [FAST_REFUSAL_MS], before any inference could have run: AICore throttling back-to-back
 *   requests), which is repeated after a wait ([BUSY_BACKOFF_MS]) exactly as the v3 recorder does.
 * - **No database**: the extractor is called directly.
 * - **Logs numbers and the file path only** (AGENTS.md #11): never corpus text, prompt or model
 *   output, not even in a failure message.
 * - **Atomic output**: written to a temp file and renamed.
 */
@RunWith(AndroidJUnit4::class)
class TagCorpusRecorderTest {

    private var capability: OnDeviceModelCapability? = null

    @After
    fun tearDown() {
        capability?.close()
    }

    @Test
    fun recordEveryTagCorpusCaseThroughTheOnDeviceModel() = runBlocking {
        val optIn = InstrumentationRegistry.getArguments().getString(ARG_OPT_IN)
        assumeTrue(
            "Skipped: the tag corpus recorder is opt-in. Run scripts/semantic/run-device-corpus.sh --tags, " +
                "or pass -Pandroid.testInstrumentationRunnerArguments.$ARG_OPT_IN=true.",
            optIn.equals("true", ignoreCase = true),
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val capability = OnDeviceModelCapability().also { this@TagCorpusRecorderTest.capability = it }
        val extractor = GeminiNanoActivityExtractor(capability)

        // Readiness only: never a download from here (ADR-031).
        val readiness = capability.readiness()
        assumeTrue(
            "Skipped: this device's on-device model readiness is $readiness, not ${ModelReadiness.READY}. " +
                "Nothing was downloaded; downloading the model is the owner's decision alone.",
            readiness == ModelReadiness.READY,
        )

        val corpus = TagCorpus.load()
        val recordedAt = Instant.now()
        val entries = ArrayList<TagRecordingEntry>(corpus.cases.size)
        val latencies = ArrayList<Long>(corpus.cases.size)
        var busyRetries = 0

        // One launch around the whole loop: the app must stay the top foreground app (ADR-029).
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)

            // Once, untimed, so the first case does not also pay for loading the model.
            extractor.warmUp()

            for (case in corpus.cases) {
                val input = TagCorpusExtractionInput.forCase(case)
                var attempt = 0
                var result: ExtractionResult
                var latencyMs: Long
                while (true) {
                    val startedAt = System.nanoTime()
                    result = extractor.extract(input)
                    latencyMs = (System.nanoTime() - startedAt) / 1_000_000
                    val fastRefusal = result is ExtractionResult.Failure &&
                        result.kind in THROTTLE_KINDS && latencyMs < FAST_REFUSAL_MS
                    if (!fastRefusal || attempt >= BUSY_BACKOFF_MS.size) break
                    // Throttled, not answered: wait and ask again (see the class comment).
                    delay(BUSY_BACKOFF_MS[attempt])
                    attempt++
                    busyRetries++
                }
                latencies += latencyMs
                entries += TagRecordingEntry.of(case.id, result, latencyMs)
            }
        }

        val provenance = extractor.provenance
        val recording = TagRecording(
            source = RecordingSource.DEVICE,
            modelLabel = MODEL_LABEL,
            deviceModel = Build.MODEL,
            interpreterVersion = provenance.interpreterVersion,
            promptVersion = provenance.promptVersion,
            schemaVersion = provenance.schemaVersion,
            tagCorpusSha256 = corpus.sha256,
            recordedAt = recordedAt.toString(),
            notes = "busy retries: $busyRetries",
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
            "cases=${entries.size} failures=$failures busyRetries=$busyRetries totalMs=$total medianMs=${median(latencies)} " +
                "file=${outputFile.absolutePath}",
        )

        assertEquals("every tag corpus case must have been recorded", corpus.cases.size, entries.size)
    }

    /** Writes to a temp file in [dir], syncs it, then renames it over [OUTPUT_FILE]. */
    private fun writeAtomically(recording: TagRecording, dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.absolutePath}")
        val target = File(dir, OUTPUT_FILE)
        val temp = File(dir, "$OUTPUT_FILE.tmp")
        try {
            FileOutputStream(temp).use { out ->
                TagRecording.write(recording, out)
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
        const val TAG = "TagCorpusRecorder"
        const val ARG_OPT_IN = "tagCorpus"
        const val MODEL_LABEL = "gemini-nano (AICore)"
        const val OUTPUT_SUBDIR = "semantic-corpus"
        const val OUTPUT_FILE = "tag-device-recording.json"

        /** A failure faster than this cannot have run inference (real answers take ~4-6 s). */
        const val FAST_REFUSAL_MS = 1_000L

        /** The failure kinds a throttled call surfaces as (BUSY is RETRYABLE; OTHER is kept, harmlessly). */
        val THROTTLE_KINDS = setOf(InterpreterFailureKind.OTHER, InterpreterFailureKind.RETRYABLE)

        /** Waits before each repeat of a fast-refused call; its size is the retry limit. */
        val BUSY_BACKOFF_MS = longArrayOf(5_000, 10_000, 20_000, 30_000, 60_000, 60_000)
    }
}
