package com.mcfrenchpants.activityledger.core.domain.services

import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationRecord
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.model.VisibilityStatus
import com.mcfrenchpants.activityledger.core.domain.naming.NewActivityNameCheck
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
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

class CorrectionServiceTest {
    private val zone = ZoneId.of("America/Detroit")
    private val capturedAt = Instant.parse("2026-09-16T00:00:00Z")
    private val now = capturedAt.plus(Duration.ofHours(2))
    private val clock = MutableClock(now, zone)
    private val repo = InMemoryActivityRepository(clock)
    private val service = CorrectionService(repo, clock)

    private val mowLawn = repo.seedActivity("Mow lawn", aliases = listOf("cut grass"))
    private val gutters = repo.seedActivity("Clean gutters")
    private val archived = repo.seedActivity("Old hobby", CanonicalActivityStatus.ARCHIVED)
    private val merged = repo.seedActivity("Merged hobby", CanonicalActivityStatus.MERGED)

    private val occurrenceId: String = runSuspend {
        val captureId = repo.createRawCapture(
            NewRawCapture(CaptureSource.PHONE_TEXT, null, capturedAt, zone, "I cut the grass.", null, null, ProcessingState.CAPTURED),
        )
        repo.acceptInterpretation(
            captureId,
            InterpretationRecord(
                capturedAt, "test", "test", 1, InterpretationOperation.LOG_ACTIVITY, ActivityResolution.EXISTING_ACTIVITY,
                mowLawn, null, ActivityState.COMPLETED, null, capturedAt, TimePrecision.INFERRED_NOW, null, null, null,
                ValidationStatus.VALID, null,
            ),
            ActivityTarget.Existing(mowLawn), capturedAt, TimePrecision.INFERRED_NOW, ActivityState.COMPLETED,
        )
    }

    private fun correct(request: CorrectionRequest, reason: String? = null, id: String = occurrenceId) =
        runSuspend { service.correct(id, request, reason) }

    private fun assertRefused(expected: ServiceRefusal, request: CorrectionRequest, id: String = occurrenceId) {
        val writes = repo.writeCount
        assertEquals(CorrectionResult.Refused(expected), correct(request, id = id))
        assertEquals(writes, repo.writeCount)
    }

    private fun occurrence() = runSuspend { assertNotNull(repo.getOccurrence(occurrenceId)) }

    @Test
    fun `refuses unknown occurrence`() =
        assertRefused(ServiceRefusal.OccurrenceNotFound, CorrectionRequest(activityState = ActivityState.IN_PROGRESS), id = "occurrence-404")

    @Test
    fun `refuses hidden occurrence`() {
        repo.setVisibility(occurrenceId, VisibilityStatus.HIDDEN)
        assertRefused(ServiceRefusal.OccurrenceHidden, CorrectionRequest(activityState = ActivityState.IN_PROGRESS))
    }

    @Test
    fun `refuses missing, archived and merged target activities`() {
        assertRefused(ServiceRefusal.ActivityNotFound, CorrectionRequest(ActivityTarget.Existing("activity-404")))
        assertRefused(ServiceRefusal.ActivityNotActive, CorrectionRequest(ActivityTarget.Existing(archived)))
        assertRefused(ServiceRefusal.ActivityNotActive, CorrectionRequest(ActivityTarget.Existing(merged)))
    }

    @Test
    fun `refuses a time after now but allows exactly now`() {
        assertRefused(
            ServiceRefusal.OccurredAfterNow,
            CorrectionRequest(time = OccurrenceTime(now.plusMillis(1), TimePrecision.EXACT)),
        )
        assertIs<CorrectionResult.Applied>(correct(CorrectionRequest(time = OccurrenceTime(now, TimePrecision.EXACT))))
        assertEquals(now, occurrence().occurredAt)
    }

    @Test
    fun `refuses an invalid new name with the check's reason`() {
        assertRefused(
            ServiceRefusal.InvalidName(NewActivityNameCheck.Reason.CONTAINS_TIME_WORD),
            CorrectionRequest(ActivityTarget.New("Mow yesterday")),
        )
        assertRefused(ServiceRefusal.InvalidName(NewActivityNameCheck.Reason.EMPTY), CorrectionRequest(ActivityTarget.New("   ")))
    }

    @Test
    fun `refuses a new name matching an existing name or alias`() {
        assertRefused(ServiceRefusal.NameMatchesExistingActivity(gutters), CorrectionRequest(ActivityTarget.New("  clean GUTTERS. ")))
        assertRefused(ServiceRefusal.NameMatchesExistingActivity(mowLawn), CorrectionRequest(ActivityTarget.New("Cut grass")))
        assertEquals(4, repo.activities.size)
    }

    @Test
    fun `a new name matching only an archived activity is allowed`() {
        assertIs<CorrectionResult.Applied>(correct(CorrectionRequest(ActivityTarget.New("Old hobby"))))
    }

    @Test
    fun `request equal to current values changes nothing`() {
        val writes = repo.writeCount
        val same = CorrectionRequest(
            ActivityTarget.Existing(mowLawn), OccurrenceTime(capturedAt, TimePrecision.INFERRED_NOW), ActivityState.COMPLETED,
        )
        assertEquals(CorrectionResult.NothingChanged, correct(same))
        assertEquals(CorrectionResult.NothingChanged, correct(CorrectionRequest()))
        assertEquals(writes, repo.writeCount)
    }

    @Test
    fun `time change stores the given precision`() {
        val earlier = capturedAt.minus(Duration.ofDays(1))
        val result = assertIs<CorrectionResult.Applied>(correct(CorrectionRequest(time = OccurrenceTime(earlier, TimePrecision.DATE_ONLY))))
        assertEquals(earlier, occurrence().occurredAt)
        assertEquals(TimePrecision.DATE_ONLY, occurrence().timePrecision)
        val c = repo.corrections.single()
        assertEquals(result.correctionId, c.id)
        assertEquals(TimePrecision.INFERRED_NOW, c.previousTimePrecision)
        assertEquals(TimePrecision.DATE_ONLY, c.newTimePrecision)
    }

    @Test
    fun `activity change to an existing activity applies with source USER and reason`() {
        clock.advance(Duration.ofMinutes(7))
        assertIs<CorrectionResult.Applied>(correct(CorrectionRequest(ActivityTarget.Existing(gutters)), reason = "wrong one"))
        assertEquals(gutters, occurrence().canonicalActivityId)
        val c = repo.corrections.single()
        assertEquals(CorrectionSource.USER, c.source)
        assertEquals("wrong one", c.reason)
        assertEquals(now.plus(Duration.ofMinutes(7)), c.createdAt)
        assertEquals(mowLawn, c.previousActivityId)
    }

    @Test
    fun `activity change to a new activity applies with source USER and reason`() {
        assertIs<CorrectionResult.Applied>(
            correct(CorrectionRequest(ActivityTarget.New("  Edge lawn \t"), activityState = ActivityState.IN_PROGRESS), reason = "edged"),
        )
        val activity = runSuspend { assertNotNull(repo.getActivity(occurrence().canonicalActivityId)) }
        assertEquals("Edge lawn", activity.displayName)
        assertEquals("edge lawn", activity.normalizedName)
        assertEquals(CanonicalActivityStatus.ACTIVE, activity.status)
        assertEquals(ActivityState.IN_PROGRESS, occurrence().activityState)
        val c = repo.corrections.single()
        assertEquals(CorrectionSource.USER, c.source)
        assertEquals("edged", c.reason)
        assertEquals(now, c.createdAt)
    }
}
