package com.mcfrenchpants.activityledger.pipeline

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpreterFailureKind
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CaptureProcessingOutcome
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.InMemoryActivityRepository
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Host-side guard (no device, no model) for the path the phone will hit most often in real life:
 * the on-device model is not usable at that moment.
 *
 * What must hold when interpretation reports UNAVAILABLE or RETRYABLE: the capture is kept
 * exactly as spoken, no occurrence is invented, no interpretation is recorded at all, and the
 * capture stays processable so a later attempt can still succeed. Nothing may be lost because a
 * model was missing.
 *
 * This drives the real [CaptureInterpretationOrchestrator] with a stub interpreter over the
 * shared in-memory repository, which -- unlike the on-disk one -- can be asked whether an
 * interpretation row exists at all, which is one of the things that must be proven here.
 * (The same orchestrator behaviour is covered against a real Room database in core-data's own
 * host-side tests.) The vertical slice through the real model and the real database is the
 * on-device test in this module's androidTest source set.
 */
class InterpreterUnavailableGuardTest {

    private val zone = ZoneId.of("America/Detroit")
    private val capturedAt: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val captureText = "I cut the grass yesterday."

    private val clock = MutableClock(capturedAt, zone)
    private val repository = InMemoryActivityRepository(clock)
    private val interpreter = FakeActivityInterpreter()
    private val orchestrator = CaptureInterpretationOrchestrator(repository, interpreter, clock)

    @Test
    fun unavailableInterpreterLosesNothingAndLeavesTheCaptureProcessable() = runBlocking {
        assertRetainedAndRetryable(InterpreterFailureKind.UNAVAILABLE)
    }

    @Test
    fun retryableInterpreterFailureLosesNothingAndLeavesTheCaptureProcessable() = runBlocking {
        assertRetainedAndRetryable(InterpreterFailureKind.RETRYABLE)
    }

    private suspend fun assertRetainedAndRetryable(kind: InterpreterFailureKind) {
        val mowLawn = repository.seedActivity("Mow lawn")
        val captureId = repository.createRawCapture(
            NewRawCapture(
                source = CaptureSource.PHONE_TEXT,
                sourceSurface = null,
                capturedAt = capturedAt,
                zoneId = zone,
                rawText = captureText,
                speechConfidence = null,
                speechAlternativesJson = null,
                processingState = ProcessingState.CAPTURED,
            ),
        )

        interpreter.enqueue(InterpretationResult.Failure(kind, structuredResultJson = null))
        val outcome = orchestrator.process(captureId)

        val unavailable = assertIs<CaptureProcessingOutcome.InterpreterUnavailable>(outcome)
        assertEquals(kind, unavailable.kind)

        // The capture survives, unchanged (ADR-007: raw captures are immutable).
        val stored = assertNotNull(repository.getCapture(captureId))
        assertEquals(captureText, stored.rawText)
        assertEquals(capturedAt, stored.capturedAt)
        assertEquals(zone, stored.zoneId)

        // Nothing was invented and nothing was recorded.
        assertFalse(stored.hasOccurrence)
        assertTrue(repository.occurrences.isEmpty())
        assertTrue(repository.interpretationsFor(captureId).isEmpty())

        // And it is queued to be tried again, not written off.
        assertEquals(ProcessingState.FAILED_RETRYABLE, stored.processingState)

        // A later attempt, once the model is usable, still works on the very same capture.
        interpreter.enqueue(
            InterpretationResult.Success(
                candidate = InterpretationCandidate(
                    operation = InterpretationOperation.LOG_ACTIVITY,
                    activityResolution = ActivityResolution.EXISTING_ACTIVITY,
                    matchedActivityId = mowLawn,
                    proposedCanonicalName = null,
                    activityState = ActivityState.COMPLETED,
                    temporalExpression = "yesterday",
                    confidenceBand = ConfidenceBand.HIGH,
                ),
                structuredResultJson = null,
            ),
        )
        val retry = assertIs<CaptureProcessingOutcome.AutoAccepted>(orchestrator.process(captureId))

        val occurrence = assertNotNull(repository.getOccurrence(retry.occurrenceId))
        assertEquals(captureId, occurrence.rawCaptureId)
        assertEquals(mowLawn, occurrence.canonicalActivityId)
        assertEquals(TimePrecision.DATE_ONLY, occurrence.timePrecision)
        assertEquals(
            ZonedDateTime.of(2026, 9, 14, 0, 0, 0, 0, zone).toInstant(),
            occurrence.occurredAt,
        )
        assertEquals(captureText, assertNotNull(repository.getCapture(captureId)).rawText)
    }
}
