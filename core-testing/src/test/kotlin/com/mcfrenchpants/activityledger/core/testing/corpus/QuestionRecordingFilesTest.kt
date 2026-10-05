package com.mcfrenchpants.activityledger.core.testing.corpus

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Structural check of the committed question recording (test resources,
 * `src/test/resources/semantic-corpus/recordings/question-device-latest.json`), written by the
 * debug build's "AI test set" screen ("Run question set") and imported with
 * `scripts/semantic/import-question-recording.sh`.
 *
 * When the file exists it must decode strictly, match the current question corpus hash, be a
 * DEVICE recording and hold exactly one entry per question corpus case. A missing file skips the
 * test. Replay, the report and the gate live in [QuestionRegressionGateTest].
 */
class QuestionRecordingFilesTest {

    private val corpus = QuestionCorpus.load()

    @Test
    fun `device question recording is structurally valid when present`() {
        val text = QuestionRecordingFilesTest::class.java.getResourceAsStream("$RECORDINGS_DIR/$DEVICE")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assumeTrue("No question recording $RECORDINGS_DIR/$DEVICE yet; skipped.", text != null)

        val recording = QuestionRecording.read(text!!)
        assertEquals(
            corpus.sha256,
            recording.questionCorpusSha256,
            "$DEVICE was recorded against a different question corpus; re-record it.",
        )
        assertEquals(RecordingSource.DEVICE, recording.source, "$DEVICE has the wrong source")
        assertEquals(
            corpus.cases.map { it.id }.sorted(),
            recording.entries.map { it.caseId }.sorted(),
            "$DEVICE must have exactly one entry per question corpus case",
        )
    }

    private companion object {
        const val RECORDINGS_DIR = "/semantic-corpus/recordings"
        const val DEVICE = "question-device-latest.json"
    }
}
