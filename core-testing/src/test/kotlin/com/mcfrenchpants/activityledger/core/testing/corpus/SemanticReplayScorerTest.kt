package com.mcfrenchpants.activityledger.core.testing.corpus

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Self-tests of the replay scorer, driven by SYNTHETIC recording entries built in code against real
 * corpus cases. Cases are picked by their expectations, never by sentence text, so the tests keep
 * working if cases are added. Assertion messages name case ids only.
 */
class SemanticReplayScorerTest {

    private val corpus = SemanticCorpus.load()
    private val replay = SemanticReplay(corpus)

    // --- helpers -----------------------------------------------------------------------------

    private fun recording(vararg entries: RecordingEntry, sha: String = corpus.sha256) = SemanticRecording(
        source = RecordingSource.SYNTHETIC,
        modelLabel = "synthetic-scorer-selftest",
        deviceModel = null,
        interpreterVersion = "synthetic-interpreter-1",
        promptVersion = "synthetic-prompt-1",
        schemaVersion = 1,
        corpusSha256 = sha,
        recordedAt = "2026-09-17T00:00:00Z",
        notes = "hand-written answers for harness self-tests",
        entries = entries.toList(),
    )

    private fun entry(case: CorpusCase, result: InterpretationResult, latencyMs: Long? = 100): RecordingEntry =
        RecordingEntry.of(case.id, CorpusInterpretationInput.forCase(corpus, case).selection, result, latencyMs)

    private fun score(case: CorpusCase, result: InterpretationResult): ScoredEntry =
        replay.replay(recording(entry(case, result))).entries.single()

    private fun success(candidate: InterpretationCandidate) = InterpretationResult.Success(candidate, null)

    /** The answer a perfect model would give for [case], with overridable fields. */
    private fun ideal(
        case: CorpusCase,
        resolution: ActivityResolution = case.expected.resolution,
        matched: String? = case.expected.activityId,
        name: String? = case.expected.newActivityName,
        state: ActivityState? = case.expected.allowedStates.filterNotNull().firstOrNull() ?: ActivityState.COMPLETED,
        time: String? = case.expected.temporalExpression,
        band: ConfidenceBand? = ConfidenceBand.HIGH,
    ) = success(InterpretationCandidate(InterpretationOperation.LOG_ACTIVITY, resolution, matched, name, state, time, band))

    private fun reviewAnswer() = success(
        InterpretationCandidate(
            InterpretationOperation.LOG_ACTIVITY, ActivityResolution.AMBIGUOUS, null, null,
            ActivityState.COMPLETED, null, ConfidenceBand.LOW,
        ),
    )

    private val autoCases get() = corpus.cases.filter {
        it.expected.outcome == ExpectedOutcome.AUTO_ACCEPT && it.knownResolverGap == null
    }
    private val existingAuto: CorpusCase get() = autoCases.first {
        it.expected.resolution == ActivityResolution.EXISTING_ACTIVITY && it.expected.activityId != null
    }
    private val newAuto: CorpusCase get() = autoCases.first { it.expected.resolution == ActivityResolution.NEW_ACTIVITY }
    private val reviewCase: CorpusCase get() = corpus.cases.first { it.expected.outcome == ExpectedOutcome.NEEDS_REVIEW }

    // --- classification scenarios --------------------------------------------------------------

    @Test
    fun `correct existing match`() {
        val s = score(existingAuto, ideal(existingAuto))
        assertEquals(ReplayClass.CORRECT, s.replayClass, existingAuto.id)
        assertEquals(ReplayOutcome.AUTO_ACCEPTED, s.outcome)
        assertEquals(emptyList(), s.unsafeChecks)
        assertEquals(true, s.resolutionAcceptable)
        assertEquals(true, s.temporalExpressionAgrees)
        assertEquals(ConfidenceBand.HIGH, s.confidenceBand)
        assertEquals(100L, s.latencyMs)
    }

    @Test
    fun `correct new activity`() {
        val s = score(newAuto, ideal(newAuto))
        assertEquals(ReplayClass.CORRECT, s.replayClass, newAuto.id)
        assertEquals(ReplayOutcome.AUTO_ACCEPTED, s.outcome)
    }

