package com.mcfrenchpants.activityledger.core.testing.corpus

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The QUESTION corpus regression gate: replays the committed device question recording through
 * the real LookupService ([QuestionReplay]).
 *
 * Run only this with:
 *   ./gradlew :core-testing:test --tests com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRegressionGateTest
 *
 * Inputs (test resources, `src/test/resources/semantic-corpus/recordings/`):
 * - `question-device-latest.json`: the latest on-device question recording ([QuestionRecording]).
 * - `question-baseline.json`: the gate limits ([QuestionBaseline]); written from a reviewed run.
 *
 * Output: `build/reports/semantic-corpus/question-device.md` plus a console summary (case ids,
 * counts and codes only). The report is written BEFORE the gate is evaluated, so it exists even
 * when the gate fails. A missing recording skips the test; a missing baseline writes the report
 * and skips the gate (JUnit Assume). A malformed or stale recording, or a malformed baseline,
 * always fails.
 */
class QuestionRegressionGateTest {

    private val corpus = QuestionCorpus.load()

    @Test
    fun `device question recording passes the question baseline`() {
        val recordingText = resource(DEVICE)
        assumeTrue(
            "No device question recording yet ($RECORDINGS_DIR/$DEVICE is missing); question regression gate skipped.",
            recordingText != null,
        )
        val result = replayAndReport(recordingText!!)
        val baselineText = resource(BASELINE)
        assumeTrue(
            "No question baseline yet ($RECORDINGS_DIR/$BASELINE is missing); question-device report written, gate skipped.",
            baselineText != null,
        )
        val failures = QuestionGate.failures(result, QuestionGate.parseBaseline(baselineText!!), corpus)
        if (failures.isNotEmpty()) {
            fail("Question regression gate failed (${failures.size}):\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    /** Reads, replays and writes the report; on a malformed/stale file writes an error report and rethrows. */
    private fun replayAndReport(text: String): QuestionReplayResult {
        val reportFile = File(reportDir(), REPORT)
        val result = try {
            val recording = QuestionRecording.read(text)
            require(recording.source == RecordingSource.DEVICE) { "the device question recording must have source DEVICE" }
            QuestionReplay(corpus).replay(recording)
        } catch (e: Exception) {
            // A decoding error message may quote part of the file (model output), so only the
            // stale-recording and source messages (ids, hashes, enum names) are repeated.
            val detail = if (e is StaleRecordingException || e is IllegalArgumentException && e.message?.startsWith("the device") == true) {
                "${e::class.simpleName}: ${e.message}"
            } else {
                "${e::class.simpleName}"
            }
            reportFile.writeText("# Question corpus replay: device recording could not be replayed\n\n$detail\n", Charsets.UTF_8)
            throw AssertionError("device question recording is malformed or stale: $detail")
        }
        reportFile.writeText(QuestionReport.markdown(result, corpus), Charsets.UTF_8)
        println(QuestionReport.consoleSummary(result))
        println("  report: ${reportFile.path}")
        return result
    }

    private fun reportDir(): File =
        File(System.getProperty("semanticCorpus.reportDir") ?: "build/reports/semantic-corpus").apply { mkdirs() }

    private fun resource(name: String): String? =
        QuestionRegressionGateTest::class.java.getResourceAsStream("$RECORDINGS_DIR/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val RECORDINGS_DIR = "/semantic-corpus/recordings"
        const val DEVICE = "question-device-latest.json"
        const val BASELINE = "question-baseline.json"
        const val REPORT = "question-device.md"
    }
}
