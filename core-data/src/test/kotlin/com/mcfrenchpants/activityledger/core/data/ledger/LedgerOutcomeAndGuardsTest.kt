package com.mcfrenchpants.activityledger.core.data.ledger

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcfrenchpants.activityledger.core.data.db.ActivityLedgerDatabase
import com.mcfrenchpants.activityledger.core.data.db.Fixtures
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.T0
import com.mcfrenchpants.activityledger.core.data.db.Fixtures.id
import com.mcfrenchpants.activityledger.core.data.db.count
import com.mcfrenchpants.activityledger.core.data.db.entity.RawCaptureEntity
import com.mcfrenchpants.activityledger.core.data.db.insertCanonicalActivity
import com.mcfrenchpants.activityledger.core.data.id.DeterministicIdFactory
import com.mcfrenchpants.activityledger.core.data.id.IdFactory
import com.mcfrenchpants.activityledger.core.data.inMemoryTestDatabase
import com.mcfrenchpants.activityledger.core.domain.model.ActivityResolution
import com.mcfrenchpants.activityledger.core.domain.model.ActivityState
import com.mcfrenchpants.activityledger.core.domain.model.CanonicalActivityStatus
import com.mcfrenchpants.activityledger.core.domain.model.CaptureSource
import com.mcfrenchpants.activityledger.core.domain.model.CorrectionSource
import com.mcfrenchpants.activityledger.core.domain.model.InterpretationOperation
import com.mcfrenchpants.activityledger.core.domain.model.ProcessingState
import com.mcfrenchpants.activityledger.core.domain.model.TimePrecision
import com.mcfrenchpants.activityledger.core.domain.model.ValidationStatus
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The DS1.4 additions to the ledger's transactional operations: recordOutcome,
 * applyCorrectionCreatingActivity, and the "target activity must be ACTIVE" refusals.
 */
@RunWith(AndroidJUnit4::class)
class LedgerOutcomeAndGuardsTest {

    private lateinit var db: ActivityLedgerDatabase
    private lateinit var writer: ActivityLedgerWriter
    private val sql get() = db.openHelper.writableDatabase
    private val ids = DeterministicIdFactory(next = 0x10_0000L)

