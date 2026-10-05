package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionCandidate
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionExtractionResult
import com.mcfrenchpants.activityledger.core.domain.lookup.QuestionKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestionRecordingTest {

    private val corpus = QuestionCorpus.load()

    private val candidate = QuestionCandidate("synthetic subject", "synthetic action", "synthetic window", QuestionKind.COUNT)
    private val nullCandidate = QuestionCandidate(null, null, null, QuestionKind.UNKNOWN)

    private fun recording() = QuestionRecording(
        source = RecordingSource.DEVICE,
        modelLabel = "synthetic",
        deviceModel = "Test Phone",
        interpreterVersion = "q-1",
        promptVersion = "q2",
        schemaVersion = 1,
        questionCorpusSha256 = corpus.sha256,
        recordedAt = "2026-10-05T10:15:30Z",
        notes = null,
        entries = listOf(
            QuestionRecordingEntry.of(corpus.cases[0].id, QuestionExtractionResult.Success(candidate), 42),
            QuestionRecordingEntry.of(corpus.cases[1].id, QuestionExtractionResult.Success(nullCandidate), 0),
            QuestionRecordingEntry.of(corpus.cases[2].id, QuestionExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), null),
        ),
    )

    @Test
    fun `write then read gives an equal recording, via streams and strings`() {
        val original = recording()
        val out = ByteArrayOutputStream()
        QuestionRecording.write(original, out)
        assertEquals(original, QuestionRecording.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals(original, QuestionRecording.read(QuestionRecording.write(original)))
        val text = QuestionRecording.write(original)
        assertTrue("\"formatVersion\": 1" in text)
        assertTrue("\"questionCorpusSha256\": \"${corpus.sha256}\"" in text)
        assertEquals(InterpreterProvenance("q-1", "q2", 1), original.provenance)
    }

    @Test
    fun `entries map the candidate and the failure, and replay back to the same result`() {
        val r = recording()
        assertEquals(RecordedQuestion("synthetic subject", "synthetic action", "synthetic window", QuestionKind.COUNT), r.entries[0].answer)
        assertEquals(QuestionExtractionResult.Success(candidate), r.entries[0].toResult())
        assertEquals(QuestionExtractionResult.Success(nullCandidate), r.entries[1].toResult())
        assertEquals(QuestionExtractionResult.Failure(InterpreterFailureKind.RETRYABLE), r.entries[2].toResult())
        assertEquals(null, r.entries[2].answer)
    }

    @Test
    fun `nulls are written explicitly and there is no field for question text or raw output`() {
        val text = QuestionRecording.write(recording())
        listOf("\"notes\": null", "\"subject\": null", "\"action\": null", "\"dateWindow\": null", "\"answer\": null", "\"failureKind\": null", "\"latencyMs\": null")
            .forEach { assertTrue(it in text, "missing $it") }
        listOf("question\"", "rawText", "rawOutput", "prompt\"").forEach { assertFalse(it in text, "unexpected field $it") }
        corpus.cases.forEach { assertFalse(text.contains(it.question), "the recording contains a corpus question") }
    }

    @Test
    fun `answer and failure are exclusive`() {
        assertFailsWith<IllegalArgumentException> {
            QuestionRecordingEntry("a", RecordedQuestion(null, null, null, QuestionKind.UNKNOWN), InterpreterFailureKind.OTHER, 1)
        }
        assertFailsWith<IllegalArgumentException> { QuestionRecordingEntry("a", null, null, 1) }
        assertFailsWith<IllegalArgumentException> { QuestionRecordingEntry("a", null, InterpreterFailureKind.OTHER, -1) }
    }

    @Test
    fun `unknown keys, missing keys, other versions, bad instants and duplicates are rejected`() {
        val text = QuestionRecording.write(recording())
        assertFailsWith<Exception> { QuestionRecording.read(text.replaceFirst("{", "{\n  \"extra\": 1,")) }
        assertFailsWith<Exception> { QuestionRecording.read(text.replace("\"notes\": null,", "")) }
        assertFailsWith<Exception> { QuestionRecording.read(text.replaceFirst("\"dateWindow\": null,", "")) }
        assertFailsWith<Exception> { QuestionRecording.read(text.replace("\"formatVersion\": 1", "\"formatVersion\": 2")) }
        assertFailsWith<Exception> { QuestionRecording.read(text.replace("2026-10-05T10:15:30Z", "yesterday")) }
        assertFailsWith<Exception> { QuestionRecording.read(text.replace("\"kind\": \"COUNT\"", "\"kind\": \"WHENEVER\"")) }
        assertFailsWith<Exception> { QuestionRecording.read("not json") }
        val entry = recording().entries[0]
        assertFailsWith<IllegalArgumentException> { recording().copy(entries = listOf(entry, entry)) }
    }
}
