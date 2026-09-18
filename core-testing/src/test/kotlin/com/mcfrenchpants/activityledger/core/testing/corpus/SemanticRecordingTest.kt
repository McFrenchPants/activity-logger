package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.candidates.CandidateSelector
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SemanticRecordingTest {

    private val corpus = SemanticCorpus.load()
    private val case = corpus.cases.first()
    private val shown = CorpusInterpretationInput.forCase(corpus, case)

    private val candidate = InterpretationCandidate(
        operation = InterpretationOperation.QUERY_HISTORY,
        activityResolution = ActivityResolution.NEW_ACTIVITY,
        matchedActivityId = "act-synthetic",
        proposedCanonicalName = "Synthetic name",
        activityState = ActivityState.IN_PROGRESS,
        temporalExpression = "synthetic time",
        confidenceBand = ConfidenceBand.LOW,
    )

    private fun recording() = SemanticRecording(
        source = RecordingSource.SYNTHETIC,
        modelLabel = "synthetic",
        deviceModel = "Synthetic Device",
        interpreterVersion = "i-1",
        promptVersion = "p-1",
        schemaVersion = 3,
        corpusSha256 = corpus.sha256,
        recordedAt = "2026-09-17T10:15:30Z",
        notes = "round trip",
        entries = listOf(
            RecordingEntry.of(case.id, shown.selection, InterpretationResult.Success(candidate, "{\"raw\":1}"), 42),
            RecordingEntry.of(corpus.cases[1].id, shown.selection, InterpretationResult.Failure(InterpreterFailureKind.MALFORMED, "x"), null),
        ),
    )

    @Test
    fun `shared input builder uses the real selector over the fixed-id catalog`() {
        val expected = CandidateSelector().select(corpus.catalogFor(case), case.rawText)
        assertEquals(expected, shown.selection)
        assertEquals(case.rawText, shown.input.rawText)
        assertEquals(case.capturedInstant, shown.input.capturedAt)
        assertEquals(case.zone, shown.input.zoneId)
        assertEquals(expected.candidates, shown.input.candidates)
    }

    @Test
    fun `write then read gives an equal recording, via streams and strings`() {
        val original = recording()
        val out = ByteArrayOutputStream()
        SemanticRecording.write(original, out)
        assertEquals(original, SemanticRecording.read(ByteArrayInputStream(out.toByteArray())))
        assertEquals(original, SemanticRecording.read(SemanticRecording.write(original)))
        val text = SemanticRecording.write(original)
        assertTrue("\"formatVersion\": 1" in text)
        assertTrue("\"deviceModel\": \"Synthetic Device\"" in text)
        assertFalse("structuredResultJson" in text || "\\\"raw\\\"" in text, "no raw model output is recorded")
    }

    @Test
    fun `builder maps every field of a success and a failure`() {
        val entries = recording().entries
        val ok = entries[0]
        assertEquals(case.id, ok.caseId)
        assertEquals(shown.selection.candidates.map { it.id }, ok.offeredCandidateIds)
        assertEquals(shown.selection.contextHash, ok.contextHash)
        assertEquals(candidate, ok.answer?.toCandidate())
        assertEquals(null, ok.failureKind)
        assertEquals(42L, ok.latencyMs)
        assertEquals(InterpretationResult.Success(candidate, null), ok.toResult())

        val failed = entries[1]
        assertEquals(null, failed.answer)
        assertEquals(InterpreterFailureKind.MALFORMED, failed.failureKind)
        assertEquals(null, failed.latencyMs)
        assertIs<InterpretationResult.Failure>(failed.toResult())
        assertEquals(InterpretationResult.Failure(InterpreterFailureKind.MALFORMED, null), failed.toResult())
    }

    @Test
    fun `reader rejects invalid recordings`() {
        val text = SemanticRecording.write(recording())
        assertFailsWith<IllegalArgumentException> { SemanticRecording.read(text.replace("\"formatVersion\": 1", "\"formatVersion\": 2")) }
        assertFailsWith<IllegalArgumentException> { SemanticRecording.read(text.replace("\"notes\":", "\"extra\": 1, \"notes\":")) }
        assertFailsWith<IllegalArgumentException> { SemanticRecording.read(text.replace("\"failureKind\": null", "\"failureKind\": \"OTHER\"")) }
        assertFailsWith<IllegalArgumentException> { SemanticRecording.read(text.replace("2026-09-17T10:15:30Z", "yesterday")) }
        assertFailsWith<IllegalArgumentException> {
            recording().let { it.copy(entries = it.entries + it.entries.first()) }
        }
    }
}
