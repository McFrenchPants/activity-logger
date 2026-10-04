package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.extraction.ActivityExtractor
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionCandidate
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionInput
import com.mcfrenchpants.activityledger.core.domain.extraction.ExtractionResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterProvenance
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityRepository
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.CorrectionOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.ExtractedWords
import com.mcfrenchpants.activityledger.core.domain.repository.LedgerRepository
import com.mcfrenchpants.activityledger.core.domain.repository.MergeOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.repository.RenameOutcome
import com.mcfrenchpants.activityledger.core.domain.repository.TagCorrectionRequest
import com.mcfrenchpants.activityledger.core.domain.repository.TagTarget
import com.mcfrenchpants.activityledger.core.domain.repository.TaggedAcceptRequest
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownPair
import com.mcfrenchpants.activityledger.core.domain.tagging.KnownTag
import com.mcfrenchpants.activityledger.core.domain.tagging.TagCatalog
import com.mcfrenchpants.activityledger.core.domain.tagging.TagDecisionReason
import com.mcfrenchpants.activityledger.core.domain.tagging.TagKind
import com.mcfrenchpants.activityledger.core.domain.tagging.TagResolution
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationReason
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

/** Records every [acceptTagged] request; the rest of the activity contract is the in-memory fake. */
private class FakeLedgerRepository(
    private val base: InMemoryActivityRepository,
    var catalog: TagCatalog,
) : LedgerRepository, ActivityRepository by base {
    val acceptRequests = mutableListOf<TaggedAcceptRequest>()

    override suspend fun loadTagCatalog(): TagCatalog = catalog

    override suspend fun acceptTagged(request: TaggedAcceptRequest): String {
        acceptRequests += request
        return base.acceptInterpretation(
            request.captureId, request.interpretation, ActivityTarget.New("pair ${acceptRequests.size}"),
            request.occurredAt, request.timePrecision, request.activityState,
        )
    }

    override suspend fun correctTags(request: TagCorrectionRequest): CorrectionOutcome = error("not used")
    override suspend fun renameTag(kind: TagKind, tagId: String, newDisplayName: String): RenameOutcome = error("not used")
    override suspend fun mergeTags(kind: TagKind, fromTagId: String, intoTagId: String): MergeOutcome = error("not used")
    override suspend fun loadExtractedWordsForCapture(captureId: String): ExtractedWords? = error("not used")
    override suspend fun loadExtractedWordsForOccurrence(occurrenceId: String): ExtractedWords? = error("not used")
}

private class FakeExtractor : ActivityExtractor {
    override var provenance = InterpreterProvenance("fake-extractor-1", "fake-prompt-1", 3)
    private val scripted = ArrayDeque<ExtractionResult>()
    val inputs = mutableListOf<ExtractionInput>()

    fun enqueue(vararg r: ExtractionResult) {
        scripted.addAll(r)
    }

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        inputs += input
        return scripted.removeFirstOrNull() ?: error("no scripted extraction")
    }
}

class TaggedCaptureOrchestratorTest {
    private val zone = ZoneId.of("America/Detroit")
    private val capturedAt: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val processedAt: Instant = capturedAt.plusSeconds(30)
    private val clock = MutableClock(processedAt, zone)
    private val base = InMemoryActivityRepository(clock)
    private val hotTub = KnownTag("subject-1", TagKind.SUBJECT, "Hot tub", emptyList())
    private val changeFilter = KnownTag("action-1", TagKind.ACTION, "Change filter", emptyList())
    private val populated = TagCatalog(listOf(hotTub), listOf(changeFilter), listOf(KnownPair("subject-1", "action-1")))
    private val repo = FakeLedgerRepository(base, populated)
    private val extractor = FakeExtractor()
    private val orchestrator = TaggedCaptureOrchestrator(repo, extractor, clock)

    private val text = "I just changed the hot tub filter."

    private fun capture(rawText: String = text, at: Instant = capturedAt): String = runSuspend {
        base.createRawCapture(NewRawCapture(CaptureSource.PHONE_VOICE, null, at, zone, rawText, null, null, ProcessingState.CAPTURED))
    }

