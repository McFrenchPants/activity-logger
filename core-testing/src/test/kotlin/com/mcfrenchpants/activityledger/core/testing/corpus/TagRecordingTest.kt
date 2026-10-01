package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagRecordingTest {

    private val corpus = TagCorpus.load()

    private val candidate = ExtractionCandidate(
        operation = InterpretationOperation.LOG_ACTIVITY,
        subject = "synthetic subject",
        action = "synthetic action",
        activityState = ActivityState.IN_PROGRESS,
        temporalExpression = "synthetic time",
        durationExpression = "synthetic duration",
    )

    private val nullCandidate = ExtractionCandidate(
        operation = InterpretationOperation.QUERY_HISTORY,
        subject = null,
        action = null,
        activityState = null,
        temporalExpression = null,
        durationExpression = null,
    )

    private fun recording() = TagRecording(
        source = RecordingSource.SYNTHETIC,
        modelLabel = "synthetic",
        deviceModel = null,
        interpreterVersion = "e-1",
        promptVersion = "4",
        schemaVersion = 1,
        tagCorpusSha256 = corpus.sha256,
        recordedAt = "2026-10-01T10:15:30Z",
        notes = null,
        entries = listOf(
            TagRecordingEntry.of(corpus.cases[0].id, ExtractionResult.Success(candidate), 42),
            TagRecordingEntry.of(corpus.cases[1].id, ExtractionResult.Success(nullCandidate), 0),
            TagRecordingEntry.of(corpus.cases[2].id, ExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), null),
        ),
    )

    @Test
    fun `write then read gives an equal recording, via streams and strings`() {
        val original = recording()
        val out = ByteArrayOutputStream()
        TagRecording.write(original, out)
        assertEquals(original, TagRecording.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals(original, TagRecording.read(TagRecording.write(original)))
        val text = TagRecording.write(original)
        assertTrue("\"formatVersion\": 1" in text)
        assertTrue("\"tagCorpusSha256\": \"${corpus.sha256}\"" in text)
        assertEquals(InterpreterProvenance("e-1", "4", 1), original.provenance)
    }

    @Test
    fun `nulls are written explicitly`() {
        val text = TagRecording.write(recording())
        listOf(
            "\"deviceModel\": null",
            "\"notes\": null",
            "\"subject\": null",
            "\"action\": null",
            "\"activityState\": null",
            "\"temporalExpression\": null",
            "\"durationExpression\": null",
            "\"answer\": null",
            "\"failureKind\": null",
            "\"latencyMs\": null",
        ).forEach { assertTrue(it in text, "explicit null missing: $it") }
    }

    @Test
    fun `there is no field for raw model output or the case sentence`() {
        val text = TagRecording.write(recording())
        assertFalse("structuredResultJson" in text)
        assertFalse("rawText" in text)
        assertFalse("confidence" in text)
    }

    @Test
    fun `builder maps every field of a success and a failure, and replays them`() {
        val entries = recording().entries
        assertEquals(corpus.cases[0].id, entries[0].caseId)
        assertEquals(candidate, entries[0].answer?.toCandidate())
        assertEquals(null, entries[0].failureKind)
        assertEquals(42L, entries[0].latencyMs)
        assertEquals(ExtractionResult.Success(candidate), entries[0].toResult())

        assertEquals(nullCandidate, entries[1].answer?.toCandidate())

        assertEquals(null, entries[2].answer)
        assertEquals(InterpreterFailureKind.RETRYABLE, entries[2].failureKind)
        assertEquals(null, entries[2].latencyMs)
        assertEquals(ExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), entries[2].toResult())
    }

    @Test
    fun `unknown keys are rejected`() {
        val text = TagRecording.write(recording())
        assertFailsWith<IllegalArgumentException> {
            TagRecording.read(text.replace("\"notes\":", "\"extra\": 1, \"notes\":"))
        }
        assertFailsWith<IllegalArgumentException> {
            TagRecording.read(text.replaceFirst("\"subject\":", "\"confidenceBand\": \"HIGH\", \"subject\":"))
        }
    }

    @Test
    fun `a wrong formatVersion is rejected`() {
        val text = TagRecording.write(recording())
        assertFailsWith<IllegalArgumentException> {
            TagRecording.read(text.replace("\"formatVersion\": 1", "\"formatVersion\": 2"))
        }
    }

    @Test
    fun `an entry must have exactly one of answer and failureKind`() {
        val text = TagRecording.write(recording())
        // Both set: the first failureKind null belongs to the success entry.
        assertFailsWith<IllegalArgumentException> {
            TagRecording.read(text.replaceFirst("\"failureKind\": null", "\"failureKind\": \"OTHER\""))
        }
        // Neither set.
        assertFailsWith<IllegalArgumentException> {
            TagRecordingEntry(caseId = "x", answer = null, failureKind = null, latencyMs = null)
        }
        assertFailsWith<IllegalArgumentException> {
            TagRecordingEntry(
                caseId = "x",
                answer = RecordedExtraction.of(candidate),
                failureKind = InterpreterFailureKind.OTHER,
                latencyMs = null,
            )
        }
    }

    @Test
    fun `negative latency is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TagRecordingEntry.of("x", ExtractionResult.Failure(InterpreterFailureKind.OTHER), -1)
        }
        val text = TagRecording.write(recording())
        assertFailsWith<IllegalArgumentException> {
            TagRecording.read(text.replace("\"latencyMs\": 42", "\"latencyMs\": -42"))
        }
    }

    @Test
    fun `duplicate case ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            recording().let { it.copy(entries = it.entries + it.entries.first()) }
        }
    }

    @Test
    fun `a recordedAt that is not an ISO instant is rejected`() {
        val text = TagRecording.write(recording())
        assertFailsWith<IllegalArgumentException> {
            TagRecording.read(text.replace("2026-10-01T10:15:30Z", "yesterday"))
        }
        assertFailsWith<IllegalArgumentException> { recording().copy(recordedAt = "2026-10-01") }
    }
}