    @Test
    fun `new activity with an unacceptable name is unsafe`() {
        val s = score(newAuto, ideal(newAuto, name = "Zither recital rehearsal"))
        assertEquals(ReplayClass.UNSAFE_MISS, s.replayClass, newAuto.id)
        assertTrue(UnsafeCheck.WRONG_NEW_NAME in s.unsafeChecks, "${s.unsafeChecks}")
    }

    @Test
    fun `correct review`() {
        val s = score(reviewCase, reviewAnswer())
        assertEquals(ReplayClass.CORRECT, s.replayClass, reviewCase.id)
        assertEquals(ReplayOutcome.NEEDS_REVIEW, s.outcome)
        assertTrue("ACTIVITY_AMBIGUOUS" in s.validationReasons)
    }

    @Test
    fun `medium confidence answer demoted to review is a safe miss`() {
        val s = score(existingAuto, ideal(existingAuto, band = ConfidenceBand.MEDIUM))
        assertEquals(ReplayClass.SAFE_MISS, s.replayClass, existingAuto.id)
        assertEquals(ReplayOutcome.NEEDS_REVIEW, s.outcome)
        assertEquals(listOf("CONFIDENCE_NOT_HIGH"), s.validationReasons)
        assertEquals(listOf("CONFIDENCE_NOT_HIGH"), s.reasonCodes)
        assertEquals(ConfidenceBand.MEDIUM, s.confidenceBand)
    }

    @Test
    fun `matching a must-not-match activity is unsafe`() {
        val case = corpus.cases
            .filter { it.knownResolverGap == null }
            .sortedByDescending { it.expected.outcome == ExpectedOutcome.AUTO_ACCEPT }
            .first { c -> c.expected.mustNotMatch.any { id -> corpus.catalogFor(c).any { it.id == id } } }
        val forbidden = case.expected.mustNotMatch.first { id -> corpus.catalogFor(case).any { it.id == id } }
        val s = score(case, ideal(case, resolution = ActivityResolution.EXISTING_ACTIVITY, matched = forbidden, name = null))
        assertEquals(ReplayClass.UNSAFE_MISS, s.replayClass, case.id)
        assertTrue(UnsafeCheck.MUST_NOT_MATCH in s.unsafeChecks, "${case.id}: ${s.unsafeChecks}")
    }

    @Test
    fun `creating a duplicate new activity is unsafe`() {
        val s = score(
            existingAuto,
            ideal(existingAuto, resolution = ActivityResolution.NEW_ACTIVITY, matched = null, name = "Zither recital rehearsal"),
        )
        assertEquals(ReplayClass.UNSAFE_MISS, s.replayClass, existingAuto.id)
        assertEquals(listOf(UnsafeCheck.DUPLICATE_NEW_ACTIVITY), s.unsafeChecks)
        assertEquals(false, s.resolutionAcceptable)
    }

    @Test
    fun `wrong date is unsafe`() {
        val case = autoCases.first { c ->
            val date = c.expected.expectedLocalDate
            date != null && date != c.capturedInstant.atZone(c.zone).toLocalDate()
        }
        // No time words: the real resolver infers "now", i.e. the capture date, not the expected one.
        val s = score(case, ideal(case, time = null))
        assertEquals(ReplayClass.UNSAFE_MISS, s.replayClass, case.id)
        assertTrue(UnsafeCheck.WRONG_DATE in s.unsafeChecks, "${case.id}: ${s.unsafeChecks}")
    }

    @Test
    fun `wrong state is unsafe`() {
        val case = autoCases.first { c -> ActivityState.entries.any { it !in c.expected.allowedStates } }
        val bad = ActivityState.entries.first { it !in case.expected.allowedStates }
        val s = score(case, ideal(case, state = bad))
        assertEquals(ReplayClass.UNSAFE_MISS, s.replayClass, case.id)
        assertTrue(UnsafeCheck.STATE_NOT_ALLOWED in s.unsafeChecks, "${case.id}: ${s.unsafeChecks}")
    }