    private fun candidate(
        subject: String? = "hot tub",
        action: String? = "change filter",
        state: ActivityState? = ActivityState.COMPLETED,
        time: String? = null,
        duration: String? = null,
        operation: InterpretationOperation = InterpretationOperation.LOG_ACTIVITY,
    ) = ExtractionResult.Success(ExtractionCandidate(operation, subject, action, state, time, duration))

    private fun process(id: String) = runSuspend { orchestrator.process(id) }
    private fun stored(id: String) = runSuspend { assertNotNull(base.getCapture(id)) }

    private fun assertHeldForReview(id: String) {
        val record = base.interpretationsFor(id).single()
        assertEquals(ValidationStatus.NEEDS_REVIEW, record.validationStatus)
        assertEquals(ProcessingState.NEEDS_REVIEW, stored(id).processingState)
        assertTrue(base.occurrences.isEmpty())
        assertTrue(repo.acceptRequests.isEmpty())
    }

    // --- AUTO_SAVE ------------------------------------------------------------

    @Test
    fun `auto save with existing tags creates one occurrence through acceptTagged`() {
        val id = capture()
        extractor.enqueue(candidate())
        val outcome = assertIs<TaggedProcessingOutcome.AutoSaved>(process(id))

        val request = repo.acceptRequests.single()
        assertEquals(TagTarget.Existing("subject-1"), request.subject)
        assertEquals(TagTarget.Existing("action-1"), request.action)
        assertEquals(capturedAt, request.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, request.timePrecision)
        assertEquals(ActivityState.COMPLETED, request.activityState)
        assertNull(request.durationSeconds)
        assertNull(request.learnSubjectAlias)
        assertNull(request.learnActionAlias)
        assertEquals(1, base.occurrences.size)
        assertEquals(outcome.occurrenceId, base.occurrences.single().id)
        assertEquals(ProcessingState.PERSISTED, stored(id).processingState)

        val record = request.interpretation
        assertEquals(ValidationStatus.VALID, record.validationStatus)
        assertNull(record.validationReason)
        assertEquals(processedAt, record.createdAt)
        assertEquals("fake-extractor-1", record.interpreterVersion)
        assertEquals("fake-prompt-1", record.promptVersion)
        assertEquals(3, record.schemaVersion)
        assertEquals(InterpretationOperation.LOG_ACTIVITY, record.operation)
        assertEquals(ActivityResolution.UNRESOLVED, record.activityResolution)
        assertNull(record.matchedActivityId)
        assertNull(record.proposedCanonicalName)
        assertEquals("hot tub", record.extractedSubject)
        assertEquals("change filter", record.extractedAction)
        assertNull(record.modelConfidenceBand)
        assertNull(record.candidateContextHash)
        assertNull(record.structuredResultJson)
        assertEquals(1, base.interpretationsFor(id).size)
    }

    @Test
    fun `auto save from an empty catalog names both new tags`() {
        repo.catalog = TagCatalog(emptyList(), emptyList(), emptyList())
        val id = capture()
        extractor.enqueue(candidate(subject = "hot tub", action = "change filter"))
        assertIs<TaggedProcessingOutcome.AutoSaved>(process(id))
        val request = repo.acceptRequests.single()
        assertEquals(TagTarget.New("hot tub"), request.subject)
        assertEquals(TagTarget.New("change filter"), request.action)
    }

    @Test
    fun `duration for half an hour is stored as 1800 seconds on request and interpretation`() {
        val id = capture("I changed the hot tub filter for half an hour.")
        extractor.enqueue(candidate(duration = "for half an hour"))
        assertIs<TaggedProcessingOutcome.AutoSaved>(process(id))
        val request = repo.acceptRequests.single()
        assertEquals(1800L, request.durationSeconds)
        assertEquals("for half an hour", request.interpretation.durationExpression)
        assertEquals(1800L, request.interpretation.resolvedDurationSeconds)
    }

    @Test
    fun `time and duration invented by the model are dropped by grounding`() {
        val id = capture("I changed the hot tub filter.")
        extractor.enqueue(candidate(time = "yesterday", duration = "for an hour"))
        assertIs<TaggedProcessingOutcome.AutoSaved>(process(id))
        val request = repo.acceptRequests.single()
        assertEquals(capturedAt, request.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, request.timePrecision)
        assertNull(request.durationSeconds)
        assertNull(request.interpretation.temporalExpression)
        assertNull(request.interpretation.durationExpression)
        assertNull(request.interpretation.resolvedDurationSeconds)
    }

