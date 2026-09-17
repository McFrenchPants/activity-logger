package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.candidates.CandidateSelector
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationInput
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationReason
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureInterpretationOrchestratorTest {
    private val zone = ZoneId.of("America/Detroit")
    private val capturedAt: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val processedAt: Instant = capturedAt.plusSeconds(30)
    private val clock = MutableClock(processedAt, zone)
    private val repo = InMemoryActivityRepository(clock)
    private val interpreter = FakeActivityInterpreter()
    private val orchestrator = CaptureInterpretationOrchestrator(repo, interpreter, clock)

    private val mowLawn = repo.seedActivity("Mow lawn", aliases = listOf("cut grass"))
    private val gutters = repo.seedActivity("Clean gutters")
    private val archived = repo.seedActivity("Old hobby", CanonicalActivityStatus.ARCHIVED)

    private fun capture(text: String, speechConfidence: Double? = null, state: ProcessingState = ProcessingState.CAPTURED): String =
        runSuspend {
            repo.createRawCapture(NewRawCapture(CaptureSource.PHONE_VOICE, null, capturedAt, zone, text, speechConfidence, null, state))
        }

    private fun candidate(
        resolution: ActivityResolution = ActivityResolution.EXISTING_ACTIVITY,
        matched: String? = mowLawn,
        name: String? = null,
        state: ActivityState? = ActivityState.COMPLETED,
        time: String? = "yesterday",
        band: ConfidenceBand? = ConfidenceBand.HIGH,
        operation: InterpretationOperation = InterpretationOperation.LOG_ACTIVITY,
    ) = InterpretationCandidate(operation, resolution, matched, name, state, time, band)

    private fun success(c: InterpretationCandidate, json: String? = "{\"synthetic\":true}") = InterpretationResult.Success(c, json)

    private fun process(id: String) = runSuspend { orchestrator.process(id) }
    private fun stored(id: String) = runSuspend { assertNotNull(repo.getCapture(id)) }
    private fun expectedHash(text: String) = runSuspend { CandidateSelector().select(repo.loadCatalog(), text).contextHash }

    private val yesterdayMidnight: Instant = Instant.parse("2026-09-14T04:00:00Z")

    // --- AUTO_ACCEPT ---------------------------------------------------------

    @Test
    fun `auto accept existing creates one occurrence with resolved time`() {
        val id = capture("I cut the grass yesterday.")
        interpreter.enqueue(success(candidate()))
        val outcome = assertIs<CaptureProcessingOutcome.AutoAccepted>(process(id))

        val occurrence = runSuspend { assertNotNull(repo.getOccurrence(outcome.occurrenceId)) }
        assertEquals(1, repo.occurrences.size)
        assertEquals(mowLawn, occurrence.canonicalActivityId)
        assertEquals(yesterdayMidnight, occurrence.occurredAt)
        assertEquals(TimePrecision.DATE_ONLY, occurrence.timePrecision)
        assertEquals(ActivityState.COMPLETED, occurrence.activityState)
        assertEquals(ProcessingState.PERSISTED, stored(id).processingState)
        assertEquals(3, repo.activities.size)

        val record = repo.interpretationsFor(id).single()
        assertEquals(ValidationStatus.VALID, record.validationStatus)
        assertNull(record.validationReason)
        assertEquals(processedAt, record.createdAt)
        assertEquals(FakeActivityInterpreter.DEFAULT_PROVENANCE.interpreterVersion, record.interpreterVersion)
        assertEquals(FakeActivityInterpreter.DEFAULT_PROVENANCE.promptVersion, record.promptVersion)
        assertEquals(FakeActivityInterpreter.DEFAULT_PROVENANCE.schemaVersion, record.schemaVersion)
        assertEquals(mowLawn, record.matchedActivityId)
        assertEquals("yesterday", record.temporalExpression)
        assertEquals(yesterdayMidnight, record.resolvedOccurredAt)
        assertEquals(TimePrecision.DATE_ONLY, record.timePrecision)
        assertEquals(ConfidenceBand.HIGH, record.modelConfidenceBand)
        assertEquals("{\"synthetic\":true}", record.structuredResultJson)
        assertEquals(expectedHash("I cut the grass yesterday."), record.candidateContextHash)
        assertEquals("interpretation-1", occurrence.effectiveInterpretationId)
    }

    @Test
    fun `auto accept new creates an ACTIVE activity and one occurrence`() {
        val id = capture("Cleaned the dryer vent just now.")
        interpreter.enqueue(
            success(candidate(ActivityResolution.NEW_ACTIVITY, matched = null, name = "Clean dryer vent", time = null, state = ActivityState.IN_PROGRESS)),
        )
        val outcome = assertIs<CaptureProcessingOutcome.AutoAccepted>(process(id))
        val occurrence = runSuspend { assertNotNull(repo.getOccurrence(outcome.occurrenceId)) }
        val activity = runSuspend { assertNotNull(repo.getActivity(occurrence.canonicalActivityId)) }
        assertEquals("Clean dryer vent", activity.displayName)
        assertEquals("clean dryer vent", activity.normalizedName)
        assertEquals(CanonicalActivityStatus.ACTIVE, activity.status)
        assertEquals(4, repo.activities.size)
        assertEquals(1, repo.occurrences.size)
        assertEquals(capturedAt, occurrence.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, occurrence.timePrecision)
        assertEquals(ActivityState.IN_PROGRESS, occurrence.activityState)
        assertEquals(ProcessingState.PERSISTED, stored(id).processingState)
        assertEquals("Clean dryer vent", repo.interpretationsFor(id).single().proposedCanonicalName)
    }

    // --- NEEDS_REVIEW --------------------------------------------------------

    private fun assertStoredForReview(id: String, status: ValidationStatus) {
        val record = repo.interpretationsFor(id).single()
        assertEquals(status, record.validationStatus)
        assertEquals(ProcessingState.NEEDS_REVIEW, stored(id).processingState)
        assertTrue(repo.occurrences.isEmpty())
        assertEquals(3, repo.activities.size)
    }

    @Test
    fun `medium confidence needs review and logs nothing`() {
        val id = capture("I cut the grass yesterday.")
        interpreter.enqueue(success(candidate(band = ConfidenceBand.MEDIUM)))
        val outcome = assertIs<CaptureProcessingOutcome.NeedsReview>(process(id))
        assertEquals(setOf(ValidationReason.CONFIDENCE_NOT_HIGH), outcome.reasons)
        assertStoredForReview(id, ValidationStatus.NEEDS_REVIEW)
        assertEquals("CONFIDENCE_NOT_HIGH", repo.interpretationsFor(id).single().validationReason)
    }

    @Test
    fun `edged the lawn matched to Mow lawn with low confidence needs review`() {
        val id = capture("I edged the lawn.")
        interpreter.enqueue(success(candidate(time = null, band = ConfidenceBand.LOW)))
        val outcome = assertIs<CaptureProcessingOutcome.NeedsReview>(process(id))
        assertEquals(setOf(ValidationReason.CONFIDENCE_NOT_HIGH), outcome.reasons)
        assertStoredForReview(id, ValidationStatus.NEEDS_REVIEW)
        val record = repo.interpretationsFor(id).single()
        assertEquals(mowLawn, record.matchedActivityId)
        assertEquals(ConfidenceBand.LOW, record.modelConfidenceBand)
        assertEquals("CONFIDENCE_NOT_HIGH", record.validationReason)
    }

    @Test
    fun `new name duplicating an existing activity needs review with sorted reason codes`() {
        val id = capture("Mowed the lawn tomorrow.")
        interpreter.enqueue(success(candidate(ActivityResolution.NEW_ACTIVITY, matched = null, name = "Mow lawn", time = "tomorrow")))
        val outcome = assertIs<CaptureProcessingOutcome.NeedsReview>(process(id))
        assertEquals(setOf(ValidationReason.NEW_NAME_DUPLICATES_EXISTING, ValidationReason.TIME_IN_FUTURE), outcome.reasons)
        assertStoredForReview(id, ValidationStatus.NEEDS_REVIEW)
        val record = repo.interpretationsFor(id).single()
        assertEquals("NEW_NAME_DUPLICATES_EXISTING,TIME_IN_FUTURE", record.validationReason)
        assertNull(record.resolvedOccurredAt)
        assertNull(record.timePrecision)
    }

    @Test
    fun `a needs-review capture can be processed again and appends a second interpretation`() {
        val id = capture("I cut the grass yesterday.")
        interpreter.enqueue(success(candidate(band = ConfidenceBand.MEDIUM)), success(candidate()))
        assertIs<CaptureProcessingOutcome.NeedsReview>(process(id))
        val first = repo.interpretationsFor(id).single()
        clock.advance(java.time.Duration.ofMinutes(5))
        assertIs<CaptureProcessingOutcome.AutoAccepted>(process(id))
        val records = repo.interpretationsFor(id)
        assertEquals(2, records.size)
        assertEquals(first, records[0])
        assertEquals(ValidationStatus.VALID, records[1].validationStatus)
        assertEquals(processedAt.plusSeconds(300), records[1].createdAt)
        assertEquals(ProcessingState.PERSISTED, stored(id).processingState)
    }

    // --- REJECT --------------------------------------------------------------

    @Test
    fun `existing id not in the shortlist is rejected`() {
        val id = capture("Did the old hobby yesterday.")
        interpreter.enqueue(success(candidate(matched = archived)))
        val outcome = assertIs<CaptureProcessingOutcome.Rejected>(process(id))
        assertEquals(setOf(ValidationReason.EXISTING_ACTIVITY_NOT_SUPPLIED), outcome.reasons)
        assertStoredForReview(id, ValidationStatus.INVALID)
        val record = repo.interpretationsFor(id).single()
        assertEquals("EXISTING_ACTIVITY_NOT_SUPPLIED", record.validationReason)
        // Not in the ACTIVE catalog, so not stored as a matched id even though the row exists.
        assertNull(record.matchedActivityId)
        assertEquals("{\"synthetic\":true}", record.structuredResultJson)
    }

    @Test
    fun `existing answer with an invented id is rejected and stored for review with null id`() {
        val id = capture("I cut the grass yesterday.")
        val json = "{\"matchedActivityId\":\"activity-999\"}"
        interpreter.enqueue(success(candidate(matched = "activity-999"), json))
        val outcome = assertIs<CaptureProcessingOutcome.Rejected>(process(id))
        assertTrue(ValidationReason.EXISTING_ACTIVITY_NOT_SUPPLIED in outcome.reasons)
        assertStoredForReview(id, ValidationStatus.INVALID)
        val record = repo.interpretationsFor(id).single()
        assertNull(record.matchedActivityId)
        assertEquals(json, record.structuredResultJson)
        assertEquals(ActivityResolution.EXISTING_ACTIVITY, record.activityResolution)
    }

    @Test
    fun `ambiguous answer carrying an invented id is rejected and stored with null id`() {
        val id = capture("I did the yard thing.")
        val json = "{\"resolution\":\"AMBIGUOUS\",\"matchedActivityId\":\"activity-999\"}"
        interpreter.enqueue(success(candidate(ActivityResolution.AMBIGUOUS, matched = "activity-999"), json))
        val outcome = assertIs<CaptureProcessingOutcome.Rejected>(process(id))
        assertTrue(ValidationReason.UNMATCHED_WITH_ACTIVITY_FIELDS in outcome.reasons)
        assertStoredForReview(id, ValidationStatus.INVALID)
        val record = repo.interpretationsFor(id).single()
        assertNull(record.matchedActivityId)
        assertEquals(json, record.structuredResultJson)
    }

    @Test
    fun `a matched id that is in the catalog is stored on a non-accepted record`() {
        val id = capture("I cut the grass yesterday.")
        interpreter.enqueue(success(candidate(band = ConfidenceBand.MEDIUM)))
        assertIs<CaptureProcessingOutcome.NeedsReview>(process(id))
        assertEquals(mowLawn, repo.interpretationsFor(id).single().matchedActivityId)
    }

    @Test
    fun `query history is rejected`() {
        val id = capture("When did I last mow the lawn?")
        interpreter.enqueue(success(candidate(operation = InterpretationOperation.QUERY_HISTORY, time = null)))
        val outcome = assertIs<CaptureProcessingOutcome.Rejected>(process(id))
        assertEquals(setOf(ValidationReason.OPERATION_IS_QUERY), outcome.reasons)
        assertStoredForReview(id, ValidationStatus.INVALID)
        assertEquals(InterpretationOperation.QUERY_HISTORY, repo.interpretationsFor(id).single().operation)
    }

    // --- interpreter failures ------------------------------------------------

    private fun assertFailureStored(kind: InterpreterFailureKind, reason: ValidationReason) {
        val text = "Synthetic unparseable words."
        val id = capture(text)
        val provenance = com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance("custom-int", "custom-prompt", 7)
        interpreter.provenance = provenance
        interpreter.enqueue(InterpretationResult.Failure(kind, "{\"broken\""))
        val outcome = assertIs<CaptureProcessingOutcome.Rejected>(process(id))
        assertEquals(setOf(reason), outcome.reasons)
        assertStoredForReview(id, ValidationStatus.INVALID)
        val record = repo.interpretationsFor(id).single()
        assertEquals("custom-int", record.interpreterVersion)
        assertEquals("custom-prompt", record.promptVersion)
        assertEquals(7, record.schemaVersion)
        assertEquals("{\"broken\"", record.structuredResultJson)
        assertEquals(expectedHash(text), record.candidateContextHash)
        assertEquals(reason.name, record.validationReason)
        assertEquals(InterpretationOperation.UNSUPPORTED, record.operation)
        assertEquals(ActivityResolution.UNRESOLVED, record.activityResolution)
        assertEquals(processedAt, record.createdAt)
        assertNull(record.matchedActivityId)
        assertNull(record.proposedCanonicalName)
        assertNull(record.activityState)
        assertNull(record.temporalExpression)
        assertNull(record.resolvedOccurredAt)
        assertNull(record.timePrecision)
        assertNull(record.modelConfidenceBand)
    }

    @Test
    fun `malformed output is stored as invalid for review`() =
        assertFailureStored(InterpreterFailureKind.MALFORMED, ValidationReason.INTERPRETER_OUTPUT_MALFORMED)

    @Test
    fun `other failure is stored as invalid for review`() =
        assertFailureStored(InterpreterFailureKind.OTHER, ValidationReason.INTERPRETER_FAILED)

    @Test
    fun `unavailable and retryable store no interpretation and mark failed retryable`() {
        for (kind in listOf(InterpreterFailureKind.UNAVAILABLE, InterpreterFailureKind.RETRYABLE)) {
            val id = capture("I cut the grass yesterday.")
            interpreter.enqueue(InterpretationResult.Failure(kind, null))
            assertEquals(CaptureProcessingOutcome.InterpreterUnavailable(kind), process(id))
            assertTrue(repo.interpretationsFor(id).isEmpty())
            assertEquals(ProcessingState.FAILED_RETRYABLE, stored(id).processingState)
        }
        assertTrue(repo.occurrences.isEmpty())
    }

    @Test
    fun `a failed retryable capture is processed again`() {
        val id = capture("I cut the grass yesterday.")
        interpreter.enqueue(InterpretationResult.Failure(InterpreterFailureKind.UNAVAILABLE, null), success(candidate()))
        assertIs<CaptureProcessingOutcome.InterpreterUnavailable>(process(id))
        assertIs<CaptureProcessingOutcome.AutoAccepted>(process(id))
        assertEquals(2, interpreter.callCount)
    }

    // --- guards --------------------------------------------------------------

    @Test
    fun `rerun on a capture with an occurrence calls nothing and writes nothing`() {
        val id = capture("I cut the grass yesterday.")
        interpreter.enqueue(success(candidate()))
        assertIs<CaptureProcessingOutcome.AutoAccepted>(process(id))
        val calls = interpreter.callCount
        val writes = repo.writeCount
        assertEquals(CaptureProcessingOutcome.AlreadyHasOccurrence, process(id))
        assertEquals(calls, interpreter.callCount)
        assertEquals(writes, repo.writeCount)
        assertEquals(1, repo.interpretationsFor(id).size)
    }

    @Test
    fun `unknown capture id throws and calls nothing`() {
        val writes = repo.writeCount
        val e = assertFailsWith<IllegalArgumentException> { process("capture-404") }
        assertEquals("unknown capture capture-404", e.message)
        assertEquals(0, interpreter.callCount)
        assertEquals(writes, repo.writeCount)
    }

    @Test
    fun `an exception from the interpreter propagates and nothing is written`() {
        val id = capture("I cut the grass yesterday.")
        val writes = repo.writeCount
        interpreter.respondWith { throw IllegalStateException("boom") }
        assertFailsWith<IllegalStateException> { process(id) }
        assertEquals(writes, repo.writeCount)
        assertEquals(ProcessingState.CAPTURED, stored(id).processingState)
    }

    // --- interpreter input ---------------------------------------------------

    @Test
    fun `interpreter receives exactly the shortlist, raw text, instant and zone`() {
        repo.seedActivity("Rake leaves")
        val small = CaptureInterpretationOrchestrator(repo, interpreter, clock, selector = CandidateSelector(bound = 1))
        val text = "  I cut grass yesterday.  "
        val id = capture(text)
        interpreter.enqueue(success(candidate(band = ConfidenceBand.LOW)))
        runSuspend { small.process(id) }

        val selection = runSuspend { CandidateSelector(bound = 1).select(repo.loadCatalog(), text) }
        assertEquals(listOf(mowLawn), selection.candidates.map { it.id })
        val input: InterpretationInput = interpreter.receivedInputs.single()
        assertEquals(text, input.rawText)
        assertEquals(capturedAt, input.capturedAt)
        assertEquals(zone, input.zoneId)
        assertEquals(selection.candidates, input.candidates)
        assertEquals(selection.contextHash, repo.interpretationsFor(id).single().candidateContextHash)

        // Default selector: the whole ACTIVE catalog, never the archived activity.
        val id2 = capture("Synthetic words.")
        interpreter.enqueue(success(candidate()))
        process(id2)
        assertEquals(listOf(gutters, mowLawn, "activity-4"), interpreter.receivedInputs[1].candidates.map { it.id })
        assertEquals(expectedHash("Synthetic words."), repo.interpretationsFor(id2).single().candidateContextHash)
    }
}
