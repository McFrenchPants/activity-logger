package com.mcfrenchpants.activityledger.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.Fixtures
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.db.insertCanonicalActivity
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationCandidate
import com.mcfrenchpants.activityledger.core.domain.interpretation.InterpretationResult
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.ConfidenceBand
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import com.mcfrenchpants.activityledger.core.domain.naming.NameNormalizer
import com.mcfrenchpants.activityledger.core.domain.repository.ActivityTarget
import com.mcfrenchpants.activityledger.core.domain.repository.NewRawCapture
import com.mcfrenchpants.activityledger.core.domain.services.CaptureInterpretationOrchestrator
import com.mcfrenchpants.activityledger.core.domain.services.CaptureProcessingOutcome
import com.mcfrenchpants.activityledger.core.domain.services.ResolutionResult
import com.mcfrenchpants.activityledger.core.domain.services.ReviewResolutionService
import com.mcfrenchpants.activityledger.core.domain.validation.ValidationReason
import com.mcfrenchpants.activityledger.core.testing.FakeActivityInterpreter
import com.mcfrenchpants.activityledger.core.testing.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Domain services end to end on RoomActivityRepository and a real in-memory Room database. Synthetic text only. */
@RunWith(AndroidJUnit4::class)
class OrchestratorIntegrationTest {
    private val zone = ZoneId.of("America/Detroit")
    private val capturedAt: Instant = ZonedDateTime.of(2026, 9, 15, 20, 0, 0, 0, zone).toInstant()
    private val clock = MutableClock(capturedAt, zone)
    private val interpreter = FakeActivityInterpreter()
    private val mowLawn = Fixtures.id(1)

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var repository: RoomActivityRepository
    private lateinit var orchestrator: CaptureInterpretationOrchestrator

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        repository = RoomActivityRepository(db, DeterministicIdFactory(next = 0x10_0000L), clock, Dispatchers.Unconfined)
        orchestrator = CaptureInterpretationOrchestrator(repository, interpreter, clock)
        db.openHelper.writableDatabase.insertCanonicalActivity(
            Fixtures.canonicalActivity(mowLawn, normalizedName = NameNormalizer.normalize("Mow lawn"))
                .copy(displayName = "Mow lawn"),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun capture(text: String): String = repository.createRawCapture(
        NewRawCapture(CaptureSource.PHONE_VOICE, null, capturedAt, zone, text, null, null, ProcessingState.CAPTURED),
    )

    private fun rawTextHex(captureId: String): String =
        db.openHelper.writableDatabase.query("SELECT hex(raw_text) FROM raw_captures WHERE id = ?", arrayOf(captureId)).use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }

    private fun utf8Hex(text: String): String =
        text.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun cutTheGrassYesterdayIsAutoAcceptedOnRealRoom() = runBlocking<Unit> {
        val text = "I cut the grass yesterday."
        val captureId = capture(text)
        interpreter.enqueue(
            InterpretationResult.Success(
                InterpretationCandidate(
                    InterpretationOperation.LOG_ACTIVITY, ActivityResolution.EXISTING_ACTIVITY, mowLawn, null,
                    ActivityState.COMPLETED, "yesterday", ConfidenceBand.HIGH,
                ),
                "{\"synthetic\":true}",
            ),
        )

        val outcome = assertIs<CaptureProcessingOutcome.AutoAccepted>(orchestrator.process(captureId))

        val occurrence = assertNotNull(repository.getOccurrence(outcome.occurrenceId))
        assertEquals(mowLawn, occurrence.canonicalActivityId)
        assertEquals(ZonedDateTime.of(2026, 9, 14, 0, 0, 0, 0, zone).toInstant(), occurrence.occurredAt)
        assertEquals(Instant.parse("2026-09-14T04:00:00Z"), occurrence.occurredAt)
        assertEquals(TimePrecision.DATE_ONLY, occurrence.timePrecision)
        assertEquals(ActivityState.COMPLETED, occurrence.activityState)
        val stored = assertNotNull(repository.getCapture(captureId))
        assertEquals(ProcessingState.PERSISTED, stored.processingState)
        assertEquals(text, stored.rawText)
        assertEquals(utf8Hex(text), rawTextHex(captureId))

        val row = db.interpretationDao().listForRawCapture(captureId).single()
        assertEquals(ValidationStatus.VALID, row.validationStatus)
        assertEquals(mowLawn, row.matchedActivityId)
        assertEquals(interpreter.receivedInputs.single().candidates.map { it.id }, listOf(mowLawn))

        assertEquals(CaptureProcessingOutcome.AlreadyHasOccurrence, orchestrator.process(captureId))
        assertEquals(1, interpreter.callCount)
        assertEquals(1, db.interpretationDao().listForRawCapture(captureId).size)
        assertEquals(utf8Hex(text), rawTextHex(captureId))
    }

