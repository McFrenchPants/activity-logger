package com.mcfrenchpants.activityledger.core.testing.corpus

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Structural check of the committed tag corpus recordings (test resources,
 * `src/test/resources/semantic-corpus/recordings/`):
 *
 * - `tag-device-latest.json` -- written by the on-device tag recorder (`run-device-corpus.sh --tags`)
 * - `tag-standin-latest.json` -- written by the local stand-in tag recorder (`run-standin-corpus.sh --tags`)
 *
 * Each file that exists must decode, match the current tag corpus hash, carry the source its name
 * promises, and hold exactly one entry per tag corpus case. A missing file skips its test.
 *
 * Structure only. Replay, scoring, reports and the gate for these recordings live in
 * [TagReplay], [TagReport] and [TagRegressionGateTest] (TG1.4, grounding guard TG1.4b).
 */
class TagRecordingFilesTest {

    private val corpus = TagCorpus.load()

    @Test
    fun `device tag recording is structurally valid when present`() {
        check(DEVICE, RecordingSource.DEVICE)
    }

    @Test
    fun `stand-in tag recording is structurally valid when present`() {
        check(STAND_IN, RecordingSource.STAND_IN)
    }

    private fun check(name: String, source: RecordingSource) {
        val text = TagRecordingFilesTest::class.java.getResourceAsStream("$RECORDINGS_DIR/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        assumeTrue("No tag recording $RECORDINGS_DIR/$name yet; skipped.", text != null)

        val recording = TagRecording.read(text!!)
        assertEquals(
            corpus.sha256,
            recording.tagCorpusSha256,
            "$name was recorded against a different tag corpus; re-record it.",
        )
        assertEquals(source, recording.source, "$name has the wrong source")
        assertEquals(
            corpus.cases.map { it.id }.sorted(),
            recording.entries.map { it.caseId }.sorted(),
            "$name must have exactly one entry per tag corpus case",
        )
    }

    private companion object {
        const val RECORDINGS_DIR = "/semantic-corpus/recordings"
        const val DEVICE = "tag-device-latest.json"
        const val STAND_IN = "tag-standin-latest.json"
    }
}