    @Before
    fun setUp() {
        db = inMemoryTestDatabase()
        writer = ActivityLedgerWriter(db, ids)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- helpers -------------------------------------------------------------

    private fun capture(captureId: String) = RawCaptureEntity(
        id = captureId,
        source = CaptureSource.PHONE_TEXT,
        sourceSurface = null,
        capturedAt = T0,
        capturedZoneId = "Europe/London",
        rawText = "synthetic capture",
        speechConfidence = null,
        speechAlternativesJson = null,
        processingState = ProcessingState.INTERPRETING,
        createdAt = T0,
        updatedAt = T0,
    ).also { db.rawCaptureDao().insert(it) }

    private fun activity(activityId: String, status: CanonicalActivityStatus = CanonicalActivityStatus.ACTIVE) {
        val merged = if (status == CanonicalActivityStatus.MERGED) id(1) else null
        sql.insertCanonicalActivity(
            Fixtures.canonicalActivity(activityId).copy(status = status, mergedIntoActivityId = merged),
        )
    }

    private fun interpretation(captureId: String, matched: String?) = NewInterpretation(
        rawCaptureId = captureId,
        createdAt = T0 + 1,
        interpreterVersion = "test-interpreter",
        promptVersion = "test-prompt",
        schemaVersion = 1,
        operation = InterpretationOperation.LOG_ACTIVITY,
        activityResolution = ActivityResolution.EXISTING_ACTIVITY,
        matchedActivityId = matched,
        proposedCanonicalName = null,
        activityState = ActivityState.COMPLETED,
        temporalExpression = null,
        resolvedOccurredAt = null,
        timePrecision = null,
        modelConfidenceBand = null,
        candidateContextHash = null,
        structuredResultJson = null,
        validationStatus = ValidationStatus.VALID,
        validationReason = null,
    )

    private fun accept(captureId: String, activityId: String) = writer.acceptInterpretation(
        AcceptInterpretationRequest(
            rawCaptureId = captureId,
            interpretation = interpretation(captureId, matched = null),
            newActivity = null,
            canonicalActivityId = activityId,
            occurredAt = T0,
            timePrecision = TimePrecision.EXACT,
            activityState = ActivityState.COMPLETED,
            now = T0 + 100,
        ),
    )

    /** id(1) ACTIVE, id(2) ARCHIVED, id(3) MERGED into id(1); an occurrence of capture id(10) on id(1). */
    private fun seededOccurrence(): String {
        activity(id(1))
        activity(id(2), CanonicalActivityStatus.ARCHIVED)
        activity(id(3), CanonicalActivityStatus.MERGED)
        capture(id(10))
        return accept(id(10), id(1))
    }

    // --- target activity must be ACTIVE --------------------------------------

    @Test
    fun acceptRefusesArchivedOrMergedExistingActivity() {
        activity(id(1))
        activity(id(2), CanonicalActivityStatus.ARCHIVED)
        activity(id(3), CanonicalActivityStatus.MERGED)
        val raw = capture(id(10))

        for (target in listOf(id(2), id(3))) {
            assertFailsWith<IllegalArgumentException> { accept(raw.id, target) }
        }
        assertEquals(0, sql.count("interpretations"))
        assertEquals(0, sql.count("activity_occurrences"))
        assertEquals(3, sql.count("canonical_activities"))
        val after = assertNotNull(db.rawCaptureDao().getById(raw.id))
        assertEquals(raw.processingState, after.processingState)
        assertEquals(raw.updatedAt, after.updatedAt)
    }

    @Test
    fun applyCorrectionRefusesMissingOrNonActiveActivity() {
        val occurrenceId = seededOccurrence()
        val before = assertNotNull(db.activityOccurrenceDao().getById(occurrenceId))

        for (target in listOf(id(999), id(2), id(3))) {
            assertFailsWith<IllegalArgumentException> {
                writer.applyCorrection(
                    occurrenceId, OccurrenceChanges(canonicalActivityId = target, occurredAt = T0 + 7),
                    CorrectionSource.USER, null, T0 + 900,
                )
            }
        }
        assertEquals(0, sql.count("corrections"))
        assertEquals(before, db.activityOccurrenceDao().getById(occurrenceId))
    }

    // --- applyCorrectionCreatingActivity ---------------------------------------

    @Test
    fun applyCorrectionCreatingActivityCreatesActiveActivityAndOneCorrection() {
        val occurrenceId = seededOccurrence()
        val activitiesBefore = sql.count("canonical_activities")

        val correctionId = writer.applyCorrectionCreatingActivity(
            occurrenceId, NewCanonicalActivity("Edge lawn", "edge lawn"),
            OccurrenceChanges(activityState = ActivityState.IN_PROGRESS), CorrectionSource.USER, null, T0 + 900,
        )

        assertEquals(activitiesBefore + 1, sql.count("canonical_activities"))
        val row = db.correctionDao().listForOccurrence(occurrenceId).single()
        assertEquals(correctionId, row.id)
        val created = assertNotNull(db.canonicalActivityDao().getById(assertNotNull(row.newCanonicalActivityId)))
        assertEquals(CanonicalActivityStatus.ACTIVE, created.status)
        assertEquals("edge lawn", created.normalizedName)
        assertEquals(T0 + 900, created.createdAt)
        assertEquals(id(1), row.previousCanonicalActivityId)
        assertEquals(ActivityState.IN_PROGRESS, row.newActivityState)
        val after = assertNotNull(db.activityOccurrenceDao().getById(occurrenceId))
        assertEquals(created.id, after.canonicalActivityId)
        assertEquals(ActivityState.IN_PROGRESS, after.activityState)
    }

    @Test
    fun applyCorrectionCreatingActivityRollsBackActivityWhenCorrectionInsertFails() {
        val occurrenceId = seededOccurrence()
        val existingCorrection = assertNotNull(
            writer.applyCorrection(
                occurrenceId, OccurrenceChanges(occurredAt = T0 + 1), CorrectionSource.USER, null, T0 + 500,
            ),
        )
        val before = assertNotNull(db.activityOccurrenceDao().getById(occurrenceId))
        val activitiesBefore = sql.count("canonical_activities")

        // New activity id, then a DUPLICATE correction id -> the correction insert fails after
        // the canonical activity insert already ran.
        val scripted = ArrayDeque(listOf(id(60), existingCorrection))
        val failing = ActivityLedgerWriter(db, IdFactory { scripted.removeFirst() })
        assertFailsWith<SQLiteConstraintException> {
            failing.applyCorrectionCreatingActivity(
                occurrenceId, NewCanonicalActivity("Would be new", "would be new"), OccurrenceChanges(),
                CorrectionSource.USER, null, T0 + 900,
            )
        }
        assertTrue(scripted.isEmpty(), "both ids were consumed, so the activity insert really ran")
        assertNull(db.canonicalActivityDao().getById(id(60)))
        assertEquals(activitiesBefore, sql.count("canonical_activities"))
        assertEquals(1, sql.count("corrections"))
        assertEquals(before, db.activityOccurrenceDao().getById(occurrenceId))
    }

    @Test
    fun applyCorrectionCreatingActivityRefusesUnknownOccurrenceOrNamedActivity() {
        val occurrenceId = seededOccurrence()
        val activitiesBefore = sql.count("canonical_activities")

        assertFailsWith<IllegalArgumentException> {
            writer.applyCorrectionCreatingActivity(
                id(999), NewCanonicalActivity("New", "new"), OccurrenceChanges(), CorrectionSource.USER, null, T0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            writer.applyCorrectionCreatingActivity(
                occurrenceId, NewCanonicalActivity("New", "new"), OccurrenceChanges(canonicalActivityId = id(1)),
                CorrectionSource.USER, null, T0,
            )
        }
        assertEquals(activitiesBefore, sql.count("canonical_activities"))
        assertEquals(0, sql.count("corrections"))
    }

    // --- recordOutcome -------------------------------------------------------

    @Test
    fun recordOutcomeRollsBackStateUpdateWhenInterpretationInsertFails() {
        val raw = capture(id(10))

        // The state update runs first; the interpretation insert then fails its foreign key
        // (matched activity id(999) does not exist). The state change must be rolled back.
        assertFailsWith<SQLiteConstraintException> {
            writer.recordOutcome(raw.id, interpretation(raw.id, matched = id(999)), ProcessingState.NEEDS_REVIEW, T0 + 50)
        }
        assertEquals(0, sql.count("interpretations"))
        val after = assertNotNull(db.rawCaptureDao().getById(raw.id))
        assertEquals(raw.processingState, after.processingState)
        assertEquals(raw.updatedAt, after.updatedAt)
    }

    @Test
    fun recordOutcomeStoresInterpretationAndStateTogether() {
        val raw = capture(id(10))
        val interpretationId = writer.recordOutcome(
            raw.id, interpretation(raw.id, matched = null), ProcessingState.FAILED_FINAL, T0 + 50,
        )
        assertEquals(interpretationId, db.interpretationDao().listForRawCapture(raw.id).single().id)
        val after = assertNotNull(db.rawCaptureDao().getById(raw.id))
        assertEquals(ProcessingState.FAILED_FINAL, after.processingState)
        assertEquals(T0 + 50, after.updatedAt)
    }

    @Test
    fun recordOutcomeRefusesInterpretationOfAnotherCapture() {
        val raw = capture(id(10))
        val other = capture(id(11))
        assertFailsWith<IllegalArgumentException> {
            writer.recordOutcome(raw.id, interpretation(other.id, matched = null), ProcessingState.NEEDS_REVIEW, T0 + 50)
        }
        assertEquals(0, sql.count("interpretations"))
        assertEquals(raw.updatedAt, db.rawCaptureDao().getById(raw.id)?.updatedAt)
    }
}
