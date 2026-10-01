package com.mcfrenchpants.activityledger.core.testing.corpus

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The TAG corpus regression gate: replays committed tag recordings through the real tag
 * decision policy, TemporalResolver and DurationResolver ([TagReplay]).
 *
 * Run only this with:
 *   ./gradlew :core-testing:test --tests com.mcfrenchpants.activityledger.core.testing.corpus.TagRegressionGateTest
 *
 * Inputs (test resources, `src/test/resources/semantic-corpus/recordings/`):
 * - `tag-device-latest.json`: the latest real on-device tag recording ([TagRecording] format).
 * - `tag-baseline.json`: the gate limits ([TagBaseline]); created from a reviewed device run.
 * - `tag-standin-latest.json`: the latest stand-in tag recording; reported, never gated.
 *
 * Outputs: `build/reports/semantic-corpus/tag-device.md` and `tag-standin.md`, plus a console
 * summary (case ids, counts and codes only). Reports are written BEFORE the gate is evaluated,
 * so they exist even when the gate fails. A missing file skips the test (JUnit Assume). A
 * malformed or stale recording, or a malformed baseline, always fails.
 */
class TagRegressionGateTest {

    private val corpus = TagCorpus.load()

    @Test
    fun `device tag recording passes the tag baseline`() {
        val recordingText = resource(DEVICE)
        assumeTrue(
            "No device tag recording yet ($RECORDINGS_DIR/$DEVICE is missing); tag regression gate skipped.",
            recordingText != null,
        )
        val result = replayAndReport(recordingText!!, "tag-device.md", "device")
        val baselineText = resource(BASELINE)
        assumeTrue(
            "No tag baseline yet ($RECORDINGS_DIR/$BASELINE is missing); tag-device report written, gate skipped.",
            baselineText != null,
        )
        val failures = TagGate.failures(result, TagGate.parseBaseline(baselineText!!), corpus)
        if (failures.isNotEmpty()) {
            fail("Tag regression gate failed (${failures.size}):\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    @Test
    fun `stand-in tag recording is replayed and reported without gating on scores`() {
        val recordingText = resource(STAND_IN)
        assumeTrue(
            "No stand-in tag recording yet ($RECORDINGS_DIR/$STAND_IN is missing); skipped.",
            recordingText != null,
        )
        replayAndReport(recordingText!!, "tag-standin.md", "stand-in")
        // Scores are informational only: nothing further is asserted.
    }

    /** Reads, replays and writes the report; on a malformed/stale file writes an error report and rethrows. */
    private fun replayAndReport(text: String, reportName: String, label: String): TagReplayResult {
        val reportFile = File(reportDir(), reportName)
        val result = try {
            TagReplay(corpus).replay(TagRecording.read(text))
        } catch (e: Exception) {
            // A decoding error message may quote part of the file (model output), so only the
            // stale-recording message (ids and hashes only) is repeated; otherwise the class name.
            val detail = if (e is StaleRecordingException) "${e::class.simpleName}: ${e.message}" else "${e::class.simpleName}"
            reportFile.writeText(
                "# Tag corpus replay: $label recording could not be replayed\n\n$detail\n",
                Charsets.UTF_8,
            )
            throw AssertionError("$label tag recording is malformed or stale: $detail")
        }
        reportFile.writeText(TagReport.markdown(result, corpus), Charsets.UTF_8)
        println(TagReport.consoleSummary(result))
        println("  report: ${reportFile.path}")
        return result
    }

    private fun reportDir(): File =
        File(System.getProperty("semanticCorpus.reportDir") ?: "build/reports/semantic-corpus").apply { mkdirs() }

    private fun resource(name: String): String? =
        TagRegressionGateTest::class.java.getResourceAsStream("$RECORDINGS_DIR/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val RECORDINGS_DIR = "/semantic-corpus/recordings"
        const val DEVICE = "tag-device-latest.json"
        const val STAND_IN = "tag-standin-latest.json"
        const val BASELINE = "tag-baseline.json"
    }
}
