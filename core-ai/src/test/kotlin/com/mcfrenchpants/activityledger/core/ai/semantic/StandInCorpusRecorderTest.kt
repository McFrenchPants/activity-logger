package com.mcfrenchpants.activityledger.core.ai.semantic

import com.mcfrenchpants.activityledger.core.ai.INTERPRETATION_SCHEMA_VERSION
import com.mcfrenchpants.activityledger.core.ai.INTERPRETER_VERSION
import com.mcfrenchpants.activityledger.core.ai.PROMPT_VERSION
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.testing.corpus.CorpusInterpretationInput
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingEntry
import com.mcfrenchpants.activityledger.core.testing.corpus.RecordingSource
import com.mcfrenchpants.activityledger.core.testing.corpus.SemanticCorpus
import com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRecording
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.fail

/**
 * The semantic corpus STAND-IN recorder: sends every corpus case to an open-source model served
 * locally by Ollama and writes a STAND_IN recording for `SemanticRegressionGateTest` to replay.
 *
 * For fast prompt iteration without the phone. Its scores are NEVER the official measurement --
 * only a DEVICE recording from the real on-device model is.
 *
 * Opt-in: skipped unless the test JVM has `-DsemanticStandIn=true`, which core-ai's Gradle build
 * sets only for `-PsemanticStandIn=true`. Normally run via `scripts/semantic/run-standin-corpus.sh`.
 * Optional: `semanticStandIn.model`, `semanticStandIn.baseUrl` (loopback only).
 *
 * Console output is ONE line of counts, timings and the output path: no corpus text, prompt or
 * model output is ever printed (AGENTS.md #11). It never pulls or installs a model.
 */
class StandInCorpusRecorderTest {

    @Test
    fun `records every corpus case against the local stand-in model`() {
        assumeTrue(
            "Stand-in recorder is opt-in (-PsemanticStandIn=true); skipped.",
            System.getProperty(PROP_ENABLED).equals("true", ignoreCase = true),
        )
        val outputPath = System.getProperty(PROP_OUTPUT)?.takeIf { it.isNotBlank() }
            ?: fail("System property $PROP_OUTPUT is not set; run through Gradle with -PsemanticStandIn=true.")
        val model = System.getProperty(PROP_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: OllamaStandInClient.DEFAULT_MODEL
        val baseUrl = System.getProperty(PROP_BASE_URL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: OllamaStandInClient.DEFAULT_BASE_URL

        val client = try {
            OllamaStandInClient(baseUrl = baseUrl, model = model)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Refused stand-in base URL.")
        }
        val modelInfo = try {
            client.requireModel()
        } catch (e: StandInSetupException) {
            fail(e.message ?: "Stand-in server check failed.")
        }

        val corpus = SemanticCorpus.load()
        val runStart = System.nanoTime()
        val latencies = ArrayList<Long>(corpus.cases.size)
        val entries = corpus.cases.map { case ->
            val (selection, input) = CorpusInterpretationInput.forCase(corpus, case)
            val start = System.nanoTime()
            val result = client.interpret(input)
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            latencies += latencyMs
            RecordingEntry.of(case.id, selection, result, latencyMs)
        }
        val totalMs = (System.nanoTime() - runStart) / 1_000_000

        val succeeded = entries.count { it.answer != null }
        val malformed = entries.count { it.failureKind == InterpreterFailureKind.MALFORMED }
        val other = entries.count { it.failureKind != null && it.failureKind != InterpreterFailureKind.MALFORMED }
        if (entries.isNotEmpty() && entries.all { it.failureKind == InterpreterFailureKind.OTHER }) {
            // Every call failed at the transport level (server died, model crashed): that is not
            // a measurement, so do not overwrite a previous recording with it.
            fail(
                "Every one of ${entries.size} stand-in calls to $baseUrl failed (HTTP/IO error or " +
                    "timeout); nothing was written. Check the Ollama server and try again.",
            )
        }

        val recording = SemanticRecording(
            source = RecordingSource.STAND_IN,
            modelLabel = "ollama:$model" + (modelInfo.digest?.let { " @$it" } ?: ""),
            deviceModel = null,
            interpreterVersion = INTERPRETER_VERSION,
            promptVersion = PROMPT_VERSION,
            schemaVersion = INTERPRETATION_SCHEMA_VERSION,
            corpusSha256 = corpus.sha256,
            recordedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            notes = NOTES,
            entries = entries,
        )
        val output = File(outputPath)
        writeAtomically(recording, output)

        val sorted = latencies.sorted()
        val median = if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]
        val max = sorted.lastOrNull() ?: 0
        println(
            "Stand-in recording (NOT official): ${entries.size} cases, $succeeded answered, " +
                "$malformed malformed, $other other failures; total ${totalMs / 1000.0}s, " +
                "median ${median}ms, max ${max}ms; model ollama:$model; written to ${output.absolutePath}",
        )
    }

    /** Temp file in the same directory, then rename over the target, so no half-written file. */
    private fun writeAtomically(recording: SemanticRecording, target: File) {
        val dir = requireNotNull(target.absoluteFile.parentFile) { "output path has no parent directory" }
        dir.mkdirs()
        val temp = File.createTempFile(target.name, ".tmp", dir)
        try {
            temp.outputStream().use { SemanticRecording.write(recording, it) }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val PROP_ENABLED = "semanticStandIn"
        const val PROP_OUTPUT = "semanticStandIn.output"
        const val PROP_MODEL = "semanticStandIn.model"
        const val PROP_BASE_URL = "semanticStandIn.baseUrl"

        const val NOTES =
            "Stand-in model served locally by Ollama, NOT Gemini Nano: not the official result. " +
                "Same system instruction, prompt text, generation settings and decoder as the device; " +
                "the schema rendering appended to the prompt approximates ML Kit's includeSchemaInPrompt text."
    }
}