    @Test
    fun inventedMatchedIdIsRejectedAndStoredForReviewOnRealRoom() = runBlocking<Unit> {
        val captureId = capture("I cut the grass yesterday.")
        val json = "{\"matchedActivityId\":\"activity-999\"}"
        interpreter.enqueue(
            InterpretationResult.Success(
                InterpretationCandidate(
                    InterpretationOperation.LOG_ACTIVITY, ActivityResolution.EXISTING_ACTIVITY, "activity-999", null,
                    ActivityState.COMPLETED, "yesterday", ConfidenceBand.HIGH,
                ),
                json,
            ),
        )

        val outcome = assertIs<CaptureProcessingOutcome.Rejected>(orchestrator.process(captureId))
        assertTrue(ValidationReason.EXISTING_ACTIVITY_NOT_SUPPLIED in outcome.reasons)

        val row = db.interpretationDao().listForRawCapture(captureId).single()
        assertEquals(ValidationStatus.INVALID, row.validationStatus)
        assertNull(row.matchedActivityId)
        assertEquals(json, row.structuredResultJson)
        val stored = assertNotNull(repository.getCapture(captureId))
        assertEquals(ProcessingState.NEEDS_REVIEW, stored.processingState)
        assertFalse(stored.hasOccurrence)
        assertEquals(0, db.openHelper.writableDatabase.count("activity_occurrences"))
    }

    @Test
    fun ambiguousCaptureNeedsReviewThenResolvesToNewActivityOnRealRoom() = runBlocking<Unit> {
        val text = "Did the yard thing."
        val captureId = capture(text)
        interpreter.enqueue(
            InterpretationResult.Success(
                InterpretationCandidate(
                    InterpretationOperation.LOG_ACTIVITY, ActivityResolution.AMBIGUOUS, null, null,
                    ActivityState.COMPLETED, null, ConfidenceBand.HIGH,
                ),
                "{}",
            ),
        )
        assertIs<CaptureProcessingOutcome.NeedsReview>(orchestrator.process(captureId))
        assertEquals(ProcessingState.NEEDS_REVIEW, assertNotNull(repository.getCapture(captureId)).processingState)
        val earlier = db.interpretationDao().listForRawCapture(captureId).single()
        assertEquals(ValidationStatus.NEEDS_REVIEW, earlier.validationStatus)

        clock.advance(Duration.ofMinutes(5))
        val resolution = ReviewResolutionService(repository, clock)
            .resolve(captureId, ActivityTarget.New("Weed garden"))
        val resolved = assertIs<ResolutionResult.Resolved>(resolution)

        val occurrence = assertNotNull(repository.getOccurrence(resolved.occurrenceId))
        val activity = assertNotNull(repository.getActivity(occurrence.canonicalActivityId))
        assertEquals("Weed garden", activity.displayName)
        assertEquals(CanonicalActivityStatus.ACTIVE, activity.status)
        assertEquals(capturedAt, occurrence.occurredAt)
        assertEquals(TimePrecision.INFERRED_NOW, occurrence.timePrecision)
        assertEquals(ProcessingState.PERSISTED, assertNotNull(repository.getCapture(captureId)).processingState)

        val rows = db.interpretationDao().listForRawCapture(captureId)
        assertEquals(2, rows.size)
        assertEquals(earlier, rows[0])
        val userRow = rows[1]
        assertEquals("user-resolution", userRow.interpreterVersion)
        assertEquals("none", userRow.promptVersion)
        assertEquals(ValidationStatus.VALID, userRow.validationStatus)
        assertEquals(ActivityResolution.NEW_ACTIVITY, userRow.activityResolution)
        assertEquals("Weed garden", userRow.proposedCanonicalName)
        assertNull(userRow.matchedActivityId)
        assertEquals(userRow.id, occurrence.effectiveInterpretationId)
        assertEquals(utf8Hex(text), rawTextHex(captureId))
    }
}