    @Test
    fun `grounded time words are resolved and stored`() {
        val id = capture("I changed the hot tub filter yesterday.")
        extractor.enqueue(candidate(time = "yesterday"))
        assertIs<TaggedProcessingOutcome.AutoSaved>(process(id))
        val request = repo.acceptRequests.single()
        assertEquals(Instant.parse("2026-09-14T04:00:00Z"), request.occurredAt)
        assertEquals(TimePrecision.DATE_ONLY, request.timePrecision)
        assertEquals("yesterday", request.interpretation.temporalExpression)
        assertEquals(request.occurredAt, request.interpretation.resolvedOccurredAt)
    }

    // --- CONFIRM / NEEDS_REVIEW ----------------------------------------------

    @Test
    fun `near match asks to confirm, stores the interpretation and logs nothing`() {
        val id = capture("I changed the hot tup filter for 10 minutes.")
        extractor.enqueue(candidate(subject = "hot tup", duration = "10 minutes"))
        val outcome = assertIs<TaggedProcessingOutcome.NeedsConfirm>(process(id))

        val proposal = outcome.proposal
        val near = assertIs<TagResolution.Near>(proposal.subject)
        assertEquals(listOf(hotTub), near.candidates)
        assertEquals("hot tup", proposal.extractedSubject)
        assertEquals("change filter", proposal.extractedAction)
        assertIs<TagResolution.Exact>(proposal.action)
        assertEquals(setOf(TagDecisionReason.SUBJECT_NEAR_EXISTING), proposal.reasons)
        assertEquals(capturedAt, proposal.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, proposal.timePrecision)
        assertEquals("10 minutes", proposal.durationExpression)
        assertEquals(600L, proposal.durationSeconds)
        assertEquals(ActivityState.COMPLETED, proposal.activityState)
        assertEquals(id, proposal.captureId)

        assertHeldForReview(id)
        val record = base.interpretationsFor(id).single()
        assertEquals("hot tup", record.extractedSubject)
        assertEquals(600L, record.resolvedDurationSeconds)
        assertEquals("SUBJECT_NEAR_EXISTING", record.validationReason)
    }

    @Test
    fun `missing action needs review and logs nothing`() {
        val id = capture()
        extractor.enqueue(candidate(action = null))
        val outcome = assertIs<TaggedProcessingOutcome.NeedsReview>(process(id))
        assertEquals(setOf(TagDecisionReason.ACTION_MISSING), outcome.reasons)
        assertTrue(outcome.problems.isEmpty())
        assertNotNull(outcome.proposal)
        assertHeldForReview(id)
        assertEquals("ACTION_MISSING", base.interpretationsFor(id).single().validationReason)
    }

    @Test
    fun `auto save with a future time is downgraded to review`() {
        val id = capture("I will change the hot tub filter tomorrow.")
        extractor.enqueue(candidate(time = "tomorrow"))
        val outcome = assertIs<TaggedProcessingOutcome.NeedsReview>(process(id))
        assertEquals(setOf(ValidationReason.TIME_IN_FUTURE), outcome.problems)
        assertTrue(outcome.reasons.isEmpty())
        assertNull(outcome.proposal?.occurredAt)
        assertHeldForReview(id)
        val record = base.interpretationsFor(id).single()
        assertEquals("TIME_IN_FUTURE", record.validationReason)
        assertNull(record.resolvedOccurredAt)
    }

    @Test
    fun `auto save with an unresolvable time is downgraded to review`() {
        // 02:30 does not exist on the spring-forward day in this zone.
        val gapDay = ZonedDateTime.of(2027, 3, 14, 20, 0, 0, 0, zone).toInstant()
        val id = capture("I changed the hot tub filter at 2:30am.", gapDay)
        extractor.enqueue(candidate(time = "2:30am"))
        val outcome = assertIs<TaggedProcessingOutcome.NeedsReview>(process(id))
        assertEquals(setOf(ValidationReason.TIME_UNRESOLVABLE), outcome.problems)
        assertHeldForReview(id)
    }

