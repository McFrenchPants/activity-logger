package com.mcfrenchpants.activityledger.core.testing.corpus

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The semantic regression GATE: replays committed model recordings through the real pipeline.
 *
 * Run only this with:
 *   ./gradlew :core-testing:test --tests com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest
 *
 * Inputs (test resources, `src/test/resources/semantic-corpus/recordings/`):
 * - `device-latest.json`: the latest real on-device recording (SemanticRecording format).
 * - `baseline.json`: JSON array of case ids that must stay CORRECT (see [SemanticGate]).
 * - `standin-latest.json`: the latest stand-in model recording; reported, never gated on scores.
 *
 * Outputs: `build/reports/semantic-corpus/device.md` and `standin.md`, plus a console summary
 * (case ids, counts and reason codes only). Reports are written BEFORE the gate is evaluated, so
 * they exist even when the gate fails. A missing file skips the test (JUnit Assume). A malformed or
 * stale recording always fails.
 */
class SemanticRegressionGateTest {

    private val corpus = SemanticCorpus.load()

    @Test
    fun `device recording keeps every baseline case correct`() {
        val recordingText = resource(DEVICE)
        assumeTrue(
            "No device recording has been recorded yet ($RECORDINGS_DIR/$DEVICE is missing); semantic regression gate skipped.",
            recordingText != null,
        )
        val result = replayAndReport(recordingText!!, "device.md", "device")
        val baselineText = resource(BASELINE)
        assumeTrue(
            "No baseline has been recorded yet ($RECORDINGS_DIR/$BASELINE is missing); device report written, gate skipped.",
            baselineText != null,
        )
        val failures = SemanticGate.failures(result, SemanticGate.parseBaseline(baselineText!!), corpus)
        if (failures.isNotEmpty()) {
            fail("Semantic regression gate failed (${failures.size}):\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    @Test
    fun `stand-in recording is replayed and reported without gating on scores`() {
        val recordingText = resource(STAND_IN)
        assumeTrue(
            "No stand-in recording has been recorded yet ($RECORDINGS_DIR/$STAND_IN is missing); skipped.",
            recordingText != null,
        )
        replayAndReport(recordingText!!, "standin.md", "stand-in")
        // Scores are informational only: nothing further is asserted.
    }

    /** Reads, replays and writes the report; on a malformed/stale file writes an error report and rethrows. */
    private fun replayAndReport(text: String, reportName: String, label: String): ReplayResult {
        val reportFile = File(reportDir(), reportName)
        val result = try {
            SemanticReplay(corpus).replay(SemanticRecording.read(text))
        } catch (e: Exception) {
            reportFile.writeText(
                "# Semantic regression replay: $label recording could not be replayed\n\n" +
                    "${e::class.simpleName}: ${e.message}\n",
                Charsets.UTF_8,
            )
            throw AssertionError("$label recording is malformed or stale: ${e.message}", e)
        }
        reportFile.writeText(SemanticReport.markdown(result, corpus), Charsets.UTF_8)
        println(SemanticReport.consoleSummary(result))
        println("  report: ${reportFile.path}")
        return result
    }

    private fun reportDir(): File =
        File(System.getProperty("semanticCorpus.reportDir") ?: "build/reports/semantic-corpus").apply { mkdirs() }

    private fun resource(name: String): String? =
        SemanticRegressionGateTest::class.java.getResourceAsStream("$RECORDINGS_DIR/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val RECORDINGS_DIR = "/semantic-corpus/recordings"
        const val DEVICE = "device-latest.json"
        const val STAND_IN = "standin-latest.json"
        const val BASELINE = "baseline.json"
    }
}
