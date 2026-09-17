package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
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
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import com.mcfrenchpants.activityledger.core.testing.runSuspend
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewResolutionServiceTest {
    private val zone = ZoneId.of("America/Detroit")
    private val capturedAt = Instant.parse("2026-09-16T00:00:00Z")
    private val now = capturedAt.plus(Duration.ofHours(1))
    private val clock = MutableClock(now, zone)
    private val repo = InMemoryActivityRepository(clock)
    private val interpreter = FakeActivityInterpreter()
    private val orchestrator = CaptureInterpretationOrchestrator(repo, interpreter, clock)
    private val service = ReviewResolutionService(repo, clock)

    private val mowLawn = repo.seedActivity("Mow lawn", aliases = listOf("cut grass"))
    private val gutters = repo.seedActivity("Clean gutters")
    private val archived = repo.seedActivity("Old hobby", CanonicalActivityStatus.ARCHIVED)

    /** A capture the orchestrator left in NEEDS_REVIEW (ambiguous answer). */
    private fun reviewCapture(): String = runSuspend {
        val id = repo.createRawCapture(
            NewRawCapture(CaptureSource.PHONE_VOICE, null, capturedAt, zone, "I did the yard thing.", 0.9, null, ProcessingState.CAPTURED),
        )
        interpreter.enqueue(
            InterpretationResult.Success(
                InterpretationCandidate(
                    InterpretationOperation.LOG_ACTIVITY, ActivityResolution.AMBIGUOUS, null, null,
                    ActivityState.COMPLETED, null, ConfidenceBand.MEDIUM,
                ),
                "{}",
            ),
        )
        assertIs<CaptureProcessingOutcome.NeedsReview>(orchestrator.process(id))
        id
    }

    private fun resolve(
        captureId: String,
        target: ActivityTarget,
        time: OccurrenceTime? = null,
        state: ActivityState = ActivityState.COMPLETED,
    ) = runSuspend { service.resolve(captureId, target, time, state) }

    private fun assertRefused(expected: ServiceRefusal, captureId: String, target: ActivityTarget, time: OccurrenceTime? = null) {
        val writes = repo.writeCount
        val interpretations = repo.interpretationsFor(captureId)
        assertEquals(ResolutionResult.Refused(expected), resolve(captureId, target, time))
        assertEquals(writes, repo.writeCount)
        assertEquals(interpretations, repo.interpretationsFor(captureId))
    }

    @Test
    fun `resolves to an existing activity with default time and user-resolution interpretation`() {
        val id = reviewCapture()
        val earlier = repo.interpretationsFor(id).single()
        val result = assertIs<ResolutionResult.Resolved>(resolve(id, ActivityTarget.Existing(mowLawn)))

        val occurrence = runSuspend { assertNotNull(repo.getOccurrence(result.occurrenceId)) }
        assertEquals(mowLawn, occurrence.canonicalActivityId)
        assertEquals(capturedAt, occurrence.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, occurrence.timePrecision)
        assertEquals(ActivityState.COMPLETED, occurrence.activityState)
        assertEquals(ProcessingState.PERSISTED, runSuspend { repo.getCapture(id) }?.processingState)

        val records = repo.interpretationsFor(id)
        assertEquals(2, records.size)
        assertEquals(earlier, records[0])
        val record = records[1]
        assertEquals("user-resolution", record.interpreterVersion)
        assertEquals(USER_RESOLUTION_INTERPRETER_VERSION, record.interpreterVersion)
        assertEquals("none", record.promptVersion)
        assertEquals(USER_RESOLUTION_SCHEMA_VERSION, record.schemaVersion)
        assertEquals(ValidationStatus.VALID, record.validationStatus)
        assertNull(record.validationReason)
        assertEquals(InterpretationOperation.LOG_ACTIVITY, record.operation)
        assertEquals(ActivityResolution.EXISTING_ACTIVITY, record.activityResolution)
        assertEquals(mowLawn, record.matchedActivityId)
        assertNull(record.proposedCanonicalName)
        assertEquals(ActivityState.COMPLETED, record.activityState)
        assertNull(record.temporalExpression)
        assertEquals(capturedAt, record.resolvedOccurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, record.timePrecision)
        assertNull(record.modelConfidenceBand)
        assertNull(record.candidateContextHash)
        assertNull(record.structuredResultJson)
        assertEquals(now, record.createdAt)
        assertEquals("interpretation-2", occurrence.effectiveInterpretationId)
    }

    @Test
    fun `resolves to a new activity with an explicit time override`() {
        val id = reviewCapture()
        val time = OccurrenceTime(capturedAt.minus(Duration.ofDays(2)), TimePrecision.DATE_ONLY)
        val result = assertIs<ResolutionResult.Resolved>(
            resolve(id, ActivityTarget.New("  Trim hedges "), time, ActivityState.IN_PROGRESS),
        )
        val occurrence = runSuspend { assertNotNull(repo.getOccurrence(result.occurrenceId)) }
        val activity = runSuspend { assertNotNull(repo.getActivity(occurrence.canonicalActivityId)) }
        assertEquals("Trim hedges", activity.displayName)
        assertEquals(CanonicalActivityStatus.ACTIVE, activity.status)
        assertEquals(time.occurredAt, occurrence.occurredAt)
        assertEquals(TimePrecision.DATE_ONLY, occurrence.timePrecision)
        assertEquals(ActivityState.IN_PROGRESS, occurrence.activityState)

        val record = repo.interpretationsFor(id).last()
        assertEquals(ActivityResolution.NEW_ACTIVITY, record.activityResolution)
        assertNull(record.matchedActivityId)
        assertEquals("Trim hedges", record.proposedCanonicalName)
        assertEquals(time.occurredAt, record.resolvedOccurredAt)
        assertEquals(TimePrecision.DATE_ONLY, record.timePrecision)
        assertEquals(ActivityState.IN_PROGRESS, record.activityState)
    }

    @Test
    fun `works for a failed retryable capture with no interpretation`() {
        val id = runSuspend {
            repo.createRawCapture(NewRawCapture(CaptureSource.WATCH_VOICE, null, capturedAt, zone, "Synthetic.", null, null, ProcessingState.CAPTURED))
        }
        interpreter.enqueue(InterpretationResult.Failure(InterpreterFailureKind.UNAVAILABLE, null))
        runSuspend { orchestrator.process(id) }
        assertTrue(repo.interpretationsFor(id).isEmpty())
        assertEquals(ProcessingState.FAILED_RETRYABLE, runSuspend { repo.getCapture(id) }?.processingState)

        assertIs<ResolutionResult.Resolved>(resolve(id, ActivityTarget.Existing(gutters)))
        assertEquals(USER_RESOLUTION_INTERPRETER_VERSION, repo.interpretationsFor(id).single().interpreterVersion)
    }

    @Test
    fun `refuses unknown capture`() = assertRefused(ServiceRefusal.CaptureNotFound, "capture-404", ActivityTarget.Existing(mowLawn))

    @Test
    fun `refuses a capture that already has an occurrence`() {
        val id = reviewCapture()
        assertIs<ResolutionResult.Resolved>(resolve(id, ActivityTarget.Existing(mowLawn)))
        assertRefused(ServiceRefusal.CaptureAlreadyHasOccurrence, id, ActivityTarget.Existing(gutters))
    }

    @Test
    fun `refuses missing or non-ACTIVE activity`() {
        val id = reviewCapture()
        assertRefused(ServiceRefusal.ActivityNotFound, id, ActivityTarget.Existing("activity-404"))
        assertRefused(ServiceRefusal.ActivityNotActive, id, ActivityTarget.Existing(archived))
    }

    @Test
    fun `refuses invalid or duplicate new names`() {
        val id = reviewCapture()
        assertRefused(ServiceRefusal.InvalidName(NewActivityNameCheck.Reason.FIRST_PERSON_NARRATIVE), id, ActivityTarget.New("I mowed"))
        assertRefused(ServiceRefusal.NameMatchesExistingActivity(mowLawn), id, ActivityTarget.New("CUT GRASS"))
        assertRefused(ServiceRefusal.NameMatchesExistingActivity(gutters), id, ActivityTarget.New("Clean gutters"))
        assertEquals(3, repo.activities.size)
    }

    @Test
    fun `refuses a time after now but allows exactly now`() {
        val id = reviewCapture()
        assertRefused(ServiceRefusal.OccurredAfterNow, id, ActivityTarget.Existing(mowLawn), OccurrenceTime(now.plusMillis(1), TimePrecision.EXACT))
        assertIs<ResolutionResult.Resolved>(resolve(id, ActivityTarget.Existing(mowLawn), OccurrenceTime(now, TimePrecision.EXACT)))
    }

    @Test
    fun `refuses a default capture time that is after now`() {
        clock.currentInstant = capturedAt.minusMillis(1)
        val id = runSuspend {
            repo.createRawCapture(NewRawCapture(CaptureSource.PHONE_TEXT, null, capturedAt, zone, "Synthetic.", null, null, ProcessingState.NEEDS_REVIEW))
        }
        assertRefused(ServiceRefusal.OccurredAfterNow, id, ActivityTarget.Existing(mowLawn))
    }
}