    @Test
    fun `auto save with a missing state is downgraded to review`() {
        val id = capture()
        extractor.enqueue(candidate(state = null))
        val outcome = assertIs<TaggedProcessingOutcome.NeedsReview>(process(id))
        assertEquals(setOf(ValidationReason.STATE_MISSING), outcome.problems)
        assertHeldForReview(id)
        assertEquals("STATE_MISSING", base.interpretationsFor(id).single().validationReason)
    }

    @Test
    fun `a confirm decision keeps its outcome even with a future time`() {
        val id = capture("I will change the hot tup filter tomorrow.")
        extractor.enqueue(candidate(subject = "hot tup", time = "tomorrow"))
        val outcome = assertIs<TaggedProcessingOutcome.NeedsConfirm>(process(id))
        assertNull(outcome.proposal.occurredAt)
        assertHeldForReview(id)
        assertEquals("SUBJECT_NEAR_EXISTING", base.interpretationsFor(id).single().validationReason)
    }

    // --- extractor failures ---------------------------------------------------

    @Test
    fun `unavailable and retryable store no interpretation and mark failed retryable`() {
        for (kind in listOf(InterpreterFailureKind.UNAVAILABLE, InterpreterFailureKind.RETRYABLE)) {
            val id = capture()
            extractor.enqueue(ExtractionResult.Failure(kind))
            assertEquals(TaggedProcessingOutcome.InterpreterUnavailable(kind), process(id))
            assertTrue(base.interpretationsFor(id).isEmpty())
            assertEquals(ProcessingState.FAILED_RETRYABLE, stored(id).processingState)
        }
        assertTrue(base.occurrences.isEmpty())
    }

    private fun assertFailureStored(kind: InterpreterFailureKind, reason: ValidationReason) {
        val id = capture()
        extractor.enqueue(ExtractionResult.Failure(kind))
        val outcome = assertIs<TaggedProcessingOutcome.Rejected>(process(id))
        assertEquals(setOf(reason), outcome.reasons)
        val record = base.interpretationsFor(id).single()
        assertEquals(ValidationStatus.INVALID, record.validationStatus)
        assertEquals(reason.name, record.validationReason)
        assertEquals(InterpretationOperation.UNSUPPORTED, record.operation)
        assertEquals(ActivityResolution.UNRESOLVED, record.activityResolution)
        assertNull(record.extractedSubject)
        assertNull(record.extractedAction)
        assertNull(record.durationExpression)
        assertNull(record.resolvedOccurredAt)
        assertEquals("fake-extractor-1", record.interpreterVersion)
        assertEquals(ProcessingState.NEEDS_REVIEW, stored(id).processingState)
        assertTrue(base.occurrences.isEmpty())
    }

    @Test
    fun `malformed output is stored as invalid`() =
        assertFailureStored(InterpreterFailureKind.MALFORMED, ValidationReason.INTERPRETER_OUTPUT_MALFORMED)

    @Test
    fun `other failure is stored as invalid`() =
        assertFailureStored(InterpreterFailureKind.OTHER, ValidationReason.INTERPRETER_FAILED)

    // --- guards ---------------------------------------------------------------

    @Test
    fun `a capture with an occurrence does not call the extractor`() {
        val id = capture()
        extractor.enqueue(candidate())
        assertIs<TaggedProcessingOutcome.AutoSaved>(process(id))
        val calls = extractor.inputs.size
        val writes = base.writeCount
        assertEquals(TaggedProcessingOutcome.AlreadyHasOccurrence, process(id))
        assertEquals(calls, extractor.inputs.size)
        assertEquals(writes, base.writeCount)
    }

    @Test
    fun `unknown capture id throws a payload free message`() {
        val e = assertFailsWith<IllegalArgumentException> { process("capture-404") }
        assertEquals("unknown capture capture-404", e.message)
        assertTrue(extractor.inputs.isEmpty())
    }

    @Test
    fun `the extractor receives the raw text, instant and zone only`() {
        val id = capture()
        extractor.enqueue(candidate())
        process(id)
        assertEquals(ExtractionInput(text, capturedAt, zone), extractor.inputs.single())
    }

    @Test
    fun `an interpretation record type carries no confidence on the tag path`() {
        val id = capture()
        extractor.enqueue(candidate(action = null))
        process(id)
        val record: InterpretationRecord = base.interpretationsFor(id).single()
        assertNull(record.modelConfidenceBand)
    }
}