    @Test
    fun `auto accepting a review case is unsafe`() {
        val case = corpus.cases.first { c ->
            c.expected.outcome == ExpectedOutcome.NEEDS_REVIEW && corpus.catalogFor(c).isNotEmpty()
        }
        val anyId = corpus.catalogFor(case).first().id
        val s = score(case, ideal(case, resolution = ActivityResolution.EXISTING_ACTIVITY, matched = anyId, name = null, time = null))
        assertEquals(ReplayClass.UNSAFE_MISS, s.replayClass, case.id)
        assertTrue(UnsafeCheck.EXPECTED_REVIEW in s.unsafeChecks)
    }

    @Test
    fun `unavailable and retryable failures are not run`() {
        listOf(InterpreterFailureKind.UNAVAILABLE, InterpreterFailureKind.RETRYABLE).forEach { kind ->
            val s = score(existingAuto, InterpretationResult.Failure(kind, null))
            assertEquals(ReplayClass.NOT_RUN, s.replayClass, "$kind")
            assertEquals(ReplayOutcome.INTERPRETER_UNAVAILABLE, s.outcome)
            assertEquals(kind, s.failureKind)
            assertEquals(null, s.resolutionAcceptable)
            assertEquals(null, s.temporalExpressionAgrees)
        }
    }

    @Test
    fun `malformed and other failures go to review`() {
        val malformed = score(existingAuto, InterpretationResult.Failure(InterpreterFailureKind.MALFORMED, null))
        assertEquals(ReplayClass.SAFE_MISS, malformed.replayClass)
        assertEquals(ReplayOutcome.REJECTED, malformed.outcome)
        assertEquals(listOf("INTERPRETER_OUTPUT_MALFORMED"), malformed.validationReasons)

        val other = score(reviewCase, InterpretationResult.Failure(InterpreterFailureKind.OTHER, null))
        assertEquals(ReplayClass.CORRECT, other.replayClass)
        assertEquals(listOf("INTERPRETER_FAILED"), other.validationReasons)

        val malformedOnReview = score(reviewCase, InterpretationResult.Failure(InterpreterFailureKind.MALFORMED, null))
        assertEquals(ReplayClass.CORRECT, malformedOnReview.replayClass)
    }

    @Test
    fun `temporal agreement is trimmed and case-insensitive`() {
        val case = autoCases.first { it.expected.temporalExpression != null }
        val shouted = "  " + case.expected.temporalExpression!!.uppercase() + " "
        assertEquals(true, score(case, ideal(case, time = shouted)).temporalExpressionAgrees)
    }

    @Test
    fun `ideal answers score correct across the whole corpus`() {
        val wrong = corpus.cases.filter { it.knownResolverGap == null }.mapNotNull { case ->
            val answer = if (case.expected.outcome == ExpectedOutcome.AUTO_ACCEPT) ideal(case) else reviewAnswer()
            val s = score(case, answer)
            if (s.replayClass == ReplayClass.CORRECT) null else "${case.id}=${s.replayClass}:${s.reasonCodes}"
        }
        assertEquals(emptyList(), wrong)
    }

    // --- staleness ---------------------------------------------------------------------------

    @Test
    fun `unknown case id fails loudly`() {
        val e = assertFailsWith<StaleRecordingException> {
            replay.replay(recording(entry(existingAuto, ideal(existingAuto)).copy(caseId = "no-such-case")))
        }
        assertTrue("no-such-case" in e.message.orEmpty())
    }

    @Test
    fun `context hash or candidate mismatch fails loudly naming the case`() {
        val good = entry(existingAuto, ideal(existingAuto))
        val badHash = assertFailsWith<StaleRecordingException> {
            replay.replay(recording(good.copy(contextHash = "0".repeat(64))))
        }
        assertTrue(existingAuto.id in badHash.message.orEmpty())
        val badIds = assertFailsWith<StaleRecordingException> {
            replay.replay(recording(good.copy(offeredCandidateIds = good.offeredCandidateIds.drop(1) + "act-not-offered")))
        }
        assertTrue(existingAuto.id in badIds.message.orEmpty())
    }

    // --- report and gate ---------------------------------------------------------------------

    private fun mixedResult(sha: String = corpus.sha256): ReplayResult = replay.replay(
        recording(
            entry(existingAuto, ideal(existingAuto), latencyMs = 300),
            entry(newAuto, ideal(newAuto), latencyMs = 100),
            entry(reviewCase, reviewAnswer(), latencyMs = 200),
            entry(
                corpus.cases.first { it !in listOf(existingAuto, newAuto, reviewCase) && it.expected.outcome == ExpectedOutcome.AUTO_ACCEPT },
                InterpretationResult.Failure(InterpreterFailureKind.UNAVAILABLE, null),
                latencyMs = null,
            ),
            sha = sha,
        ),
    )

    @Test
    fun `markdown report has every section and the synthetic banner`() {
        val result = mixedResult()
        val md = SemanticReport.markdown(result, corpus)
        listOf(
            "## Provenance", "## Totals", "## By category", "## UNSAFE_MISS", "## SAFE_MISS",
            "## Confidence band vs class", "Temporal expression agreement: 3 of 3", "median 200 ms, max 300 ms",
            "## NOT_RUN", "SYNTHETIC ANSWERS -- NOT A MODEL RESULT, NOT THE OFFICIAL RESULT",
        ).forEach { assertTrue(it in md, "missing: $it") }
        assertEquals("STAND-IN MODEL -- NOT THE OFFICIAL RESULT", SemanticReport.banner(RecordingSource.STAND_IN))
        assertEquals(null, SemanticReport.banner(RecordingSource.DEVICE))
        val standIn = result.copy(recording = result.recording.copy(source = RecordingSource.STAND_IN))
        assertTrue("STAND-IN MODEL -- NOT THE OFFICIAL RESULT" in SemanticReport.markdown(standIn))
    }

    @Test
    fun `console summary carries ids, counts and codes but no sentences`() {
        val result = replay.replay(
            recording(
                entry(existingAuto, ideal(existingAuto, band = ConfidenceBand.MEDIUM)),
                entry(newAuto, ideal(newAuto, name = "Zither recital rehearsal")),
            ),
        )
        val summary = SemanticReport.consoleSummary(result)
        assertTrue("SAFE_MISS ${existingAuto.id}: CONFIDENCE_NOT_HIGH" in summary, summary)
        assertTrue("UNSAFE_MISS ${newAuto.id}: WRONG_NEW_NAME" in summary, summary)
        assertTrue("SAFE_MISS=1" in summary && "UNSAFE_MISS=1" in summary)
        corpus.cases.forEach { assertFalse(it.rawText in summary, "console summary leaks the sentence of ${it.id}") }
        assertFalse("Zither" in summary)
        assertFalse("Zither" in SemanticReport.markdown(result, corpus), "proposed names must not appear in the report")
    }

    @Test
    fun `gate fails naming baseline cases that are not correct`() {
        val result = mixedResult()
        val notRunId = result.of(ReplayClass.NOT_RUN).single().caseId
        assertEquals(emptyList(), SemanticGate.failures(result, listOf(existingAuto.id, reviewCase.id), corpus))
        val failures = SemanticGate.failures(result, listOf(existingAuto.id, notRunId), corpus)
        assertEquals(1, failures.size)
        assertTrue(notRunId in failures.single() && "NOT_RUN" in failures.single())
    }

    @Test
    fun `gate flags a different corpus with missing baseline cases, and unknown baseline ids`() {
        val otherCorpus = mixedResult(sha = "f".repeat(64))
        val missing = corpus.cases.first { c -> otherCorpus.entries.none { it.caseId == c.id } }.id
        val failures = SemanticGate.failures(otherCorpus, listOf(existingAuto.id, missing, "no-such-case"), corpus)
        assertTrue(failures.any { "different corpus" in it && missing in it }, "$failures")
        assertTrue(failures.any { "no-such-case" in it }, "$failures")
        assertNotEquals(otherCorpus.corpusSha256, otherCorpus.recording.corpusSha256)
    }

    @Test
    fun `baseline format is a plain JSON list of unique case ids`() {
        assertEquals(listOf("a", "b"), SemanticGate.parseBaseline("""["a", "b"]"""))
        assertFailsWith<IllegalArgumentException> { SemanticGate.parseBaseline("""["a", "a"]""") }
        assertFailsWith<IllegalArgumentException> { SemanticGate.parseBaseline("""{"ids": []}""") }
    }
}
